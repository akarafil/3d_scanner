package com.magicv3.scanner3d.ui.scan

import android.util.Log
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import com.magicv3.scanner3d.infra.camera.CameraController
import com.magicv3.scanner3d.infra.camera.CameraLensCatalog
import com.magicv3.scanner3d.infra.camera.AuxProbe
import com.magicv3.scanner3d.infra.camera.RawAuxCaptureSession
import com.magicv3.scanner3d.ui.capture.CaptureButton
import com.magicv3.scanner3d.ui.capture.CaptureState
import com.magicv3.scanner3d.ui.hud.SystemHud
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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

        // [Phase 1.8] — CaptureButton (BottomCenter, thumb ergonomi)
        CaptureButton(
            state = captureState,
            onClick = {
                if (captureState != CaptureState.IDLE) return@CaptureButton

                triggerCounter++
                captureState = CaptureState.CAPTURING
                lastCaptureLog = "Tele (id=4) capture triggered…"
                Log.i("ScanScreen", "Capture triggered (Phase 2.1.0 — Tele bypass)")

                captureScope.launch {
                    val result = RawAuxCaptureSession(context).captureSingleFrame()
                    result
                        .onSuccess { file ->
                            captureState = CaptureState.DONE
                            lastCaptureLog = "✅ Tele frame saved: ${file.name} (${file.length()} B)"
                            Log.i("ScanScreen", "Tele JPEG: ${file.absolutePath}")
                        }
                        .onFailure { err ->
                            captureState = CaptureState.ERROR
                            lastCaptureLog = "❌ Tele capture failed: ${err.message}"
                            Log.e("ScanScreen", "Tele capture failed", err)
                        }

                    // Auto-return to IDLE for next capture
                    delay(if (captureState == CaptureState.DONE) 400 else 1500)
                    captureState = CaptureState.IDLE
                }
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp)
        )

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
