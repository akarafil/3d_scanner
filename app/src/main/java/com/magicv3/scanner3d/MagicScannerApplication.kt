package com.magicv3.scanner3d

import android.app.Application
import com.magicv3.scanner3d.infra.ai.AiServiceLocator

class MagicScannerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Phase 0: Base setup
        // H-3: AI motorları app-scope singleton container'ı başlat.
        // Engine'ler burada kurulmaz; lazy olarak ilk erişimde (arka planda) yüklenir.
        AiServiceLocator.initialize(this)
    }

    override fun onTerminate() {
        // B18: App sonlandırmasında native AI engine kaynaklarını serbest bırak.
        // Not: Android gerçek cihazlarda onTerminate her zaman tetiklenmez (process
        // doğrudan öldürülür); bu best-effort temizliktir — emulator/test teardown
        // ve diğer kapatma yolları için güvenlidir (AiServiceLocator.release lazy guard'lıdır).
        super.onTerminate()
        AiServiceLocator.release()
    }
}
