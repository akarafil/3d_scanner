package com.magicv3.scanner3d.infra.camera

import android.hardware.camera2.CameraCharacteristics
import android.util.Size
import android.util.SizeF

/**
 * Tek bir kamera sensörünü (logical veya physical) temsil eden immutable veri modeli.
 *
 * @property logicalId    Bu sensörün ait olduğu logical camera ID (örn. "0")
 * @property physicalId   Bu sensörün physical ID'si (logical ise logicalId'ye eşit)
 * @property lensType     Sınıflandırılmış sensör tipi (main/uw/tele/periscope/selfie)
 * @property facing        LENS_FACING_BACK / LENS_FACING_FRONT / LENS_FACING_EXTERNAL
 * @property focalLengthMm         Ham focal length (mm) - LENS_INFO_AVAILABLE_FOCAL_LENGTHS[0]
 * @property focalLength35mmEquiv  35mm formatına dönüştürülmüş eşdeğer focal length
 * @property sensorPhysicalSizeMm  Ham sensör boyutu (mm) - SENSOR_INFO_PHYSICAL_SIZE (SizeF)
 * @property pixelArraySize        Sensör çözünürlüğü (pixel) - SENSOR_INFO_PIXEL_ARRAY_SIZE (Size)
 * @property megapixels            Hesaplanan megapixels (pixelArraySize'dan)
 * @property zoomRatioVsMain       Main lens'e göre optik zoom oranı (main=1.0)
 * @property isPhysical            `getPhysicalCameraIds()`'ten geldiyse true; logical kendisiyse false
 * @property hasLogicalMultiCamera Parent logical camera LOGICAL_MULTI_CAMERA destekliyor mu
 */
data class CameraLens(
    val logicalId: String,
    val physicalId: String,
    val lensType: CameraLensType,
    val facing: Int,
    val focalLengthMm: Float,
    val focalLength35mmEquiv: Float,
    val sensorPhysicalSizeMm: SizeF,
    val pixelArraySize: Size,
    val megapixels: Float,
    val zoomRatioVsMain: Float,
    val isPhysical: Boolean,
    val hasLogicalMultiCamera: Boolean,
    val sensorOrientationDegrees: Int = 90
) {
    /** Kısa log-friendly biçim — logcat'e yazdırmak için */
    fun toLogString(): String {
        val facingLabel = when (facing) {
            CameraCharacteristics.LENS_FACING_BACK -> "BACK"
            CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
            CameraCharacteristics.LENS_FACING_EXTERNAL -> "EXTERNAL"
            else -> "facing=$facing"
        }
        return "[phys=$physicalId] type=${lensType.name.padEnd(10)} " +
            "focal=${focalLength35mmEquiv.toInt()}mm(raw=${focalLengthMm.format(1)}mm) " +
            "sensor=${sensorPhysicalSizeMm.width}x${sensorPhysicalSizeMm.height}mm " +
            "mp=${megapixels.format(1)} zoom=${zoomRatioVsMain.format(1)}x " +
            "physical=$isPhysical multiCam=$hasLogicalMultiCamera facing=$facingLabel"
    }

    private fun Float.format(decimals: Int): String = "%.${decimals}f".format(this)
}
