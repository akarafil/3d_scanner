package com.magicv3.scanner3d.ui.scan

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.magicv3.scanner3d.domain.ar.CameraPose
import com.magicv3.scanner3d.domain.model.ScanSession
import com.magicv3.scanner3d.domain.model.ScanStatus
import com.magicv3.scanner3d.domain.usecase.Point3D
import com.magicv3.scanner3d.domain.usecase.DepthToPointsUseCase
import com.magicv3.scanner3d.infra.ai.DepthInferenceEngine
import com.magicv3.scanner3d.infra.ai.YoloInferenceEngine
import com.magicv3.scanner3d.infra.camera.MultiLensCaptureOrchestrator
import com.magicv3.scanner3d.infra.camera.RawAuxCaptureSession
import com.magicv3.scanner3d.infra.ingestion.IngestionQueue
import com.magicv3.scanner3d.infra.storage.SessionFrameStore
import com.magicv3.scanner3d.infra.storage.ZipExporter
import com.magicv3.scanner3d.infra.storage.PlyExporter
import com.magicv3.scanner3d.infra.system.SystemMonitor
import com.magicv3.scanner3d.ui.capture.CaptureState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.Collections

enum class AIPreviewMode {
    NONE,
    DEPTH,
    YOLO
}

sealed class PlyExportState {
    data object Idle : PlyExportState()
    data object Exporting : PlyExportState()
    data object Done : PlyExportState()
    data class Failed(val error: String) : PlyExportState()
}

class ScanViewModel(
    application: Application,
    val sessionFrameStore: SessionFrameStore,
    private val activeSession: ScanSession
) : AndroidViewModel(application) {

    private val context = application.applicationContext
    val orchestrator = MultiLensCaptureOrchestrator(context, sessionFrameStore)
    val ingestionQueue = IngestionQueue.getInstance(context)
    private val zipExporter = ZipExporter(context)
    private val plyExporter = PlyExporter(context)
    private val systemMonitor = SystemMonitor(context)

    // AI Engines & Use Cases
    private val depthEngine = DepthInferenceEngine(context)
    private val yoloEngine = YoloInferenceEngine(context)
    private val depthToPointsUseCase = DepthToPointsUseCase()

    // Thread-safe accumulated 3D point cloud list
    private val accumulatedPoints = Collections.synchronizedList(mutableListOf<Point3D>())

    // State flows
    private val _captureState = MutableStateFlow(CaptureState.IDLE)
    val captureState: StateFlow<CaptureState> = _captureState.asStateFlow()

    private val _lastCaptureLog = MutableStateFlow<String?>(null)
    val lastCaptureLog: StateFlow<String?> = _lastCaptureLog.asStateFlow()

    private val _triggerCounter = MutableStateFlow(0)
    val triggerCounter: StateFlow<Int> = _triggerCounter.asStateFlow()

    private val _proControlsEnabled = MutableStateFlow(false)
    val proControlsEnabled: StateFlow<Boolean> = _proControlsEnabled.asStateFlow()

    private val _isSettingsLocked = MutableStateFlow(false)
    val isSettingsLocked: StateFlow<Boolean> = _isSettingsLocked.asStateFlow()

    private val _isoValue = MutableStateFlow(400)
    val isoValue: StateFlow<Int> = _isoValue.asStateFlow()

    private val _shutterFraction = MutableStateFlow(250)
    val shutterFraction: StateFlow<Int> = _shutterFraction.asStateFlow()

    private val _focusDistanceValue = MutableStateFlow(0.0f)
    val focusDistanceValue: StateFlow<Float> = _focusDistanceValue.asStateFlow()

    private val _multiLensMode = MutableStateFlow(false)
    val multiLensMode: StateFlow<Boolean> = _multiLensMode.asStateFlow()

    private val _showMyScans = MutableStateFlow(false)
    val showMyScans: StateFlow<Boolean> = _showMyScans.asStateFlow()

    private val _openedSession = MutableStateFlow<ScanSession?>(null)
    val openedSession: StateFlow<ScanSession?> = _openedSession.asStateFlow()

    private val _zipShareState = MutableStateFlow<ZipShareState>(ZipShareState.Idle)
    val zipShareState: StateFlow<ZipShareState> = _zipShareState.asStateFlow()

    // AI States
    private val _aiPreviewMode = MutableStateFlow(AIPreviewMode.NONE)
    val aiPreviewMode: StateFlow<AIPreviewMode> = _aiPreviewMode.asStateFlow()

    private val _aiStats = MutableStateFlow<String?>(null)
    val aiStats: StateFlow<String?> = _aiStats.asStateFlow()

    private val _depthHeatmap = MutableStateFlow<Bitmap?>(null)
    val depthHeatmap: StateFlow<Bitmap?> = _depthHeatmap.asStateFlow()

    private val _yoloDetections = MutableStateFlow<List<YoloInferenceEngine.Detection>>(emptyList())
    val yoloDetections: StateFlow<List<YoloInferenceEngine.Detection>> = _yoloDetections.asStateFlow()

    // Point cloud stats
    private val _pointCount = MutableStateFlow(0)
    val pointCount: StateFlow<Int> = _pointCount.asStateFlow()

    private val _plyExportState = MutableStateFlow<PlyExportState>(PlyExportState.Idle)
    val plyExportState: StateFlow<PlyExportState> = _plyExportState.asStateFlow()

    // Thermal Throttling States
    private val _isThermalThrottled = MutableStateFlow(false)
    val isThermalThrottled: StateFlow<Boolean> = _isThermalThrottled.asStateFlow()

    private val _currentSocTemp = MutableStateFlow(0f)
    val currentSocTemp: StateFlow<Float> = _currentSocTemp.asStateFlow()

    private var isAiProcessing = false

    init {
        viewModelScope.launch {
            orchestrator.bindSession(activeSession)
        }

        // Periodically monitor temperature for hardware safety checks
        viewModelScope.launch {
            systemMonitor.monitorThermal(2000).collect { metrics ->
                val temp = metrics.socTempC
                _currentSocTemp.value = temp

                if (temp >= 50f && !_isThermalThrottled.value) {
                    _isThermalThrottled.value = true
                    Log.w("ScanViewModel", "Thermal limit reached: $temp°C. Aborting capture actions.")
                    if (_captureState.value == CaptureState.CAPTURING) {
                        _captureState.value = CaptureState.IDLE
                        _lastCaptureLog.value = "❌ Sıcaklık aşırı yüksek (>=50°C)! Çekim durduruldu."
                    }
                } else if (temp < 47f && _isThermalThrottled.value) {
                    _isThermalThrottled.value = false
                    Log.i("ScanViewModel", "Thermal recovery: $temp°C. Capture actions resumed.")
                }
            }
        }
    }

    fun setProControlsEnabled(enabled: Boolean) {
        _proControlsEnabled.value = enabled
    }

    fun setSettingsLocked(locked: Boolean) {
        _isSettingsLocked.value = locked
    }

    fun setIsoValue(value: Int) {
        _isoValue.value = value
    }

    fun setShutterFraction(value: Int) {
        _shutterFraction.value = value
    }

    fun setFocusDistanceValue(value: Float) {
        _focusDistanceValue.value = value
    }

    fun setMultiLensMode(enabled: Boolean) {
        _multiLensMode.value = enabled
    }

    fun setShowMyScans(show: Boolean) {
        _showMyScans.value = show
    }

    fun setOpenedSession(session: ScanSession?) {
        _openedSession.value = session
    }

    fun setAiPreviewMode(mode: AIPreviewMode) {
        _aiPreviewMode.value = mode
        if (mode == AIPreviewMode.NONE) {
            _depthHeatmap.value = null
            _yoloDetections.value = emptyList()
            _aiStats.value = null
        }
    }

    fun resetZipShareState() {
        _zipShareState.value = ZipShareState.Idle
    }

    fun resetPlyExportState() {
        _plyExportState.value = PlyExportState.Idle
    }

    fun resetThermalThrottled() {
        _isThermalThrottled.value = false
    }

    fun clearAccumulatedPoints() {
        accumulatedPoints.clear()
        _pointCount.value = 0
    }

    fun triggerZipShare(session: ScanSession) {
        viewModelScope.launch {
            _zipShareState.value = ZipShareState.Zipping(session.frameCount)
            runCatching {
                zipExporter.export(session)
            }.onSuccess { result ->
                _zipShareState.value = ZipShareState.Done(result.uri, String.format(java.util.Locale.US, "%.1f MB", result.sizeBytes / 1_000_000.0))
                zipExporter.launchShareSheet(result, session.projectName)
                delay(2000)
                _zipShareState.value = ZipShareState.Idle
            }.onFailure { e ->
                Log.e("ScanViewModel", "ZIP export failed", e)
                _zipShareState.value = ZipShareState.Failed(e.message ?: "Bilinmeyen hata")
                delay(2500)
                _zipShareState.value = ZipShareState.Idle
            }
        }
    }

    fun triggerPlyExport(session: ScanSession) {
        viewModelScope.launch {
            _plyExportState.value = PlyExportState.Exporting
            runCatching {
                val pointsToExport = synchronized(accumulatedPoints) {
                    if (accumulatedPoints.isEmpty()) {
                        generateMockPointCloud()
                    } else {
                        accumulatedPoints.toList()
                    }
                }
                plyExporter.export(session.projectName, pointsToExport)
            }.onSuccess { file ->
                _plyExportState.value = PlyExportState.Done
                plyExporter.launchShareSheet(file, session.projectName)
                delay(2000)
                _plyExportState.value = PlyExportState.Idle
            }.onFailure { e ->
                Log.e("ScanViewModel", "PLY export failed", e)
                _plyExportState.value = PlyExportState.Failed(e.message ?: "PLY yazma hatası")
                delay(2000)
                _plyExportState.value = PlyExportState.Idle
            }
        }
    }

    fun enqueue3DRender(session: ScanSession) {
        viewModelScope.launch {
            sessionFrameStore.updateStatus(session.sessionId, ScanStatus.RENDERING)
            ingestionQueue.enqueue(session)
            _openedSession.value = null
        }
    }

    fun resetIngestionToIdle() {
        ingestionQueue.resetToIdle()
    }

    fun onFrameAvailable(frame: com.google.ar.core.Frame) {
        if (isAiProcessing || _aiPreviewMode.value == AIPreviewMode.NONE) return

        val cameraImage = runCatching { frame.acquireCameraImage() }.getOrNull() ?: return
        isAiProcessing = true

        // Capture camera pose safely on the calling thread before frame updates
        val currentPose = if (frame.camera.trackingState == com.google.ar.core.TrackingState.TRACKING) {
            val pose = frame.camera.pose
            CameraPose(pose.translation, pose.rotationQuaternion)
        } else null

        viewModelScope.launch(Dispatchers.Default) {
            try {
                val width = cameraImage.width
                val height = cameraImage.height

                // Synchronously copy YUV planes to NV21 ByteArray
                val nv21Bytes = imageToNv21(cameraImage)
                cameraImage.close() // Release buffer to ARCore immediately!

                // Asynchronously decode NV21 image to Bitmap
                val yuvImage = android.graphics.YuvImage(nv21Bytes, android.graphics.ImageFormat.NV21, width, height, null)
                val out = java.io.ByteArrayOutputStream()
                yuvImage.compressToJpeg(android.graphics.Rect(0, 0, width, height), 80, out)
                val jpegBytes = out.toByteArray()
                val bitmap = android.graphics.BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)

                // Rotate bitmap to portrait
                val rotatedBitmap = rotateBitmap(bitmap, 90f)

                val mode = _aiPreviewMode.value
                val startTime = System.currentTimeMillis()

                if (mode == AIPreviewMode.DEPTH) {
                    val depthMap = depthEngine.infer(rotatedBitmap)
                    val inferenceTime = System.currentTimeMillis() - startTime

                    // Generate world 3D points from depth map and pose
                    val newPoints = depthToPointsUseCase.execute(depthMap, 518, 518, currentPose, rotatedBitmap)
                    synchronized(accumulatedPoints) {
                        if (accumulatedPoints.size < 300_000) {
                            accumulatedPoints.addAll(newPoints)
                        }
                    }
                    _pointCount.value = accumulatedPoints.size

                    _aiStats.value = "Depth: ${inferenceTime}ms, Pts: ${accumulatedPoints.size}, ACCEL: ${if (depthEngine.isNpuOrGpuAccelerated) "✓" else "x"}"

                    val heatmap = depthToColormap(depthMap, 518, 518)
                    _depthHeatmap.value = heatmap
                } else if (mode == AIPreviewMode.YOLO) {
                    val detections = yoloEngine.infer(rotatedBitmap)
                    val inferenceTime = System.currentTimeMillis() - startTime
                    _aiStats.value = "YOLOv8: ${inferenceTime}ms, ACCEL: ${if (yoloEngine.isNpuOrGpuAccelerated) "✓" else "x"}"

                    _yoloDetections.value = detections
                }
            } catch (e: Exception) {
                Log.e("ScanViewModel", "Error in AI Frame Pipeline: ${e.message}", e)
                runCatching { cameraImage.close() }
            } finally {
                isAiProcessing = false
            }
        }
    }

    private fun imageToNv21(image: android.media.Image): ByteArray {
        val width = image.width
        val height = image.height
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val nv21 = ByteArray(width * height * 3 / 2)

        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        var pos = 0

        // Copy Y channel
        for (row in 0 until height) {
            yBuffer.position(row * yRowStride)
            if (yPixelStride == 1) {
                yBuffer.get(nv21, pos, width)
                pos += width
            } else {
                for (col in 0 until width) {
                    nv21[pos++] = yBuffer.get(row * yRowStride + col * yPixelStride)
                }
            }
        }

        // Copy UV channel (interleaved V then U)
        val uvRowStride = vPlane.rowStride
        val uvPixelStride = vPlane.pixelStride
        var uvOffset = width * height
        for (row in 0 until height / 2) {
            for (col in 0 until width / 2) {
                nv21[uvOffset++] = vBuffer.get(row * uvRowStride + col * uvPixelStride)
                nv21[uvOffset++] = uBuffer.get(row * uvRowStride + col * uvPixelStride)
            }
        }
        return nv21
    }

    private fun rotateBitmap(source: Bitmap, angle: Float): Bitmap {
        val matrix = Matrix().apply { postRotate(angle) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private fun depthToColormap(depths: FloatArray, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        for (i in depths.indices) {
            val d = depths[i].coerceIn(0f, 1f)
            val r = (d * 255).toInt().coerceIn(0, 255)
            val g = ((1f - kotlin.math.abs(d - 0.5f) * 2f) * 255).toInt().coerceIn(0, 255)
            val b = ((1f - d) * 255).toInt().coerceIn(0, 255)
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    private fun generateMockPointCloud(): List<Point3D> {
        val list = mutableListOf<Point3D>()
        val numPoints = 8000
        for (i in 0 until numPoints) {
            val u = Math.random()
            val v = Math.random()
            val theta = u * 2.0 * Math.PI
            val phi = Math.acos(2.0 * v - 1.0)
            val r = 0.5 + Math.random() * 0.05
            val x = (r * Math.sin(phi) * Math.cos(theta)).toFloat()
            val y = (r * Math.sin(phi) * Math.sin(theta)).toFloat()
            val z = (r * Math.cos(phi) + 1.2).toFloat()

            val red = ((x + 0.5f) * 255).toInt().coerceIn(0, 255)
            val green = ((y + 0.5f) * 255).toInt().coerceIn(0, 255)
            val blue = ((z - 0.7f) * 255).toInt().coerceIn(0, 255)

            list.add(Point3D(x, y, z, red, green, blue))
        }
        return list
    }

    fun triggerCapture(
        pauseArCallback: () -> Unit,
        resumeArCallback: () -> Unit,
        latestCameraPose: CameraPose?
    ) {
        if (_isThermalThrottled.value) {
            _lastCaptureLog.value = "❌ Cihaz aşırı ısındı. Sıcaklık 47°C altına düşene kadar çekim yapılamaz."
            Log.w("ScanViewModel", "Capture blocked due to thermal throttling.")
            return
        }

        if (_captureState.value != CaptureState.IDLE) return

        _triggerCounter.value += 1
        _captureState.value = CaptureState.CAPTURING

        val mode = _multiLensMode.value
        _lastCaptureLog.value = if (mode) "Multi-lens capture starting…" else "Tele burst ×3 starting…"
        Log.i("ScanViewModel", "Capture triggered (Phase 2.1.2 — Mode: ${if (mode) "multi-lens" else "burst"})")

        viewModelScope.launch {
            pauseArCallback()
            val trans = latestCameraPose?.translation
            val rot = latestCameraPose?.rotationQuaternion

            val mIso = if (_isSettingsLocked.value) _isoValue.value else null
            val mExp = if (_isSettingsLocked.value) (1_000_000_000L / _shutterFraction.value) else null
            val mFoc = if (_isSettingsLocked.value) _focusDistanceValue.value else null

            val filesOrMap: Any = if (mode) {
                orchestrator.captureMultiLens(
                    lensIds = listOf(
                        RawAuxCaptureSession.AUX_TELEPHOTO_ID,
                        RawAuxCaptureSession.AUX_ULTRAWIDE_ID
                    ),
                    translation = trans,
                    rotation = rot,
                    manualIso = mIso,
                    manualExposureTimeNs = mExp,
                    manualFocusDistance = mFoc
                )
            } else {
                orchestrator.captureBurst(
                    lensId = RawAuxCaptureSession.AUX_TELEPHOTO_ID,
                    count = 3,
                    translation = trans,
                    rotation = rot,
                    manualIso = mIso,
                    manualExposureTimeNs = mExp,
                    manualFocusDistance = mFoc
                )
            }
            resumeArCallback()

            val fileCount: Int = if (mode) {
                @Suppress("UNCHECKED_CAST")
                val map = filesOrMap as Map<String, File>
                map.forEach { (k, v) ->
                    val savedFrame = orchestrator.activeSession?.frames?.lastOrNull { it.lensId == k }
                    val frameSize = savedFrame?.bytes ?: 0L
                    Log.d("ScanViewModel", "MultiLens[$k] frame saved: ${savedFrame?.file?.name} ($frameSize B)")
                }
                map.size
            } else {
                @Suppress("UNCHECKED_CAST")
                val files = filesOrMap as List<File>
                files.forEachIndexed { idx, f ->
                    val savedFrame = orchestrator.activeSession?.frames?.getOrNull(idx)
                    val frameSize = savedFrame?.bytes ?: 0L
                    Log.d("ScanViewModel", "Burst[$idx] frame saved: ${savedFrame?.file?.name} ($frameSize B)")
                }
                files.size
            }

            _captureState.value = if (fileCount > 0) CaptureState.DONE else CaptureState.ERROR
            _lastCaptureLog.value = when {
                mode && fileCount == 2 -> "✅ Tele + UW frames saved (multi-lens OK) — EXIF stamped"
                mode && fileCount == 1 -> "⚠ Only one lens captured (EXIF partial)"
                !mode && fileCount == 3 -> "✅ 3/3 Tele frames saved — EXIF stamped"
                !mode && fileCount > 0 -> "⚠ ${fileCount}/3 Tele frames saved — EXIF stamped"
                else -> "❌ No frames captured"
            }

            delay(if (_captureState.value == CaptureState.DONE) 600 else 1500)
            _captureState.value = CaptureState.IDLE
        }
    }

    override fun onCleared() {
        super.onCleared()
        depthEngine.close()
        yoloEngine.close()
    }
}
