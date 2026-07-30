package com.magicv3.scanner3d.infra.camera

/**
 * Magic V3'ün kamera modülündeki sensör tiplerini temsil eder.
 * Sınıflandırma, fiziksel sensörlerin odak uzaklığı oranına (main'e göre)
 * dayanır — Honor Magic V3 spesifikasyonuna göre:
 *
 *  - MAIN       : 50MP, 23mm equiv (raw ~6.5mm), f/1.6 — sensör 1/1.3"
 *  - ULTRAWIDE  : 12MP, 16mm equiv (raw ~2.4mm), f/2.2 — sensör 1/2.x"
 *  - TELEPHOTO  : 12MP, 70mm equiv (~3x optical), f/1.9 — foldablePeriscope
 *                veya folded telephoto prime
 *  - PERISCOPE  : 10MP, 100mm+ equiv (~5x+ optical), f/2.x
 *  - SELFIE     : ön kamera logical (id=1)
 *  - UNKNOWN    : sınıflandırma algoritması güvensiz kaldıysa
 */
enum class CameraLensType {
    MAIN,
    ULTRAWIDE,
    TELEPHOTO,
    PERISCOPE,
    SELFIE,
    UNKNOWN
}
