package com.magicv3.scanner3d.ui.scan

import android.view.ViewGroup
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Kamera önizlemesini Compose'a entegre eden UI host katmanı.
 *
 * Bu Composable sadece PreviewView'i yaratır ve callback ile dışarı verir —
 * kamera akışı HENÜZ BAĞLI DEĞİL. ProcessCameraProvider bağlantısı
 * Phase 1.3'te ScanScreen → CameraController.bindPreview(...) çağrısı ile
 * yapılacak.
 *
 * Mimari Karar — implementationMode = COMPATIBLE (TextureView):
 * ── SD 8 Gen 3 / Adreno 750 yorumu ──────────────────────────────────
 * • PERFORMANCE modu SurfaceView kullanır. SurfaceView, Compose UI
 *   hiyerarşisinden AYRI bir Native window'da render edilir →
 *   üstüne bindirilen Compose overlay'ler (HUD, capture butonu)
 *   Surface hole-region dışında "delik" oluşturur, overlay'ler
 *   preview üzerinde düzgün composite olmaz (HUD yan tarafta
 *   "ayrı katmanda" görünür, görsel bütünlük bozulur).
 * • COMPATIBLE modu TextureView kullanır → normal View hiyerarşisinin
 *   parçasıdır. Compose'un GPU pipeline'ı ile aynı surface'da
 *   composite olur → HUD overlay + capture butonu örtüşmesi
 *   pixel-perfect.
 * • Adreno 750 thermal budget çalışırken yeterlidir — TextureView extra kompozisyon
 *   maliyeti (<0.5W) HUD estetiği için tolere edilebilir.
 * • Sonuç: COMPATIBLE modu bu mimari için "stabiliteWorkaround" değil,
 *   doğrudan doğru mimari kararı.
 *
 * ScaleType.FILL_CENTER:
 * Kamera feed'i preview alanına fill edilir, aspect ratio uyumsuzsa
 * kenarlar crop edilir. Tarama tam ekran fullscreen UX için ideal.
 */
@Composable
fun CameraPreviewSurface(
    modifier: Modifier = Modifier,
    onPreviewViewReady: (PreviewView) -> Unit
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            PreviewView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                scaleType = PreviewView.ScaleType.FILL_CENTER
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            }.also { previewView ->
                // Host yaratildi — ScanScreen burada referansını tutup
                // Phase 1.3'te CameraController.bindPreview()'a iletecek.
                onPreviewViewReady(previewView)
            }
        }
    )
}
