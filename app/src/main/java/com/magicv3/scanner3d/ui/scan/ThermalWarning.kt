package com.magicv3.scanner3d.ui.scan

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun ThermalWarningDialog(
    isThrottled: Boolean,
    currentTemp: Float,
    onDismiss: () -> Unit
) {
    if (isThrottled) {
        AlertDialog(
            onDismissRequest = {}, // Force user attention, non-dismissible outside clicks
            confirmButton = {
                TextButton(onClick = onDismiss) {
                    Text("Sıcaklığı Takip Et")
                }
            },
            title = {
                Text("Cihaz Aşırı Isındı", color = Color(0xFFFF5252))
            },
            text = {
                Column {
                    Text(
                        String.format(
                            java.util.Locale.US,
                            "SoC sıcaklığı kritik seviyeye (%.1f°C) ulaştı. NPU donanımını korumak amacıyla tarama duraklatılmıştır.",
                            currentTemp
                        )
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "Lütfen cihazın soğumasını bekleyin. Tarama 80.0°C altında otomatik olarak tekrar aktif olacaktır.",
                        color = Color.Yellow
                    )
                }
            }
        )
    }
}
