package com.magicv3.scanner3d.infra.camera

import android.content.Context
import android.os.Build
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.common.util.concurrent.ListenableFuture

/**
 * CameraX ProcessCameraProvider sarmalayıcı — uygulamanın kamera omurgası.
 *
 * Mimari konum: infra/camera/ → HAL'ye (Camera2 API) en yakın uygulama katmanı.
 * Domain/UI'dan ayrılmıştır; UI sadece initialize() / bind / unbind çağırır.
 *
 * ── Donanım-Yazılım Etkileşimi (Honor Magic V3 / SD 8 Gen 3) ───────────
 *
 * 1. ProcessCameraProvider.getInstance(ctx)
 *    → CameraX internal olarak Camera2 Manager'a sorgu atar
 *    → cihazın tüm kamera ID'leri enumerate edilir
 *    → Honor Magic V3: ID 0 (50MP Main), ID 1 (UW), ID 2 (Tele 3.5x)
 *      + ID 3/4 (front foldable inner + cover)
 *    → Bu fazda sadece ID 0 (DEFAULT_BACK_CAMERA) kullanılır
 *    → Multi-camera fusion Phase 2'de
 *
 * 2. bindToLifecycle(lifecycleOwner, selector, preview)
 *    → Camera2 API → CameraDevice.open() (ID 0)
 *    → CaptureSession başlatılır (PREVIEW template)
 *    → ISP pipeline (Spectra 3×18-bit) devreye girer:
 *        LSC → Demosaic → NR → AWB → Tone Map → YUV/RGBA
 *    → Stream target: ~1080p @30fps (preview-quality, ISP otomatik negotiate)
 *
 * 3. Preview.setSurfaceProvider(previewView.surfaceProvider)
 *    → SurfaceTexture yaratılır (GPU-side)
 *    → ISP çıktısı SurfaceTexture'e flow eder
 *    → Adreno 750 GPU texture'ı compose eder → TextureView render
 *    → Compose AndroidView katmanında composite → ekranda canlı görüntü
 *
 * ── Lifecycle Aware ?? ──────────────────────────────────────────────
 * bindToLifecycle sayesinde:
 *   onStop  → CameraX otomatik capture session'ı kapatır (kamera serbest)
 *   onStart → CameraX otomatik yeniden bind eder (session resume)
 *   Manuel unbind() sadece Activity destroy/dispose'da çağrılır.
 *
 * ── Thread Modeli ──────────────────────────────────────────────────
 * • getInstance() → main thread'de çağrılır, ListenableFuture döner
 * • addListener callback → ContextCompat.getMainExecutor(ctx) ile main thread'de çalışır
 * • bindToLifecycle → main thread'de olmak ZORUNDA (UI thread constraint)
 * • SurfaceProvider → CameraX internal thread havuzu kullanır, main thread bloklanmaz
 *
 * @param context Activity context (getSystemService için Activity gerekmez,
 *                 ApplicationContext yeterli — DisplayManager'a erişir)
 * @param lifecycleOwner Compose LocalLifecycleOwner → ComponentActivity
 */
class CameraController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) {
    companion object {
        private const val TAG = "CameraController"
    }

    private var cameraProviderFuture: ListenableFuture<ProcessCameraProvider>? = null
    private var previewUseCase: Preview? = null
    private var boundCamera: Camera? = null

    // ── Public API ───────────────────────────────────────────────────

    /**
     * CameraProvider'ı asenkron başlatır.
     *
     * Camera2 initialization cihaza göre 100-300ms sürebilir (Honor Magic V3 ortalama ~150ms).
     * Bu çağrı blocking değildir — ListenableFuture pattern ile thread-safe.
     *
     * Çağrı sırası: initialize() → bindPreview(previewView)
     */
    fun initialize() {
        if (cameraProviderFuture != null) {
            Log.w(TAG, "initialize() already called — ignoring duplicate")
            return
        }
        cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        Log.i(TAG, "CameraProvider initialization requested")
    }

    /**
     * Preview use case'i yaratır ve lifecycle'a bind eder.
     *
     * Bu çağrı initialize()'dan sonra yapılmalıdır. ListenableFuture callback
     * içinde bindToLifecycle çağrılır — asenkron, non-blocking.
     *
     * @param previewView ScanScreen'den gelen, zaten yaratılmış PreviewView
     */
    fun bindPreview(previewView: PreviewView) {
        val provider = cameraProviderFuture ?: run {
            Log.e(TAG, "bindPreview() called before initialize() — abort")
            return
        }

        provider.addListener({
            try {
                val cameraProvider = provider.get()

                // Idempotent: önceki use case'leri temizle (rebind güvenliği)
                cameraProvider.unbindAll()

                // ── Preview Use Case ────────────────────────────────
                // Preview.Builder() ile sadece preview stream'i istiyoruz.
                // ImageAnalysis / ImageCapture Phase 2+ için buraya eklenecek.
                val preview = Preview.Builder()
                    .setTargetRotation(getDisplayRotation())
                    .build()
                    .also { p ->
                        // SurfaceProvider: PreviewView kendi yönetir.
                        // CameraX, SurfaceTexture lifecycle'ını otomatik handle eder.
                        p.setSurfaceProvider(previewView.surfaceProvider)
                    }

                // ── Camera Selector ─────────────────────────────────
                // DEFAULT_BACK_CAMERA → Camera2 ID 0 (50MP Main, f/1.6, OIS)
                // Çünkü leica Summilux lens + SD 8 Gen 3 ISP'in en güçlü lensi.
                // UW (ID 1) ve Tele (ID 2) Phase 2'de concurrent bind edilecek.
                val selector = CameraSelector.DEFAULT_BACK_CAMERA

                // ── Bind to Lifecycle ───────────────────────────────
                // Bu çağrı Camera2 session'ı başlatır → ISP devreye girer.
                boundCamera = cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    selector,
                    preview
                )
                previewUseCase = preview

                Log.i(TAG, "Preview bound — CameraID: ${boundCamera?.cameraInfo}")
            } catch (exc: Exception) {
                // Yaygın hatalar:
                //   - CameraAccessException: kamera başka app tarafından kullanımda
                //   - IllegalStateException: lifecycle destroy oldu
                //   - IllegalArgumentException: selector ile eşleşen kamera yok
                Log.e(TAG, "bindPreview failed — Camera2/ISP error", exc)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * Tüm use case'leri unbind eder ve kamera kaynağını serbest bırakır.
     *
     * Çağrılmalı: ScanScreen DisposableEffect.onDispose → Activity destroy
     * Çağrılmazsa: kamera leak → diğer app'ler kameraya erişemez + batarya drain
     */
    fun unbind() {
        cameraProviderFuture?.let { provider ->
            // Future hala pending olabilir — callback içinde unbindAll yapıyoruz
            // ama future tamamlandıysa direkt de yapabiliriz.
            if (provider.isDone && !provider.isCancelled) {
                try {
                    provider.get().unbindAll()
                } catch (e: Exception) {
                    Log.e(TAG, "unbind: provider.get() failed", e)
                }
            }
        }
        cameraProviderFuture = null
        previewUseCase = null
        boundCamera = null
        Log.i(TAG, "Unbound — camera released")
    }

    // ── Private ──────────────────────────────────────────────────────

    /**
     * Mevcut display rotation'ı döndürür.
     *
     * Honor Magic V3'te:
     *   Book mode (foldable açıldığında): 90°/270° (landscape-derivative)
     *   Cover screen (kapalı): 0° (portrait)
     *   Rotate edildiğinde: Compose recompose → LaunchedEffect re-run → rebind
     *
     * Preview use case'in targetRotation'ını güncellemek:
     *   → Camera2'ye "output buffer'ı bu açıyla rotate et" der
     *   → ESP çıktısı doğru orientation'da TextureView'e ulaşır
     *   → Ekstra GPU rotation pass'inden kaçınılır (performans kazancı)
     */
    @Suppress("DEPRECATION")
    private fun getDisplayRotation(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.display?.rotation ?: Surface.ROTATION_0
        } else {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.defaultDisplay.rotation
        }
    }
}
