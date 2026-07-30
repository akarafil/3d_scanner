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
