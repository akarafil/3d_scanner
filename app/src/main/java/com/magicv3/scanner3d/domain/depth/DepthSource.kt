package com.magicv3.scanner3d.domain.depth

import com.google.ar.core.Frame

/**
 * Faz 4 / Strateji C — Depth kaynağı soyutlaması.
 *
 * Bir kare için elde edilen depth verisini taşır. Depth verisi iki biçimde gelir:
 *  - **Metrik (ARCore)**: [isMetric] = true, [depths] doğrudan metre cinsindendir ve
 *    [metersPerUnit] her zaman `1f`'dir.
 *  - **Normalize (TFLite / Depth Anything)**: [isMetric] = false, [depths] `0..1`
 *    aralığındadır ve [metersPerUnit], normalize değeri metreye çeviren kalibrasyon
 *    faktörüdür ([DepthScaleEstimator.estimateScale] ile üretilir).
 *
 * @property depths Depth haritası (satır-major, `width * height` boyutunda).
 * @property width Depth haritası genişliği (piksel).
 * @property height Depth haritası yüksekliği (piksel).
 * @property isMetric `true` = metre cinsinden (ARCore), `false` = normalize (TFLite).
 * @property metersPerUnit Normalize depth için metreye çevirme ölçeği; metrik depth için `1f`.
 * @property intrinsics Bu kareye ait intrinsikler (varsa); null ise çağıran
 *                      [CameraIntrinsicsProvider] / [CameraIntrinsics.SAFE_DEFAULT] kullanır.
 * @property sourceName Kaynak adı: `"arcore"` veya `"tflite"` (stat/teşhis için).
 * @property sourceWidth Depth/intrinsik referans uzayının kaynak genişliği;
 *                       intrinsikler başka çözünürlükte ifade ediliyorsa (örn.
 *                       RGB kamera ya da full-sensor) bu alan [DepthToPointsUseCase]
 *                       içindeki ölçeklemede fallback olarak kullanılır. 0 = bilinmiyor.
 * @property sourceHeight Kaynak yüksekliği (bkz. [sourceWidth]).
 *
 * B16 notu: [depths] bir `FloatArray` olduğundan data class üretilen `equals`/`hashCode`
 * dizi üzerinde **referans** eşitliği kullanır (içerik eşitliği değil). DepthMap'i harita
 * anahtarı veya eşitlik karşılaştırması için kullanmayın — yalnızca DTO olarak taşıyın.
 */
data class DepthMap(
    val depths: FloatArray,
    val width: Int,
    val height: Int,
    val isMetric: Boolean,          // true = metre cinsinden (ARCore), false = normalize (TFLite)
    val metersPerUnit: Float,       // normalize depth için scale; metrik depth için 1f
    val intrinsics: CameraIntrinsics?,
    val sourceName: String,         // "arcore" | "tflite"
    val sourceWidth: Int = 0,       // intrinsik referans uzayının genişliği (0 = bilinmiyor)
    val sourceHeight: Int = 0,      // intrinsik referans uzayının yüksekliği (0 = bilinmiyor)
) {
    /**
     * B16: Devasa depth dizisini (örn. 518x518 = 268k float) toString içine
     * dökmeyelim — yalnızca tanımlayıcı alanlar özetlenir (log/teşhis dostu).
     */
    override fun toString(): String =
        "DepthMap(source=$sourceName, ${width}x$height, isMetric=$isMetric, " +
            "metersPerUnit=$metersPerUnit, depths=${depths.size}, " +
            "intrinsics=${intrinsics?.let { "cx=${it.cx},cy=${it.cy},fx=${it.fx},fy=${it.fy}" } ?: "null"})"
}

/**
 * Faz 4 / Strateji C — Kare bazında depth üreten soyut kaynak.
 *
 * Strateji C: ana kamera için ARCore Depth API (metrik, poz ile hizalı) önceliklidir;
 * aux/tele akışı ve ARCore depth hazır olmadığı durumlarda TFLite (Depth Anything)
 * yalnızca referans olarak kullanılır ([TfliteDepthSource]).
 *
 * Implementasyonlar:
 *  - [ArCoreDepthSource]: `frame.acquireDepthImage16Bits()` → metrik (metre) DepthMap.
 *  - [TfliteDepthSource]: `frame.acquireCameraImage()` → Bitmap → Depth Anything → normalize DepthMap.
 *
 * Kaynaklar, [acquireDepth] bu karede veri üretemiyorsa `null` döner; çağıran
 * sıradaki kaynağa düşer (fallback zinciri).
 *
 * Mimari not (CTO kararı): Arayüz `com.google.ar.core.Frame`'e doğrudan bağımlıdır —
 * bu, ARCore Depth API'nin ve camera image akışının birlikte kullanıldığı mevcut
 * hibrit mimarinin pragmatik kabulüdür. İleri fazda aux/tele akışı için Frame
 * bağımlılığını soyutlayacak geniş bir refactor yapılmayacaktır (bkz. [TfliteDepthSource]
 * yorumu); bu not mimari olarak belgelenir.
 */
interface DepthSource {

    /**
     * Verilen ARCore karesi için depth haritasını üretir.
     *
     * @param frame ARCore `Session.update()` sonucu gelen kare.
     * @return [DepthMap] (metrik veya normalize) ya da bu karede depth hazır değilse `null`.
     */
    fun acquireDepth(frame: Frame): DepthMap?
}
