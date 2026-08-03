package com.magicv3.scanner3d.ui.scan.manager

import android.content.Context
import android.hardware.camera2.CameraManager
import android.util.Log
import com.magicv3.scanner3d.domain.ar.CameraPose
import com.magicv3.scanner3d.infra.camera.MultiLensCaptureOrchestrator
import com.magicv3.scanner3d.infra.camera.ProCameraCapabilities
import com.magicv3.scanner3d.infra.camera.RawAuxCaptureSession
import com.magicv3.scanner3d.ui.capture.CaptureState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

class CameraCaptureManager constructor(
    @ApplicationContext private val context: Context,
    val orchestrator: MultiLensCaptureOrchestrator
) {
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

    private val _evValue = MutableStateFlow(0f)
    val evValue: StateFlow<Float> = _evValue.asStateFlow()

    private val _colorTempValue = MutableStateFlow(5500)
    val colorTempValue: StateFlow<Int> = _colorTempValue.asStateFlow()

    private val _isoRange = MutableStateFlow(100..1600)
    val isoRange: StateFlow<IntRange> = _isoRange.asStateFlow()

    private val _evRange = MutableStateFlow(-12..12)
    val evRange: StateFlow<IntRange> = _evRange.asStateFlow()

    private val _evStep = MutableStateFlow(1f / 6f)
    val evStep: StateFlow<Float> = _evStep.asStateFlow()

    private val _multiLensMode = MutableStateFlow(false)
    val multiLensMode: StateFlow<Boolean> = _multiLensMode.asStateFlow()

    fun loadCapabilities(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            val cameraManager = runCatching {
                context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            }.getOrNull()
            if (cameraManager != null) {
                val teleId = RawAuxCaptureSession.AUX_TELEPHOTO_ID
                _isoRange.value = ProCameraCapabilities.readSensitivityRange(cameraManager, teleId)
                _evRange.value = ProCameraCapabilities.readAeCompensationRange(cameraManager, teleId)
                _evStep.value = ProCameraCapabilities.readAeCompensationStep(cameraManager, teleId)
                Log.i(TAG, "Pro capability ranges loaded: iso=${_isoRange.value}, ev=${_evRange.value}, step=${_evStep.value}")
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

    fun setEvValue(value: Float) {
        _evValue.value = value
    }

    fun setColorTemperature(value: Int) {
        _colorTempValue.value = value
    }

    fun setMultiLensMode(enabled: Boolean) {
        _multiLensMode.value = enabled
    }

    fun appliedSettingsSummary(): String = if (_isSettingsLocked.value) {
        String.format(
            java.util.Locale.US,
            "ISO %d • 1/%ds • F %.1f • WB %dK",
            _isoValue.value,
            _shutterFraction.value,
            _focusDistanceValue.value,
            _colorTempValue.value
        )
    } else {
        String.format(java.util.Locale.US, "EV %+.1f • AF/AE AUTO", _evValue.value)
    }

    fun triggerCapture(
        scope: CoroutineScope,
        isThermalThrottled: Boolean,
        pauseArCallback: () -> Unit,
        resumeArCallback: () -> Unit,
        latestCameraPose: CameraPose?,
        onThermalAbortLog: (String) -> Unit
    ) {
        if (isThermalThrottled) {
            onThermalAbortLog("❌ Cihaz kritik ısındı (>=85°C). Sıcaklık 80°C altına düşene kadar çekim yapılamaz.")
            Log.w(TAG, "Capture blocked due to thermal throttling.")
            return
        }

        if (_captureState.value != CaptureState.IDLE) return

        _triggerCounter.value += 1
        _captureState.value = CaptureState.CAPTURING

        val mode = _multiLensMode.value
        _lastCaptureLog.value = if (mode) "Multi-lens capture starting…" else "Tele burst ×3 starting…"

        scope.launch {
            pauseArCallback()
            try {
                val trans = latestCameraPose?.translation
                val rot = latestCameraPose?.rotationQuaternion

                val mIso = if (_isSettingsLocked.value) _isoValue.value else null
                val mExp = if (_isSettingsLocked.value) (1_000_000_000L / _shutterFraction.value) else null
                val mFoc = if (_isSettingsLocked.value) _focusDistanceValue.value else null
                val mEv = if (!_isSettingsLocked.value) _evValue.value else null
                val mWb = if (_isSettingsLocked.value) _colorTempValue.value else null

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
                        manualFocusDistance = mFoc,
                        manualEv = mEv,
                        manualColorTemperature = mWb
                    )
                } else {
                    orchestrator.captureBurst(
                        lensId = RawAuxCaptureSession.AUX_TELEPHOTO_ID,
                        count = 3,
                        translation = trans,
                        rotation = rot,
                        manualIso = mIso,
                        manualExposureTimeNs = mExp,
                        manualFocusDistance = mFoc,
                        manualEv = mEv,
                        manualColorTemperature = mWb
                    )
                }

                val fileCount: Int = if (mode) {
                    @Suppress("UNCHECKED_CAST")
                    val map = filesOrMap as Map<String, File>
                    map.size
                } else {
                    @Suppress("UNCHECKED_CAST")
                    val files = filesOrMap as List<File>
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
            } catch (e: Exception) {
                Log.e(TAG, "Capture pipeline failed", e)
                _captureState.value = CaptureState.ERROR
                _lastCaptureLog.value = "❌ Çekim hatası: ${e.message ?: "Bilinmeyen hata"}"
            } finally {
                resumeArCallback()
            }

            delay(if (_captureState.value == CaptureState.DONE) 600 else 1500)
            _captureState.value = CaptureState.IDLE
        }
    }

    companion object {
        private const val TAG = "CameraCaptureManager"
    }
}
