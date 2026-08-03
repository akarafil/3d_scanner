package com.magicv3.scanner3d.domain.depth

/**
 * Kamera intrinsikleri için soyut sağlayıcı.
 *
 * Amaç: DepthToPointsUseCase'te hardcode edilen hatalı intrinsik
 * değerlerini (fx=500, fy=500, cx=259, cy=259) gerçek donanımdan
 * okunacak şekilde arayüzleştirmek. Kalibrasyon bilinmiyorsa
 * [CameraIntrinsics.SAFE_DEFAULT] kullanılır.
 *
 * Faz 4 (Strateji C) kapsamında ARCore imageIntrinsics ya da
 * CameraCharacteristics tabanlı uygulamalar eklenebilir.
 */
interface CameraIntrinsicsProvider {

    /**
     * Kameranın intrinsiklerini döndürür; okunamıyorsa null.
     */
    fun getIntrinsics(): CameraIntrinsics?
}
