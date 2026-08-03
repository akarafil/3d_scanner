package com.magicv3.scanner3d.infra.depth

import com.magicv3.scanner3d.domain.depth.DepthScaleEstimator

/**
 * Faz 4 / Strateji C — Varsayılan depth→metre kalibrasyon uygulaması.
 *
 * DepthAnything normalize (0..1) depth ürettiği için metrik dönüşümde
 * sabit bir ölçek faktörü kullanılır. [calibrationMeters] değeri cihaz /
 * model bazlı kalibrasyon çalışması sonrası güncellenebilir (SharedPreferences
 * veya uzaktan config ile beslenebilir).
 *
 * İleriye dönük genişletme notu: Gerçek kalibrasyon için bu sınıf
 * `estimateScale()`'i döndürmek yerine bilinen bir referans derinliğinden
 * (örn. ARCore'un metrik depth'i ile TFLite normalize depth'inin aynı sahneye
 * oturtulmasıyla) dinamik ölçek hesaplayan bir `ArCoreDepthScaleEstimator` ile
 * değiştirilebilir. Arayüz [DepthScaleEstimator] bu değişimi destekler; çağıranlar
 * [DepthMap.metersPerUnit] üzerinden kaynak tipini değiştirmeden çalışır.
 *
 * @property calibrationMeters Normalize depth'in 1.0 değerine karşılık gelen metre.
 */
class DefaultDepthScaleEstimator(
    private val calibrationMeters: Float = DEFAULT_METERS_FALLBACK,
) : DepthScaleEstimator {

    override fun estimateScale(): Float = calibrationMeters

    companion object {
        /**
         * Varsayılan max menzil: 2.5 metre (güvenli varsayılan).
         * B12: Ortak sabit domain katmanındaki [DepthScaleEstimator] companion'ında
         * tanımlıdır; bu değer DepthToPointsUseCase ve TfliteDepthSource tarafından
         * da referanslanır (tek kaynak).
         */
        const val DEFAULT_METERS_FALLBACK =
            com.magicv3.scanner3d.domain.depth.DepthScaleEstimator.DEFAULT_METERS_FALLBACK
    }
}
