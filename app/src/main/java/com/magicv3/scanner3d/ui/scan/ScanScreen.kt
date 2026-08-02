package com.magicv3.scanner3d.ui.scan

import android.net.Uri
import android.util.Log
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.magicv3.scanner3d.domain.model.ScanSession
import com.magicv3.scanner3d.domain.model.ScanStatus
import com.magicv3.scanner3d.domain.ar.CameraPose
import com.magicv3.scanner3d.ui.scan.ArPointCloudSurfaceView
import androidx.compose.ui.viewinterop.AndroidView
import com.magicv3.scanner3d.infra.camera.CameraController
import com.magicv3.scanner3d.infra.camera.CameraLensCatalog
import kotlinx.coroutines.isActive
import com.magicv3.scanner3d.infra.camera.AuxProbe
import com.magicv3.scanner3d.infra.camera.RawAuxCaptureSession
import com.magicv3.scanner3d.infra.camera.MultiLensCaptureOrchestrator
import com.magicv3.scanner3d.infra.storage.SessionFrameStore
import com.magicv3.scanner3d.infra.storage.ZipExporter
import com.magicv3.scanner3d.infra.ingestion.IngestionQueue
import com.magicv3.scanner3d.infra.ingestion.IngestionState
import com.magicv3.scanner3d.ui.capture.CaptureButton
import com.magicv3.scanner3d.ui.capture.CaptureState
import com.magicv3.scanner3d.ui.hud.SystemHud
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

// [Phase 2.6] ZIP Export State
sealed interface ZipShareState {
    data object Idle : ZipShareState
    data class Zipping(val total: Int) : ZipShareState
    data class Done(val uri: Uri, val displaySize: String) : ZipShareState
    data class Failed(val message: String) : ZipShareState
}

/**
 * Ana tarama ekranı — kamera preview'ın host edildiği kök layout.
 */
@Composable
fun ScanScreen(
    activeSession: ScanSession,
    sessionFrameStore: SessionFrameStore,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraController = remember { CameraController(context, lifecycleOwner) }

    var previewView by remember { mutableStateOf<PreviewView?>(null) }

    var captureState by remember { mutableStateOf(CaptureState.IDLE) }
    val captureScope = rememberCoroutineScope()
    var lastCaptureLog by remember { mutableStateOf<String?>(null) }
    var triggerCounter by remember { mutableStateOf(0) }

    var multiLensMode by remember { mutableStateOf(false) }

    var showMyScans by remember { mutableStateOf(false) }
    
    // [Phase 2.4] openedSession state for ScanDetailScreen overlay
    var openedSession by remember { mutableStateOf<ScanSession?>(null) }

    // [Phase 2.6] Zip sharing status and exporter
    var zipShareState by remember { mutableStateOf<ZipShareState>(ZipShareState.Idle) }
    val zipExporter = remember { ZipExporter(context) }

    // [Phase 3.3] Ingestion queue and state
    val ingestionQueue = remember { IngestionQueue.getInstance(context) }
    val ingestionState by ingestionQueue.queueState.collectAsStateWithLifecycle()

    val orchestrator = remember { MultiLensCaptureOrchestrator(context, sessionFrameStore) }
    val progressState by orchestrator.progress.collectAsStateWithLifecycle()

    var latestCameraPose by remember { mutableStateOf<CameraPose?>(null) }
    val arSurfaceView = remember {
        ArPointCloudSurfaceView(context) { pose ->
            latestCameraPose = pose
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            arSurfaceView.onDestroy()
        }
    }

    LaunchedEffect(activeSession) {
        orchestrator.bindSession(activeSession)
    }

    // ===== Faz 2.0 + 2.0.5 — Geçici catalog dump + aux probe =====
    LaunchedEffect(Unit) {
        runCatching {
            CameraLensCatalog(context).enumerateLenses()
            AuxProbe(context).probe()
        }.onFailure {
            Log.e("ScanScreen", "Camera enumeration/probe failed", it)
        }
    }
    // ===== Geçici dump sonu (Faz 2.4'te kaldırılacak) =====

    fun triggerZipShare(session: ScanSession) {
        captureScope.launch {
            zipShareState = ZipShareState.Zipping(session.frameCount)
            runCatching {
                zipExporter.export(session)
            }.onSuccess { result ->
                zipShareState = ZipShareState.Done(result.uri, String.format(java.util.Locale.US, "%.1f MB", result.sizeBytes / 1_000_000.0))
                zipExporter.launchShareSheet(result, session.projectName)
                delay(2000)
                zipShareState = ZipShareState.Idle
            }.onFailure { e ->
                Log.e("ScanScreen", "ZIP export failed", e)
                zipShareState = ZipShareState.Failed(e.message ?: "Bilinmeyen hata")
                delay(2500)
                zipShareState = ZipShareState.Idle
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        CameraPreviewSurface(
            modifier = Modifier.fillMaxSize(),
            onPreviewViewReady = { pv ->
                previewView = pv
            }
        )

        AndroidView(
            factory = { arSurfaceView },
            modifier = Modifier.fillMaxSize()
        )

        SystemHud(
            context = context,
            modifier = Modifier
                .padding(8.dp)
                .align(Alignment.TopStart)
        )

        // [Phase 2.1.2] — Cyber-styled Toggle Mode Selector (TopCenter)
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 24.dp)
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                    shape = RoundedCornerShape(20.dp)
                )
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(20.dp)
                )
                .clickable { multiLensMode = !multiLensMode }
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            color = if (multiLensMode) Color.Cyan else Color.Green,
                            shape = CircleShape
                        )
                )
                Text(
                    text = if (multiLensMode) "MODE: MULTI-LENS (TELE + UW)" else "MODE: BURST ×3 (TELE)",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }

        // [Faz 1] — Geri Dönüş Butonu (TopEnd)
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 24.dp, end = 24.dp)
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                    shape = CircleShape
                )
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                    shape = CircleShape
                )
        ) {
            Icon(
                imageVector = Icons.Default.ArrowBack,
                contentDescription = "Geri",
                tint = MaterialTheme.colorScheme.primary
            )
        }

        // [Phase 1.8] — CaptureButton (BottomCenter, thumb ergonomi)
        CaptureButton(
            state = captureState,
            onClick = {
                if (captureState != CaptureState.IDLE) return@CaptureButton

                triggerCounter++
                captureState = CaptureState.CAPTURING
                lastCaptureLog = if (multiLensMode) "Multi-lens capture starting…" else "Tele burst ×3 starting…"
                Log.i("ScanScreen", "Capture triggered (Phase 2.1.2 — Mode: ${if (multiLensMode) "multi-lens" else "burst"})")

                captureScope.launch {
                    arSurfaceView.onPause()
                    val pose = latestCameraPose
                    val trans = pose?.translation
                    val rot = pose?.rotationQuaternion

                    val filesOrMap: Any = if (multiLensMode) {
                        orchestrator.captureMultiLens(
                            lensIds = listOf(
                                RawAuxCaptureSession.AUX_TELEPHOTO_ID,
                                RawAuxCaptureSession.AUX_ULTRAWIDE_ID
                            ),
                            translation = trans,
                            rotation = rot
                        )
                    } else {
                        orchestrator.captureBurst(
                            lensId = RawAuxCaptureSession.AUX_TELEPHOTO_ID,
                            count = 3,
                            translation = trans,
                            rotation = rot
                        )
                    }
                    arSurfaceView.onResume()

                    val fileCount: Int = if (multiLensMode) {
                        @Suppress("UNCHECKED_CAST")
                        val map = filesOrMap as Map<String, File>
                        map.forEach { (k, v) ->
                            val savedFrame = orchestrator.activeSession?.frames?.lastOrNull { it.lensId == k }
                            val frameSize = savedFrame?.bytes ?: 0L
                            Log.d("ScanScreen", "MultiLens[$k] frame saved: ${savedFrame?.file?.name} ($frameSize B)")
                        }
                        map.size
                    } else {
                        @Suppress("UNCHECKED_CAST")
                        val files = filesOrMap as List<File>
                        files.forEachIndexed { idx, f ->
                            val savedFrame = orchestrator.activeSession?.frames?.getOrNull(idx)
                            val frameSize = savedFrame?.bytes ?: 0L
                            Log.d("ScanScreen", "Burst[$idx] frame saved: ${savedFrame?.file?.name} ($frameSize B)")
                        }
                        files.size
                    }

                    captureState = if (fileCount > 0) CaptureState.DONE else CaptureState.ERROR
                    lastCaptureLog = when {
                        multiLensMode && fileCount == 2 -> "✅ Tele + UW frames saved (multi-lens OK) — EXIF stamped"
                        multiLensMode && fileCount == 1 -> "⚠ Only one lens captured (EXIF partial)"
                        !multiLensMode && fileCount == 3 -> "✅ 3/3 Tele frames saved — EXIF stamped"
                        !multiLensMode && fileCount > 0 -> "⚠ ${fileCount}/3 Tele frames saved — EXIF stamped"
                        else -> "❌ No frames captured"
                    }

                    delay(if (captureState == CaptureState.DONE) 600 else 1500)
                    captureState = CaptureState.IDLE
                }
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp)
        )

        when (val p = progressState) {
            is MultiLensCaptureOrchestrator.CaptureProgress.FrameStarted ->
                LinearProgressIndicator(
                    progress = { (p.index + 1f) / p.total },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 124.dp)
                        .fillMaxWidth(0.6f)
                )
            is MultiLensCaptureOrchestrator.CaptureProgress.FrameSuccess ->
                LinearProgressIndicator(
                    progress = { (p.index + 1f) / p.total },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 124.dp)
                        .fillMaxWidth(0.6f)
                )
            is MultiLensCaptureOrchestrator.CaptureProgress.FrameFailure ->
                LinearProgressIndicator(
                    progress = { (p.index + 1f) / p.total },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 124.dp)
                        .fillMaxWidth(0.6f)
                )
            else -> {}
        }

        lastCaptureLog?.let { log ->
            Text(
                text = log,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 132.dp)
            )
        }
    }

    // [Phase 2.3] — Show/Hide "Taramalarım" Screen Dialog
    if (showMyScans) {
        MyScansScreen(
            store = sessionFrameStore,
            onClose = { showMyScans = false },
            onOpen = { session ->
                showMyScans = false
                openedSession = session
            }
        )
    }

    // [Phase 2.4] — Detail Screen Overlay
    openedSession?.let { session ->
        ScanDetailScreen(
            session = session,
            onClose = { openedSession = null },
            onShareZip = { s -> triggerZipShare(s) },
            onResumeCapture = { openedSession = null },
            onStart3DRender = {
                captureScope.launch {
                    sessionFrameStore.updateStatus(session.sessionId, ScanStatus.RENDERING)
                    ingestionQueue.enqueue(session)
                    openedSession = null
                }
            }
        )
    }

    // [Phase 2.6] — ZIP Share Progress dialogs
    when (val s = zipShareState) {
        is ZipShareState.Zipping -> AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text("ZIP Hazırlanıyor") },
            text = {
                Column {
                    Text("${s.total} kare paketleniyor…")
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        )
        is ZipShareState.Failed -> AlertDialog(
            onDismissRequest = { zipShareState = ZipShareState.Idle },
            confirmButton = {
                TextButton(onClick = { zipShareState = ZipShareState.Idle }) {
                    Text("Tamam")
                }
            },
            title = { Text("Paylaşım başarısız") },
            text = { Text(s.message) }
        )
        else -> {}
    }

    // [Phase 3.3] — Ingestion Queue Status Dialogs
    when (val s = ingestionState) {
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
                    Text("${s.progress} / ${s.total} kare paketleniyor...")
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { if (s.total > 0) s.progress.toFloat() / s.total else 0f },
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
            onDismissRequest = { ingestionQueue.resetToIdle() },
            confirmButton = {
                TextButton(onClick = { ingestionQueue.resetToIdle() }) {
                    Text("Harika")
                }
            },
            title = { Text("Başarıyla Teslim Edildi") },
            text = { Text("MNP paketi AlgorDroid motoruna başarıyla iletildi.") }
        )
        is IngestionState.Failed -> AlertDialog(
            onDismissRequest = { ingestionQueue.resetToIdle() },
            confirmButton = {
                TextButton(onClick = { ingestionQueue.resetToIdle() }) {
                    Text("Kapat")
                }
            },
            title = { Text("Hata") },
            text = { Text(s.reason) }
        )
        else -> {}
    }

    LaunchedEffect(previewView) {
        val pv = previewView ?: return@LaunchedEffect
        cameraController.initialize()
        cameraController.bindPreview(pv)
    }

    DisposableEffect(Unit) {
        onDispose { cameraController.unbind() }
    }
}
