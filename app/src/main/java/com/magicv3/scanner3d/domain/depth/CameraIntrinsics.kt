package com.magicv3.scanner3d.domain.depth

/**
 * Kameranın pinhole intrinsik parametrelerini temsil eden immutable veri modeli.
 *
 * @property fx X ekseni focal length (piksel)
 * @property fy Y ekseni focal length (piksel)
 * @property cx Principal point X (piksel)
 * @property cy Principal point Y (piksel)
 * @property sourceWidth  fx/fy/cx/cy değerlerinin ifade edildiği piksel uzayının
 *                        genişliği; 0 ise "bilinmiyor" (ölçekleme yapılmaz).
 * @property sourceHeight fx/fy/cx/cy değerlerinin ifade edildiği piksel uzayının
 *                        yüksekliği; 0 ise "bilinmiyor" (ölçekleme yapılmaz).
 */
data class CameraIntrinsics(
    val fx: Float,
    val fy: Float,
    val cx: Float,
    val cy: Float,
    val sourceWidth: Int = 0,
    val sourceHeight: Int = 0,
) {
    companion object {
        /**
         * Güvenli varsayılan intrinsikler — kalibrasyon bilinmediğinde kullanılır.
         * Depth modeli 518x518 çıktı ürettiği için köşe yaklaşık (259,259).
         * Gerçek değerler [CameraIntrinsicsProvider] aracılığıyla
         * CameraCharacteristics/ARCore'dan okunur.
         *
         * SAFE_DEFAULT değerleri zaten depth grid'i (518x518) uzayında ifade
         * edildiğinden sourceWidth/sourceHeight = 0'dır (ölçekleme yapılmaz).
         */
        val SAFE_DEFAULT = CameraIntrinsics(
            fx = 500f,
            fy = 500f,
            cx = 259f,
            cy = 259f,
        )
    }
}
