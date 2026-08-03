package com.magicv3.scanner3d.infra.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Batch-3 — Pro kamera yetenekleri.
 *
 * Cihazdan kamera AÇILMADAN okunabilen CameraCharacteristics değerlerini toplar
 * (getCameraCharacteristics kapalı kamera üzerinde de çalışır) ve EV/WB dönüşümlerinin
 * saf matematik fonksiyonlarını barındırır.
 *
 * Tüm cihaz okumaları runCatching ile sarılır — hiçbir çağrı throw etmez, başarısızlıkta
 * sabit fallback değerlere düşer. Saf fonksiyonlar (evToCameraUnits / kelvinToRgbGains)
 * Android'e bağımlı değildir ve birim test edilebilir (ProCameraMathTest).
 */
object ProCameraCapabilities {

    private const val TAG = "ProCameraCaps"

    /** ISO aralığı fallback'i — Honor Magic V3 tele sensörü tipik değeri. */
    private val DEFAULT_ISO_RANGE = 100..1600

    /** AE kompanzasyon aralığı fallback'i (kamera birimi). */
    private val DEFAULT_EV_RANGE = -12..12

    /** AE kompanzasyon adımı fallback'i (1/6 EV ≈ 0.1667). */
    private const val DEFAULT_EV_STEP = 1f / 6f

    /**
     * SENSOR_INFO_SENSITIVITY_RANGE → ISO aralığı (IntRange).
     * Cihaz yanıtı yoksa/okunamazsa 100..1600 döner.
     */
    fun readSensitivityRange(cameraManager: CameraManager, cameraId: String): IntRange =
        runCatching {
            val range = cameraManager.getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            range?.let { it.lower..it.upper } ?: DEFAULT_ISO_RANGE
        }.getOrElse { e ->
            Log.w(TAG, "SENSOR_INFO_SENSITIVITY_RANGE okunamadı: ${e.message}")
            DEFAULT_ISO_RANGE
        }

    /**
     * CONTROL_AE_COMPENSATION_RANGE → EV kompanzasyon aralığı (IntRange, kamera birimi).
     * Cihaz yanıtı yoksa/okunamazsa -12..12 döner.
     */
    fun readAeCompensationRange(cameraManager: CameraManager, cameraId: String): IntRange =
        runCatching {
            val range = cameraManager.getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
            range?.let { it.lower..it.upper } ?: DEFAULT_EV_RANGE
        }.getOrElse { e ->
            Log.w(TAG, "CONTROL_AE_COMPENSATION_RANGE okunamadı: ${e.message}")
            DEFAULT_EV_RANGE
        }

    /**
     * CONTROL_AE_COMPENSATION_STEP → EV adımı (Float, kamera birimi).
     * Rational numerator/denominator bölümü olarak okunur (örn. 1/6 ≈ 0.1667).
     * Cihaz yanıtı yoksa/okunamazsa 1f/6f döner.
     */
    fun readAeCompensationStep(cameraManager: CameraManager, cameraId: String): Float =
        runCatching {
            val step = cameraManager.getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
            step?.let { it.numerator.toFloat() / it.denominator.toFloat() } ?: DEFAULT_EV_STEP
        }.getOrElse { e ->
            Log.w(TAG, "CONTROL_AE_COMPENSATION_STEP okunamadı: ${e.message}")
            DEFAULT_EV_STEP
        }

    /**
     * EV (stop) değerini kameranın AE kompanzasyon birimine çevirir.
     *
     * CONTROL_AE_EXPOSURE_COMPENSATION, AE adımının (step) katları cinsinden istenen
     * pozlama kaydırmasıdır: units = (ev / step).roundToInt() — ardından cihazın
     * desteklediği [lower, upper] aralığına kıstırılır.
     *
     * Saf fonksiyon — birim test edilebilir (ProCameraMathTest).
     */
    internal fun evToCameraUnits(ev: Float, step: Float, lower: Int, upper: Int): Int =
        (ev / step).roundToInt().coerceIn(lower, upper)

    /**
     * Kelvin cinsinden renk sıcaklığını (R-kazanç, B-kazanç) çiftine çevirir.
     *
     * Tanner-Helland yaklaşımı kullanılır: kelvin/100 → RGB (0..255) parçalı formüller,
     * ardından yeşil=1 olacak şekilde normalize edilir:
     *   rGain = (r/255) / (g/255), bGain = (b/255) / (g/255).
     * g==0 ise nötr (1f, 1f) döner. Girdi 1000..40000 aralığına kıstırılır.
     *
     * Saf fonksiyon — birim test edilebilir (ProCameraMathTest).
     */
    internal fun kelvinToRgbGains(kelvin: Int): Pair<Float, Float> {
        val temp = kelvin.coerceIn(1000, 40000) / 100f

        val red: Float
        val green: Float
        val blue: Float

        if (temp <= 66f) {
            red = 255f
            green = 99.4708025861f * ln(temp) - 161.1195681661f
            blue = if (temp <= 19f) 0f else 138.5177312231f * ln(temp - 10f) - 305.0447927307f
        } else {
            red = 329.698727446f * (temp - 60f).pow(-0.1332047592f)
            green = 288.1221695283f * (temp - 60f).pow(-0.0755148492f)
            blue = 255f
        }

        val r = red.coerceIn(0f, 255f)
        val g = green.coerceIn(0f, 255f)
        val b = blue.coerceIn(0f, 255f)

        if (g == 0f) return 1f to 1f

        val rGain = (r / 255f) / (g / 255f)
        val bGain = (b / 255f) / (g / 255f)
        return rGain to bGain
    }
}
