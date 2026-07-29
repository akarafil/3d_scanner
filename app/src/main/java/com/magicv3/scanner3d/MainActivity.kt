package com.magicv3.scanner3d

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.magicv3.scanner3d.infra.permission.CameraPermissionState
import com.magicv3.scanner3d.infra.permission.rememberCameraPermissionState
import com.magicv3.scanner3d.ui.theme.MagicScannerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MagicScannerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MagicScannerApp()
                }
            }
        }
    }
}

/**
 * Uygulama kök Composable — izin state'e göre router.
 *
 * 4 durum cover (CameraPermissionState):
 *   NOT_REQUESTED       → Onboarding + "Kamera İzni Ver" butonu
 *   GRANTED             → Kamera hazır placeholder (Phase 1.2'de ScanScreen olur)
 *   DENIED              → "Tekrar Dene" ekranı
 *   PERMANENTLY_DENIED  → Ayarlara yönlendirme ekranı
 */
@Composable
fun MagicScannerApp() {
    val context = LocalContext.current
    val permission = rememberCameraPermissionState(context)

    when (permission.state) {
        CameraPermissionState.NOT_REQUESTED -> PermissionRequestScreen(
            onRequest = permission.requestPermission
        )

        CameraPermissionState.GRANTED -> CameraReadyPlaceholderScreen()

        CameraPermissionState.DENIED -> PermissionDeniedScreen(
            onRetry = permission.requestPermission
        )

        CameraPermissionState.PERMANENTLY_DENIED -> PermissionPermanentlyDeniedScreen()
    }
}

// ── Ekranlar ───────────────────────────────────────────────────────────

@Composable
private fun PermissionRequestScreen(onRequest: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                "Magic 3D Scanner",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "3B taramaya başlamak için kamera erişimi gereklidir.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onRequest) { Text("Kamera İzni Ver") }
        }
    }
}

@Composable
private fun CameraReadyPlaceholderScreen() {
    // Phase 1.2'de buraya ScanScreen gelecek — şimdilik landmark.
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            "Kamera hazır —\nÖnizleme Phase 1.2'de eklenecek",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}

@Composable
private fun PermissionDeniedScreen(onRetry: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                "Kamera İzni Reddedildi",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "3B tarama kamera olmadan çalışamaz. Lütfen izni verin.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onRetry) { Text("Tekrar Dene") }
        }
    }
}

@Composable
private fun PermissionPermanentlyDeniedScreen() {
    val context = LocalContext.current
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                "Kalıcı Olarak Reddedildi",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Sistem artık izin dialog'unu göstermiyor.\n" +
                    "Ayarlar → Uygulama → Magic 3D Scanner → İzinler yolundan " +
                    "kamera iznini elle verin.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(24.dp))
            OutlinedButton(onClick = {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                }
                context.startActivity(intent)
            }) { Text("Ayarları Aç") }
        }
    }
}
