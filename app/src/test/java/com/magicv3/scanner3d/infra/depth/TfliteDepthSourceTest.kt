package com.magicv3.scanner3d.infra.depth

import android.content.Context
import android.graphics.Bitmap
import android.media.Image
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer

/**
 * TfliteDepthSource birim testleri (model yok → dürüst null).
 *
 * NOT (Batch-6+): main assets'te `depth_anything_v2_small.tflite` artık mevcut; ancak
 * bu testler gerçek DepthInferenceEngine'e **var olmayan** bir model dosya adı vererek
 * "model yok" senaryosunu assets içeriğinden bağımsız garanti eder. [depthFromBitmap]
 * bu durumda **null** üretir (sahte/mock depth yok).
 *
 * Kapsam:
 *  - depthFromBitmap → model yokken null.
 *  - depthFromBitmap → model yokken kalibrasyon durumu ne olursa olsun null kalır.
 *  - imageToNv21: YUV_420_888 plane'lerini NV21 düzenine doğru kopyalar (Image mock ile).
 *  - rotateBitmap: kaynak bitmap recycle edilir.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TfliteDepthSourceTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun source(estimator: com.magicv3.scanner3d.domain.depth.DepthScaleEstimator): TfliteDepthSource =
        TfliteDepthSource(
            depthEngine = com.magicv3.scanner3d.infra.ai.DepthInferenceEngine(
                context,
                // "Model yok" senaryosu: gerçekte var olmayan dosya adı — sahte çıktı üretilmez.
                "varolmayan_model.tflite",
            ),
            yoloEngine = com.magicv3.scanner3d.infra.ai.YoloInferenceEngine(context),
            depthScaleEstimator = estimator,
        )

    @Test
    fun `depthFromBitmap_modelYokkenNullDoner`() {
        val src = source(DefaultDepthScaleEstimator()) // 2.5f

        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val map = src.depthFromBitmap(bitmap)

        // Model assets'te yok → engine boş sonuç döner → dürüst null (sahte depth yok).
        assertNull("Model yokken depth üretilmemeli (sahte depth yok)", map)
    }

    @Test
    fun `depthFromBitmap_modelYokkenKalibrasyonAnlamsizNullKalir`() {
        val estimator = mockk<com.magicv3.scanner3d.domain.depth.DepthScaleEstimator>()
        every { estimator.estimateScale() } returns 0f
        val src = source(estimator)

        val map = src.depthFromBitmap(Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888))

        // Model yokken kalibrasyon akışı hiç çalışmaz — depth null kalır (sahte üretim yok).
        assertNull("Model yokken kalibrasyon sonucu ne olursa olsun null kalmalı", map)
    }

    @Test
    fun `depthFromBitmap_engineHataFirlatirsaNullDoner`() {
        // F2: inference engine hata fırlatırsa dürüst davranış null'dur — sahte depth
        // üretilmez, çağıran (ScanViewModel) kullanıcıya net mesaj gösterir. ARCore
        // RET_CHECK riski altında TFLite tek güvenilir depth kaynağı olduğundan
        // inference hatası hiçbir yoldan UI'a crash olarak sızmamalıdır.
        val engine = mockk<com.magicv3.scanner3d.infra.ai.DepthInferenceEngine>()
        every { engine.infer(any()) } throws RuntimeException("native inference crash")
        val src = TfliteDepthSource(
            depthEngine = engine,
            yoloEngine = mockk(relaxed = true),
            depthScaleEstimator = DefaultDepthScaleEstimator(),
        )

        val map = src.depthFromBitmap(Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888))

        assertNull("Engine hata verdiğinde null dönmeli (crash yok, sahte üretim yok)", map)
    }

    @Test
    fun `imageToNv21_yuvPlaneLariniNv21DuzenineKopyalar`() {
        val src = source(DefaultDepthScaleEstimator())
        val width = 4
        val height = 4

        // Y: 16 byte, satır başına değerleri 1..16
        val yData = ByteArray(width * height) { (it + 1).toByte() }
        // U/V: 2x2, sabit 100/200
        val uData = ByteArray(width * height / 4) { 100.toByte() }
        val vData = ByteArray(width * height / 4) { 200.toByte() }

        val yPlane = mockk<Image.Plane>()
        every { yPlane.rowStride } returns width
        every { yPlane.pixelStride } returns 1
        every { yPlane.buffer } returns ByteBuffer.wrap(yData)

        val uPlane = mockk<Image.Plane>()
        every { uPlane.rowStride } returns width / 2
        every { uPlane.pixelStride } returns 1
        every { uPlane.buffer } returns ByteBuffer.wrap(uData)

        val vPlane = mockk<Image.Plane>()
        every { vPlane.rowStride } returns width / 2
        every { vPlane.pixelStride } returns 1
        every { vPlane.buffer } returns ByteBuffer.wrap(vData)

        val image = mockk<Image>()
        every { image.width } returns width
        every { image.height } returns height
        every { image.planes } returns arrayOf(yPlane, uPlane, vPlane)

        val nv21 = src.imageToNv21(image, null)

        // Boyut: width*height*3/2 = 24
        assertEquals(width * height * 3 / 2, nv21.size)

        // Y kanalı (0..15) sırayla 1..16
        for (i in 0 until width * height) {
            assertEquals((i + 1).toByte(), nv21[i])
        }

        // NV21: interleaved V, U — 4 çift
        for (i in 0 until width * height / 4) {
            assertEquals("V değeri", 200.toByte(), nv21[width * height + i * 2])
            assertEquals("U değeri", 100.toByte(), nv21[width * height + i * 2 + 1])
        }
    }

    @Test
    fun `imageToNv21_uvStrideFarkliysaChromaDogruKonumlanir`() {
        // B6: U ve V plane'lerinin rowStride'i farklı olduğunda chroma doğru okunmalı.
        // U rowStride=8 (padded), V rowStride=4 (packed).
        val src = source(DefaultDepthScaleEstimator())
        val width = 8
        val height = 8

        val yData = ByteArray(width * height) { (it + 1).toByte() }
        // U: 4x4 chroma ama rowStride 8 — her satırda ilk 4 hücre gerçek, sonra padding.
        val uData = ByteArray(4 * 8)
        for (row in 0 until 4) {
            for (col in 0 until 8) {
                uData[row * 8 + col] = (100 + row).toByte()
            }
        }
        // V: 4x4 packed — rowStride 4, kapasite 16.
        val vData = ByteArray(4 * 4) { 200.toByte() }

        val yPlane = mockk<Image.Plane>()
        every { yPlane.rowStride } returns width
        every { yPlane.pixelStride } returns 1
        every { yPlane.buffer } returns ByteBuffer.wrap(yData)

        val uPlane = mockk<Image.Plane>()
        every { uPlane.rowStride } returns 8
        every { uPlane.pixelStride } returns 1
        every { uPlane.buffer } returns ByteBuffer.wrap(uData)

        val vPlane = mockk<Image.Plane>()
        every { vPlane.rowStride } returns 4
        every { vPlane.pixelStride } returns 1
        every { vPlane.buffer } returns ByteBuffer.wrap(vData)

        val image = mockk<Image>()
        every { image.width } returns width
        every { image.height } returns height
        every { image.planes } returns arrayOf(yPlane, uPlane, vPlane)

        val nv21 = src.imageToNv21(image, null)

        // NV21 chroma: her (row,col) için offset = width*height + (row*4+col)*2 → [V, U]
        for (row in 0 until height / 2) {
            for (col in 0 until width / 2) {
                val offset = width * height + (row * (width / 2) + col) * 2
                assertEquals("V(row=$row,col=$col)", 200.toByte(), nv21[offset])
                // U satır bazlı değer (100+row) — rowStride 8 ile doğru satır okunmalı.
                assertEquals("U(row=$row,col=$col)", (100 + row).toByte(), nv21[offset + 1])
            }
        }
    }

    @Test
    fun `rotateBitmap_kaynakBitmapRecycleEdilir`() {
        // B8: rotate sonrası kaynak (decode edilen) bitmap recycle edilir — tepe bellek düşer.
        val src = source(DefaultDepthScaleEstimator())
        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)

        val rotated = src.rotateBitmap(bitmap, 90f)

        assertFalse("Rotated bitmap recycle edilmemeli", rotated.isRecycled)
        assertTrue("Kaynak bitmap recycle edilmeli", bitmap.isRecycled)
        assertEquals(10, rotated.width)
        assertEquals(10, rotated.height)
    }
}
