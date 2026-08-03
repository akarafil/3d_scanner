package com.magicv3.scanner3d.infra.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ProCameraCapabilities saf matematik testleri (Batch-3).
 *
 * `evToCameraUnits` ve `kelvinToRgbGains` Android'e bağımlı olmayan saf
 * fonksiyonlardır; bu yüzden plain JUnit ile test edilir (Robolectric gerekmez).
 *
 * - evToCameraUnits: EV (stop) → kamera AE kompanzasyon birimi; step'e bölünür,
 *   roundToInt ile yuvarlanır, cihaz aralığına kıstırılır.
 * - kelvinToRgbGains: Tanner-Helland yaklaşımı ile Kelvin → RGB, yeşil=1 olacak
 *   şekilde normalize edilmiş (rGain, bGain).
 */
class ProCameraMathTest {

    // ── evToCameraUnits ────────────────────────────────────────────────

    @Test
    fun `evToCameraUnits_sifirEV_sifirBirim`() {
        assertEquals(0, ProCameraCapabilities.evToCameraUnits(0f, 1f / 6f, -12, 12))
    }

    @Test
    fun `evToCameraUnits_artibirEV_altiBirim`() {
        assertEquals(6, ProCameraCapabilities.evToCameraUnits(1f, 1f / 6f, -12, 12))
    }

    @Test
    fun `evToCameraUnits_eksibirEV_eksiAltiBirim`() {
        assertEquals(-6, ProCameraCapabilities.evToCameraUnits(-1f, 1f / 6f, -12, 12))
    }

    @Test
    fun `evToCameraUnits_ustSinirinUzeriKlemplenir`() {
        assertEquals(12, ProCameraCapabilities.evToCameraUnits(10f, 1f / 6f, -12, 12))
    }

    @Test
    fun `evToCameraUnits_altSinirinAltinaKlemplenir`() {
        assertEquals(-12, ProCameraCapabilities.evToCameraUnits(-10f, 1f / 6f, -12, 12))
    }

    @Test
    fun `evToCameraUnits_yarimAdimYuvarlanir`() {
        // 0.5 EV, 1/6 step ile → 3 birim (3.0 tam tamına).
        assertEquals(3, ProCameraCapabilities.evToCameraUnits(0.5f, 1f / 6f, -12, 12))
        // 0.9 EV → 5.4 → roundToInt = 5.
        assertEquals(5, ProCameraCapabilities.evToCameraUnits(0.9f, 1f / 6f, -12, 12))
    }

    // ── kelvinToRgbGains ───────────────────────────────────────────────

    @Test
    fun `kelvinToRgbGains_5500K_noralseYakin`() {
        val (rGain, bGain) = ProCameraCapabilities.kelvinToRgbGains(5500)
        assertEquals(1.0f, rGain, 0.1f)
        assertEquals(1.0f, bGain, 0.1f)
    }

    @Test
    fun `kelvinToRgbGains_2500K_sicakTonKirmiziAgirlikli`() {
        val (rGain, bGain) = ProCameraCapabilities.kelvinToRgbGains(2500)
        assertTrue("rGain > 1 olmalı (sıcak ton)", rGain > 1f)
        assertTrue("bGain < 1 olmalı (sıcak ton)", bGain < 1f)
    }

    @Test
    fun `kelvinToRgbGains_8000K_sogukTonMaviAgirlikli`() {
        val (rGain, bGain) = ProCameraCapabilities.kelvinToRgbGains(8000)
        assertTrue("rGain < 1 olmalı (soğuk ton)", rGain < 1f)
        assertTrue("bGain > 1 olmalı (soğuk ton)", bGain > 1f)
    }

    @Test
    fun `kelvinToRgbGains_arttikcaBgainMonotonArtar`() {
        // Sıcaktan (düşük Kelvin) soğuğa (yüksek Kelvin) giderken bGain monoton artmalı.
        val kelvins = listOf(1000, 2000, 3000, 4000, 5000, 6000, 7000, 8000, 9000, 10000)
        val bGains = kelvins.map { ProCameraCapabilities.kelvinToRgbGains(it).second }
        for (i in 1 until bGains.size) {
            assertTrue(
                "bGain monoton artmalı (k=${kelvins[i]}, bGain=${bGains[i]})",
                bGains[i] >= bGains[i - 1]
            )
        }
    }

    @Test
    fun `kelvinToRgbGains_aralikDisiKlemplenir`() {
        // 1K ve 10^9 K gibi uç değerler 1000..40000'e kıstırılır; hata fırlatılmaz.
        val cold = ProCameraCapabilities.kelvinToRgbGains(1)
        val hot = ProCameraCapabilities.kelvinToRgbGains(1_000_000_000)
        assertTrue("1K klemplenince 1000K davranışı beklenir", cold.first >= 1f)
        assertTrue("1_000_000_000K klemplenince 40000K davranışı beklenir", hot.first <= 1f)
    }
}
