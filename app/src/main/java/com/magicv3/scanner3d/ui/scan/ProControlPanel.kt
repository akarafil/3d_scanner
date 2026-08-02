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

            // ISO Slider
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("ISO", style = MaterialTheme.typography.labelSmall)
                    Text("$isoValue", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
                }
                Slider(
                    value = isoValue.toFloat(),
                    onValueChange = { onIsoChange(it.toInt()) },
                    valueRange = 100f..1600f,
                    steps = 14,
                    enabled = !isSettingsLocked,
                    modifier = Modifier.height(24.dp)
                )
            }

            // Enstantane Slider
            val shutterSteps = listOf(125, 250, 500, 1000)
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
                    onValueChange = { onShutterChange(shutterSteps[it.toInt().coerceIn(0, 3)]) },
                    valueRange = 0f..3f,
                    steps = 2,
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
