package com.magicv3.scanner3d.ui.scan

import android.util.Log
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.magicv3.scanner3d.infra.camera.CameraController
import com.magicv3.scanner3d.infra.camera.CameraLensCatalog
import com.magicv3.scanner3d.infra.camera.AuxProbe
import com.magicv3.scanner3d.infra.camera.RawAuxCaptureSession
import com.magicv3.scanner3d.infra.camera.MultiLensCaptureOrchestrator
import com.magicv3.scanner3d.infra.storage.SessionFrameStore
import com.magicv3.scanner3d.ui.capture.CaptureButton
import com.magicv3.scanner3d.ui.capture.CaptureState
import com.magicv3.scanner3d.ui.hud.SystemHud
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Ana tarama ekranı — kamera preview'ın host edildiği kök layout.
 *
 * Layout planı (full Phase 1 sonrası):
 * ┌──────────────────────────────────────┐
 * │                                     ▒│ ← [1.7] SystemHud — TopStart (RAM/CPU/SoC)
 * │                                     ▒│
 * │           KAMERA ÖNİZLEME             │ ← CameraPreviewSurface
 * │           (TextureView, 1.3'te canlı) │
 * │                                      │
 * │                                      │
 * │                  (◯)                 │ ← [1.8] CaptureButton — BottomCenter
 * └──────────────────────────────────────┘
 *
 * Faz 1.8 (BU ADIM):
 * • CameraPreviewSurface PreviewView yaratır → onPreviewViewReady ile referans gelir
 * • LaunchedEffect(previewView) → CameraController.initialize() + bindPreview()
 * • SystemHud overlay sol üst köşeye yerleştirilir.
 * • CaptureButton deklanşör butonu alt-ortaya yerleştirilir ve state machine tetiklenir.
 * • DisposableEffect → composition dispose'da unbind (Activity destroy)
 *
 * Lifecycle akışı:
 *   Activity onStart → bindToLifecycle (CameraX otomatik)
 *   Activity onStop  → capture session close (CameraX otomatik)
 *   Activity destroy → DisposableEffect.onDispose → unbind()
 *
 * Ön koşul: Kamera izni GRANTED (MainActivity router).
 */
@Composable
fun ScanScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraController = remember { CameraController(context, lifecycleOwner) }

    // PreviewView referansı — CameraPreviewSurface factory'de yaratılır,
    // callback ile buraya iletilir. null → non-null geçişi bind'i tetikler.
    var previewView by remember { mutableStateOf<PreviewView?>(null) }

    // [Phase 1.8] — Capture state + coroutine scope
    var captureState by remember { mutableStateOf(CaptureState.IDLE) }
    val captureScope = rememberCoroutineScope()
    var lastCaptureLog by remember { mutableStateOf<String?>(null) }
    var triggerCounter by remember { mutableStateOf(0) }

    // [Phase 2.1.2] — Toggle Mode: false = Burst (Tele x3), true = Multi-Lens (Tele + UW)
    var multiLensMode by remember { mutableStateOf(false) }

    // [Phase 2.3] — Storage projects and UI folder triggers
    val sessionFrameStore = remember { SessionFrameStore(context) }
    var showMyScans by remember { mutableStateOf(false) }

    val orchestrator = remember { MultiLensCaptureOrchestrator(context, sessionFrameStore) }
    val progressState by orchestrator.progress.collectAsStateWithLifecycle()

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

    Box(modifier = Modifier.fillMaxSize()) {
        CameraPreviewSurface(
            modifier = Modifier.fillMaxSize(),
            onPreviewViewReady = { pv ->
                // AndroidView factory yaratıldığında çağrılır (composition'da).
                // Sadece referansı saklıyoruz — side-effect composition'da değil.
                previewView = pv
            }
        )

        // [Phase 1.7] — SystemHud overlay (Modifier.align(Alignment.TopStart).padding(8.dp))
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
                // Mini LED indicator
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

        // [Phase 2.3] — Taramalarım (My Scans) Folder Button (TopEnd)
        IconButton(
            onClick = { showMyScans = true },
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
                imageVector = Icons.Default.Folder,
                contentDescription = "Taramalarım",
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
                    // Create new session directory & meta file for this scan trigger
                    orchestrator.startNewSession(sessionFrameStore)

                    val filesOrMap: Any = if (multiLensMode) {
                        orchestrator.captureMultiLens(
                            lensIds = listOf(
                                RawAuxCaptureSession.AUX_TELEPHOTO_ID,
                                RawAuxCaptureSession.AUX_ULTRAWIDE_ID
                            )
                        )
                    } else {
                        orchestrator.captureBurst(
                            lensId = RawAuxCaptureSession.AUX_TELEPHOTO_ID,
                            count = 3
                        )
                    }

                    val fileCount: Int = if (multiLensMode) {
                        @Suppress("UNCHECKED_CAST")
                        val map = filesOrMap as Map<String, File>
                        map.forEach { (k, v) ->
                            Log.i("ScanScreen", "MultiLens[$k] → ${v.absolutePath} (${v.length()} B)")
                        }
                        map.size
                    } else {
                        @Suppress("UNCHECKED_CAST")
                        val files = filesOrMap as List<File>
                        files.forEachIndexed { idx, f ->
                            Log.i("ScanScreen", "Burst[$idx] → ${f.absolutePath} (${f.length()} B)")
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

        // Progress bar (Phase 2.1.1 diagnostic, alt-orta)
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
            else -> { /* idle / done: hide bar */ }
        }

        // Optional small status text under the shutter (Phase 2.1.0 diagnostic)
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
            onClose = { showMyScans = false }
        )
    }

    // ── Camera Bind (previewView hazır olunca) ──────────────────────
    // LaunchedEffect key = previewView:
    //   • null → erken return (henüz host yaratılmadı)
    //   • non-null → initialize + bind → camera CANLI
    //   • composition recreate (rotation) → yeni PreviewView → re-bind
    LaunchedEffect(previewView) {
        val pv = previewView ?: return@LaunchedEffect
        cameraController.initialize()
        cameraController.bindPreview(pv)
    }

    // ── Camera Unbind (composition dispose) ─────────────────────────
    // Activity destroy / navigation away → onDispose → kamera serbest.
    // BindToLifecycle onStop'ta session'ı duraklatır ama resource tam
    // serbest olması için unbind() şarttır (diğer app'ler kamera kullanabilsin).
    DisposableEffect(Unit) {
        onDispose { cameraController.unbind() }
    }
}
