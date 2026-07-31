package com.magicv3.scanner3d.infra.camera

import android.content.Context
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Phase 2.2 — Aux JPEG'leri için photogrammetry-grade EXIF metadata stamping.
 *
 * - Camera2 stream OTF'de metadata yazmıyor; biz disk'e yazıldıktan
 *   sonra atomic read-modify-write ile stamp HttpResponse ediyoruz.
 * - androidx.exifinterface.media.ExifInterface kullanarak Android 4.4-
 *   sonrası standartlarıyla tam uyum.
 */
class AuxExifWriter {

    /**
     * Bir aux JPEG dosyasına lens metadata'nı stamp HttpResponse eder.
     *
     * @param jpegFile    Disk üzerindeki aux_*.jpg dosyası
     * @param lensId      Kayıt lens ID (örn "4", "2")
     * @param lens        LensCatalog'dan gelen tanım (focal, type, sensor)
     * @param width       ImageReader tarafından raporlanan captured width
     * @param height      ImageReader tarafından raporlanan captured height
     * @param sessionId   Çekim oturumunun UUID'si (multi-lens grouping için)
     *
     * @return Aynı [jpegFile] referansı (chain için), ya da hata durumunda null
     */
    suspend fun stamp(
        jpegFile: File,
        lensId: String,
        lens: CameraLens?,
        width: Int,
        height: Int,
        sessionId: UUID,
    ): File? = withContext(Dispatchers.IO) {
        if (!jpegFile.exists() || jpegFile.length() == 0L) return@withContext null

        try {
            val exif = ExifInterface(jpegFile.absolutePath)

            // ---- Identity / Source ----
            exif.setAttribute(ExifInterface.TAG_MAKE, "Honor")
            exif.setAttribute(ExifInterface.TAG_MODEL, "Magic V3")
            exif.setAttribute(ExifInterface.TAG_SOFTWARE, "Magic 3D Scanner v2 - Phase 2.2 (AuxBypass)")

            // ---- Lens Identity (SfM feature grouping için) ----
            val lensModel = when (lens?.lensType) {
                CameraLensType.TELEPHOTO  -> "Telephoto-aux-$lensId"
                CameraLensType.ULTRAWIDE  -> "Ultrawide-aux-$lensId"
                CameraLensType.MAIN       -> "Main-aux-$lensId"
                CameraLensType.PERISCOPE  -> "Periscope-aux-$lensId"
                CameraLensType.SELFIE     -> "Selfie-aux-$lensId"
                CameraLensType.UNKNOWN    -> "Unknown-aux-$lensId"
                null                      -> "Unknown-aux-$lensId"
            }
            exif.setAttribute(ExifInterface.TAG_LENS_MAKE, "AuxBypass")
            exif.setAttribute(ExifInterface.TAG_LENS_MODEL, lensModel)

            // ---- Optics — Focal Length ----
            // TAG_FOCAL_LENGTH Rational formatında "num/den" mm olarak saklanır.
            // AuxLensCatalog raw focal'ı float olarak tutuyor → mm × 100 rational'a dönüştür.
            val focalMm = lens?.focalLengthMm ?: 0f
            if (focalMm > 0f) {
                val focalRational = "${(focalMm * 100).toInt()}/100"
                exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, focalRational)

                val focal35mm = lens?.focalLength35mmEquiv ?: 0f
                if (focal35mm > 0f) {
                    exif.setAttribute(
                        ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
                        focal35mm.toInt().toString(),
                    )
                }
            }

            // ---- Geometry — Orientation ----
            // Back-facing aux lens'ler sensor orientation = 90° veriyor (LensCatalog'ta gördük).
            // Bu, JPEG'in "yatay" gösterilmek için 90° CW döndürülmesi gerektiği anlamına gelir.
            // EXIF Orientation = 6 → "90° CW rotate" (camera manufacturer convention)
            val sensorOrientation = lens?.sensorOrientationDegrees ?: 90
            val orientationTag = when (sensorOrientation) {
                90  -> ExifInterface.ORIENTATION_ROTATE_90.toString()   // "6"
                180 -> ExifInterface.ORIENTATION_ROTATE_180.toString() // "3"
                270 -> ExifInterface.ORIENTATION_ROTATE_270.toString() // "8"
                else -> ExifInterface.ORIENTATION_NORMAL.toString()    // "1"
            }
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, orientationTag)

            // ---- Dimensions (bazı viewer'lar JPEG SOF'dan okumaz, explicit tag ister) ----
            exif.setAttribute(ExifInterface.TAG_IMAGE_WIDTH, width.toString())
            exif.setAttribute(ExifInterface.TAG_IMAGE_LENGTH, height.toString())
            exif.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, width.toString())
            exif.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, height.toString())

            // ---- Time ----
            val dateStr = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date())
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, dateStr)
            exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, dateStr)
            exif.setAttribute(ExifInterface.TAG_DATETIME, dateStr)

            // ---- User Comment — SfM grouping için custom metadata ----
            // ColMAP/Meshroom bu alanı parse edip lens ayrımı yapabilir.
            val comment = "lens_id=$lensId;focal=${focalMm}mm;session=${sessionId};scanner_phase=2.2"
            exif.setAttribute(ExifInterface.TAG_USER_COMMENT, comment)

            exif.saveAttributes()

            Log.i(TAG, "[$lensId] ✅ EXIF stamped: $lensModel, focal=${focalMm}mm, orient=$sensorOrientation°, ${width}x${height}")
            jpegFile
        } catch (e: Exception) {
            Log.e(TAG, "[$lensId] ❌ EXIF stamp failed: ${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "AuxExifWriter"
    }
}
