package com.magicv3.scanner3d.ui.scan

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
fun ProControlPanel(
    isoValue: Int,
    shutterFraction: Int,
    focusDistanceValue: Float,
    isSettingsLocked: Boolean,
    onIsoChange: (Int) -> Unit,
    onShutterChange: (Int) -> Unit,
    onFocusChange: (Float) -> Unit,
    onLockToggle: () -> Unit,
    // Batch-3: EV + WB kontrolleri ve cihaz yeteneği aralıkları.
    evValue: Float = 0f,
    onEvChange: (Float) -> Unit = {},
    colorTempValue: Int = 5500,
    onColorTempChange: (Int) -> Unit = {},
    isoRange: IntRange = 100..1600,
    evRange: IntRange = -12..12,
    evStep: Float = 1f / 6f,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .width(280.dp)
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
                shape = RoundedCornerShape(16.dp)
            )
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                shape = RoundedCornerShape(16.dp)
            )
            .padding(16.dp)
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "🎛️ PRO KAMERA KONTROLLERİ",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )

            // ISO Slider — aralık cihaz yeteneğinden (isoRange) gelir.
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("ISO", style = MaterialTheme.typography.labelSmall)
                    Text("$isoValue", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
                }
                val isoSteps = ((isoRange.last - isoRange.first) / 100).coerceIn(10, 50)
                Slider(
                    value = isoValue.toFloat().coerceIn(isoRange.first.toFloat(), isoRange.last.toFloat()),
                    onValueChange = { onIsoChange(it.toInt()) },
                    valueRange = isoRange.first.toFloat()..isoRange.last.toFloat(),
                    steps = isoSteps,
                    enabled = !isSettingsLocked,
                    modifier = Modifier.height(24.dp)
                )
            }

            // Enstantane Slider — genişletilmiş presets (30..4000).
            val shutterSteps = listOf(30, 60, 125, 250, 500, 1000, 2000, 4000)
            val activeStepIndex = shutterSteps.indexOf(shutterFraction).coerceAtLeast(0)
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Enstantane", style = MaterialTheme.typography.labelSmall)
                    Text("1/${shutterFraction}s", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
                }
                Slider(
                    value = activeStepIndex.toFloat(),
                    onValueChange = { onShutterChange(shutterSteps[it.toInt().coerceIn(0, shutterSteps.size - 1)]) },
                    valueRange = 0f..(shutterSteps.size - 1).toFloat(),
                    steps = shutterSteps.size - 2,
                    enabled = !isSettingsLocked,
                    modifier = Modifier.height(24.dp)
                )
            }

            // Odak Distance Slider
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Odak (Focus)", style = MaterialTheme.typography.labelSmall)
                    val focusText = if (focusDistanceValue == 0.0f) "Sonsuz (∞)" else String.format(java.util.Locale.US, "%.1f Diopter", focusDistanceValue)
                    Text(focusText, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
                }
                Slider(
                    value = focusDistanceValue,
                    onValueChange = onFocusChange,
                    valueRange = 0.0f..10.0f,
                    enabled = !isSettingsLocked,
                    modifier = Modifier.height(24.dp)
                )
            }

            // EV Slider — AE kompanzasyonu (yalnızca AE AUTO'da uygulanır).
            // Kaydırıcı artık cihazın AE kompanzasyon aralığını EV cinsinden yansıtır (range × step birimi).
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("EV", style = MaterialTheme.typography.labelSmall)
                    Text(
                        text = String.format(java.util.Locale.US, "%+.1f", evValue),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                // Etkin EV sınırları cihazın AE kompanzasyon aralığından (evRange × evStep) türetilir.
                val evMinEv = (evRange.first * evStep).coerceAtLeast(-4.0f)
                val evMaxEv = (evRange.last * evStep).coerceAtMost(4.0f)
                val evSliderSteps = (((evMaxEv - evMinEv) / evStep).roundToInt() - 1).coerceIn(4, 64)
                Slider(
                    value = evValue.coerceIn(evMinEv, evMaxEv),
                    onValueChange = onEvChange,
                    valueRange = evMinEv..evMaxEv,
                    steps = evSliderSteps,
                    enabled = true,
                    modifier = Modifier.height(24.dp)
                )
                if (isSettingsLocked) {
                    Text(
                        text = "Manuel pozlama kilitliyken EV devre dışı",
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }

            // WB Slider — renk sıcaklığı (Kelvin, 2500..8000).
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("WB", style = MaterialTheme.typography.labelSmall)
                    Text("${colorTempValue}K", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
                }
                Slider(
                    value = colorTempValue.toFloat().coerceIn(2500f, 8000f),
                    onValueChange = { onColorTempChange(it.toInt()) },
                    valueRange = 2500f..8000f,
                    steps = 54, // 100K adımları (55 segment)
                    enabled = !isSettingsLocked,
                    modifier = Modifier.height(24.dp)
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Lock Button
            Button(
                onClick = onLockToggle,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isSettingsLocked) Color(0xFFE91E63) else MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(
                    imageVector = if (isSettingsLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isSettingsLocked) "Pozlama/Odak Sabitlendi" else "Ayarları Sabitle (Lock AE/AF)",
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}
