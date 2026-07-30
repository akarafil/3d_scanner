package com.magicv3.scanner3d.ui.hud

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.magicv3.scanner3d.domain.model.cpuLevelFromPercent
import com.magicv3.scanner3d.domain.model.ramLevelFromPercent
import com.magicv3.scanner3d.domain.model.thermalLevelFromCelsius
import com.magicv3.scanner3d.domain.model.CpuMetrics
import com.magicv3.scanner3d.domain.model.RamMetrics
import com.magicv3.scanner3d.domain.model.ThermalMetrics
import com.magicv3.scanner3d.domain.model.formatBytes
import com.magicv3.scanner3d.infra.system.SystemMonitor

/**
 * Kamera preview üstünde gösterilen sistem HUD overlay.
 *
 * Üç metrik bloğu dikey olarak istiflenir:
 *   1. RAM     (1 sn polling)
 *   2. CPU     (1 sn polling, 8-core sparkline dahil)
 *   3. Thermal (2 sn polling, SoC/GPU/ISP/Battery/Skin)
 *
 * Lifecycle-aware: collectAsStateWithLifecycle ile uygulama
 * arka plana atılınca polling otomatik durur (batarya koruması).
 *
 * @param context ApplicationContext türevi — SystemMonitor oluşturmak için
 * @param modifier Dış kutu modifier'ı — caller pozisyon/sizing ayarlar
 */
@Composable
fun SystemHud(
    context: Context,
    modifier: Modifier = Modifier
) {
    // SystemMonitor singleton gibi davranır — composable scope'ta hatırla
    val systemMonitor = remember(context) {
        SystemMonitor(context.applicationContext)
    }

    // Üç metrik flow'larını lifecycle-aware topla
    val ram: RamMetrics by systemMonitor.monitorRam(intervalMs = 1000)
        .collectAsStateWithLifecycle(initialValue = RamMetrics.EMPTY)
    val cpu: CpuMetrics by systemMonitor.monitorCpu(intervalMs = 1000)
        .collectAsStateWithLifecycle(initialValue = CpuMetrics.EMPTY)
    val thermal: ThermalMetrics by systemMonitor.monitorThermal(intervalMs = 2000)
        .collectAsStateWithLifecycle(initialValue = ThermalMetrics.EMPTY)

    // HUD Container — yarı saydam arkaplan, rounded
    Surface(
        modifier = modifier.width(240.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
        tonalElevation = 4.dp
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Başlık
            Text(
                text = "MagicScanner HUD",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )

            // _____________ RAM BÖLÜMÜ _____________
            RamSection(ram)

            // _____________ CPU BÖLÜMÜ _____________
            CpuSection(cpu)

            // _____________ THERMAL BÖLÜMÜ _____________
            ThermalSection(thermal)
        }
    }
}

// ════════════════════════════════════════════════════════════════
// RAM BÖLÜMÜ
// ════════════════════════════════════════════════════════════════

@Composable
private fun RamSection(ram: RamMetrics) {
    val swapPercent = if (ram.swapTotalBytes > 0) ram.swapUsedPercent else 0
    val swapValueText = if (ram.swapTotalBytes > 0) {
        "${formatBytes(ram.swapUsedBytes)} / ${formatBytes(ram.swapTotalBytes)}"
    } else "N/A"

    HudBar(
        label = "RAM",
        valueText = "${formatBytes(ram.usedBytes)} / ${formatBytes(ram.totalBytes)}  ${ram.usedPercent}%",
        percent = ram.usedPercent,
        level = ramLevelFromPercent(ram.usedPercent)
    )
    HudBar(
        label = if (ram.swapTotalBytes > 0) "Swap" else "Swap (Honor RAM Turbo)",
        valueText = "$swapValueText  $swapPercent%",
        percent = swapPercent,
        level = ramLevelFromPercent(swapPercent)
    )
}

// ════════════════════════════════════════════════════════════════
// CPU BÖLÜMÜ
// ════════════════════════════════════════════════════════════════

@Composable
private fun CpuSection(cpu: CpuMetrics) {
    HudBar(
        label = "CPU Total",
        valueText = "${cpu.totalUsagePercent}%   app ${cpu.appUsagePercent}%",
        percent = cpu.totalUsagePercent,
        level = cpuLevelFromPercent(cpu.totalUsagePercent)
    )
    CoreBars(
        perCoreUsagePercents = cpu.perCoreUsagePercents
    )
}

// ════════════════════════════════════════════════════════════════
// THERMAL BÖLÜMÜ
// ════════════════════════════════════════════════════════════════

@Composable
private fun ThermalSection(thermal: ThermalMetrics) {
    // SoC barı — ana termal göstergesi
    HudBar(
        label = "SoC Temp",
        valueText = "%.1f°C".format(thermal.socTempC),
        percent = ((thermal.socTempC / 100f) * 100).toInt().coerceIn(0, 100),
        level = thermalLevelFromCelsius(thermal.socTempC)
    )

    // Ek termal değerler — text satırı halinde
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        HudBar(
            label = "GPU",
            valueText = "%.1f°C".format(thermal.gpuTempC),
            percent = ((thermal.gpuTempC / 100f) * 100).toInt().coerceIn(0, 100),
            level = thermalLevelFromCelsius(thermal.gpuTempC),
            showBar = false
        )
        HudBar(
            label = "ISP",
            valueText = "%.1f°C".format(thermal.ispTempC),
            percent = ((thermal.ispTempC / 100f) * 100).toInt().coerceIn(0, 100),
            level = thermalLevelFromCelsius(thermal.ispTempC),
            showBar = false
        )
        HudBar(
            label = "Skin",
            valueText = "%.1f°C".format(thermal.skinTempC),
            percent = ((thermal.skinTempC / 60f) * 100).toInt().coerceIn(0, 100),
            level = thermalLevelFromCelsius(thermal.skinTempC),
            showBar = false
        )
        HudBar(
            label = "Battery",
            valueText = "%.1f°C".format(thermal.batteryTempC),
            percent = ((thermal.batteryTempC / 60f) * 100).toInt().coerceIn(0, 100),
            level = thermalLevelFromCelsius(thermal.batteryTempC),
            showBar = false
        )
    }

    // Throttle durumu — son uyarı satırı
    val throttleColor = when (thermal.throttlingLevel) {
        0 -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        1 -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.error
    }
    val throttleText = when (thermal.throttlingLevel) {
        0 -> if (thermal.throttleWarning) "⚠ Throttle near" else "Throttle: L0 (clean)"
        1 -> "⚠ Throttle: L1 (light)"
        else -> "⛔ Throttle: L2 (heavy)"
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        Text(
            text = throttleText,
            color = throttleColor,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
