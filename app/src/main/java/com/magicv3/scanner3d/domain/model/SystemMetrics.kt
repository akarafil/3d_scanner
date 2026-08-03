package com.magicv3.scanner3d.domain.model

/**
 * Sistem metrik veri modelleri — Phase 1.4 (RAM) ile başlar,
 * Phase 1.5 (sonraki) : CPU (top komutu parsing veya /proc/stat)
 * Phase 1.6 (sonraki) : Thermal (SensorManager TYPE_TEMPERATURE)
 *
 * Tüm metrikler immutable data class olarak tanımlanır —
 * Compose state olarak güvenle kullanılabilir (no mutation side-effect).
 */

// ══════════════════════════════════════════════════════════════════════
// RAM METRICS
// ══════════════════════════════════════════════════════════════════════

/**
 * Sistem RAM kullanım metrikleri — tek bir okuma anlık görüntüsü.
 *
 * ── Honor Magic V3 RAM Mimarisi Özeti ──────────────────────────────
 * Fiziksel RAM : 12GB LPDDR5x @ 8533 MT/s
 *                (Snapdragon 8 Gen 3 memory controller — 4-channel)
 * Sanal Swap   : 12GB "Honor RAM Turbo"
 *                (UFS 4.0 depolamadan carve-out — zRAM benzeri)
 * Toplam       : ~24GB "görünür" RAM (kernel ikisini birden raporlar)
 *
 * ── Field Anlamları ────────────────────────────────────────────────
 * @param totalBytes      Fiziksel RAM boyutu (≈12GB)
 * @param availableBytes  Kullanılabilir RAM (free + cached + reclaimable)
 *                        Linux'ta "free" yanıltıcıdır — cached aslında
 *                        tahsis edilebilir. Android bunu düzeltir.
 * @param usedBytes       totalBytes - availableBytes (gerçek kullanım)
 * @param usedPercent     0..100 arası yüzde — HUD bar rengini belirler
 * @param lowMemory       Sistem low-memory durumunda mı (kernel LMK active)
 * @param thresholdBytes  Low-memory threshold (altına inince lowMemory=true)
 * @param swapTotalBytes  Honor RAM Turbo swap toplam boyutu (≈12GB)
 * @param swapUsedBytes   Şu an swap'ta ne kadar data
 * @param swapUsedPercent 0..100 arası swap yüzdesi
 * @param appPssBytes     Bu uygulamanın PSS (Proportional Set Size) payı
 *                        — kendi memory footprint'imizi izlemek için
 * @param timestamp       Okuma anı (System.currentTimeMillis)
 */
data class RamMetrics(
    val totalBytes: Long,
    val availableBytes: Long,
    val usedBytes: Long,
    val usedPercent: Int,
    val lowMemory: Boolean,
    val thresholdBytes: Long,
    val swapTotalBytes: Long,
    val swapUsedBytes: Long,
    val swapUsedPercent: Int,
    val appPssBytes: Long,
    val timestamp: Long
) {
    companion object {
        /** Hata veya okuma yapılamadığında dummy değer. */
        val EMPTY = RamMetrics(
            totalBytes = 0L,
            availableBytes = 0L,
            usedBytes = 0L,
            usedPercent = 0,
            lowMemory = false,
            thresholdBytes = 0L,
            swapTotalBytes = 0L,
            swapUsedBytes = 0L,
            swapUsedPercent = 0,
            appPssBytes = 0L,
            timestamp = 0L
        )
    }
}

/**
 * HUD seviye sınıflandırması — SystemHud (Phase 1.7) renk kodlaması için.
 *
 * RAM eşikleri (Magic V3 12GB fiziksel referans):
 *   GOOD : <60% kullanım → HudGood (mint green)
 *   WARN : 60-80%       → HudWarn (amber)
 *   CRIT : >80%         → HudCrit (red) — belki tarama durdurulmalı
 */
enum class MetricLevel {
    GOOD,
    WARN,
    CRIT
}

/**
 * RAM yüzdesinden MetricLevel üretir — HUD renk kodlaması için
 * tek noktadan eşik yönetimi.
 */
fun ramLevelFromPercent(percent: Int): MetricLevel = when {
    percent < 60 -> MetricLevel.GOOD
    percent < 80 -> MetricLevel.WARN
    else -> MetricLevel.CRIT
}

/**
 * Byte cinsinden değerleri human-readable string'e çevirir
 * (SystemHud için "5.2 GB / 12.0 GB" formatı).
 */
fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex++
    }
    return "%.1f %s".format(value, units[unitIndex])
}

// ══════════════════════════════════════════════════════════════════════
// CPU METRICS  (Phase 1.5)
// ══════════════════════════════════════════════════════════════════════

/**
 * Sistem CPU kullanım metrikleri — iki ardışık okuma arasındaki delta.
 *
 * ── Honor Magic V3 / SD 8 Gen 3 Topolojisi ──────────────────────────
 *   Cluster 0 (Efficiency): cpu0-cpu1  Cortex-A520 @ 2.30 GHz
 *   Cluster 1 (Performance): cpu2-cpu3 Cortex-A720 @ 3.00 GHz
 *   Cluster 2 (Performance): cpu4-cpu5 Cortex-A720 @ 3.20 GHz
 *   Cluster 3 (Prime)      : cpu7       Cortex-X4   @ 3.30 GHz
 *                            (cpu6   A720 aux — cihaza göre değişken)
 *
 *   Toplam 8 çekirdek. Cluster-boundary'ler kernel boot parametreleri
 *   ve qcom dtbo'ya göre sınırlar değişebilir — bu fazda cluster ayrımı
 *   yapılmaz, sadece per-core percent List<Int> tutulur.
 *
 * ── Field Anlamları ────────────────────────────────────────────────
 * @param totalUsagePercent   8 çekirdeğin aggregate kullanımı (%) 0..100
 * @param perCoreUsagePercents Her çekirdek için ayrı yüzde — index = cpuN
 *                              Liste boyutu cihazın çekirdek sayısı
 *                              (8 beklenir, runtime'da doğrula)
 * @param appUsagePercent     Bu process'in tüm CPU'lara oranı (%)
 *                            Kamera + ISP pipeline yükünü yansıtır
 * @param coreCount           Cihazdaki toplam çekirdek sayısı
 * @param uptimeJiffies       İşletim sistemi boot jiffies (debug)
 * @param timestamp           Okuma anı (System.currentTimeMillis)
 */
data class CpuMetrics(
    val totalUsagePercent: Int,
    val perCoreUsagePercents: List<Int>,
    val appUsagePercent: Int,
    val coreCount: Int,
    val uptimeJiffies: Long,
    val timestamp: Long
) {
    companion object {
        /** İlk okuma (önceki snapshot yoksa) ya da hata durumunda dummy. */
        val EMPTY = CpuMetrics(
            totalUsagePercent = 0,
            perCoreUsagePercents = emptyList(),
            appUsagePercent = 0,
            coreCount = 0,
            uptimeJiffies = 0L,
            timestamp = 0L
        )
    }
}

/**
 * CPU yüzdesinden MetricLevel üretir — RAM'den farklı eşik:
 *   GOOD : <50%  → HudGood (kamera + Compose yükü az)
 *   WARN : 50-80% → HudWarn (tarama sırasında beklenen)
 *   CRIT : >80%  → HudCrit (thermal throttle riski artıyor)
 *
 * RAM'den daha dar eşik çünkü CPU throttle'a RAM'den önce girer.
 */
fun cpuLevelFromPercent(percent: Int): MetricLevel = when {
    percent < 50 -> MetricLevel.GOOD
    percent < 80 -> MetricLevel.WARN
    else -> MetricLevel.CRIT
}

// ══════════════════════════════════════════════════════════════════════
// THERMAL METRICS (Phase 1.6)
// ══════════════════════════════════════════════════════════════════════

/**
 * Termal zone tipleri — /sys/class/thermal/thermal_zone* /type
 * dosyasından okunan string'e göre map.
 */
enum class ThermalZoneType {
    CPU_CORE,       // "cpu-*" veya "cpu-thermal"
    GPU,            // "gpu-*"
    ISP,            // "isp-*" veya "camera-*"
    NPU,            // "npu-*" veya "apu-*" (Honor "apu" kullanır)
    SKIN,           // "skin-*" veya "quiet-therm"
    BATTERY,        // "battery-*"
    DDR,            // "ddr-*" veya "mem-*"
    WLAN,           // "wlan-*" veya "wifi-*"
    MODEM,          // "modem-*"
    UNKNOWN         // eşleşmeyen zone'lar
}

/**
 * Tek bir termal zone ölçümü.
 */
data class ThermalZoneReading(
    val zoneId: Int,
    val zoneTypeRaw: String,
    val zoneType: ThermalZoneType,
    val tempCelsius: Float,
    val timestamp: Long
)

/**
 * Sistem termal metrikleri — tüm önemli termal zonelardan
 * gelen verinin özetlenmiş hali.
 */
data class ThermalMetrics(
    val socTempC: Float,
    val skinTempC: Float,
    val batteryTempC: Float,
    val gpuTempC: Float,
    val ispTempC: Float,
    val npuTempC: Float = 0f,
    val cpuZoneTempsC: List<Float>,
    val allZoneReadings: List<ThermalZoneReading>,
    val throttlingLevel: Int,
    val throttleWarning: Boolean,
    val timestamp: Long,
    val thermalStatus: Int = -1
) {
    companion object {
        val EMPTY = ThermalMetrics(
            socTempC = 0f,
            skinTempC = 0f,
            batteryTempC = 0f,
            gpuTempC = 0f,
            ispTempC = 0f,
            npuTempC = 0f,
            cpuZoneTempsC = emptyList(),
            allZoneReadings = emptyList(),
            throttlingLevel = 0,
            throttleWarning = false,
            timestamp = 0L,
            thermalStatus = -1
        )
    }
}

/**
 * Celsius sıcaklıktan MetricLevel üretir:
 *   GOOD : <60°C → kamera/ISP tam hızda, throttle yok
 *   WARN : 60-80°C → bazı cluster'lar throttle olabilir
 *   CRIT : >80°C → mutlak throttle, frame rate düşebilir
 */
fun thermalLevelFromCelsius(tempC: Float): MetricLevel = when {
    tempC < 60f -> MetricLevel.GOOD
    tempC < 80f -> MetricLevel.WARN
    else -> MetricLevel.CRIT
}


