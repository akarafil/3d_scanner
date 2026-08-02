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

@Composable
fun PlyExportDialog(
    exportState: PlyExportState,
    onDismiss: () -> Unit
) {
    when (exportState) {
        is PlyExportState.Exporting -> AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text("3D PLY Dışa Aktarılıyor") },
            text = {
                Column {
                    Text("Nokta bulutu dosyası oluşturuluyor…")
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        )
        is PlyExportState.Failed -> AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(onClick = onDismiss) {
                    Text("Tamam")
                }
            },
            title = { Text("Dışa aktarım başarısız") },
            text = { Text(exportState.error) }
        )
        else -> {}
    }
}
