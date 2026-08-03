package com.magicv3.scanner3d.infra.ai

import android.content.Context

/**
 * H-3: App-scope AI motor container.
 *
 * DepthInferenceEngine / YoloInferenceEngine ağır TFLite modelleri barındırır
 * (bir kez yüklenir, onlarca MB native heap). Her ScanViewModel'in kendi engine
 * kurması (ScanViewModel key=sessionId ile her oturumda yeniden kurulur) bellek
 * büyümesine yol açardı; bu container engine'leri tek instance olarak tutar.
 *
 * Model yükleme [lazy] ile yapılır: ilk erişimde (AI preview açıldığında,
 * ScanViewModel.onFrameAvailable → Dispatchers.Default) arka planda kurulur —
 * main thread'de jank oluşturmaz. Assets'te model yokken engine'ler dürüst
 * boş/no-op çıktı üretir; SAHTE/mock AI çıktısı üretilmez.
 *
 * Kullanım (mevcut manual DI anlayışı — IngestionQueue.getInstance gibi):
 *   AiServiceLocator.depthEngine / AiServiceLocator.yoloEngine
 */
object AiServiceLocator {

    private var appContext: Context? = null

    // B18: Lazy delegeler ayrı tutulur — release() içinde engine'in gerçekten
    // initialize edilip edilmediği isInitialized() ile kontrol edilir (hiç
    // erişilmediyse model yükleyip hemen kapatmak anlamsızdır).
    private val depthEngineLazy: Lazy<DepthInferenceEngine> = lazy {
        DepthInferenceEngine(requireAppContext())
    }

    private val yoloEngineLazy: Lazy<YoloInferenceEngine> = lazy {
        YoloInferenceEngine(requireAppContext())
    }

    /** Depth engine singleton — ilk erişimde arka planda (lazy) yüklenir. */
    val depthEngine: DepthInferenceEngine by depthEngineLazy

    /** YOLOv8 engine singleton — ilk erişimde arka planda (lazy) yüklenir. */
    val yoloEngine: YoloInferenceEngine by yoloEngineLazy

    /**
     * [MagicScannerApplication.onCreate] tarafından çağrılmalıdır.
     * Engine'ler burada kurulmaz; yalnızca app context tutulur.
     */
    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * B18: App sonlandırmasında native engine kaynaklarını serbest bırakır.
     *
     * Yalnızca daha önce initialize edilmiş engine'ler kapatılır (lazy guard) —
     * hiç kullanılmamış engine için model yüklenip kapatılmaz. MagicScannerApplication
     * tarafından best-effort olarak çağrılır; Android'de process ölümünde onTerminate
     * her cihazda tetiklenmez ancak emulator/test teardown ve manuel çağrılar için güvenlidir.
     */
    fun release() {
        if (depthEngineLazy.isInitialized()) {
            runCatching { depthEngine.close() }
        }
        if (yoloEngineLazy.isInitialized()) {
            runCatching { yoloEngine.close() }
        }
        appContext = null
    }

    private fun requireAppContext(): Context =
        checkNotNull(appContext) {
            "AiServiceLocator.initialize(context) Application.onCreate içinde çağrılmalıdır."
        }
}
