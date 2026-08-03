package com.magicv3.scanner3d.infra.depth

import android.media.Image
import com.google.ar.core.Camera
import com.google.ar.core.CameraIntrinsics
import com.google.ar.core.Frame
import com.google.ar.core.exceptions.NotYetAvailableException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import io.mockk.every
import io.mockk.mockk
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ArCoreDepthSource birim testleri.
 *
 * Kapsam:
 *  - F2 fallback garantisi: [ArCoreDepthSource.acquireDepth] ARCore depth üretemediğinde
 *    (NotYetAvailable / Unavailable / beklenmedik native RET_CHECK benzeri hata / bozuk
 *    görüntü) HİÇBİR durumda fırlatmaz; null döner — üst katman TFLite fallback'e düşer.
 *    Frame, ARCore native bağımlılığı olmadan MockK ile mock edilir.
 *  - Saf dönüşüm fonksiyonu [ArCoreDepthSource.mmToMeters]:
 *    DEPTH16 (16-bit milimetre) → metre dönüşümü + negatif/taşan değer davranışı.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ArCoreDepthSourceTest {

    private val source = ArCoreDepthSource()

    @Test
    fun `mmToMeters_milimetreyiMetreyeCevirir`() {
        val mm = shortArrayOf(0, 500, 1000, 2500, 12500)

        val meters = source.mmToMeters(mm)

        assertEquals(5, meters.size)
        assertEquals(0f, meters[0], 0.001f)
        assertEquals(0.5f, meters[1], 0.001f)
        assertEquals(1f, meters[2], 0.001f)
        assertEquals(2.5f, meters[3], 0.001f)
        assertEquals(12.5f, meters[4], 0.001f)
    }

    @Test
    fun `mmToMeters_bosDiziBosDoner`() {
        val meters = source.mmToMeters(ShortArray(0))

        assertEquals(0, meters.size)
    }

    @Test
    fun `mmToMeters_32MetreUstuNegatifOkunur`() {
        // DEPTH16 unsigned milimetre; Java Short imzalı olduğundan 32768mm üstü
        // negatif okunur. mmToMeters yalnızca dönüştürür; filtreleme (0.05m altı)
        // DepthToPointsUseCase'in metrik filtre katmanında yapılır.
        val mm = shortArrayOf(65535.toShort(), 32767)

        val meters = source.mmToMeters(mm)

        assertEquals(-0.001f, meters[0], 0.001f)
        assertEquals(32.767f, meters[1], 0.001f)
    }

    // ── F2: ARCore depth üretemediğinde TFLite fallback garantisi ──────────────
    // acquireDepth HİÇBİR durumda fırlatmamalı; null dönmeli. ScanViewModel
    // `arDepthMap ?: tfliteDepthSource.depthFromBitmap(...)` ile null'da fallback'e düşer.

    @Test
    fun `acquireDepth_arcoreDepthHazirDegilseNullDoner`() {
        // Depth henüz tahmin edilmedi — NotYetAvailableException tipik senaryo.
        val frame = mockk<Frame>()
        every { frame.acquireDepthImage16Bits() } throws NotYetAvailableException("depth henüz hazır değil")

        assertNull(
            "ARCore depth yokken null dönmeli (TFLite fallback garantisi)",
            source.acquireDepth(frame)
        )
    }

    @Test
    fun `acquireDepth_arcoreDesteklenmezseNullDoner`() {
        // Cihaz/bu oturum depth üretmiyor — UnavailableException alt sınıfı.
        val frame = mockk<Frame>()
        every { frame.acquireDepthImage16Bits() } throws UnavailableDeviceNotCompatibleException("cihaz depth desteklemiyor")

        assertNull(
            "ARCore depth kullanılamazsa null dönmeli (TFLite fallback garantisi)",
            source.acquireDepth(frame)
        )
    }

    @Test
    fun `acquireDepth_beklenmedikNativeHataNullDoner`() {
        // F2: Honor Magic V3'teki `spherical_rectifier.cc RET_CHECK` benzeri native
        // hatalar yüzeye RuntimeException olarak yansıyabilir — kaynak asla fırlatmaz.
        val frame = mockk<Frame>()
        every { frame.acquireDepthImage16Bits() } throws RuntimeException("RET_CHECK failure (kUnrectifiedPinhole vs kUnrectifiedOriginal)")

        assertNull(
            "Beklenmedik hata null'a çevrilmeli (crash yok, fallback garanti)",
            source.acquireDepth(frame)
        )
    }

    @Test
    fun `acquireDepth_planErisilemezseNullDoner`() {
        // Depth görüntüsü döndü ama plane/uzay bozuk (native hata artığı) — dönüşüm
        // içindeki hata da null'a çevrilir; TFLite fallback garanti kalır.
        val image = mockk<Image>()
        every { image.planes } returns emptyArray()
        every { image.close() } returns Unit
        val frame = mockk<Frame>()
        every { frame.acquireDepthImage16Bits() } returns image

        assertNull(
            "Depth görüntüsü bozuksa null dönmeli (fallback garanti)",
            source.acquireDepth(frame)
        )
    }

    // ── MEDIUM-3: DEPTH16 "0 = veri yok" dürüstlüğü ─────────────────────────────
    // DEPTH16 spesifikasyonunda 0 değeri "bilinmeyen/veri yok" demektir. ARCore depth
    // üretemediğinde non-null boş görüntü döndürebilir (F2 — Honor Magic V3 RET_CHECK).
    // Böyle görüntülerden DepthMap üretmek AR_CORE durumunu yanlış raporlar; bu testler
    // boş / tamamı sıfır buffer'ın null'a çevrilmesini (TFLite fallback) ve anlamlı
    // verinin non-null DepthMap üretmesini doğrular.

    /**
     * İçerisinde [shorts] DEPTH16 (16-bit milimetre) değerleri olan bir [Image] mock'u.
     *
     * `shorts == null` ise **boş** buffer (limit=0) üretilir → `availableShorts == 0`.
     * rowStride sıkı paketlenmiş kabul edilir (rowStride == width * pixelStride).
     */
    private fun mockDepthImage(width: Int, height: Int, shorts: ShortArray?): Image {
        val buffer = if (shorts == null) {
            ByteBuffer.allocate(0) // boş buffer: availableShorts == 0
        } else {
            ByteBuffer.allocate(shorts.size * 2).apply {
                order(ByteOrder.LITTLE_ENDIAN)
                for (s in shorts) putShort(s)
                rewind()
            }
        }
        val plane = mockk<Image.Plane>()
        every { plane.buffer } returns buffer
        every { plane.rowStride } returns width * 2
        every { plane.pixelStride } returns 2
        val image = mockk<Image>()
        every { image.planes } returns arrayOf(plane)
        every { image.width } returns width
        every { image.height } returns height
        every { image.close() } returns Unit
        return image
    }

    /**
     * [image] döndüren ve (opsiyonel) imageIntrinsics zinciri olan [Frame] mock'u.
     *
     * `intrinsicsDims == null` ise camera zinciri stub'lanmaz; readImageIntrinsics
     * runCatching içinde MockKException'ı null'a çevirir (intrinsics boş kalır).
     */
    private fun mockFrame(image: Image, intrinsicsDims: IntArray?): Frame {
        val frame = mockk<Frame>()
        every { frame.acquireDepthImage16Bits() } returns image
        if (intrinsicsDims != null) {
            val intrinsics = mockk<CameraIntrinsics>()
            every { intrinsics.imageDimensions } returns intrinsicsDims
            every { intrinsics.focalLength } returns floatArrayOf(500f, 500f)
            every { intrinsics.principalPoint } returns floatArrayOf(250f, 250f)
            val camera = mockk<Camera>()
            every { camera.imageIntrinsics } returns intrinsics
            every { frame.camera } returns camera
        }
        return frame
    }

    @Test
    fun `produceDepthMap_bosBufferNullDoner`() {
        // MEDIUM-3: Boş buffer (0 short) anlamlı depth içermez → acquireDepth null
        // dönmeli; AR_CORE yanlış raporlanmaz, TFLite fallback devreye girer.
        val image = mockDepthImage(width = 4, height = 2, shorts = null)
        val frame = mockFrame(image, intrinsicsDims = null)

        assertNull(
            "DEPTH16 boş buffer null dönmeli (TFLite fallback garantisi)",
            source.acquireDepth(frame)
        )
    }

    @Test
    fun `produceDepthMap_tamamiSifirBufferNullDoner`() {
        // MEDIUM-3: DEPTH16'da 0 "veri yok"tur. ARCore depth üretemediğinde non-null
        // boş görüntü döndürebilir (F2 — Honor Magic V3 RET_CHECK senaryosu): tüm
        // hücreler 0 ise null dönmeli, AR_CORE durumu dürüstçe raporlanmalı.
        val shorts = ShortArray(4 * 2) // tamamı 0
        val image = mockDepthImage(width = 4, height = 2, shorts = shorts)
        val frame = mockFrame(image, intrinsicsDims = null)

        assertNull(
            "DEPTH16 tamamı sıfır null dönmeli (TFLite fallback garantisi)",
            source.acquireDepth(frame)
        )
    }

    @Test
    fun `produceDepthMap_anlamliVeriNonNullDoner`() {
        // MEDIUM-3: En az bir sıfırdan farklı hücre varsa DepthMap üretilmeli; mm → metre
        // dönüşümü ve intrinsik ölçeklemesi yapılmalı (sourceName="arcore", isMetric=true).
        val width = 4
        val height = 2
        val shorts = shortArrayOf(1000, 0, 2500, 500, 2000, 1500, 0, 3000) // milimetre
        val image = mockDepthImage(width = width, height = height, shorts = shorts)
        val frame = mockFrame(image, intrinsicsDims = intArrayOf(width, height))

        val map = source.acquireDepth(frame)

        assertNotNull("Anlamlı depth varsa DepthMap non-null olmalı", map)
        val depthMap = map!!
        assertEquals(width, depthMap.width)
        assertEquals(height, depthMap.height)
        assertEquals("arcore", depthMap.sourceName)
        assertTrue("ARCore depth metrik olmalı", depthMap.isMetric)
        assertEquals(1f, depthMap.metersPerUnit, 0.001f)
        // mm → metre: 1000/1000=1.0, 2500/1000=2.5, vb. (0'lar "bilinmeyen" olarak korunur).
        assertArrayEquals(
            "DEPTH16 milimetre değerleri metreye çevrilmeli",
            floatArrayOf(1f, 0f, 2.5f, 0.5f, 2f, 1.5f, 0f, 3f),
            depthMap.depths,
            0.001f,
        )
        // intrinsics depth grid'ine ölçeklenir: dims == depth çözünürlüğü → ölçek 1.
        assertNotNull("İntrinsikler okunabilmeli", depthMap.intrinsics)
        assertEquals(500f, depthMap.intrinsics!!.fx, 0.001f)
        assertEquals(250f, depthMap.intrinsics!!.cx, 0.001f)
    }
}
