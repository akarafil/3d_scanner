package com.magicv3.scanner3d.ui.scan

import android.net.Uri
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

// [Phase 2.6] ZIP Export State
sealed interface ZipShareState {
    data object Idle : ZipShareState
    data class Zipping(val total: Int) : ZipShareState
    data class Done(val uri: Uri, val displaySize: String) : ZipShareState
    data class Failed(val message: String) : ZipShareState
}

@Composable
fun ZipShareDialog(
    zipShareState: ZipShareState,
    onDismiss: () -> Unit
) {
    when (zipShareState) {
        is ZipShareState.Zipping -> AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text("ZIP Hazırlanıyor") },
            text = {
                Column {
                    Text("${zipShareState.total} kare paketleniyor…")
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        )
        is ZipShareState.Failed -> AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(onClick = onDismiss) {
                    Text("Tamam")
                }
            },
            title = { Text("Paylaşım başarısız") },
            text = { Text(zipShareState.message) }
        )
        else -> {}
    }
}
