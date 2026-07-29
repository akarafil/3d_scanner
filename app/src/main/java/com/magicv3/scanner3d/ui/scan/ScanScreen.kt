package com.magicv3.scanner3d.ui.scan

import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.magicv3.scanner3d.infra.camera.CameraController

/**
 * Ana tarama ekranı — kamera preview'ın host edildiği kök layout.
 *
 * Layout planı (full Phase 1 sonrası):
 * ┌──────────────────────────────────────┐
 * │                                     ▒│ ← [1.7] SystemHud — TopEnd (RAM/CPU/SoC)
 * │                                     ▒│
 * │           KAMERA ÖNİZLEME             │ ← CameraPreviewSurface
 * │           (TextureView, 1.3'te canlı) │
 * │                                      │
 * │                                      │
 * │                  (◯)                 │ ← [1.8] CaptureButton — BottomCenter
 * └──────────────────────────────────────┘
 *
 * Faz 1.3 (BU ADIM):
 * • CameraPreviewSurface PreviewView yaratır → onPreviewViewReady ile referans gelir
 * • LaunchedEffect(previewView) → CameraController.initialize() + bindPreview()
 * • Camera2 → Spectra ISP → Adreno 750 → TextureView → canlı görüntü
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

    Box(modifier = Modifier.fillMaxSize()) {
        CameraPreviewSurface(
            modifier = Modifier.fillMaxSize(),
            onPreviewViewReady = { pv ->
                // AndroidView factory yaratıldığında çağrılır (composition'da).
                // Sadece referansı saklıyoruz — side-effect composition'da değil.
                previewView = pv
            }
        )

        // [Phase 1.7] — SystemHud overlay (Modifier.align(TopEnd).padding(8.dp))
        // [Phase 1.8] — CaptureButton (Modifier.align(BottomCenter).padding(48.dp))
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
