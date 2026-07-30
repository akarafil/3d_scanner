package com.magicv3.scanner3d.ui.hud

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.magicv3.scanner3d.domain.model.MetricLevel
import com.magicv3.scanner3d.domain.model.cpuLevelFromPercent
import com.magicv3.scanner3d.ui.theme.HudCrit
import com.magicv3.scanner3d.ui.theme.HudGood
import com.magicv3.scanner3d.ui.theme.HudWarn

/**
 * Per-core CPU visualization — 8 tane ince dikey bar.
 *
 * SD 8 Gen 3 topolojisi:
 *   bar0,bar1 → Cortex-A520 (Efficiency)
 *   bar2..bar5 → Cortex-A720 (Performance)
 *   bar6 → Cortex-A720 aux veya A520 (cihaza göre)
 *   bar7 → Cortex-X4 Prime
 *
 * @param perCoreUsagePercents Her çekirdek için yüzde (0..100)
 *   Liste boyutu 8 beklenir ama dinamiktir
 */
@Composable
fun CoreBars(
    perCoreUsagePercents: List<Int>,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Per-core (${perCoreUsagePercents.size})",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            fontSize = 9.sp,
            fontWeight = FontWeight.Normal
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
                .padding(top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            perCoreUsagePercents.forEach { percent ->
                CoreBar(percent = percent)
            }
            // Eğer 8'den az core varsa, kalan slotları boş bırak
            for (i in 0 until (8 - perCoreUsagePercents.size)) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(1.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.15f))
                )
            }
        }
    }
}

@Composable
private fun RowScope.CoreBar(percent: Int) {
    val animatedPercent by animateFloatAsState(
        targetValue = percent.coerceIn(0, 100).toFloat(),
        animationSpec = tween(150),
        label = "core_bar"
    )

    val color = when (cpuLevelFromPercent(percent)) {
        MetricLevel.GOOD -> HudGood
        MetricLevel.WARN -> HudWarn
        MetricLevel.CRIT -> HudCrit
    }

    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .clip(RoundedCornerShape(1.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.2f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight(animatedPercent / 100f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(1.dp))
                .background(color)
        )
    }
}
