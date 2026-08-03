package com.magicv3.scanner3d.domain.depth

/**
 * Faz 4 / Strateji C — Depth → metre (metric) kalibrasyon arayüzü.
 *
 * Farklı depth kaynakları farklı ölçekler üretir:
 *  - DepthAnything (TFLite) normalize 0..1 depth üretir → metreye çevirmek için
 *    bir ölçek faktörü gerekir.
 *  - ARCore Depth API doğrudan metrik (metre) depth üretir.
 *
 * Bu arayüz dönüşümü soyutlar; [DefaultDepthScaleEstimator] kalibrasyon
 * faktörünü yönetir. Faz 4'te depth kaynağına göre farklı uygulamalar
 * (örn. ARCoreDepthScaleEstimator) eklenebilir.
 */
interface DepthScaleEstimator {

    /**
     * **metersPerUnit** — normalize depth'i metreye çeviren faktör. Metrik kaynaklar 1f kullanır.
     *
     * Normalize (0..1) depth üreten kaynaklar (Depth Anything / TFLite) için bu değer,
     * `depth * estimateScale()` ifadesini metreye çevirir. Örnek: DepthAnything için
     * 2.5f → depth 0.0..1.0 → 0..2.5 metre.
     *
     * Metrik depth üreten kaynaklar (ARCore Depth API) doğrudan metre döndürdüğünden
     * bu değeri uygulamaz; [DepthMap.metersPerUnit] onlar için `1f` olur.
     *
     * Geçersiz (<=0) değer dönerse çağıran güvenli fallback (2.5f) kullanır.
     */
    fun estimateScale(): Float

    companion object {
        /**
         * Kalibrasyon faktörü geçersiz (<=0) olduğunda kullanılan güvenli fallback
         * (metre). Domain katmanındaki ortak sabit — [DefaultDepthScaleEstimator],
         * [DepthToPointsUseCase] ve [TfliteDepthSource] bu sabiti referanslar.
         */
        const val DEFAULT_METERS_FALLBACK = 2.5f
    }
}
