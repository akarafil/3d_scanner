package com.magicv3.scanner3d.infra.system

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.util.Log
import com.magicv3.scanner3d.domain.model.RamMetrics
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
    companion object {
        private const val TAG = "SystemMonitor"
        private const val DEFAULT_INTERVAL_MS = 1000L
        private const val PROC_MEMINFO_PATH = "/proc/meminfo"
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
}
