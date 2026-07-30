package com.magicv3.scanner3d.ui.hud

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import com.magicv3.scanner3d.ui.theme.HudCrit
import com.magicv3.scanner3d.ui.theme.HudGood
import com.magicv3.scanner3d.ui.theme.HudWarn

/**
 * Tek metrik için kullanılan HUD satırı — sol etiket, sağ değer,
 * altında ince progress bar.
 *
 * @param label Sol metrik ismi (örn "RAM", "CPU")
 * @param valueText Sağ tarafta gösterilecek string (örn "42%")
 * @param percent 0..100 arası yüzde — bar doluluğu
 * @param level MetricLevel — bar rengini belirler
 * @param barHeight Bar yüksekliği (default 4.dp)
 * @param showBar true → bar görünür, false → sadece text satırı
 */
@Composable
fun HudBar(
    label: String,
    valueText: String,
    percent: Int,
    level: MetricLevel,
    modifier: Modifier = Modifier,
    barHeight: androidx.compose.ui.unit.Dp = 4.dp,
    showBar: Boolean = true
) {
    val color = when (level) {
        MetricLevel.GOOD -> HudGood
        MetricLevel.WARN -> HudWarn
        MetricLevel.CRIT -> HudCrit
    }

    // Bar doluluğu animasyonu — 300ms ease-out
    val animatedPercent by animateFloatAsState(
        targetValue = percent.coerceIn(0, 100).toFloat(),
        animationSpec = tween(durationMillis = 300),
        label = "hud_bar_$label"
    )

    Column(modifier = modifier.fillMaxWidth()) {
        // Etiket + Değer satırı
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
            androidx.compose.foundation.layout.Spacer(
                modifier = Modifier.weight(1f)
            )
            Text(
                text = valueText,
                color = color,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // Bar — sadece showBar=true ise gösterilir
        if (showBar) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(barHeight)
                    .padding(top = 2.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.3f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(animatedPercent / 100f)
                        .height(barHeight)
                        .clip(RoundedCornerShape(2.dp))
                        .background(color)
                )
            }
        }
    }
}
