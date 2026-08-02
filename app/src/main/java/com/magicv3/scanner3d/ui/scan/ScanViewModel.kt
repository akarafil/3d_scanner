package com.magicv3.scanner3d.ui.scan

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.magicv3.scanner3d.domain.ar.CameraPose
import com.magicv3.scanner3d.domain.model.ScanSession
import com.magicv3.scanner3d.domain.model.ScanStatus
import com.magicv3.scanner3d.infra.camera.MultiLensCaptureOrchestrator
import com.magicv3.scanner3d.infra.camera.RawAuxCaptureSession
import com.magicv3.scanner3d.infra.ingestion.IngestionQueue
import com.magicv3.scanner3d.infra.storage.SessionFrameStore
import com.magicv3.scanner3d.infra.storage.ZipExporter
import com.magicv3.scanner3d.ui.capture.CaptureState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

class ScanViewModel(
    application: Application,
    val sessionFrameStore: SessionFrameStore,
    private val activeSession: ScanSession
) : AndroidViewModel(application) {

    private val context = application.applicationContext
    val orchestrator = MultiLensCaptureOrchestrator(context, sessionFrameStore)
    val ingestionQueue = IngestionQueue.getInstance(context)
    private val zipExporter = ZipExporter(context)

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

    init {
        viewModelScope.launch {
            orchestrator.bindSession(activeSession)
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

    fun resetZipShareState() {
        _zipShareState.value = ZipShareState.Idle
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

    fun triggerCapture(
        pauseArCallback: () -> Unit,
        resumeArCallback: () -> Unit,
        latestCameraPose: CameraPose?
    ) {
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
}
