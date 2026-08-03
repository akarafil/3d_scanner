package com.magicv3.scanner3d.infra.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import android.util.Size
import android.util.SizeF
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Phase 2.2.1 — Aux bypass ID'leri için synthetic CameraLens resolver.
 *
 * Honor getCameraIdList() aux ID'leri [2..5,8,9] gizlediği için
 * LensCatalog bu ID'leri içermiyor. Bu sınıf, her bir aux bypass ID için
 * CameraManager.getCameraCharacteristics(id) doğrudan çağırıp (AuxProbe'un
 * zaten "OK" dediği ID'ler için), CameraLens tipinde tam metadata çıkarır.
 *
 * Bu sayede AuxExifWriter her JPEG'e gerçek LensType ve FocalLength
 * stamplayebilir.
 */
class AuxLensCatalog(private val context: Context) {

    /**
     * Bilinen bypass ID'leri için synthetic CameraLens map'i oluşturur.
     *
     * @param auxIds AuxProbe tarafından OPENABLE olarak teyit edilen ID'ler
     *              (örn. [2, 3, 4, 5, 8, 9])
     * @return lensId -> CameraLens eşlemi (oluşturulamayan ID'ler hariç)
     */
    suspend fun resolve(auxIds: List<String>): Map<String, CameraLens> =
        withContext(Dispatchers.IO) {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val result = mutableMapOf<String, CameraLens>()

            for (id in auxIds) {
                val lens = runCatching { buildLens(manager, id) }.getOrNull()
                if (lens != null) {
                    result[id] = lens
                    Log.i(
                        TAG,
                        "[$id] resolved: type=${lens.lensType} focal=${lens.focalLengthMm}mm" +
                            " (${lens.focalLength35mmEquiv}mm equiv) orient=${lens.sensorOrientationDegrees}°" +
                            " pixel=${lens.megapixels}MP"
                    )
                } else {
                    Log.w(TAG, "[$id] resolve failed — skipping")
                }
            }

            Log.i(TAG, "AuxLensCatalog resolution complete: ${result.size}/${auxIds.size} lens mapped")
            result
        }

    /**
     * Tek bir aux ID için CameraLens inşa eder.
     * AuxProbe zaten characteristics okumasının SecurityException vermediğini
     * teyit etmiş durumda — yani getCameraCharacteristics burada güvenli.
     */
    private fun buildLens(manager: CameraManager, id: String): CameraLens {
        val ch = manager.getCameraCharacteristics(id)

        // ---- Optics ----
        val focalLengths = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?: floatArrayOf(0f)
        val focalMm = focalLengths.firstOrNull() ?: 0f

        val sensorSize = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            ?: SizeF(0f, 0f)
        val pixelArraySize = ch.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        val pixelW = pixelArraySize?.width ?: 0
        val pixelH = pixelArraySize?.height ?: 0

        // ---- Identity ----
        val facing = ch.get(CameraCharacteristics.LENS_FACING)
            ?: CameraCharacteristics.LENS_FACING_BACK
        val sensorOrientation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

        // ---- Classification (zoom ratio bazlı — LensCatalog ile aynı heuristik) ----
        // Aux bypass sensörlerin RAW_ZOOM_RATIO bilgisine doğrudan erişmek
        // risklidir (API 30+) — focal length ve pixel sayısıyla sınıflandırıyoruz.
        val lensType = classifyByFocalAndPixels(focalMm, sensorSize, pixelW * pixelH)

        // ---- 35mm equivalent (crop factor) ----
        val diagMm = if (sensorSize.width > 0f && sensorSize.height > 0f) {
            kotlin.math.sqrt(
                (sensorSize.width * sensorSize.width +
                 sensorSize.height * sensorSize.height).toDouble()
            ).toFloat()
        } else 0f
        val focal35mm = if (diagMm > 0f) (focalMm * FULL_FRAME_DIAG_MM / diagMm) else 0f

        return CameraLens(
            logicalId = id,
            physicalId = id,           // aux bypass: kendisi bir "physical" gibi davranır
            lensType = lensType,
            facing = facing,
            focalLengthMm = focalMm,
            focalLength35mmEquiv = focal35mm,
            sensorPhysicalSizeMm = sensorSize,
            pixelArraySize = Size(pixelW, pixelH),
            megapixels = (pixelW * pixelH) / 1_000_000f,
            zoomRatioVsMain = if (lensType == CameraLensType.TELEPHOTO) 5f
                else if (lensType == CameraLensType.ULTRAWIDE) 0.5f
                else 1f,
            isPhysical = true,
            hasLogicalMultiCamera = false,
            sensorOrientationDegrees = sensorOrientation
        )
    }

    /**
     * Focal length + pixel count'a göre lens tipini heuristik sınıflandırır.
     *
     * Honor Magic V3 teyit edilmiş değerler (AuxProbe summary):
     *  - id=2  → 1.96mm, 3520x2640 (UW)
     *  - id=4  → 14.92mm, 4096x3072 (Tele, 5x)
     *  - id=3,5 → 1.92mm, 2560x1920 (Selfie logical sub-id)
     *  - id=8,9 → 1.92mm, 640x480  (safety/depth sensörleri, fallback)
     *  - id=6,7 → 5.4mm, rejected
     *
     * Testability: birim testler doğrudan çağırabilsin diye internal.
     */
    internal fun classifyByFocalAndPixels(
        focalMm: Float,
        sensorSize: SizeF,
        pixelCount: Int,
    ): CameraLensType {
        // focal length yoksa unknown
        if (focalMm <= 0f) return CameraLensType.UNKNOWN

        // Telephoto: küçük sensör + uzun focal (≥ 12mm raw tipik olarak 5x zoom)
        // id=4: 14.92mm, ~6.5mm diagonal → 5x equiv = tele
        if (focalMm >= 10f) return CameraLensType.TELEPHOTO

        // Ultrawide: kısa focal (≤ 2.5mm) + ~9MP+ pixel
        // id=2: 1.96mm, 9.29MP → UW
        if (focalMm <= 2.5f && pixelCount >= 8_000_000) return CameraLensType.ULTRAWIDE

        // Kısa focal + daha düşük çözünürlük → selfie veya yardımcı sensör
        if (focalMm <= 2.5f && pixelCount in 4_000_000..8_000_000) return CameraLensType.SELFIE

        // Standart ana kamera focal (~5.4mm) — ama MAIN logicalten değilse beklenmez
        if (focalMm in 4f..8f) return CameraLensType.MAIN

        return CameraLensType.UNKNOWN
    }

    companion object {
        private const val TAG = "AuxLensCatalog"
        private const val FULL_FRAME_DIAG_MM = 43.27f  // sqrt(36² + 24²)
    }
}
