package com.magicv3.scanner3d.infra.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

import com.magicv3.scanner3d.domain.model.ScanSession
import com.magicv3.scanner3d.infra.storage.SessionFrameStore

/**
 * Phase 2.2.1 — Multi-lens capture orchestrator.
 *
 * Wraps RawAuxCaptureSession to deliver:
 *  1. Multi-frame burst from a single lens ( autoFocus settle + N still frames )
 *  2. Lens switching: take one photo from each of [Tele, UW, Main] for rich-texture scanning
 *  3. Centralised output directory management (aux_captures) — caller-visible StateFlow
 *  4. Retry on transient onError=2 (legitimate for multi-lens warmup race)
 *  5. EXIF orientation & optical metadata stamping resolved from AuxLensCatalog (Phase 2.2.1)
 *
 * Not in scope yet:
 *  - Concurrent multi-lens sessions (Honor allows 1 open camera at a time)
 *  - 3D-reconstruction code itself
 */
class MultiLensCaptureOrchestrator(
    private val context: Context,
    private val frameStore: SessionFrameStore = SessionFrameStore(context),
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

        /** Hard retry cap per frame to avoid infinite loop on transient onError=2. */
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

    private val exifWriter = AuxExifWriter()
    private val mutex = Mutex()
    private var auxLensMap: Map<String, CameraLens>? = null

    var activeSession: ScanSession? = null
        internal set

    fun bindSession(session: ScanSession) {
        activeSession = session
    }

    suspend fun startNewSession(store: SessionFrameStore, name: String? = null) {
        activeSession = store.createSession(name)
    }

    private suspend fun getAuxLensMap(): Map<String, CameraLens> = withContext(Dispatchers.IO) {
        auxLensMap ?: mutex.withLock {
            auxLensMap ?: run {
                val candidateIds = listOf("0", "1", "2", "3", "4", "5", "8", "9")
                val resolved = AuxLensCatalog(context).resolve(candidateIds)
                auxLensMap = resolved
                resolved
            }
        }
    }

    private fun readImageSize(file: File): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(file.absolutePath, options)
        return Pair(options.outWidth, options.outHeight)
    }

    private suspend fun captureAndStamp(
        lensId: String,
        sessionId: UUID,
        translation: FloatArray? = null,
        rotation: FloatArray? = null,
        manualIso: Int? = null,
        manualExposureTimeNs: Long? = null,
        manualFocusDistance: Float? = null,
    ): File? {
        val raw = captureWithRetry(
            lensId = lensId,
            attempt = 0,
            manualIso = manualIso,
            manualExposureTimeNs = manualExposureTimeNs,
            manualFocusDistance = manualFocusDistance
        ) ?: return null
        val (w, h) = runCatching { readImageSize(raw) }.getOrDefault(Pair(0, 0))
        val lens = getAuxLensMap()[lensId]
        val stamped = exifWriter.stamp(
            jpegFile = raw,
            lensId = lensId,
            lens = lens,
            width = w,
            height = h,
            sessionId = sessionId
        )
        if (stamped != null) {
            activeSession?.let { session ->
                val updatedSession = frameStore.appendFrame(
                    session = session,
                    sourceJpeg = stamped,
                    lensId = lensId,
                    lensType = lens?.lensType?.name ?: "UNKNOWN",
                    focalMm = lens?.focalLengthMm ?: 0f,
                    translation = translation,
                    rotation = rotation
                )
                activeSession = updatedSession
            }
        }
        return stamped
    }

    /**
     * Take a burst of [count] frames from one lens, with AF settle between shots.
     * Returns the list of saved files (some may be missing if individual frames failed).
     */
    suspend fun captureBurst(
        lensId: String = DEFAULT_LENS,
        count: Int = DEFAULT_BURST,
        translation: FloatArray? = null,
        rotation: FloatArray? = null,
        manualIso: Int? = null,
        manualExposureTimeNs: Long? = null,
        manualFocusDistance: Float? = null,
    ): List<File> = withContext(Dispatchers.IO) {
        val permissionGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (!permissionGranted) {
            Log.e(TAG, "[$lensId] Kamera izni yok!")
            _progress.value = CaptureProgress.FrameFailure(0, count, lensId, SecurityException("CAMERA permission missing"))
            return@withContext emptyList<File>()
        }

        val sessionId = activeSession?.sessionId ?: UUID.randomUUID()
        val files = mutableListOf<File>()

        val session = RawAuxCaptureSession(context, lensId, outputDir)
        try {
            Log.i(TAG, "[$lensId] Opening sticky camera session for burst of $count...")
            session.open()
            for (i in 0 until count) {
                _progress.value = CaptureProgress.FrameStarted(i, count, lensId)
                var attempt = 0
                var file: File? = null
                while (attempt <= MAX_RETRIES_PER_FRAME) {
                    try {
                        val raw = session.captureFrame(manualIso, manualExposureTimeNs, manualFocusDistance)
                        val (w, h) = runCatching { readImageSize(raw) }.getOrDefault(Pair(0, 0))
                        val lens = getAuxLensMap()[lensId]
                        val stamped = exifWriter.stamp(
                            jpegFile = raw,
                            lensId = lensId,
                            lens = lens,
                            width = w,
                            height = h,
                            sessionId = sessionId
                        )
                        if (stamped != null) {
                            activeSession?.let { scanSession ->
                                val updatedSession = frameStore.appendFrame(
                                    session = scanSession,
                                    sourceJpeg = stamped,
                                    lensId = lensId,
                                    lensType = lens?.lensType?.name ?: "UNKNOWN",
                                    focalMm = lens?.focalLengthMm ?: 0f,
                                    translation = translation,
                                    rotation = rotation
                                )
                                activeSession = updatedSession
                            }
                        }
                        file = stamped
                        break
                    } catch (e: Exception) {
                        Log.w(TAG, "[$lensId] Sticky capture attempt=$attempt failed: ${e.message}")
                        attempt++
                        if (attempt <= MAX_RETRIES_PER_FRAME) {
                            delay(150L * attempt)
                        }
                    }
                }

                if (file != null) {
                    files.add(file)
                    _progress.value = CaptureProgress.FrameSuccess(i, count, lensId, file)
                } else {
                    _progress.value =
                        CaptureProgress.FrameFailure(i, count, lensId, IOException("frame $i failed after retries"))
                }
                if (i < count - 1) delay(INTER_FRAME_GAP_MS)
            }
        } catch (e: Exception) {
            Log.e(TAG, "[$lensId] Sticky session burst failed: ${e.message}", e)
            _progress.value = CaptureProgress.FrameFailure(0, count, lensId, e)
        } finally {
            Log.i(TAG, "[$lensId] Closing sticky camera session.")
            runCatching { session.close() }
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
        translation: FloatArray? = null,
        rotation: FloatArray? = null,
        manualIso: Int? = null,
        manualExposureTimeNs: Long? = null,
        manualFocusDistance: Float? = null,
    ): Map<String, File> = withContext(Dispatchers.IO) {
        val sessionId = activeSession?.sessionId ?: UUID.randomUUID()
        val result = LinkedHashMap<String, File>()
        for (id in lensIds) {
            _progress.value = CaptureProgress.FrameStarted(result.size, lensIds.size, id)
            val file = captureAndStamp(
                lensId = id,
                sessionId = sessionId,
                translation = translation,
                rotation = rotation,
                manualIso = manualIso,
                manualExposureTimeNs = manualExposureTimeNs,
                manualFocusDistance = manualFocusDistance
            )
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

    private suspend fun captureWithRetry(
        lensId: String,
        attempt: Int,
        manualIso: Int? = null,
        manualExposureTimeNs: Long? = null,
        manualFocusDistance: Float? = null,
    ): File? {
        val permissionGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (!permissionGranted) {
            Log.e(TAG, "[$lensId] Kamera izni yok!")
            return null
        }

        if (attempt > MAX_RETRIES_PER_FRAME) {
            Log.w(TAG, "[$lensId] giving up after $attempt retries")
            return null
        }
        val session = RawAuxCaptureSession(context, lensId, outputDir)
        val result = session.captureSingleFrame(
            manualIso = manualIso,
            manualExposureTimeNs = manualExposureTimeNs,
            manualFocusDistance = manualFocusDistance
        )
        return if (result.isSuccess) {
            result.getOrNull()!!.also { Log.i(TAG, "[$lensId] frame saved on attempt=$attempt") }
        } else {
            val err = result.exceptionOrNull()
            Log.w(TAG, "[$lensId] capture attempt=$attempt failed: ${err?.message}")
            // OnError=2 is transient for recently-closed sessions — short backoff before retry.
            delay(150L * (attempt + 1))
            captureWithRetry(
                lensId = lensId,
                attempt = attempt + 1,
                manualIso = manualIso,
                manualExposureTimeNs = manualExposureTimeNs,
                manualFocusDistance = manualFocusDistance
            )
        }
    }
}
