package com.magicv3.scanner3d.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SystemMetrics saf fonksiyon testleri.
 *
 * Kapsam:
 *  - ramLevelFromPercent (60/80 eşikleri)
 *  - cpuLevelFromPercent (50/80 eşikleri)
 *  - thermalLevelFromCelsius (60/80 eşikleri)
 *  - formatBytes (0, KB, MB, GB — locale bağımsız)
 *
 * Saf domain fonksiyonları — Robolectric gerektirmez.
 */
class SystemMetricsTest {

    @Test
    fun `ramLevelFromPercent_esiklerDogru`() {
        assertEquals(MetricLevel.GOOD, ramLevelFromPercent(0))
        assertEquals(MetricLevel.GOOD, ramLevelFromPercent(59))
        assertEquals(MetricLevel.WARN, ramLevelFromPercent(60))
        assertEquals(MetricLevel.WARN, ramLevelFromPercent(79))
        assertEquals(MetricLevel.CRIT, ramLevelFromPercent(80))
        assertEquals(MetricLevel.CRIT, ramLevelFromPercent(100))
    }

    @Test
    fun `cpuLevelFromPercent_esiklerDogru`() {
        assertEquals(MetricLevel.GOOD, cpuLevelFromPercent(49))
        assertEquals(MetricLevel.WARN, cpuLevelFromPercent(50))
        assertEquals(MetricLevel.WARN, cpuLevelFromPercent(79))
        assertEquals(MetricLevel.CRIT, cpuLevelFromPercent(80))
        assertEquals(MetricLevel.CRIT, cpuLevelFromPercent(95))
    }

    @Test
    fun `thermalLevelFromCelsius_esiklerDogru`() {
        assertEquals(MetricLevel.GOOD, thermalLevelFromCelsius(59f))
        assertEquals(MetricLevel.WARN, thermalLevelFromCelsius(60f))
        assertEquals(MetricLevel.WARN, thermalLevelFromCelsius(79f))
        assertEquals(MetricLevel.CRIT, thermalLevelFromCelsius(80f))
        assertEquals(MetricLevel.CRIT, thermalLevelFromCelsius(100f))
    }

    @Test
    fun `formatBytes_sifirVeBirimler`() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("0 B", formatBytes(-5))

        assertByteFormat("B", bytes = 1023L, expectedValue = 1023.0)
        assertByteFormat("KB", bytes = 1024L, expectedValue = 1.0)
        assertByteFormat("MB", bytes = 1024L * 1024, expectedValue = 1.0)
        assertByteFormat("GB", bytes = 1024L * 1024 * 1024, expectedValue = 1.0)
    }

    /** Locale farkından (virgül/nokta) bağımsız kontrol. */
    private fun assertByteFormat(unit: String, bytes: Long, expectedValue: Double) {
        val s = formatBytes(bytes)
        assertTrue("Birim $unit içermeli: $s", s.endsWith(unit))
        val numeric = s.substringBefore(" ").replace(',', '.').toDouble()
        assertEquals("$bytes için değer", expectedValue, numeric, 0.05)
    }
}
