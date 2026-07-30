package com.magicv3.scanner3d.infra.system

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.magicv3.scanner3d.domain.model.RamMetrics
import com.magicv3.scanner3d.domain.model.CpuMetrics
import com.magicv3.scanner3d.domain.model.ThermalMetrics
import com.magicv3.scanner3d.domain.model.ThermalZoneReading
import com.magicv3.scanner3d.domain.model.ThermalZoneType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File


/**
 * Sistem donanım metriklerini okuyan infra katmanı servisi.
 *
 * Mimari konum: infra/system/ → çekirdek API'leri (ActivityManager,
 * Debug, /proc) doğrudan çağırır. Domain ya da UI işi DEĞİL.
 *
 * Phase 1.4 (BU ADIM): Sadece RAM
 * Phase 1.5 (sonraki) : CPU (top komutu parsing veya /proc/stat)
 * Phase 1.6 (sonraki) : Thermal (SensorManager TYPE_TEMPERATURE)
 *
 * ── Reaktif Pattern: Flow ──────────────────────────────────────────
 * • Flow<RamMetrics> → cold stream, collect edildiğinde çalışır
 * • Compose tarafı: viewModel.metrics.collectAsState(initial = EMPTY)
 *   → collect sistemi lifecycle-aware otomatik başlat/durdurur
 * • Cold flow → collect durduğunda polling de durur (resource boşa gitmez)
 *
 * ── Thread Modeli ──────────────────────────────────────────────────
 * • flow { ... } builder: coroutine context'te çalışır
 *   (varsayılan: collect çağıran thread — Compose'da main)
 * • flowOn(Dispatchers.IO) → yukarı akış (flow builder gövdesi)
 *   IO dispatcher'a taşınır → /proc/meminfo file read main'i bloklamaz
 * • ActivityManager.getMemoryInfo() → binder IPC (system_server)
 *   ~1-3ms — main thread'de zararsız ama yine de IO'da daha iyi
 * • Debug.getMemoryInfo() → native libmeminfo → ~5-10ms
 *   /proc/<pid>/smaps rollup — IO dispatcher şart
 *
 * @param context Herhangi bir context — getSystemService ActivityMgr'ye
 *                ulaşır. ApplicationContext yeterli.
 */
class SystemMonitor(
    context: Context
) {
    /** 5-tuple — Kotlin stdlib'de yok, private data class. */
    private data class Quintuple<A, B, C, D, E>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D,
        val fifth: E
    )

    companion object {
        private const val TAG = "SystemMonitor"
        private const val DEFAULT_INTERVAL_MS = 1000L
        private const val PROC_MEMINFO_PATH = "/proc/meminfo"
        private const val PROC_STAT_PATH = "/proc/stat"
        private const val PROC_SELF_STAT_PATH = "/proc/self/stat"
        private const val THERMAL_BASE_PATH = "/sys/class/thermal"
        private const val THERMAL_INTERVAL_MS = 2000L
        private const val THERMAL_FALLBACK_INTERVAL_MS = 3000L
    }

    // Application context — SystemMonitor uzun ömürlü olabilir
    private val appContext = context.applicationContext
    private val activityManager: ActivityManager? =
        appContext.getSystemService(ActivityManager::class.java)

    // ── Public API ───────────────────────────────────────────────────

    /**
     * RAM metriklerini periyodik yayınlayan cold flow.
     *
     * @param intervalMs Okuma sıklığı. Default 1000ms (1sn) — HUD
     *                  için yeterli frekans, batarya yükü mininum.
     *                  500ms'ye inmek performans etkisi yüksek olmaz
     *                  ama HUD titremesi için mantıksız.
     * @return Cold Flow<RamMetrics> — collect edilene kadar çalışmaz.
     *         Hot yapmak için stateIn() kullanılır (Phase 1.7'de).
     */
    fun monitorRam(intervalMs: Long = DEFAULT_INTERVAL_MS): Flow<RamMetrics> = flow {
        while (true) {
            val metrics = readRamMetrics()
            emit(metrics)
            delay(intervalMs)
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Tek seferlik RAM okuması — anlık snapshot gerektiğinde.
     * Flow dışında tekil okuma için (test, debug, başlangıç değeri).
     */
    suspend fun snapshotRam(): RamMetrics {
        // IO context'te çağırılmalı — bu metod suspended olduğu için
        // otomatik olarak caller'ın context'inde çalışır. Garanti
        // etmek için withContext(Dispatchers.IO) {}
        return readRamMetrics()
    }

    // ── Private — RAM okuma implemantasyonu ──────────────────────────

    /**
     * RAM metriklerini üç kaynaktan toplar:
     *  1. ActivityManager.MemoryInfo → system RAM (kernel sys_mem)
     *  2. Debug.MemoryInfo           → app process PSS (smaps rollup)
     *  3. /proc/meminfo parsing      → swap (Honor RAM Turbo için)
     *
     * Üçü de birleştirilip RamMetrics'e paketlenir.
     */
    private fun readRamMetrics(): RamMetrics {
        // 1) System-wide RAM — ActivityManagerService binder IPC
        val memInfo = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memInfo)

        // 2) App process PSS — Debug.MemoryInfo (libmeminfo native)
        val debugMemInfo = Debug.MemoryInfo()
        Debug.getMemoryInfo(debugMemInfo)
        // totalPss KB cinsinden → byte'a çevir
        val appPssBytes = debugMemInfo.totalPss.toLong() * 1024L

        // 3) /proc/meminfo → SwapTotal, SwapFree (Honor RAM Turbo)
        val (swapTotalBytes, swapFreeBytes) = readProcMeminfoSwap()

        // Hesaplamalar — Long arithmetic, overflow'a karşı dikkat
        val usedBytes = memInfo.totalMem - memInfo.availMem
        val usedPercent = if (memInfo.totalMem > 0) {
            ((usedBytes * 100L) / memInfo.totalMem).toInt()
        } else 0

        val swapUsedBytes = (swapTotalBytes - swapFreeBytes).coerceAtLeast(0L)
        val swapUsedPercent = if (swapTotalBytes > 0) {
            ((swapUsedBytes * 100L) / swapTotalBytes).toInt()
        } else 0

        return RamMetrics(
            totalBytes = memInfo.totalMem,
            availableBytes = memInfo.availMem,
            usedBytes = usedBytes,
            usedPercent = usedPercent,
            lowMemory = memInfo.lowMemory,
            thresholdBytes = memInfo.threshold,
            swapTotalBytes = swapTotalBytes,
            swapUsedBytes = swapUsedBytes,
            swapUsedPercent = swapUsedPercent,
            appPssBytes = appPssBytes,
            timestamp = System.currentTimeMillis()
        )
    }

    /**
     * /proc/meminfo dosyasını parse eder — yalnızca swap bilgisi için.
     *
     * Honor Magic V3'te özel durum:
     * • SwapTotal: 12GB civarı (Honor RAM Turbo virtual swap)
     * • SwapFree:  SwapTotal - kullanılan
     *
     * Depolamadan swaplanan veri miktarını hesaplar.
     */
    private fun readProcMeminfoSwap(): Pair<Long, Long> {
        return try {
            val meminfoFile = File(PROC_MEMINFO_PATH)
            if (!meminfoFile.exists() || !meminfoFile.canRead()) {
                Log.w(TAG, "/proc/meminfo not accessible — swap metrics will be 0")
                return Pair(0L, 0L)
            }

            var swapTotalBytes = 0L
            var swapFreeBytes = 0L

            meminfoFile.useLines { lines ->
                lines.forEach { line ->
                    when {
                        line.startsWith("SwapTotal:") -> {
                            swapTotalBytes = parseMeminfoKb(line) * 1024L
                        }
                        line.startsWith("SwapFree:") -> {
                            swapFreeBytes = parseMeminfoKb(line) * 1024L
                        }
                    }
                }
            }

            Pair(swapTotalBytes, swapFreeBytes)
        } catch (e: Exception) {
            Log.w(TAG, "/proc/meminfo read failed — returning zeros", e)
            Pair(0L, 0L)
        }
    }

    /**
     * /proc/meminfo tarzı bir satırdan kB değerini parse eder.
     * Format: "SwapTotal:       12345678 kB"
     */
    private fun parseMeminfoKb(line: String): Long {
        return Regex("(\\d+)").find(line)?.value?.toLongOrNull() ?: 0L
    }

    // ════════════════════════════════════════════════════════════════
    // CPU MONITORING — Phase 1.5
    // ════════════════════════════════════════════════════════════════

    /**
     * CPU metriklerini periyodik yayınlayan cold flow — Delta tabanlı.
     *
     * Çalışma prensibi:
     *  1. İlk okuma → previous snapshot sakla → CpuMetrics.EMPTY emit
     *  2. Sonraki her okuma → current snapshot → delta hesap → CpuMetrics emit
     *
     * Delta yoksa (ilk okuma) 0 döner — kullanıcı ilk saniye HUD'da 0%
     * görür, 2. saniye 1 sn sonra gerçek değer gelir. Bu beklenen davranış.
     *
     * @param intervalMs Okuma sıklığı (default 1000ms — RAM ile aynı)
     * @return Cold Flow<CpuMetrics>
     */
    fun monitorCpu(intervalMs: Long = DEFAULT_INTERVAL_MS): Flow<CpuMetrics> = flow {
        // Önce native /proc dene
        val initialSnapshot = readCpuSnapshot()
        val nativeProcStatAvailable = initialSnapshot.coreCount > 0

        if (nativeProcStatAvailable) {
            // /proc/stat erişilebilir — Plan A (delta-based)
            var previous = initialSnapshot
            emit(CpuMetrics.EMPTY.copy(
                coreCount = previous.coreCount,
                uptimeJiffies = previous.uptimeJiffies,
                timestamp = System.currentTimeMillis()
            ))
            while (true) {
                delay(intervalMs)
                val current = readCpuSnapshot()
                emit(computeCpuDelta(previous, current))
                previous = current
            }
        } else {
            // /proc/stat bloke — Plan B (app CPU time delta)
            Log.w(TAG, "/proc/stat blocked — using platform API fallback (app CPU only)")
            var prevAppCpuMs = Process.getElapsedCpuTime()
            var prevWallMs = SystemClock.elapsedRealtime()
            val coreCount = Runtime.getRuntime().availableProcessors()

            // İlk emit
            emit(CpuMetrics.EMPTY.copy(
                coreCount = coreCount,
                uptimeJiffies = 0L,
                timestamp = System.currentTimeMillis()
            ))

            while (true) {
                delay(intervalMs)
                val currAppCpuMs = Process.getElapsedCpuTime()
                val currWallMs = SystemClock.elapsedRealtime()
                val deltaAppMs = (currAppCpuMs - prevAppCpuMs).coerceAtLeast(0L)
                val deltaWallMs = (currWallMs - prevWallMs).coerceAtLeast(1L)
                // App CPU% — tüm çekirdekler bazında kapasite normalize edildi
                // (delta_app_ms / (delta_wall_ms × core_count)) × 100
                val appUsagePercent = ((deltaAppMs * 100L) / (deltaWallMs * coreCount))
                    .toInt().coerceIn(0, 100)
                // Per-core breakdown yok — app'i dağıtık göster (her core'a ortalama)
                val perCoreUsage = List(coreCount) { appUsagePercent }

                emit(CpuMetrics(
                    totalUsagePercent = appUsagePercent,
                    perCoreUsagePercents = perCoreUsage,
                    appUsagePercent = appUsagePercent,
                    coreCount = coreCount,
                    uptimeJiffies = currWallMs,
                    timestamp = System.currentTimeMillis()
                ))
                prevAppCpuMs = currAppCpuMs
                prevWallMs = currWallMs
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Tek seferlik CPU okuması — delta hesap için iki nokta gerekir.
     * Bu fonksiyonla arka arkaya iki kez çağırıp delta hesaplanabilir.
     * Standalone kullanım için değil, yardımcı metod.
     */
    suspend fun snapshotCpu(): CpuMetrics {
        val s1 = readCpuSnapshot()
        delay(DEFAULT_INTERVAL_MS)
        val s2 = readCpuSnapshot()
        return computeCpuDelta(s1, s2)
    }

    // ── CPU Snapshot Yapısı (private inner) ─────────────────────────

    /**
     * Bir /proc/stat okuma anlık görüntüsü — delta için saklanır.
     *
     * @param totalJiffies Tüm çekirdeklerin toplam jiffiesi (idle dahil)
     * @param totalIdleJiffies Tüm çekirdeklerin idle jiffiesi (idle+iowait)
     * @param perCoreTotal List<Long> — her çekirdeğin toplam jiffiesi
     * @param perCoreIdle  List<Long> — her çekirdeğin idle jiffiesi
     * @param appCpuJiffies /proc/self/stat → utime + stime
     * @param coreCount Çekirdek sayısı
     * @param uptimeJiffies /proc/stat ilk satır "btime" yok; cpu aggregate
     *                      toplamı uptime proxy'si olarak kullanılır
     */
    private data class CpuSnapshot(
        val totalJiffies: Long,
        val totalIdleJiffies: Long,
        val perCoreTotal: List<Long>,
        val perCoreIdle: List<Long>,
        val appCpuJiffies: Long,
        val coreCount: Int,
        val uptimeJiffies: Long
    )

    /**
     * /proc/stat + /proc/self/stat'ı okuyup snapshot oluşturur.
     * Bu metod IO dispatcher'da çağrılır (flowOn(IO) garantisi).
     */
    private fun readCpuSnapshot(): CpuSnapshot {
        val (totalJiffies, totalIdleJiffies, perCoreTotal, perCoreIdle, coreCount) =
            readProcStat()

        val appCpuJiffies = readProcSelfStat()

        return CpuSnapshot(
            totalJiffies = totalJiffies,
            totalIdleJiffies = totalIdleJiffies,
            perCoreTotal = perCoreTotal,
            perCoreIdle = perCoreIdle,
            appCpuJiffies = appCpuJiffies,
            coreCount = coreCount,
            uptimeJiffies = totalJiffies
        )
    }

    /**
     * /proc/stat dosyasını parse eder.
     *
     * Format (SD 8 Gen 3 — 8 çekirdek):
     *   cpu  123456 789 23456 987654 ...        ← aggregate (space)
     *   cpu0 12345 67 2345 98765 ...            ← per-core 0
     *   cpu1 ...
     *   ...
     *   cpu7 ...
     *   intr 1234567890 ...
     *   ctxt ...
     *   btime 1700000000
     *   ...
     *
     * Heap allocation minimize için useLines{} kullanırız.
     *
     * @return (aggregateTotal, aggregateIdle, perCoreTotal[], perCoreIdle[], coreCount)
     */
    private fun readProcStat(): Quintuple<Long, Long, List<Long>, List<Long>, Int> {
        return try {
            val statFile = File(PROC_STAT_PATH)
            if (!statFile.exists() || !statFile.canRead()) {
                Log.w(TAG, "/proc/stat not accessible — CPU metrics empty")
                return Quintuple(0L, 0L, emptyList(), emptyList(), 0)
            }

            var aggregateTotal = 0L
            var aggregateIdle = 0L
            val coresTotal = mutableListOf<Long>()
            val coresIdle = mutableListOf<Long>()

            statFile.useLines { lines ->
                lines.forEach lineLoop@{ line ->
                    // Aggregate satır (boşluk sonrası "cpu"): "cpu  12345 678 ..."
                    if (line.startsWith("cpu ")) {
                        val parts = line.trim().split(Regex("\\s+"))
                        // parts[0]="cpu", parts[1..]=user nice system idle iowait irq softirq steal
                        if (parts.size >= 5) {
                            val user = parts[1].toLongOrNull() ?: 0L
                            val nice = parts[2].toLongOrNull() ?: 0L
                            val system = parts[3].toLongOrNull() ?: 0L
                            val idle = parts[4].toLongOrNull() ?: 0L
                            val iowait = if (parts.size > 5) parts[5].toLongOrNull() ?: 0L else 0L
                            val irq = if (parts.size > 6) parts[6].toLongOrNull() ?: 0L else 0L
                            val softirq = if (parts.size > 7) parts[7].toLongOrNull() ?: 0L else 0L
                            val steal = if (parts.size > 8) parts[8].toLongOrNull() ?: 0L else 0L

                            aggregateTotal = user + nice + system + idle + iowait + irq + softirq + steal
                            aggregateIdle = idle + iowait
                        }
                    }
                    // Per-core satırlar: "cpu0 123 ...", "cpu1 234 ..."
                    else if (line.startsWith("cpu") && line.length > 3 && line[3].isDigit()) {
                        val parts = line.trim().split(Regex("\\s+"))
                        if (parts.size >= 5) {
                            val user = parts[1].toLongOrNull() ?: 0L
                            val nice = parts[2].toLongOrNull() ?: 0L
                            val system = parts[3].toLongOrNull() ?: 0L
                            val idle = parts[4].toLongOrNull() ?: 0L
                            val iowait = if (parts.size > 5) parts[5].toLongOrNull() ?: 0L else 0L
                            val irq = if (parts.size > 6) parts[6].toLongOrNull() ?: 0L else 0L
                            val softirq = if (parts.size > 7) parts[7].toLongOrNull() ?: 0L else 0L
                            val steal = if (parts.size > 8) parts[8].toLongOrNull() ?: 0L else 0L

                            coresTotal.add(user + nice + system + idle + iowait + irq + softirq + steal)
                            coresIdle.add(idle + iowait)
                        }
                    }
                }
            }

            Quintuple(
                aggregateTotal,
                aggregateIdle,
                coresTotal,
                coresIdle,
                coresTotal.size
            )
        } catch (e: Exception) {
            Log.w(TAG, "/proc/stat read failed — CPU metrics zero", e)
            Quintuple(0L, 0L, emptyList(), emptyList(), 0)
        }
    }

    /**
     * /proc/self/stat'ın 14. (utime) ve 15. (stime) alanlarını okur.
     *
     * Format (man proc):
     *   pid (comm) state ppid pgrp session tty_nr tpgid flags ...
     *   minflt cminflt majflt cmajflt
     *   utime stime cutime cstime            ← sırasıyla 14, 15, 16, 17
     *   priority nice ...
     *
     * "(comm)" parantez içinde boşluk içerebilir → split naive patlar.
     * Çözüm: son ")" ile utime arasındaki kalanı parse et.
     *
     * @return utime + stime (jiffies)
     */
    private fun readProcSelfStat(): Long {
        return try {
            val statFile = File(PROC_SELF_STAT_PATH)
            if (!statFile.exists() || !statFile.canRead()) return 0L

            // Tüm satırı tek seferde oku — küçük dosya (~1KB)
            val line = statFile.bufferedReader().use { it.readLine() } ?: return 0L

            // comm field'ı "(...)" içinde, boşluk içerebilir.
            // Son ")" sonrası kalan kelimelerin içinde 13. ve 14. alanlar
            // utime ve stime'dir (1-indexed man proc'a göre).
            val lastParen = line.lastIndexOf(')')
            if (lastParen < 0) return 0L

            // fields[0] = "state"  (1-indexed 3. alan = state  → array idx 0)
            // fields[1] = "ppid"   (4. alan → idx 1)
            // ...
            // utime = 14. alan → idx 11  (14 - 3 = 11)
            // stime = 15. alan → idx 12
            val fields = line.substring(lastParen + 1).trim().split(Regex("\\s+"))
            if (fields.size <= 12) return 0L

            val utime = fields[11].toLongOrNull() ?: 0L
            val stime = fields[12].toLongOrNull() ?: 0L
            utime + stime
        } catch (e: Exception) {
            Log.w(TAG, "/proc/self/stat read failed — app CPU time zero", e)
            0L
        }
    }

    /**
     * İki snapshot arasındaki delta'yı CpuMetrics'e çevirir.
     *
     * Formül:
     *   delta_total = curr.totalJiffies - prev.totalJiffies
     *   delta_idle  = curr.idleJiffies  - prev.idleJiffies
     *   usage_%     = ((delta_total - delta_idle) × 100) / delta_total
     *
     * Per-core için aynı formül her (curr.perCore[i], prev.perCore[i]) için.
     *
     * App share:
     *   delta_app = curr.appCpuJiffies - prev.appCpuJiffies
     *   app_%     = (delta_app × 100) / delta_total
     *   (delta_total = aggregate delta = 8 çekirdek total jiffies Δ)
     *
     * Edge cases:
     *   - delta_total <= 0 → 0% (erişilemedi ya da Çok hızlı okuma)
     *   - delta_idle > delta_total → clamp to 0% (clock skew nadir)
     *   - Liste boyutları farklı → zip'e güvenli (shorter list ekseninde)
     */
    private fun computeCpuDelta(prev: CpuSnapshot, curr: CpuSnapshot): CpuMetrics {
        // Aggregate delta
        val deltaTotal = (curr.totalJiffies - prev.totalJiffies).coerceAtLeast(0L)
        val deltaIdle = (curr.totalIdleJiffies - prev.totalIdleJiffies).coerceAtLeast(0L)

        val totalUsagePercent = if (deltaTotal > 0) {
            ((deltaTotal - deltaIdle) * 100L / deltaTotal).toInt().coerceIn(0, 100)
        } else 0

        // Per-core delta — listeler farklı boyutta olabilir (nadir)
        val perCoreUsage = mutableListOf<Int>()
        val minSize = minOf(prev.perCoreTotal.size, curr.perCoreTotal.size)
        for (i in 0 until minSize) {
            val dTotal = (curr.perCoreTotal[i] - prev.perCoreTotal[i]).coerceAtLeast(0L)
            val dIdle = (curr.perCoreIdle[i] - prev.perCoreIdle[i]).coerceAtLeast(0L)
            val pct = if (dTotal > 0) {
                ((dTotal - dIdle) * 100L / dTotal).toInt().coerceIn(0, 100)
            } else 0
            perCoreUsage.add(pct)
        }

        // App CPU share — app delta / aggregate total delta
        val deltaApp = (curr.appCpuJiffies - prev.appCpuJiffies).coerceAtLeast(0L)
        val appUsagePercent = if (deltaTotal > 0) {
            ((deltaApp * 100L) / deltaTotal).toInt().coerceIn(0, 100)
        } else 0

        return CpuMetrics(
            totalUsagePercent = totalUsagePercent,
            perCoreUsagePercents = perCoreUsage.toList(),
            appUsagePercent = appUsagePercent,
            coreCount = curr.coreCount,
            uptimeJiffies = curr.uptimeJiffies,
            timestamp = System.currentTimeMillis()
        )
    }

    // ════════════════════════════════════════════════════════════════
    // THERMAL MONITORING — Phase 1.6
    // ════════════════════════════════════════════════════════════════

    /**
     * Termal metrikleri periyodik yayınlayan cold flow.
     *
     * Çekirdek yaklaşım: /sys/class/thermal/thermal_zone* dizinini
     * enumerate et → her zone için (type, temp) oku → ThermalZoneReading
     * üret → toplu ThermalMetrics'e map et.
     *
     * @param intervalMs Okuma sıklığı. Default 2000ms — thermaller
     *                  yavaş değişir, RAM/CPU'dan daha az. Batarya
     *                  tasarrufu + log noise azaltır.
     * @return Cold Flow<ThermalMetrics>
     */
    fun monitorThermal(intervalMs: Long = THERMAL_INTERVAL_MS): Flow<ThermalMetrics> = flow {
        while (true) {
            // Önce /sys/class/thermal dene — root cihazlarda çalışır
            val zones = readThermalSnapshot()
            val metrics = if (zones.isNotEmpty()) {
                computeThermalMetrics(zones)
            } else {
                // Honor V3 fallback: PowerManager + BatteryManager
                readThermalFromPlatformApis()
            }
            emit(metrics)
            delay(if (zones.isEmpty()) THERMAL_FALLBACK_INTERVAL_MS else intervalMs)
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Tek seferlik termal snapshot — debug/test için.
     */
    suspend fun snapshotThermal(): ThermalMetrics {
        val zones = readThermalSnapshot()
        return computeThermalMetrics(zones)
    }

    /**
     * /sys/class/thermal/thermal_zone* dizinini enumerate eder.
     * Her zone için (type, temp) okur.
     *
     * @return ThermalZoneReading listesi
     */
    private fun readThermalSnapshot(): List<ThermalZoneReading> {
        return try {
            val thermalDir = File(THERMAL_BASE_PATH)
            if (!thermalDir.exists() || !thermalDir.canRead()) {
                Log.w(TAG, "/sys/class/thermal not accessible — thermal metrics empty")
                return emptyList()
            }

            // thermal_zone0, thermal_zone1, ... N
            val zoneDirs = thermalDir.listFiles { file ->
                file.isDirectory && file.name.startsWith("thermal_zone")
            }?.sortedBy { dir ->
                val num = dir.name.removePrefix("thermal_zone").toIntOrNull() ?: 0
                num
            } ?: emptyList()

            val readings = mutableListOf<ThermalZoneReading>()
            for (zoneDir in zoneDirs) {
                val zoneId = zoneDir.name.removePrefix("thermal_zone").toIntOrNull() ?: -1
                if (zoneId < 0) continue

                val typeFile = File(zoneDir, "type")
                val tempFile = File(zoneDir, "temp")
                if (!typeFile.exists() || !tempFile.exists()) continue

                val zoneTypeRaw = typeFile.bufferedReader().use { it.readLine() }?.trim()
                    ?: continue
                val tempStr = tempFile.bufferedReader().use { it.readLine() }?.trim()
                    ?: continue

                val tempMilliC = tempStr.toLongOrNull() ?: continue
                val tempC = tempMilliC / 1000.0f

                readings.add(
                    ThermalZoneReading(
                        zoneId = zoneId,
                        zoneTypeRaw = zoneTypeRaw,
                        zoneType = classifyThermalZone(zoneTypeRaw),
                        tempCelsius = tempC,
                        timestamp = System.currentTimeMillis()
                    )
                )
            }

            readings
        } catch (e: Exception) {
            Log.w(TAG, "readThermalSnapshot failed", e)
            emptyList()
        }
    }

    /**
     * Ham zone type string'ini ThermalZoneType enum'ına mapler.
     *
     * SD 8 Gen 3 / Magic OS tipik zone type'ları:
     *   "cpu-0-0-usr"    → CPU_CORE
     *   "cpu-1-0-usr"    → CPU_CORE
     *   "gpu-1-0-usr"    → GPU
     *   "quiet-therm"    → SKIN (Qualcomm "quiet-therm" = skin proxy)
     *   "skin-therm"     → SKIN
     *   "battery"        → BATTERY
     *   "apu-therm"      → NPU (Honor "apu" kullanır)
     *   "npu-therm"      → NPU
     *   "ddr-therm"      → DDR
     *   "mem-therm"      → DDR
     *   "wlan-therm"     → WLAN
     *   "modem-therm"    → MODEM
     *
     * Substring match — case-sensitive, lowercase normalize.
     */
    private fun classifyThermalZone(typeRaw: String): ThermalZoneType {
        val t = typeRaw.lowercase()
        return when {
            t.startsWith("cpu") || t.contains("cpu") -> ThermalZoneType.CPU_CORE
            t.startsWith("gpu") || t.contains("gpu") -> ThermalZoneType.GPU
            t.startsWith("isp") || t.contains("isp") || t.startsWith("camera") -> ThermalZoneType.ISP
            t.startsWith("npu") || t.startsWith("apu") || t.contains("npu") || t.contains("apu") -> ThermalZoneType.NPU
            t.startsWith("skin") || t.startsWith("quiet") || t.contains("skin") || t.contains("quiet") -> ThermalZoneType.SKIN
            t.startsWith("battery") || t.contains("battery") -> ThermalZoneType.BATTERY
            t.startsWith("ddr") || t.startsWith("mem") || t.contains("ddr") || t.contains("mem") -> ThermalZoneType.DDR
            t.startsWith("wlan") || t.startsWith("wifi") || t.contains("wlan") || t.contains("wifi") -> ThermalZoneType.WLAN
            t.startsWith("modem") || t.contains("modem") -> ThermalZoneType.MODEM
            else -> ThermalZoneType.UNKNOWN
        }
    }

    /**
     * Ham zone okumalarını birleştirip ThermalMetrics üretir.
     */
    private fun computeThermalMetrics(zones: List<ThermalZoneReading>): ThermalMetrics {
        val cpuCores = zones.filter { it.zoneType == ThermalZoneType.CPU_CORE }
        val gpuZones = zones.filter { it.zoneType == ThermalZoneType.GPU }
        val skinZones = zones.filter { it.zoneType == ThermalZoneType.SKIN }
        val ispZones = zones.filter { it.zoneType == ThermalZoneType.ISP }
        val npuZones = zones.filter { it.zoneType == ThermalZoneType.NPU }
        val batteryZones = zones.filter { it.zoneType == ThermalZoneType.BATTERY }

        val socTempC = cpuCores.maxOfOrNull { it.tempCelsius } ?: 0f
        val skinTempC = skinZones.maxOfOrNull { it.tempCelsius } ?: 0f
        val gpuTempC = gpuZones.maxOfOrNull { it.tempCelsius } ?: 0f
        val ispTempC = ispZones.maxOfOrNull { it.tempCelsius } ?: 0f
        val npuTempC = npuZones.maxOfOrNull { it.tempCelsius } ?: 0f
        val batteryTempC = batteryZones.maxOfOrNull { it.tempCelsius } ?: 0f
        val cpuZoneTemps = cpuCores.map { it.tempCelsius }

        val throttlingLevel = when {
            socTempC >= 90f -> 2
            socTempC >= 80f -> 1
            else -> 0
        }
        val throttleWarning = socTempC >= 75f

        return ThermalMetrics(
            socTempC = socTempC,
            skinTempC = skinTempC,
            batteryTempC = batteryTempC,
            gpuTempC = gpuTempC,
            ispTempC = ispTempC,
            npuTempC = npuTempC,
            cpuZoneTempsC = cpuZoneTemps,
            allZoneReadings = zones,
            throttlingLevel = throttlingLevel,
            throttleWarning = throttleWarning,
            timestamp = System.currentTimeMillis()
        )
    }

    // ════════════════════════════════════════════════════════════════
    // THERMAL FALLBACK — Honor V3 SELinux block workaround (Phase 1.6.5)
    // /sys/class/thermal sysfs_thermal context untrusted_app'a kapalı.
    // Bu fallback PowerManager thermal status + BatteryManager sticky
    // intent kullanır — Android platform API'leriyle Honor'da çalışır.
    // ════════════════════════════════════════════════════════════════

    /**
     * PowerManager.ThermalStatus (0..6) → Celsius'a kabaca map.
     * Status=NONE boş 0, ardından her seviye artışı ~10°C varsay.
     */
    private fun thermalStatusToCelsius(status: Int): Float {
        return 35f + (status.coerceIn(0, 6) * 10f)
    }

    /**
     * PowerManager.ThermalStatus → throttlingLevel (0/1/2)
     */
    private fun thermalStatusToThrottleLevel(status: Int): Int = when (status) {
        in 0..2 -> 0
        3 -> 1
        else -> 2
    }

    /**
     * PowerManager.ThermalStatus → throttleWarning bool
     */
    private fun thermalStatusToThrottleWarning(status: Int): Boolean = status >= 2

    /**
     * BatteryManager sticky intent'ten battery temp oku.
     * Format: int × 0.1°C (270 = 27.0°C).
     */
    private fun readBatteryTempCelsius(): Float {
        return try {
            val intent = appContext.registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            ) ?: return 0f
            val tempRaw = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
            if (tempRaw > 0) tempRaw / 10.0f else 0f
        } catch (e: Exception) {
            Log.w(TAG, "Battery temp read failed", e)
            0f
        }
    }

    /**
     * /sys/class/thermal erişilemiyorsa (SELinux block) bu fallback.
     */
    private fun readThermalFromPlatformApis(): ThermalMetrics {
        var status = 0
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val powerManager = appContext.getSystemService(PowerManager::class.java)
            status = powerManager?.currentThermalStatus ?: 0
        }

        val socTempC = thermalStatusToCelsius(status)
        val skinTempC = socTempC - 5f
        val batteryTempC = readBatteryTempCelsius()
        val gpuTempC = socTempC + 2f
        val ispTempC = socTempC
        val npuTempC = socTempC - 2f

        return ThermalMetrics(
            socTempC = socTempC,
            skinTempC = skinTempC,
            batteryTempC = batteryTempC,
            gpuTempC = gpuTempC,
            ispTempC = ispTempC,
            npuTempC = npuTempC,
            cpuZoneTempsC = emptyList(),
            allZoneReadings = emptyList(),
            throttlingLevel = thermalStatusToThrottleLevel(status),
            throttleWarning = thermalStatusToThrottleWarning(status),
            timestamp = System.currentTimeMillis()
        )
    }
}
