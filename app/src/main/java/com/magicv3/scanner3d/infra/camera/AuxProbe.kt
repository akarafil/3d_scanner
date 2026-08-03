package com.magicv3.scanner3d.infra.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Phase 2.0.5 — Aux Probe. Honor'un [CameraManager.getCameraIdList]
 * filtresini bypass etmek için doğrudan [CameraManager.openCamera] ile "phantom" aux
 * ID'leri açmayı dener.
 *
 * B-6: Probe sonuçları artık ölü kod değil — [getCachedOpenableIds] / [refreshIfNeeded]
 * üzerinden gerçekten okunur ve [MultiLensCaptureOrchestrator.getAuxLensMap] tarafından
 * aux lens kataloğu için kullanılır. Cache, cihaz değişikliği (Build.FINGERPRINT) veya
 * TTL (24 saat) dolduğunda otomatik invalidate edilir.
 */
class AuxProbe(private val context: Context) {

    private val prefs = context.getSharedPreferences("aux_probe_cache", Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "AuxProbe"
        private const val OPEN_TIMEOUT_MS = 2000L
        private val PROBE_IDS = listOf("2", "3", "4", "5", "6", "7", "8", "9")
        private const val KEY_OPENABLE_IDS = "openable_lens_ids"
        private const val KEY_DEVICE_FINGERPRINT = "device_fingerprint"
        private const val KEY_PROBE_TIMESTAMP_MS = "probe_timestamp_ms"

        /** Cache TTL: 24 saat. Dolunca probe yeniden koşulur. */
        private const val CACHE_TTL_MS = 24L * 60 * 60 * 1000L
    }

    data class Result(
        val id: String,
        val charsOk: Boolean,
        val charsSummary: String,
        val openOutcome: OpenOutcome,
        val openError: String?
    )

    enum class OpenOutcome {
        NOT_ATTEMPTED,
        SUCCESS,
        REJECTED,
        TIMEOUT
    }

    /**
     * Cache'teki açılabilir aux lens ID'lerini döndürür.
     *
     * Invalidation stratejisi:
     *  - Cache başka bir cihaza (Build.FINGERPRINT farklı) aitse → boş döner, yeniden probe tetiklenir.
     *  - Cache TTL (24s) dolmuşsa → boş döner, yeniden probe tetiklenir.
     */
    fun getCachedOpenableIds(): List<String> {
        val fp = prefs.getString(KEY_DEVICE_FINGERPRINT, null)
        if (fp == null || fp != currentDeviceFingerprint()) {
            Log.i(TAG, "AuxProbe: cache device fingerprint mismatch — invalidated")
            return emptyList()
        }
        if (isCacheStale()) {
            Log.i(TAG, "AuxProbe: cache TTL expired — invalidated")
            return emptyList()
        }
        val cached = prefs.getString(KEY_OPENABLE_IDS, null) ?: return emptyList()
        return cached.split(",").map { it.trim() }.filter { it.isNotBlank() }
    }

    /**
     * Cache'i cihaz için taze tutar; gerekirse gerçek probe'u koşar.
     *
     * @param force true ise cache taze olsa bile probe'u zorla yeniler.
     * @return Çağrı sonunda geçerli bir açılabilir ID seti varsa true.
     */
    suspend fun refreshIfNeeded(force: Boolean = false): Boolean {
        if (!force && getCachedOpenableIds().isNotEmpty()) return true
        probe()
        return getCachedOpenableIds().isNotEmpty()
    }

    private fun isCacheStale(): Boolean {
        val ts = prefs.getLong(KEY_PROBE_TIMESTAMP_MS, 0L)
        return ts == 0L || System.currentTimeMillis() - ts > CACHE_TTL_MS
    }

    private fun currentDeviceFingerprint(): String =
        Build.FINGERPRINT.ifBlank { "${Build.MODEL}_${Build.DEVICE}" }

    /**
     * Tüm phantom ID'ler üzerinde gerçek probe koşar ve sonuçları cache'ler.
     * Cache zaten taze ise çağıranlar [refreshIfNeeded] kullanmalıdır.
     */
    suspend fun probe(): List<Result> = withContext(Dispatchers.IO) {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val results = mutableListOf<Result>()

        Log.i(TAG, "===== Faz 2.0.5 Aux Probe STARTED =====")
        Log.i(TAG, "Target IDs: $PROBE_IDS")

        // Single HandlerThread for all probe calls
        val thread = HandlerThread("AuxProbeThread").apply { start() }
        val handler = Handler(thread.looper)

        val openableIds = mutableListOf<String>()

        try {
            for (id in PROBE_IDS) {
                // Stage 1: characteristics
                val charsResult = runCatching { cameraManager.getCameraCharacteristics(id) }
                val charsOk = charsResult.isSuccess
                val summary = if (charsOk) {
                    val c = charsResult.getOrNull()!!
                    val facing = c.get(CameraCharacteristics.LENS_FACING)
                    val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                        ?.firstOrNull()
                    val pixelArr = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                    "facing=$facing focal=${focal}mm " +
                        "pixel=${pixelArr?.width}x${pixelArr?.height}"
                } else {
                    val e = charsResult.exceptionOrNull()
                    "${e?.javaClass?.simpleName}: ${e?.message}"
                }
                Log.i(TAG, "[id=$id] Stage 1 — characteristics: ok=$charsOk summary=$summary")

                // Stage 2: openCamera
                val (openOutcome, openErr) = if (charsOk) {
                    tryOpenCamera(cameraManager, id, handler)
                } else {
                    OpenOutcome.NOT_ATTEMPTED to "chars failed"
                }

                if (openOutcome == OpenOutcome.SUCCESS) {
                    openableIds.add(id)
                }

                Log.i(TAG, "[id=$id] Stage 2 — openCamera: outcome=$openOutcome err=$openErr")
                results.add(Result(id, charsOk, summary, openOutcome, openErr))
            }
        } finally {
            thread.quitSafely()
        }

        Log.i(TAG, "===== Faz 2.0.5 Aux Probe COMPLETE =====")

        // B-6: sonuçları cihaz kimliği + zaman damgasıyla cache'le.
        prefs.edit()
            .putString(KEY_OPENABLE_IDS, openableIds.joinToString(","))
            .putString(KEY_DEVICE_FINGERPRINT, currentDeviceFingerprint())
            .putLong(KEY_PROBE_TIMESTAMP_MS, System.currentTimeMillis())
            .apply()
        Log.i(TAG, "AuxProbe: cached openable lenses for ${currentDeviceFingerprint()}: $openableIds")

        results
    }

    private suspend fun tryOpenCamera(
        cameraManager: CameraManager,
        id: String,
        handler: Handler
    ): Pair<OpenOutcome, String?> = withTimeoutOrNull(OPEN_TIMEOUT_MS) {
        suspendCancellableCoroutine<Pair<OpenOutcome, String?>> { cont ->
            val cb = object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    Log.i(TAG, "[id=$id] StateCallback.onOpened — ✅ BYPASS")
                    runCatching { camera.close() }
                    if (cont.isActive) cont.resume(OpenOutcome.SUCCESS to null)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    Log.w(TAG, "[id=$id] StateCallback.onDisconnected — REJECTED")
                    runCatching { camera.close() }
                    if (cont.isActive) {
                        cont.resume(OpenOutcome.REJECTED to "onDisconnected")
                    }
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.w(TAG, "[id=$id] StateCallback.onError error=$error — REJECTED")
                    runCatching { camera.close() }
                    if (cont.isActive) {
                        cont.resume(OpenOutcome.REJECTED to "onError=$error")
                    }
                }
            }

            try {
                cameraManager.openCamera(id, cb, handler)
            } catch (e: SecurityException) {
                Log.w(TAG, "[id=$id] openCamera → SecurityException: ${e.message}")
                if (cont.isActive) {
                    cont.resume(OpenOutcome.REJECTED to "SecurityException: ${e.message}")
                }
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "[id=$id] openCamera → IAE: ${e.message}")
                if (cont.isActive) {
                    cont.resume(OpenOutcome.REJECTED to "IAE: ${e.message}")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "[id=$id] openCamera → ${t.javaClass.simpleName}: ${t.message}")
                if (cont.isActive) {
                    cont.resume(OpenOutcome.REJECTED to "${t.javaClass.simpleName}: ${t.message}")
                }
            }
        }
    } ?: (OpenOutcome.TIMEOUT to "2s timeout").also {
        Log.w(TAG, "[id=$id] ⏰ openCamera TIMEOUT — callback yutuldu")
    }
}
