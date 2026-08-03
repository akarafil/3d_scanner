package com.magicv3.scanner3d.domain.usecase

import android.graphics.Bitmap
import android.graphics.Color
import com.magicv3.scanner3d.domain.depth.CameraIntrinsics
import com.magicv3.scanner3d.domain.depth.CameraIntrinsicsProvider
import com.magicv3.scanner3d.domain.depth.DepthMap
import com.magicv3.scanner3d.domain.depth.DepthScaleEstimator
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * DepthToPointsUseCase birim testleri.
 *
 * Kapsam:
 *  - Strateji C arayüz enjeksiyonu: DepthScaleEstimator + CameraIntrinsicsProvider
 *  - Gürültü filtreleri (depth <= 0.01 / >= 0.99 dışlanır)
 *  - Metre ölçeği ve <=0 fallback davranışı
 *  - Intrinsics projeksiyonu (provider dolu / SAFE_DEFAULT)
 *  - Quaternion rotasyon (identity + 90° Z dönüşü)
 *  - STRIDE=3 downsampling ve 518x518 mock depth nokta sayısı/menzil
 *
 * Bitmap gerektirdiği için Robolectric (NATIVE graphics) altında çalışır.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DepthToPointsUseCaseTest {

    private fun estimator(scale: Float): DepthScaleEstimator =
        mockk<DepthScaleEstimator>().apply { every { estimateScale() } returns scale }

    private fun intrinsicsProvider(intrinsics: CameraIntrinsics?): CameraIntrinsicsProvider? =
        mockk<CameraIntrinsicsProvider>().apply { every { getIntrinsics() } returns intrinsics }

    private fun bitmap(width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.RED)
        }

    @Test
    fun `sifirVeBireYakinDerinlik_noktayaKatilmaz`() {
        val useCase = DepthToPointsUseCase(estimator(2.5f))
        val depths = FloatArray(81) { 0.5f }
        // STRIDE=3, 9x9 grid → örneklenen noktalar (x,y) in {0,3,6} → idx {0,3,6,27,30,33,54,57,60}
        depths[0] = 0f       // <= 0.01 → atlanır
        depths[3] = 1f       // >= 0.99 → atlanır
        depths[6] = 0.01f    // sınır (<= 0.01) → atlanır
        depths[27] = 0.99f   // sınır (>= 0.99) → atlanır
        depths[30] = 0.005f  // gürültü → atlanır

        val points = useCase.execute(depths, 9, 9, pose = null, rgbBitmap = bitmap(9, 9))

        // 9 örneklenen noktadan 5'i filtrelendi → 4 nokta
        assertEquals(4, points.size)
    }

    @Test
    fun `olcekUygulanir_zCameraDerinlikIleCarplir`() {
        val useCase = DepthToPointsUseCase(estimator(2.5f))
        val depths = FloatArray(81) { 0.5f }

        val points = useCase.execute(depths, 9, 9, pose = null, rgbBitmap = bitmap(9, 9))

        // depth 0.5 * scale 2.5 → zCamera = 1.25 (identity pose → world z aynı)
        assertEquals(1.25f, points[0].z, 0.001f)
    }

    @Test
    fun `olcekSifirVeyaNegatifse_fallbackKullanilir`() {
        val useCase = DepthToPointsUseCase(estimator(0f)) // geçersiz kalibrasyon
        val depths = FloatArray(81) { 0.5f }

        val points = useCase.execute(depths, 9, 9, pose = null, rgbBitmap = bitmap(9, 9))

        // fallback DEFAULT_METERS_FALLBACK = 2.5 → z = 0.5 * 2.5 = 1.25
        assertEquals(1.25f, points[0].z, 0.001f)
    }

    @Test
    fun `intrinsicsProviderDoluysa_projeksiyondaKullanilir`() {
        val useCase = DepthToPointsUseCase(
            depthScaleEstimator = estimator(2.5f),
            intrinsicsProvider = intrinsicsProvider(CameraIntrinsics(fx = 100f, fy = 100f, cx = 50f, cy = 50f)),
        )
        val depths = FloatArray(81) { 0.5f }

        val points = useCase.execute(depths, 9, 9, pose = null, rgbBitmap = bitmap(9, 9))

        // (0,0) noktası: xCamera = (0 - 50) * 1.25 / 100 = -0.625
        assertEquals(-0.625f, points[0].x, 0.001f)
        assertEquals(-0.625f, points[0].y, 0.001f)
    }

    @Test
    fun `intrinsicsProviderYoksa_safeDefaultKullanilir`() {
        val useCase = DepthToPointsUseCase(
            depthScaleEstimator = estimator(2.5f),
            intrinsicsProvider = null,
        )
        val depths = FloatArray(81) { 0.5f }

        val points = useCase.execute(depths, 9, 9, pose = null, rgbBitmap = bitmap(9, 9))

        // SAFE_DEFAULT: fx=500, fy=500, cx=259, cy=259 → xCamera = (0-259)*1.25/500 = -0.6475
        assertEquals(-0.6475f, points[0].x, 0.001f)
    }

    @Test
    fun `rotateVectorByQuaternion_identityPoseDegismez`() {
        val useCase = DepthToPointsUseCase(estimator(2.5f))
        val rotated = useCase.rotateVectorByQuaternion(
            floatArrayOf(1f, 2f, 3f),
            floatArrayOf(0f, 0f, 0f, 1f) // identity quaternion
        )

        assertEquals(1f, rotated[0], 0.001f)
        assertEquals(2f, rotated[1], 0.001f)
        assertEquals(3f, rotated[2], 0.001f)
    }

    @Test
    fun `rotateVectorByQuaternion_90DereceZAxisDondurur`() {
        val useCase = DepthToPointsUseCase(estimator(2.5f))
        val sin45 = kotlin.math.sqrt(0.5f).toFloat() // 0.7071...
        val rotated = useCase.rotateVectorByQuaternion(
            floatArrayOf(1f, 0f, 0f),
            floatArrayOf(0f, 0f, sin45, sin45) // +90° Z ekseni etrafında
        )

        assertEquals(0f, rotated[0], 0.001f)
        assertEquals(1f, rotated[1], 0.001f)
        assertEquals(0f, rotated[2], 0.001f)
    }

    @Test
    fun `strideDownsample_noktaSayisiDogru`() {
        val useCase = DepthToPointsUseCase(estimator(2.5f))
        val depths = FloatArray(100) { 0.5f } // 10x10

        val points = useCase.execute(depths, 10, 10, pose = null, rgbBitmap = bitmap(10, 10))

        // STRIDE=3 → x in {0,3,6,9} (4), y in {0,3,6,9} (4) → 16 nokta
        assertEquals(16, points.size)
    }

    @Test
    fun `mockDepth518x518_noktaSayisiVeMenzil`() {
        val useCase = DepthToPointsUseCase(estimator(2.5f))
        val size = 518
        val depths = FloatArray(size * size) { 0.5f }

        val points = useCase.execute(depths, size, size, pose = null, rgbBitmap = bitmap(size, size))

        // STRIDE=3 → ceil(518/3)=173 per eksen → 173*173 = 29929 nokta
        assertEquals(29929, points.size)

        // Tüm noktalar: z = 0.5 * 2.5 = 1.25, x/y menzil yaklaşık [-0.65, 0.65]
        for (p in points) {
            assertEquals(1.25f, p.z, 0.001f)
            assertTrue("x menzil dışı: ${p.x}", abs(p.x) <= 1f)
            assertTrue("y menzil dışı: ${p.y}", abs(p.y) <= 1f)
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Faz 4 / Strateji C — yeni execute(depthMap, pose, rgbBitmap) overload'u
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `depthMapMetric_altVeUstEsikDisindakilerAtlanir`() {
        val useCase = DepthToPointsUseCase(estimator(2.5f))
        val depths = FloatArray(81) { 1.0f } // 9x9, varsayılan geçerli (1.0m)
        // STRIDE grid indeksleri {0,3,6,27,30,33,54,57,60}
        depths[0] = 0.04f   // < 0.05m → gürültü, atlanır
        depths[3] = 21f     // > 20m → güvenilmez, atlanır
        val depthMap = DepthMap(
            depths = depths,
            width = 9,
            height = 9,
            isMetric = true,
            metersPerUnit = 1f,
            intrinsics = null,
            sourceName = "arcore",
        )

        val points = useCase.execute(depthMap, pose = null, rgbBitmap = bitmap(9, 9))

        // 9 örneklenen noktadan 2'si filtrelendi → 7 nokta
        assertEquals(7, points.size)
        // Metrik depth doğrudan metre: z = 1.0 (scale uygulanmaz)
        assertEquals(1.0f, points[0].z, 0.001f)
    }

    @Test
    fun `depthMapMetric_20mSiniriDahildir`() {
        val useCase = DepthToPointsUseCase(estimator(2.5f))
        val depths = FloatArray(81) { 20f } // sınır değer → dahil (<= 20)
        val depthMap = DepthMap(depths, 9, 9, isMetric = true, metersPerUnit = 1f, intrinsics = null, sourceName = "arcore")

        val points = useCase.execute(depthMap, pose = null, rgbBitmap = bitmap(9, 9))

        assertEquals(9, points.size)
        assertEquals(20f, points[0].z, 0.001f)
    }

    @Test
    fun `depthMapNormalize_filtreVeOlcekUygulanir`() {
        val useCase = DepthToPointsUseCase(estimator(2.5f))
        val depths = FloatArray(81) { 0.5f }
        depths[0] = 0.005f  // <= 0.01 → atlanır
        depths[3] = 0.995f  // >= 0.99 → atlanır
        val depthMap = DepthMap(
            depths = depths,
            width = 9,
            height = 9,
            isMetric = false,
            metersPerUnit = 2.5f,
            intrinsics = null,
            sourceName = "tflite",
        )

        val points = useCase.execute(depthMap, pose = null, rgbBitmap = bitmap(9, 9))

        // 9 - 2 = 7 nokta
        assertEquals(7, points.size)
        // z = 0.5 * metersPerUnit(2.5) = 1.25
        assertEquals(1.25f, points[0].z, 0.001f)
    }

    @Test
    fun `depthMapMetric_metersPerUnitUygulanmaz`() {
        val useCase = DepthToPointsUseCase(estimator(2.5f))
        val depths = FloatArray(81) { 1.5f }
        // isMetric=true iken metersPerUnit değeri 1f'den farklı olsa bile yok sayılır.
        val depthMap = DepthMap(depths, 9, 9, isMetric = true, metersPerUnit = 99f, intrinsics = null, sourceName = "arcore")

        val points = useCase.execute(depthMap, pose = null, rgbBitmap = bitmap(9, 9))

        assertEquals(1.5f, points[0].z, 0.001f)
    }

    @Test
    fun `depthMapIntrinsicsNull_useCaseProviderDevreyeGirer`() {
        val useCase = DepthToPointsUseCase(
            depthScaleEstimator = estimator(2.5f),
            intrinsicsProvider = intrinsicsProvider(CameraIntrinsics(fx = 100f, fy = 100f, cx = 50f, cy = 50f)),
        )
        val depths = FloatArray(81) { 0.5f }
        val depthMap = DepthMap(depths, 9, 9, isMetric = false, metersPerUnit = 2.5f, intrinsics = null, sourceName = "tflite")

        val points = useCase.execute(depthMap, pose = null, rgbBitmap = bitmap(9, 9))

        // provider cx=50, cy=50 → xCamera = (0-50)*1.25/100 = -0.625
        assertEquals(-0.625f, points[0].x, 0.001f)
        assertEquals(-0.625f, points[0].y, 0.001f)
    }

    @Test
    fun `depthMapIntrinsicsNull_useCaseProviderYoksaSafeDefault`() {
        val useCase = DepthToPointsUseCase(
            depthScaleEstimator = estimator(2.5f),
            intrinsicsProvider = null,
        )
        val depths = FloatArray(81) { 0.5f }
        val depthMap = DepthMap(depths, 9, 9, isMetric = false, metersPerUnit = 2.5f, intrinsics = null, sourceName = "tflite")

        val points = useCase.execute(depthMap, pose = null, rgbBitmap = bitmap(9, 9))

        // SAFE_DEFAULT: fx=500, fy=500, cx=259, cy=259 → xCamera = (0-259)*1.25/500 = -0.6475
        assertEquals(-0.6475f, points[0].x, 0.001f)
    }

    @Test
    fun `depthMapMetric_inlineIntrinsicsOnceliklidir`() {
        // depthMap.intrinsics dolu; provider da dolu olsa inline değer kazanmalı.
        val useCase = DepthToPointsUseCase(
            depthScaleEstimator = estimator(2.5f),
            intrinsicsProvider = intrinsicsProvider(CameraIntrinsics(fx = 500f, fy = 500f, cx = 259f, cy = 259f)),
        )
        val depths = FloatArray(81) { 1.0f }
        val depthMap = DepthMap(
            depths, 9, 9,
            isMetric = true,
            metersPerUnit = 1f,
            intrinsics = CameraIntrinsics(fx = 100f, fy = 100f, cx = 50f, cy = 50f),
            sourceName = "arcore",
        )

        val points = useCase.execute(depthMap, pose = null, rgbBitmap = bitmap(9, 9))

        // inline cx=50 → xCamera = (0-50)*1/100 = -0.5 (provider cx=259 kullanılmaz)
        assertEquals(-0.5f, points[0].x, 0.001f)
        assertEquals(1.0f, points[0].z, 0.001f)
    }

    @Test
    fun `depthMapNormalize_metersPerUnitSifirsaFallbackKullanilir`() {
        val useCase = DepthToPointsUseCase(estimator(0f)) // geçersiz kalibrasyon
        val depths = FloatArray(81) { 0.5f }
        val depthMap = DepthMap(depths, 9, 9, isMetric = false, metersPerUnit = 0f, intrinsics = null, sourceName = "tflite")

        val points = useCase.execute(depthMap, pose = null, rgbBitmap = bitmap(9, 9))

        // fallback DEFAULT_METERS_FALLBACK = 2.5 → z = 0.5 * 2.5 = 1.25
        assertEquals(1.25f, points[0].z, 0.001f)
    }

    // ─────────────────────────────────────────────────────────────────────
    // B9/B1 — intrinsik çözünürlük ölçeklemesi (depth grid'ine normalize)
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `intrinsics640x480den_160x120DepthGridineOlceklenir`() {
        // İntrinsikler RGB kamera (640x480) uzayında; depth 160x120 grid'ine
        // ölçeklenir. Ölçek = 160/640 = 0.25 (X), 120/480 = 0.25 (Y).
        val intrinsics = CameraIntrinsics(
            fx = 640f, fy = 480f, cx = 320f, cy = 240f,
            sourceWidth = 640, sourceHeight = 480,
        )
        val useCase = DepthToPointsUseCase(estimator(2.5f))
        val depths = FloatArray(160 * 120) { 0.5f }
        val depthMap = DepthMap(
            depths = depths, width = 160, height = 120,
            isMetric = false, metersPerUnit = 2.5f,
            intrinsics = intrinsics, sourceName = "tflite",
        )

        val points = useCase.execute(depthMap, pose = null, rgbBitmap = bitmap(160, 120))

        // fx_scaled = 640 * 0.25 = 160; cx_scaled = 320 * 0.25 = 80
        // nokta (0,0): xCamera = (0-80) * 1.25 / 160 = -0.625
        assertEquals(-0.625f, points[0].x, 0.001f)
        // fy_scaled = 480 * 0.25 = 120; cy_scaled = 240 * 0.25 = 60
        // nokta (0,0): yCamera = (0-60) * 1.25 / 120 = -0.625
        assertEquals(-0.625f, points[0].y, 0.001f)
    }

    @Test
    fun `intrinsicsSourceWidthSifirsa_OlceklemeYapilmaz`() {
        // sourceWidth/sourceHeight 0 ise intrinsikler aynen kullanılır (ölçekleme yok).
        val intrinsics = CameraIntrinsics(fx = 100f, fy = 100f, cx = 50f, cy = 50f) // source 0
        val useCase = DepthToPointsUseCase(estimator(2.5f))
        val depths = FloatArray(160 * 120) { 0.5f }
        val depthMap = DepthMap(
            depths = depths, width = 160, height = 120,
            isMetric = false, metersPerUnit = 2.5f,
            intrinsics = intrinsics, sourceName = "tflite",
        )

        val points = useCase.execute(depthMap, pose = null, rgbBitmap = bitmap(160, 120))

        // Ölçekleme yok: xCamera = (0-50) * 1.25 / 100 = -0.625
        assertEquals(-0.625f, points[0].x, 0.001f)
        assertEquals(-0.625f, points[0].y, 0.001f)
    }

    @Test
    fun `intrinsicsFullSensorProviderdan_160x120DepthGridineOlceklenir`() {
        // TFLite yolu: depthMap.intrinsics null, usecase intrinsikleri provider'dan
        // (CameraCharacteristics, full-sensor 4096x3072) okur ve depth grid'ine ölçekler.
        val intrinsics = CameraIntrinsics(
            fx = 4096f, fy = 3072f, cx = 2048f, cy = 1536f,
            sourceWidth = 4096, sourceHeight = 3072,
        )
        val useCase = DepthToPointsUseCase(
            depthScaleEstimator = estimator(2.5f),
            intrinsicsProvider = intrinsicsProvider(intrinsics),
        )
        val depths = FloatArray(160 * 120) { 0.5f }
        val depthMap = DepthMap(
            depths = depths, width = 160, height = 120,
            isMetric = false, metersPerUnit = 2.5f,
            intrinsics = null, sourceName = "tflite",
        )

        val points = useCase.execute(depthMap, pose = null, rgbBitmap = bitmap(160, 120))

        // fx_scaled = 4096 * (160/4096) = 160; cx_scaled = 2048 * (160/4096) = 80
        // xCamera = (0-80) * 1.25 / 160 = -0.625
        assertEquals(-0.625f, points[0].x, 0.001f)
        // fy_scaled = 3072 * (120/3072) = 120; cy_scaled = 1536 * (120/3072) = 60
        // yCamera = (0-60) * 1.25 / 120 = -0.625
        assertEquals(-0.625f, points[0].y, 0.001f)
    }

    @Test
    fun `depthBoyutGridleEslesmezse_bosListeDoner`() {
        // B10: depths.size != width*height → geri yansıtma yapılmaz, boş liste.
        val useCase = DepthToPointsUseCase(estimator(2.5f))
        val depths = FloatArray(100) { 0.5f } // 100 != 9*9=81

        val points = useCase.execute(depths, 9, 9, pose = null, rgbBitmap = bitmap(9, 9))

        assertTrue("Grid eşleşmezse boş liste dönmeli", points.isEmpty())
    }
}
