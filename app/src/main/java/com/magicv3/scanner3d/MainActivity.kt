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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import com.magicv3.scanner3d.domain.model.ScanSession
import com.magicv3.scanner3d.domain.model.ScanStatus
import com.magicv3.scanner3d.infra.permission.CameraPermissionState
import com.magicv3.scanner3d.infra.permission.rememberCameraPermissionState
import com.magicv3.scanner3d.infra.ingestion.IngestionQueue
import com.magicv3.scanner3d.infra.storage.SessionFrameStore
import com.magicv3.scanner3d.infra.storage.ZipExporter
import com.magicv3.scanner3d.ui.scan.HomeScreen
import com.magicv3.scanner3d.ui.scan.MyScansScreen
import com.magicv3.scanner3d.ui.scan.ScanDetailScreen
import com.magicv3.scanner3d.ui.scan.ScanScreen
import com.magicv3.scanner3d.ui.scan.ScanViewModel
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

sealed interface Screen {
    data object Home : Screen
    data object MyScans : Screen
    data class Scan(val session: ScanSession) : Screen
    data class ScanDetail(val session: ScanSession) : Screen
}

/**
 * Uygulama kök Composable — izin state'e göre router.
 */
@Composable
fun MagicScannerApp() {
    val context = LocalContext.current
    val permission = rememberCameraPermissionState(context)
    val store = remember { SessionFrameStore(context) }

    when (permission.state) {
        CameraPermissionState.NOT_REQUESTED -> PermissionRequestScreen(
            onRequest = permission.requestPermission
        )

        CameraPermissionState.GRANTED -> {
            var currentScreen by remember { mutableStateOf<Screen>(Screen.Home) }
            val scope = rememberCoroutineScope()
            val ingestionQueue = remember { IngestionQueue.getInstance(context) }

            when (val scr = currentScreen) {
                is Screen.Home -> HomeScreen(
                    store = store,
                    onStartNewScan = { session -> currentScreen = Screen.Scan(session) },
                    onOpenMyScans = { currentScreen = Screen.MyScans }
                )
                is Screen.MyScans -> MyScansScreen(
                    store = store,
                    onClose = { currentScreen = Screen.Home },
                    onOpen = { session -> currentScreen = Screen.ScanDetail(session) }
                )
                is Screen.Scan -> {
                    val application = LocalContext.current.applicationContext as android.app.Application
                    val scanViewModel = androidx.lifecycle.viewmodel.compose.viewModel<ScanViewModel>(
                        key = scr.session.sessionId.toString(),
                        factory = object : androidx.lifecycle.ViewModelProvider.Factory {
                            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                                @Suppress("UNCHECKED_CAST")
                                return ScanViewModel(
                                    application = application,
                                    sessionFrameStore = store,
                                    activeSession = scr.session
                                ) as T
                            }
                        }
                    )
                    ScanScreen(
                        viewModel = scanViewModel,
                        onBack = { currentScreen = Screen.Home }
                    )
                }
                is Screen.ScanDetail -> {
                    val sessionsList by store.sessions.collectAsStateWithLifecycle()
                    val liveSession = remember(sessionsList, scr.session.sessionId) {
                        sessionsList.firstOrNull { it.sessionId == scr.session.sessionId } ?: scr.session
                    }
                    ScanDetailScreen(
                        session = liveSession,
                        onClose = { currentScreen = Screen.MyScans },
                        onShareZip = { session ->
                            scope.launch {
                                runCatching {
                                    val zipExporter = ZipExporter(context)
                                    val result = zipExporter.export(session)
                                    zipExporter.launchShareSheet(result, session.projectName)
                                }
                            }
                        },
                        onResumeCapture = {
                            currentScreen = Screen.Scan(liveSession)
                        },
                        onStart3DRender = {
                            scope.launch {
                                store.updateStatus(liveSession.sessionId, ScanStatus.RENDERING)
                                ingestionQueue.enqueue(liveSession)
                            }
                        }
                    )
                }
            }
        }

        CameraPermissionState.DENIED -> PermissionDeniedScreen(
            onRetry = permission.requestPermission
        )

        CameraPermissionState.PERMANENTLY_DENIED -> PermissionPermanentlyDeniedScreen()
    }
}

// ── İzin Ekranları ────────────────────────────────────────────────────────

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
