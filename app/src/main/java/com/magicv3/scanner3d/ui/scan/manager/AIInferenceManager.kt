package com.magicv3.scanner3d.ui.scan.manager

import android.graphics.Bitmap
import android.media.Image
import android.hardware.display.DisplayManager
import android.util.Log
import android.view.Display
import android.view.Surface
import com.google.ar.core.Frame
import com.google.ar.core.TrackingState
import com.magicv3.scanner3d.domain.ar.CameraPose
import com.magicv3.scanner3d.domain.ar.DepthSourceState
import com.magicv3.scanner3d.domain.depth.DepthMap
import com.magicv3.scanner3d.domain.depth.PointCloudStore
import com.magicv3.scanner3d.domain.usecase.DepthToPointsUseCase
import com.magicv3.scanner3d.infra.ai.YoloInferenceEngine
import com.magicv3.scanner3d.infra.depth.ArCoreDepthSource
import com.magicv3.scanner3d.infra.depth.TfliteDepthSource
import com.magicv3.scanner3d.ui.scan.AIPreviewMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

class AIInferenceManager constructor(
    private val yoloEngine: YoloInferenceEngine,
    private val tfliteDepthSource: TfliteDepthSource,
    private val arCoreDepthSource: ArCoreDepthSource,
    private val depthToPointsUseCase: DepthToPointsUseCase,
    private val pointCloudStore: PointCloudStore
) {
    private val _aiPreviewMode = MutableStateFlow(AIPreviewMode.NONE)
    val aiPreviewMode: StateFlow<AIPreviewMode> = _aiPreviewMode.asStateFlow()

    private val _aiStats = MutableStateFlow<String?>(null)
    val aiStats: StateFlow<String?> = _aiStats.asStateFlow()

    private val _depthHeatmap = MutableStateFlow<Bitmap?>(null)
    val depthHeatmap: StateFlow<Bitmap?> = _depthHeatmap.asStateFlow()

    private val _yoloDetections = MutableStateFlow<List<YoloInferenceEngine.Detection>>(emptyList())
    val yoloDetections: StateFlow<List<YoloInferenceEngine.Detection>> = _yoloDetections.asStateFlow()

    private val _depthSourceState = MutableStateFlow<DepthSourceState>(DepthSourceState.NONE)
    val depthSourceState: StateFlow<DepthSourceState> = _depthSourceState.asStateFlow()

    private val _yoloModelLoaded = MutableStateFlow(true)
    val yoloModelLoaded: StateFlow<Boolean> = _yoloModelLoaded.asStateFlow()

    @Volatile
    private var isAiProcessing = false
    private var aiFrameSkipCounter = 0

    val isYoloModelLoaded: Boolean
        get() = yoloEngine.isModelLoaded

    fun setAiPreviewMode(mode: AIPreviewMode, scope: CoroutineScope) {
        _aiPreviewMode.value = mode
        if (mode == AIPreviewMode.NONE) {
            _depthHeatmap.value = null
            _yoloDetections.value = emptyList()
            _aiStats.value = null
        } else if (mode == AIPreviewMode.YOLO) {
            scope.launch(Dispatchers.Default) {
                _yoloModelLoaded.value = yoloEngine.isModelLoaded
            }
        }
    }

    fun onFrameAvailable(
        frame: Frame,
        isThermalWarned: Boolean,
        isThermalThrottled: Boolean,
        scope: CoroutineScope,
        displayRotation: Int
    ) {
        if (isAiProcessing || _aiPreviewMode.value == AIPreviewMode.NONE) return

        // Termal uyarı altında AI inference kare atlar (ısıyı düşürür, çekim engellenmez).
        if (isThermalWarned || isThermalThrottled) {
            aiFrameSkipCounter++
            if (aiFrameSkipCounter % AI_FRAME_SKIP_DIVISOR != 0) return
        }

        val isTracking = frame.camera.trackingState == TrackingState.TRACKING
        val currentPose = if (isTracking) {
            val pose = frame.camera.pose
            CameraPose(pose.translation, pose.rotationQuaternion)
        } else null

        val arDepthMap = if (_aiPreviewMode.value == AIPreviewMode.DEPTH && isTracking) {
            arCoreDepthSource.acquireDepth(frame)
        } else null

        val cameraImage = runCatching { frame.acquireCameraImage() }.getOrNull()
        if (cameraImage == null) {
            isAiProcessing = false
            return
        }
        isAiProcessing = true

        scope.launch(Dispatchers.Default) {
            try {
                val mode = _aiPreviewMode.value
                val startTime = System.currentTimeMillis()

                if (mode == AIPreviewMode.YOLO) {
                    if (!yoloEngine.isModelLoaded) {
                        _yoloDetections.value = emptyList()
                        _aiStats.value = "YOLOv8: model yüklü değil (assets'e yolov8s.tflite ekleyin)"
                        return@launch
                    }
                    val rotatedBitmap = tfliteDepthSource.bitmapFromImage(cameraImage, currentDepthRotationDegrees(displayRotation)) ?: return@launch
                    val detections = yoloEngine.infer(rotatedBitmap)
                    val inferenceTime = System.currentTimeMillis() - startTime
                    _aiStats.value = "YOLOv8: ${inferenceTime}ms, ACCEL: ${if (yoloEngine.isNpuOrGpuAccelerated) "✓" else "x"}"
                    _yoloDetections.value = detections
                } else if (mode == AIPreviewMode.DEPTH) {
                    val rotatedBitmap = tfliteDepthSource.bitmapFromImage(cameraImage, currentDepthRotationDegrees(displayRotation)) ?: return@launch
                    val depthMap = arDepthMap ?: tfliteDepthSource.depthFromBitmap(rotatedBitmap)

                    _depthSourceState.value = when {
                        arDepthMap != null -> DepthSourceState.AR_CORE
                        depthMap != null -> DepthSourceState.TFLITE
                        else -> DepthSourceState.NONE
                    }

                    if (depthMap == null) {
                        _depthHeatmap.value = null
                        _aiStats.value = "DEPTH: model yüklü değil (assets'e depth_anything_v2_small.tflite ekleyin)"
                        return@launch
                    }
                    val inferenceTime = System.currentTimeMillis() - startTime

                    val newPoints = depthToPointsUseCase.execute(depthMap, currentPose, rotatedBitmap)
                    pointCloudStore.addPoints(newPoints)

                    val heatmap = tfliteDepthSource.depthToColormap(depthMap.depths, depthMap.width, depthMap.height)
                    _depthHeatmap.value = heatmap
                    _aiStats.value = buildDepthStats(depthMap, inferenceTime)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in AI Frame Pipeline: ${e.message}", e)
            } finally {
                runCatching { cameraImage.close() }
                isAiProcessing = false
            }
        }
    }

    private fun buildDepthStats(depthMap: DepthMap?, inferenceTimeMs: Long): String {
        val source = when {
            depthMap == null -> "DEPTH: yok"
            depthMap.isMetric -> "DEPTH: ARCore (metrik)"
            else -> "DEPTH: AI (kalibre)"
        }

        val accel = when {
            depthMap == null -> ""
            depthMap.isMetric -> "ACCEL: ✓"
            else -> "ACCEL: ${if (tfliteDepthSource.isAccelerated) "✓" else "x"}"
        }
        return "$source, Pts: ${pointCloudStore.pointCount.value}, $accel (${inferenceTimeMs}ms)"
    }

    private fun currentDepthRotationDegrees(displayRotation: Int): Float {
        val displayRot = when (displayRotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        return ((90 - displayRot + 360) % 360).toFloat()
    }

    companion object {
        private const val TAG = "AIInferenceManager"
        private const val AI_FRAME_SKIP_DIVISOR = 3
    }
}
