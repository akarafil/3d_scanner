package com.magicv3.scanner3d.infra.camera

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Phase 2.1.1 — Multi-lens capture orchestrator.
 *
 * Wraps RawAuxCaptureSession to deliver:
 *  1. Multi-frame burst from a single lens ( autoFocus settle + N still frames )
 *  2. Lens switching: take one photo from each of [Tele, UW, Main] for rich-texture scanning
 *  3. Centralised output directory management (aux_captures) — caller-visible StateFlow
 *  4. Retry on transient onError=2 (legitimate for multi-lens warmup race)
 *
 * Not in scope yet:
 *  - Concurrent multi-lens sessions (Honor allows 1 open camera at a time)
 *  - EXIF orientation stamping (will come in Phase 2.1.2 with EXIFWriter)
 *  - 3D-reconstruction code itself
 */
class MultiLensCaptureOrchestrator(
    private val context: Context,
    private val outputDir: File =
        File(context.filesDir, "aux_captures").apply { mkdirs() },
) {
    companion object {
        private const val TAG = "LensOrchestrator"

        /** Default target lens for single-obje scanning. */
        const val DEFAULT_LENS = RawAuxCaptureSession.AUX_TELEPHOTO_ID

        /** Default burst count — 3 frames covers AF wiggle + redundancy. */
        const val DEFAULT_BURST = 3

        /** Inter-frame gap to let AE/AF re-converge (ms). */
        private const val INTER_FRAME_GAP_MS = 250L

        /** Hard retry cap per frame to avoid infinite loop on persistent onError=2. */
        private const val MAX_RETRIES_PER_FRAME = 2
    }

    /** Public progress channel: 0.0..1.0 across total frames. */
    sealed interface CaptureProgress {
        data object Idle : CaptureProgress
        data class FrameStarted(val index: Int, val total: Int, val lensId: String) : CaptureProgress
        data class FrameSuccess(val index: Int, val total: Int, val lensId: String, val file: File) : CaptureProgress
        data class FrameFailure(val index: Int, val total: Int, val lensId: String, val error: Throwable) : CaptureProgress
        data class BurstDone(val files: List<File>) : CaptureProgress
    }

    private val _progress = MutableStateFlow<CaptureProgress>(CaptureProgress.Idle)
    val progress: StateFlow<CaptureProgress> = _progress.asStateFlow()

    /**
     * Take a burst of [count] frames from one lens, with AF settle between shots.
     * Returns the list of saved files (some may be missing if individual frames failed).
     */
    suspend fun captureBurst(
        lensId: String = DEFAULT_LENS,
        count: Int = DEFAULT_BURST,
    ): List<File> = withContext(Dispatchers.IO) {
        val files = mutableListOf<File>()
        for (i in 0 until count) {
            _progress.value = CaptureProgress.FrameStarted(i, count, lensId)
            val file = captureWithRetry(lensId, attempt = 0)
            if (file != null) {
                files.add(file)
                _progress.value = CaptureProgress.FrameSuccess(i, count, lensId, file)
            } else {
                _progress.value =
                    CaptureProgress.FrameFailure(i, count, lensId, IOException("frame $i failed after retries"))
            }
            if (i < count - 1) delay(INTER_FRAME_GAP_MS)
        }
        _progress.value = CaptureProgress.BurstDone(files.toList())
        files.toList()
    }

    /**
     * Take one frame from each lens in [lensIds] sequentially (Honor allows single-session only).
     * Default = [Tele, Ultrawide] — the two plain aux sensors. Main (id=0) can be added by caller.
     */
    suspend fun captureMultiLens(
        lensIds: List<String> = listOf(
            RawAuxCaptureSession.AUX_TELEPHOTO_ID,
            RawAuxCaptureSession.AUX_ULTRAWIDE_ID,
        ),
    ): Map<String, File> = withContext(Dispatchers.IO) {
        val result = LinkedHashMap<String, File>()
        for (id in lensIds) {
            _progress.value = CaptureProgress.FrameStarted(result.size, lensIds.size, id)
            val file = captureWithRetry(id, attempt = 0)
            if (file != null) {
                result[id] = file
                _progress.value = CaptureProgress.FrameSuccess(result.size - 1, lensIds.size, id, file)
            } else {
                _progress.value =
                    CaptureProgress.FrameFailure(result.size, lensIds.size, id, IOException("lens $id failed"))
            }
            delay(INTER_FRAME_GAP_MS)
        }
        _progress.value = CaptureProgress.BurstDone(result.values.toList())
        result
    }

    private suspend fun captureWithRetry(lensId: String, attempt: Int): File? {
        if (attempt > MAX_RETRIES_PER_FRAME) {
            Log.w(TAG, "[$lensId] giving up after $attempt retries")
            return null
        }
        val session = RawAuxCaptureSession(context, lensId, outputDir)
        val result = session.captureSingleFrame()
        return if (result.isSuccess) {
            result.getOrNull()!!.also { Log.i(TAG, "[$lensId] frame saved on attempt=$attempt") }
        } else {
            val err = result.exceptionOrNull()
            Log.w(TAG, "[$lensId] capture attempt=$attempt failed: ${err?.message}")
            // OnError=2 is transient for recently-closed sessions — short backoff before retry.
            delay(150L * (attempt + 1))
            captureWithRetry(lensId, attempt + 1)
        }
    }
}
