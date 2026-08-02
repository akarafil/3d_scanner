package com.magicv3.scanner3d.ui.scan

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.magicv3.scanner3d.infra.ingestion.IngestionState

@Composable
fun IngestionDialogs(
    ingestionState: IngestionState,
    onDismiss: () -> Unit
) {
    when (ingestionState) {
        is IngestionState.Queued -> AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text("Kuyruğa Alındı") },
            text = { Text("Proje kuyruğa alındı, işleme bekleniyor...") }
        )
        is IngestionState.Validating -> AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text("EXIF Doğrulanıyor") },
            text = {
                Column {
                    Text("Kare bütünlüğü kontrol ediliyor...")
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        )
        is IngestionState.Packaging -> AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text("MNP Paketi Hazırlanıyor") },
            text = {
                Column {
                    Text("${ingestionState.progress} / ${ingestionState.total} kare paketleniyor...")
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { if (ingestionState.total > 0) ingestionState.progress.toFloat() / ingestionState.total else 0f },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        )
        is IngestionState.Transferring -> AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text("AlgorDroid'e İletiliyor") },
            text = {
                Column {
                    Text("M3SP Paketi transfer ediliyor...")
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        )
        is IngestionState.Delivered -> AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(onClick = onDismiss) {
                    Text("Harika")
                }
            },
            title = { Text("Başarıyla Teslim Edildi") },
            text = { Text("MNP paketi AlgorDroid motoruna başarıyla iletildi.") }
        )
        is IngestionState.Failed -> AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(onClick = onDismiss) {
                    Text("Kapat")
                }
            },
            title = { Text("Hata") },
            text = { Text(ingestionState.reason) }
        )
        else -> {}
    }
}
