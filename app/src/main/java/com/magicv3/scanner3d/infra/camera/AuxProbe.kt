package com.magicv3.scanner3d.infra.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Phase 2.0.5 — Geçici (throwaway) probe. Honor'un [CameraManager.getCameraIdList]
 * filtresini bypass etmek için doğrudan [CameraManager.openCamera] ile "phantom" aux
 * ID'leri (Honor vendor service logcat'inden gelen "Camera 6/7 status callback"
 * sinyali) açmayı dener.
 *
 * Bu sınıf tek bir amaç için yaşar: logcat'e "✅ id=6 OPENABLE" yazdırmak ya da
 * "❌ rejected: SecurityException: illegalAccessAuxCamera" teyidi. Sonuç alındıktan
 * sonra Faz 2.4'te kaldırılır.
 *
 * Test stratejisi (her ID için iki stage):
 *  - Stage 1: getCameraCharacteristics(id) — sync IPC, hızlı reject sinyali verir
 *  - Stage 2: openCamera(id, callback, handler) — gerçek session açma denemesi
 *             (sadece Stage 1 OK ise)
 *  - 2 saniye timeout: onOpened → SUCCESS, onFailure/disconnect → REJECTED,
 *                    no callback → TIMEOUT
 *
 * Probe ID aralığı: ["2".."9"] — [0, 1] zaten açık,感兴趣 ID'ler vendor service
 * logcat tarafından işaret edilen 6 ve 7 (gerçek UW + Periscope tahmini).
 * Üst komşuları denemek niyetiyle [2,3,4,5,8,9] da taramaya dahil.
 */
class AuxProbe(private val context: Context) {

    companion object {
        private const val TAG = "AuxProbe"
        private const val OPEN_TIMEOUT_MS = 2000L
        private val PROBE_IDS = listOf("2", "3", "4", "5", "6", "7", "8", "9")
    }

    /** Tek bir probe ID'sinin sonucu — UI/string'e serialize edilmez, sadece logcat. */
    data class Result(
        val id: String,
        val charsOk: Boolean,
        val charsSummary: String,
        val openOutcome: OpenOutcome,
        val openError: String?
    )

    enum class OpenOutcome {
        NOT_ATTEMPTED,   // Stage 1 fail - Stage 2'ye geçilmedi
        SUCCESS,         // onOpened fired - HARİKA
        REJECTED,        // onError / SecurityException / IAE / onDisconnected
        TIMEOUT          // 2 saniye callback yok - vendor servisi sessizce yutmuş olmalı
    }

    suspend fun probe(): List<Result> = withContext(Dispatchers.IO) {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val results = mutableListOf<Result>()

        Log.i(TAG, "===== Faz 2.0.5 Aux Probe STARTED =====")
        Log.i(TAG, "Target IDs: $PROBE_IDS (Honor Magic V3, SD 8 Gen 3)")

        for (id in PROBE_IDS) {
            // ----- Stage 1: characteristics -----
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
            Log.i(TAG, "[id=$id] Stage 1 — characteristics: " +
                "ok=$charsOk  summary=$summary")

            // ----- Stage 2: openCamera (sadece Stage 1 OK ise) -----
            val (openOutcome, openErr) = if (charsOk) {
                tryOpenCamera(cameraManager, id)
            } else {
                OpenOutcome.NOT_ATTEMPTED to "chars failed"
            }

            Log.i(TAG, "[id=$id] Stage 2 — openCamera: outcome=$openOutcome " +
                "err=$openErr")

            results.add(Result(id, charsOk, summary, openOutcome, openErr))
        }

        // ----- Final summary -----
        Log.i(TAG, "===== Faz 2.0.5 Aux Probe COMPLETE =====")
        Log.i(TAG, "----- SUMMARY TABLE -----")
        results.forEach { r ->
            val tag = when (r.openOutcome) {
                OpenOutcome.SUCCESS -> "✅ OPENABLE"
                OpenOutcome.REJECTED -> "❌ REJECTED"
                OpenOutcome.TIMEOUT -> "⏰  TIMEOUT"
                OpenOutcome.NOT_ATTEMPTED -> "—  (chars failed)"
            }
            Log.i(TAG, "  id=${r.id.padEnd(2)}  chars=${if (r.charsOk) "OK " else "FAIL"}  " +
                "open=$tag  err=${r.openError ?: "—"}")
        }

        Log.i(TAG, "----- DECISION -----")
        val anySuccess = results.any { it.openOutcome == OpenOutcome.SUCCESS }
        if (anySuccess) {
            val successes = results.filter { it.openOutcome == OpenOutcome.SUCCESS }
            Log.i(TAG, "VERDICT: ✅ BYPASS SUCCESS — şu ID'ler açılabildi: " +
                "${successes.map { it.id }} → Phase 2.1: bu ID'lerde capture session kur")
        } else {
            Log.i(TAG, "VERDICT: ❌ TOTAL REJECT — HwCameraUtil politikası tüm aux " +
                "ID'leri kapatıyor. Phase 2 Path B (MAIN-only pivot pipeline)'ye geç.")
        }

        results
    }

    /**
     * [CameraManager.openCamera] çağrısını suspend coroutine'e taşır.
     * 2 saniye timeout; açılırsa hemen [CameraDevice.close] — amacımız "capture kurmak"
     * değil, sadece "kamera servisi bu ID'yi açıyor mu?" sorusunun cevabı.
     */
    private suspend fun tryOpenCamera(
        cameraManager: CameraManager,
        id: String
    ): Pair<OpenOutcome, String?> = withTimeoutOrNull(OPEN_TIMEOUT_MS) {
        suspendCancellableCoroutine<Pair<OpenOutcome, String?>> { cont ->
            val hThread = HandlerThread("AuxProbe-$id").also { it.start() }
            val handler = Handler(hThread.looper)

            val cb = object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    Log.i(TAG, "[id=$id] StateCallback.onOpened — ✅ BYPASS")
                    runCatching { camera.close() }
                    hThread.quitSafely()
                    if (cont.isActive) cont.resume(OpenOutcome.SUCCESS to null)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    Log.w(TAG, "[id=$id] StateCallback.onDisconnected — REJECTED")
                    runCatching { camera.close() }
                    hThread.quitSafely()
                    if (cont.isActive) {
                        cont.resume(OpenOutcome.REJECTED to "onDisconnected")
                    }
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.w(TAG, "[id=$id] StateCallback.onError error=$error — REJECTED")
                    runCatching { camera.close() }
                    hThread.quitSafely()
                    if (cont.isActive) {
                        cont.resume(OpenOutcome.REJECTED to "onError=$error")
                    }
                }
            }

            try {
                cameraManager.openCamera(id, cb, handler)
            } catch (e: SecurityException) {
                // 🚨 İSTEDİĞİMİZ SİNYAL — Honor HwCameraUtil'in illegalAccessAuxCamera
                // bu noktada SecurityException throw eder.
                Log.w(TAG, "[id=$id] openCamera → SecurityException: ${e.message}")
                hThread.quitSafely()
                if (cont.isActive) {
                    cont.resume(OpenOutcome.REJECTED to "SecurityException: ${e.message}")
                }
            } catch (e: IllegalArgumentException) {
                // Bu ID kamera servisi seviyesinde tanınmıyor (HAL'de yok)
                Log.w(TAG, "[id=$id] openCamera → IAE: ${e.message}")
                hThread.quitSafely()
                if (cont.isActive) {
                    cont.resume(OpenOutcome.REJECTED to "IAE: ${e.message}")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "[id=$id] openCamera → ${t.javaClass.simpleName}: ${t.message}")
                hThread.quitSafely()
                if (cont.isActive) {
                    cont.resume(OpenOutcome.REJECTED to "${t.javaClass.simpleName}: ${t.message}")
                }
            }

            cont.invokeOnCancellation { hThread.quitSafely() }
        }
    } ?: (OpenOutcome.TIMEOUT to "2s timeout").also {
        Log.w(TAG, "[id=$id] ⏰  openCamera TIMEOUT — callback yutuldu / " +
            "camera service phantom-queued")
    }
}
