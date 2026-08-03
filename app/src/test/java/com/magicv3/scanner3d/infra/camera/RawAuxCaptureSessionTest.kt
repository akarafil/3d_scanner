package com.magicv3.scanner3d.infra.camera

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.media.Image
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch

/**
 * RawAuxCaptureSession YUV→NV21 testleri (B6).
 *
 * `yuv420ToNv21`, U ve V plane'lerinin stride'larını ayrı ayrı kullanacak şekilde
 * düzeltildi; bu test divergent stride (U rowStride=8 padded, V rowStride=4 packed)
 * durumunda chroma'nın doğru kaynak indeksinden okunduğunu byte-byte doğrular.
 *
 * Not: `yuv420ToJpeg`'in içindeki `YuvImage.compressToJpeg` native kodlayıcıyı
 * kullanır; Robolectric bu codec'i çalıştıramaz (boş çıktı üretir). Bu yüzden
 * gerçek JPEG üretimi cihaz testlerine bırakılır; birim test NV21 düzenini (B6
 * düzeltmesinin gerçek çıktısı) doğrular.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RawAuxCaptureSessionTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `yuv420ToNv21_uvStrideFarkliysaChromaDogruKonumlanir`() {
        val session = RawAuxCaptureSession(context)
        val width = 8
        val height = 8

        // Y: her piksel benzersiz (1..64) — satır kopyalama kaymasını yakalar.
        val yData = ByteArray(width * height) { (it + 1).toByte() }
        // U: rowStride 8 (padded), V: rowStride 4 (packed) — divergent stride.
        // Değerler indeks bazında benzersiz: U = 0..31, V = 100..115.
        // (Sabit değer kullansaydık yanlış stride okuması fark edilmezdi.)
        val uData = ByteArray(4 * 8) { (it % 256).toByte() }
        val vData = ByteArray(4 * 4) { (100 + it).toByte() }

        val yPlane = mockk<Image.Plane>()
        every { yPlane.rowStride } returns width
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
        every { image.format } returns ImageFormat.YUV_420_888
        every { image.planes } returns arrayOf(yPlane, uPlane, vPlane)

        val nv21 = session.yuv420ToNv21(image, width, height)

        // 1. Y bölgesi birebir kopyalanır.
        assertArrayEquals(yData, nv21.copyOfRange(0, width * height))

        // 2. NV21'de V önce, U sonra interleaved gelir.
        // row=0, col=0 → vIdx = 0 (vRowStride=4), uIdx = 0 (uRowStride=8)
        assertEquals(100, nv21[width * height].toInt() and 0xFF)     // V = vData[0] = 100
        assertEquals(0, nv21[width * height + 1].toInt() and 0xFF)   // U = uData[0] = 0

        // 3. row=1, col=0 → vIdx = 1*4 = 4, uIdx = 1*8 = 8.
        //    Eski hatalı kod (paylaşılan stride) uIdx'yi yanlış bulurdu (4) → uData[4]=4.
        val row1Col0Offset = width * height + (width / 2) * 2
        assertEquals(104, nv21[row1Col0Offset].toInt() and 0xFF)     // V = vData[4] = 104
        assertEquals(8, nv21[row1Col0Offset + 1].toInt() and 0xFF)   // U = uData[8] = 8
    }

    // ── F1: closeInOrder — SIRALI kapanış (session → reader → device) ──────────

    /**
     * Kapanış sırasını gerçek CameraCaptureSession olmadan kaydeden sahte teardown.
     * closeSession, [sessionResult]'u döner (onClosed gelmedi / geldi senaryosu) ve
     * istenirse [sessionDelayMs] kadar bloklanır (zaman aşımı senaryosu).
     */
    private class RecordingTeardown(
        val sessionResult: Boolean = true,
        val sessionDelayMs: Long = 0L,
    ) : AuxResourceTeardown {
        val calls = mutableListOf<String>()

        override fun closeSession(timeoutMs: Long): Boolean {
            calls += "SESSION"
            if (sessionDelayMs > 0) Thread.sleep(sessionDelayMs)
            return sessionResult
        }

        override fun closeReader() {
            calls += "READER"
        }

        override fun closeDevice() {
            calls += "DEVICE"
        }
    }

    @Test
    fun `closeInOrder_sessionOncelikliSirasiylaKapanir`() {
        // Sıra ZORUNLU: captureSession → imageReader → cameraDevice.
        // (endConfigure:905 yarışını önleyen canonical kapanış.)
        val teardown = RecordingTeardown(sessionResult = true)

        val steps = closeInOrder(teardown, timeoutMs = 2_000L)

        assertEquals(listOf("SESSION", "READER", "DEVICE"), teardown.calls)
        assertEquals(
            listOf(TeardownStep.SESSION, TeardownStep.READER, TeardownStep.DEVICE),
            steps
        )
    }

    @Test
    fun `closeInOrder_onClosedGelmezseZamanAsimiOlurAmaKapanisDevamEder`() {
        // Session zaten kapalı / onClosed gelmiyor → 2sn yerine kısa timeout kullan,
        // reader/device yine de sırayla kapatılmalı (kilitlenme OLMAMALI).
        val teardown = RecordingTeardown(sessionResult = false)

        val steps = closeInOrder(teardown, timeoutMs = 100L)

        assertEquals(listOf("SESSION", "READER", "DEVICE"), teardown.calls)
        assertTrue("kilitlenme olmadan device kapanışına ulaşılmalı", steps.contains(TeardownStep.DEVICE))
        assertFalse("timeout sonrası true dönmemeli", teardown.calls.contains("NOT_CLOSED"))
    }

    @Test
    fun `closeInOrder_sessionGecikirseZamanAsimindaDevamEder`() {
        // onClosed, timeout'tan (50ms) uzun süre sonra geliyor (200ms) → closeSession
        // false döner ama kapanış devam eder ve sıra korunur.
        val teardown = RecordingTeardown(sessionResult = false, sessionDelayMs = 200L)

        val steps = closeInOrder(teardown, timeoutMs = 50L)

        assertEquals(listOf("SESSION", "READER", "DEVICE"), teardown.calls)
        assertEquals(TeardownStep.DEVICE, steps.last())
    }

    // ── MEDIUM-1: Per-session onClosed latch (çapraz countDown yarışı) ───────────

    /**
     * MEDIUM-1 regresyon testi: Listener tek global slot kullanır ama per-session
     * identity ile bağlanır. Yanlış session'ın onClosed'u latch'i saymamalı;
     * yalnızca beklenen session'ın onClosed'u saymalı.
     */
    @Test
    fun `sessionOnClosed_yanlisSessionLatchiSaymazDogruSessionSayar`() {
        val session = RawAuxCaptureSession(context)
        val latch = CountDownLatch(1)
        val correct = mockk<CameraCaptureSession>()
        val wrong = mockk<CameraCaptureSession>()

        session.bindSessionClosedLatch(latch, expectedSession = correct)

        // Yanlış session'ın (stale/geç) onClosed'u gelirse latch sayılmaz.
        session.notifySessionClosed(wrong)
        assertEquals("yanlış session latch'i saymamalı", 1, latch.count)

        // Beklenen session'ın onClosed'u gelince latch sayılır.
        session.notifySessionClosed(correct)
        assertEquals(0, latch.count)
    }

    /**
     * MEDIUM-1 regresyon testi (çapraz yarış senaryosu):
     * A session'ı kapanıyor, B session'ı da kapanıyor; A'nın GECİKMİŞ onClosed'u
     * B kapanırken ulaşıyor. B'nin latch'i sayılmamalı (yalnızca B'nin kendi
     * onClosed'u saymalı) — HAL'de reader/device erken kapanmaz.
     */
    @Test
    fun `sessionOnClosed_caprazGecOnclosedBninLatchiniSaymaz`() {
        val session = RawAuxCaptureSession(context)
        val sessionA = mockk<CameraCaptureSession>()
        val sessionB = mockk<CameraCaptureSession>()

        val latchA = CountDownLatch(1)
        val latchB = CountDownLatch(1)

        // A kapanıyor → latchA yalnızca A'nın onClosed'u ile sayılır.
        session.bindSessionClosedLatch(latchA, expectedSession = sessionA)
        // B kapanıyor → per-session slot B'ye bağlanır.
        session.bindSessionClosedLatch(latchB, expectedSession = sessionB)

        // A'nın geç onClosed'u B kapanırken ulaşır → B'nin latch'i sayılmaz.
        session.notifySessionClosed(sessionA)
        assertEquals("A'nın stale onClosed'u B latch'ini saymamalı", 1, latchB.count)

        // B'nin kendi onClosed'u gelince B'nin latch'i sayılır.
        session.notifySessionClosed(sessionB)
        assertEquals(0, latchB.count)
    }
}
