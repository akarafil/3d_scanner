package com.magicv3.scanner3d.infra.system

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.magicv3.scanner3d.domain.model.ThermalZoneType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SystemMonitor birim testleri.
 *
 * Kapsam:
 *  - parseMeminfoKb: /proc/meminfo satırı → kB
 *  - computeCpuDelta: %50 kullanım, clamp, boş liste güvenliği
 *  - classifyThermalZone: cpu/quiet/apu eşlemesi
 *  - thermalStatusToCelsius (private; reflection ile) — 0..6 → 35..95
 *  - monitorRam flow: ilk emisyon + Turbine + monitorDispatcher sanal zaman
 *
 * monitorDispatcher enjeksiyonu sayesinde akışlar gerçek zaman olmadan test edilir.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SystemMonitorTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun monitor(dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Unconfined): SystemMonitor =
        SystemMonitor(
            context = context,
            monitorDispatcher = dispatcher,
            procMeminfoPath = "/nonexistent/meminfo", // gerçek /proc okumasını engelle
            procStatPath = "/nonexistent/stat",
            procSelfStatPath = "/nonexistent/self/stat",
            thermalBasePath = "/nonexistent/thermal",
        )

    // ── parseMeminfoKb ───────────────────────────────────────────────

    @Test
    fun `parseMeminfoKb_swapSatirindanKbDegeriniCikarir`() {
        val monitor = monitor()
        assertEquals(12345678L, monitor.parseMeminfoKb("SwapTotal:       12345678 kB"))
        assertEquals(0L, monitor.parseMeminfoKb("SwapTotal: abc"))
        assertEquals(0L, monitor.parseMeminfoKb(""))
    }

    // ── computeCpuDelta ──────────────────────────────────────────────

    private fun snapshot(
        total: Long,
        idle: Long,
        perCoreTotal: List<Long> = emptyList(),
        perCoreIdle: List<Long> = emptyList(),
        coreCount: Int = perCoreTotal.size,
    ): SystemMonitor.CpuSnapshot = SystemMonitor.CpuSnapshot(
        totalJiffies = total,
        totalIdleJiffies = idle,
        perCoreTotal = perCoreTotal,
        perCoreIdle = perCoreIdle,
        appCpuJiffies = 0L,
        coreCount = coreCount,
        uptimeJiffies = total,
    )

    @Test
    fun `computeCpuDelta_yuzdeElliKullanim`() {
        val monitor = monitor()
        val prev = snapshot(total = 1000, idle = 500, perCoreTotal = listOf(100L, 200L), perCoreIdle = listOf(50L, 100L))
        val curr = snapshot(total = 2000, idle = 1000, perCoreTotal = listOf(200L, 400L), perCoreIdle = listOf(100L, 200L))

        val metrics = monitor.computeCpuDelta(prev, curr)

        assertEquals(50, metrics.totalUsagePercent)
        assertEquals(50, metrics.perCoreUsagePercents[0])
        assertEquals(50, metrics.perCoreUsagePercents[1])
        assertEquals(2, metrics.coreCount)
    }

    @Test
    fun `computeCpuDelta_negatifOranSifiraClampEdilir`() {
        val monitor = monitor()
        // idle delta > total delta → (deltaTotal - deltaIdle) < 0 → clamp 0
        val prev = snapshot(total = 1000, idle = 0)
        val curr = snapshot(total = 1100, idle = 600)

        val metrics = monitor.computeCpuDelta(prev, curr)

        assertEquals(0, metrics.totalUsagePercent)
    }

    @Test
    fun `computeCpuDelta_bosListeGuvenli`() {
        val monitor = monitor()
        val prev = snapshot(total = 1000, idle = 500)
        val curr = snapshot(total = 2000, idle = 1000)

        val metrics = monitor.computeCpuDelta(prev, curr)

        assertEquals(50, metrics.totalUsagePercent)
        assertTrue(metrics.perCoreUsagePercents.isEmpty())
    }

    // ── classifyThermalZone ──────────────────────────────────────────

    @Test
    fun `classifyThermalZone_cpuQuietApuEslestirir`() {
        val monitor = monitor()
        assertEquals(ThermalZoneType.CPU_CORE, monitor.classifyThermalZone("cpu-0-0-usr"))
        assertEquals(ThermalZoneType.SKIN, monitor.classifyThermalZone("quiet-therm"))
        assertEquals(ThermalZoneType.NPU, monitor.classifyThermalZone("apu-therm"))
        assertEquals(ThermalZoneType.GPU, monitor.classifyThermalZone("gpu-1-0-usr"))
        assertEquals(ThermalZoneType.UNKNOWN, monitor.classifyThermalZone("unknown-zone"))
    }

    // ── thermalStatusToCelsius (private → reflection) ────────────────

    private fun thermalStatusToCelsius(monitor: SystemMonitor, status: Int): Float {
        val method = SystemMonitor::class.java.getDeclaredMethod("thermalStatusToCelsius", Int::class.javaPrimitiveType)
        method.isAccessible = true
        return method.invoke(monitor, status) as Float
    }

    @Test
    fun `thermalStatusToCelsius_sifirdanAltiya35den95e`() {
        val monitor = monitor()
        assertEquals(35f, thermalStatusToCelsius(monitor, 0), 0.01f)
        assertEquals(45f, thermalStatusToCelsius(monitor, 1), 0.01f)
        assertEquals(55f, thermalStatusToCelsius(monitor, 2), 0.01f)
        assertEquals(65f, thermalStatusToCelsius(monitor, 3), 0.01f)
        assertEquals(75f, thermalStatusToCelsius(monitor, 4), 0.01f)
        assertEquals(85f, thermalStatusToCelsius(monitor, 5), 0.01f)
        assertEquals(95f, thermalStatusToCelsius(monitor, 6), 0.01f)
        // Sınır dışı değerler coerceIn(0,6) ile kırpılır
        assertEquals(95f, thermalStatusToCelsius(monitor, 7), 0.01f)
        assertEquals(35f, thermalStatusToCelsius(monitor, -1), 0.01f)
    }

    // ── monitorRam flow (Turbine + sanal zaman) ──────────────────────

    @Test
    fun `monitorRam_ilkEmisyonYayinlar`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val monitor = monitor(dispatcher)

        monitor.monitorRam().test {
            val first = awaitItem()
            assertTrue("usedPercent 0..100 aralığında", first.usedPercent in 0..100)
            assertTrue(first.timestamp >= 0)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `monitorRam_monitorDispatcherSanalZamanIleIkinciEmisyon`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val monitor = monitor(dispatcher)

        monitor.monitorRam(intervalMs = 1000).test {
            val first = awaitItem()
            // Sanal zamanı 1sn ileri sar → delay(1000) tamamlanır, ikinci emisyon gelir
            testScheduler.advanceTimeBy(1000)
            val second = awaitItem()
            assertTrue("İkinci emisyon timestamp'i büyük/eşit olmalı", second.timestamp >= first.timestamp)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `snapshotRam_cokmezVeEmisyonUretir`() = runTest {
        val monitor = monitor()
        val metrics = monitor.snapshotRam()
        assertTrue(metrics.usedPercent in 0..100)
        assertTrue(metrics.timestamp >= 0)
    }
}
