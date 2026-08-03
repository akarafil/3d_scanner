package com.magicv3.scanner3d.ui.scan

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.hardware.camera2.CameraManager
import android.hardware.display.DisplayManager
import android.net.Uri
import android.util.Log
import android.view.Display
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.ar.core.ArCoreApk
import com.magicv3.scanner3d.domain.ar.CameraPose
import com.magicv3.scanner3d.domain.ar.DepthSourceState
import com.magicv3.scanner3d.domain.depth.DepthMap
import com.magicv3.scanner3d.domain.depth.DepthScaleEstimator
import com.magicv3.scanner3d.domain.depth.PointCloudStore
import com.magicv3.scanner3d.domain.model.ScanSession
import com.magicv3.scanner3d.domain.model.ScanStatus
import com.magicv3.scanner3d.domain.usecase.Point3D
import com.magicv3.scanner3d.domain.usecase.DepthToPointsUseCase
import com.magicv3.scanner3d.infra.ai.YoloInferenceEngine
import com.magicv3.scanner3d.infra.camera.MultiLensCaptureOrchestrator
import com.magicv3.scanner3d.infra.camera.ProCameraCapabilities
import com.magicv3.scanner3d.infra.camera.RawAuxCaptureSession
import com.magicv3.scanner3d.infra.depth.ArCoreDepthSource
import com.magicv3.scanner3d.infra.depth.CameraCharacteristicsIntrinsicsProvider
import com.magicv3.scanner3d.infra.depth.TfliteDepthSource
import com.magicv3.scanner3d.infra.ingestion.IngestionQueue
import com.magicv3.scanner3d.infra.storage.SessionFrameStore
import com.magicv3.scanner3d.infra.storage.ZipExporter
import com.magicv3.scanner3d.infra.storage.PlyExporter
import com.magicv3.scanner3d.infra.ingestion.IngestionState
import com.magicv3.scanner3d.infra.system.SystemMonitor
import com.magicv3.scanner3d.ui.capture.CaptureState
import com.magicv3.scanner3d.ui.scan.manager.AIInferenceManager
import com.magicv3.scanner3d.ui.scan.manager.CameraCaptureManager
import com.magicv3.scanner3d.ui.scan.manager.SessionExportManager
import com.magicv3.scanner3d.ui.scan.manager.ThermalSafetyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject


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

/**
 * B-3: ARCore kullanılabilirlik durumu.
 *
 * ARCore isteğe bağlı (optional) olduğundan desteklenmeyen cihazlarda
 * uygulama kamera çekimine devam edebilir; yalnızca poz takibi/AR overlay
 * kapatılır ve kullanıcıya anlamlı mesaj gösterilir.
 */
sealed class ArCoreAvailabilityState {
    data object Checking : ArCoreAvailabilityState()
    data object Available : ArCoreAvailabilityState()
    data class Unavailable(val reason: String) : ArCoreAvailabilityState()
}

@HiltViewModel
class ScanViewModel @Inject constructor(
    application: Application,
    val sessionFrameStore: SessionFrameStore,
    val pointCloudStore: PointCloudStore
) : AndroidViewModel(application) {

    @Inject
    lateinit var cameraCaptureManager: CameraCaptureManager

    @Inject
    lateinit var aiInferenceManager: AIInferenceManager

    @Inject
    lateinit var thermalSafetyManager: ThermalSafetyManager

    @Inject
    lateinit var sessionExportManager: SessionExportManager

    private val context = application.applicationContext
    private var activeSession: ScanSession? = null

    // Geriye dönük UI uyumluluğu için orchestrator'ı expose et
    val orchestrator get() = cameraCaptureManager.orchestrator
    val ingestionQueue get() = sessionExportManager // ingestionQueue wrapper or access

    fun initialize(session: ScanSession) {
        if (activeSession != null) return
        activeSession = session
        viewModelScope.launch {
            orchestrator.bindSession(session)
        }
    }

    fun initialize(sessionId: java.util.UUID) {
        if (activeSession != null) return
        viewModelScope.launch {
            val session = sessionFrameStore.getSession(sessionId)
            if (session != null) {
                activeSession = session
                orchestrator.bindSession(session)
            }
        }
    }


    // Delegated State Flows for UI compatibility
    val captureState get() = cameraCaptureManager.captureState
    val lastCaptureLog get() = cameraCaptureManager.lastCaptureLog
    val triggerCounter get() = cameraCaptureManager.triggerCounter
    val proControlsEnabled get() = cameraCaptureManager.proControlsEnabled
    val isSettingsLocked get() = cameraCaptureManager.isSettingsLocked
    val isoValue get() = cameraCaptureManager.isoValue
    val shutterFraction get() = cameraCaptureManager.shutterFraction
    val focusDistanceValue get() = cameraCaptureManager.focusDistanceValue
    val evValue get() = cameraCaptureManager.evValue
    val colorTempValue get() = cameraCaptureManager.colorTempValue
    val isoRange get() = cameraCaptureManager.isoRange
    val evRange get() = cameraCaptureManager.evRange
    val evStep get() = cameraCaptureManager.evStep
    val multiLensMode get() = cameraCaptureManager.multiLensMode

    val aiPreviewMode get() = aiInferenceManager.aiPreviewMode
    val aiStats get() = aiInferenceManager.aiStats
    val depthHeatmap get() = aiInferenceManager.depthHeatmap
    val yoloDetections get() = aiInferenceManager.yoloDetections
    val depthSourceState get() = aiInferenceManager.depthSourceState
    val yoloModelLoaded get() = aiInferenceManager.yoloModelLoaded

    val isThermalThrottled get() = thermalSafetyManager.isThermalThrottled
    val currentSocTemp get() = thermalSafetyManager.currentSocTemp
    val isThermalWarned get() = thermalSafetyManager.isThermalWarned

    val zipShareState get() = sessionExportManager.zipShareState
    val plyExportState get() = sessionExportManager.plyExportState
    val pointCount get() = pointCloudStore.pointCount

    private val _arCoreState = MutableStateFlow<ArCoreAvailabilityState>(ArCoreAvailabilityState.Checking)
    val arCoreState: StateFlow<ArCoreAvailabilityState> = _arCoreState.asStateFlow()

    val queueState get() = sessionExportManager.queueState

    private val _showMyScans = MutableStateFlow(false)
    val showMyScans: StateFlow<Boolean> = _showMyScans.asStateFlow()

    private val _openedSession = MutableStateFlow<ScanSession?>(null)
    val openedSession: StateFlow<ScanSession?> = _openedSession.asStateFlow()

    init {
        viewModelScope.launch {
            _arCoreState.value = checkArCoreAvailability()
            delay(10)
            cameraCaptureManager.loadCapabilities(viewModelScope)
            thermalSafetyManager.startMonitoring(viewModelScope) {
                // Emergency thermal safety callback
            }
        }
    }



    fun setProControlsEnabled(enabled: Boolean) = cameraCaptureManager.setProControlsEnabled(enabled)
    fun setSettingsLocked(locked: Boolean) = cameraCaptureManager.setSettingsLocked(locked)
    fun setIsoValue(value: Int) = cameraCaptureManager.setIsoValue(value)
    fun setShutterFraction(value: Int) = cameraCaptureManager.setShutterFraction(value)
    fun setFocusDistanceValue(value: Float) = cameraCaptureManager.setFocusDistanceValue(value)
    fun setEvValue(value: Float) = cameraCaptureManager.setEvValue(value)
    fun setColorTemperature(value: Int) = cameraCaptureManager.setColorTemperature(value)
    fun setMultiLensMode(enabled: Boolean) = cameraCaptureManager.setMultiLensMode(enabled)
    fun appliedSettingsSummary(): String = cameraCaptureManager.appliedSettingsSummary()

    fun setAiPreviewMode(mode: AIPreviewMode) = aiInferenceManager.setAiPreviewMode(mode, viewModelScope)
    fun resetZipShareState() = sessionExportManager.resetZipShareState()
    fun resetPlyExportState() = sessionExportManager.resetPlyExportState()
    fun resetThermalThrottled() = thermalSafetyManager.resetThermalThrottled()

    fun setShowMyScans(show: Boolean) {
        _showMyScans.value = show
    }

    fun setOpenedSession(session: ScanSession?) {
        _openedSession.value = session
    }

    fun clearAccumulatedPoints() {
        pointCloudStore.clear()
    }

    fun triggerZipShare(session: ScanSession) = sessionExportManager.triggerZipShare(session, viewModelScope)
    fun triggerPlyExport(session: ScanSession) = sessionExportManager.triggerPlyExport(session, viewModelScope)
    
    fun enqueue3DRender(session: ScanSession) {
        sessionExportManager.enqueue3DRender(session, viewModelScope) { logMessage ->
            // UI logging support (e.g. show warning logs)
        }
    }

    fun onFrameAvailable(frame: com.google.ar.core.Frame) {
        val displayRotation = currentDisplayRotation()
        aiInferenceManager.onFrameAvailable(
            frame = frame,
            isThermalWarned = thermalSafetyManager.isThermalWarned.value,
            isThermalThrottled = thermalSafetyManager.isThermalThrottled.value,
            scope = viewModelScope,
            displayRotation = displayRotation
        )
    }

    fun triggerCapture(
        pauseArCallback: () -> Unit,
        resumeArCallback: () -> Unit,
        latestCameraPose: CameraPose?
    ) {
        cameraCaptureManager.triggerCapture(
            scope = viewModelScope,
            isThermalThrottled = thermalSafetyManager.isThermalThrottled.value,
            pauseArCallback = pauseArCallback,
            resumeArCallback = resumeArCallback,
            latestCameraPose = latestCameraPose,
            onThermalAbortLog = { _ -> }
        )
    }

    fun resetIngestionToIdle() {
        sessionExportManager.resetIngestionToIdle()
    }

    fun reportArCoreUnavailable(reason: String) {
        if (_arCoreState.value !is ArCoreAvailabilityState.Unavailable) {
            _arCoreState.value = ArCoreAvailabilityState.Unavailable(reason)
        }
    }

    private suspend fun checkArCoreAvailability(): ArCoreAvailabilityState =
        withContext(Dispatchers.Default) {
            runCatching {
                when (ArCoreApk.getInstance().checkAvailability(context)) {
                    ArCoreApk.Availability.SUPPORTED_INSTALLED,
                    ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD -> ArCoreAvailabilityState.Available
                    ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED ->
                        ArCoreAvailabilityState.Unavailable("ARCore kurulu değil. Poz takibi kapalı; kamera çekimi devam eder.")
                    ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE ->
                        ArCoreAvailabilityState.Unavailable("Cihaz ARCore desteklemiyor. Poz takibi kapalı; kamera çekimi devam eder.")
                    else ->
                        ArCoreAvailabilityState.Unavailable("ARCore kullanılamıyor. Poz takibi kapalı.")
                }
            }.getOrElse { e ->
                Log.w(TAG, "ARCore availability check failed", e)
                ArCoreAvailabilityState.Unavailable(e.message ?: "ARCore kullanılamıyor.")
            }
        }

    private fun currentDisplayRotation(): Int {
        val displayManager = runCatching {
            context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        }.getOrNull() ?: return Surface.ROTATION_0
        return runCatching {
            displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.rotation
        }.getOrNull() ?: Surface.ROTATION_0
    }


    companion object {
        private const val TAG = "ScanViewModel"

        private const val THERMAL_WARNING_TEMP_C = 75f
        private const val THERMAL_ABORT_TEMP_C = 85f
        private const val THERMAL_WARNING_RECOVERY_TEMP_C = 70f
        private const val THERMAL_HARD_RECOVERY_TEMP_C = 80f
        private const val AI_FRAME_SKIP_DIVISOR = 3
    }
}
