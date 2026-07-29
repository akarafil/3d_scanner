package com.magicv3.scanner3d.ui.scan

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

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
 * Faz 1.2'de (BU ADIM):
 * • CameraPreviewSurface yaratılır → PreviewView referansı callback'e iletilir
 *   (şimdilik no-op — Phase 1.3'te CameraController.initialize() + bindPreview())
 * • Kamera HENÜZ CANLI DEĞİL — siyah/donuk ekran GÖZLENİR, normal davranış
 *
 * Ön koşul: Kamera izni GRANTED (MainActivity router bu Composable'a yalnızca
 * GRANTED durumunda yönlendirir).
 *
 * Lifecycle notu:
 * Bu Composable Composition'a bağlıdır — Activity onStop/onResume'a göre
 * dispose/recompose edilir. CameraController bağlantısı (1.3) LifecycleOwner
 * tabanlı bindToLifecycle ile yönetilecek → otomatik stop/resume.
 */
@Composable
fun ScanScreen() {
    Box(modifier = Modifier.fillMaxSize()) {
        CameraPreviewSurface(
            modifier = Modifier.fillMaxSize(),
            onPreviewViewReady = { previewView ->
                // ── Phase 1.3'te burası şu şekilde doldurulacak ───────────
                // val context = LocalContext.current
                // val lifecycleOwner = LocalLifecycleOwner.current
                // val controller = remember { CameraController(context, lifecycleOwner) }
                // LaunchedEffect(Unit) {
                //     controller.initialize()
                //     controller.bindPreview(previewView)
                // }
                // DisposableEffect(Unit) { onDispose { controller.unbind() } }
                //
                // — Bu fazda: PreviewView yaratildi, host hazır. No-op.
            }
        )

        // [Phase 1.7] — SystemHud overlay (Modifier.align(TopEnd).padding(8.dp))
        // [Phase 1.8] — CaptureButton (Modifier.align(BottomCenter).padding(48.dp))
    }
}
