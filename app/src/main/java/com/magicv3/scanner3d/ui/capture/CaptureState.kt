package com.magicv3.scanner3d.ui.capture

/**
 * Capture etkileşim state machine — deklanşör butonu yaşam döngüsü.
 *
 * IDLE      → buton hazır, dokunulabilir (CyberPrimary ring)
 * CAPTURING → işlem yapılıyor, pulsing (HudGood ring), tıklama pasif
 * DONE      → başarılı, kısa yeşil feedback → otomatik IDLE
 * ERROR     → hata, kısa kırmızı feedback → otomatik IDLE
 *
 * Phase 1.8: CAPTURING 1.5sn placeholder simülasyon (gerçek
 *            pipeline Phase 2'de bağlanır).
 */
enum class CaptureState {
    IDLE,
    CAPTURING,
    DONE,
    ERROR
}
