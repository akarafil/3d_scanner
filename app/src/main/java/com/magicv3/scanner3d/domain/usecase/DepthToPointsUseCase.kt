package com.magicv3.scanner3d.domain.usecase

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import com.magicv3.scanner3d.domain.ar.CameraPose
import com.magicv3.scanner3d.domain.depth.CameraIntrinsics
import com.magicv3.scanner3d.domain.depth.CameraIntrinsicsProvider
import com.magicv3.scanner3d.domain.depth.DepthMap
import com.magicv3.scanner3d.domain.depth.DepthScaleEstimator

data class Point3D(
    val x: Float,
    val y: Float,
    val z: Float,
    val r: Int,
    val g: Int,
    val b: Int
)

/**
 * Depth haritasını kamera intrinsikleri + pose ile 3D dünya noktalarına geri yansıtır.
 *
 * Faz 4 / Strateji C:
 *  - Depth→metre ölçeği [depthScaleEstimator] arayüzünden gelir (sabit 2.5m hardcode yerine).
 *  - Intrinsikler [intrinsicsProvider] aracılığıyla gerçek donanımdan okunur; hardcode
 *    (fx=500, fy=500, cx=259, cy=259) değerleri yalnızca son çare
 *    [CameraIntrinsics.SAFE_DEFAULT] olarak kalır.
 *  - Yeni [execute] overload'u [DepthMap] kabul eder: hem **metrik** (ARCore, metre) hem
 *    **normalize** (TFLite, `0..1 × metersPerUnit`) depth'i aynı projeksiyon mantığıyla
 *    işler. RGB bitmap ölçekleme + quaternion rotate + translate + renk okuması ortaktır.
 *  - B1: Intrinsikler depth grid çözünürlüğüne normalize edilir
 *    (`fx_scaled = fx * depthWidth / sourceWidth`) — intrinsikler başka piksel uzayında
 *    ifade edilse bile (ARCore RGB kamera / CameraCharacteristics full-sensor) projeksiyon
 *    depth haritasıyla hizalı çalışır. Kaynak çözünürlük bilinmiyorsa (0) ölçekleme yapılmaz.
 *
 * @param depthScaleEstimator Normalize depth'i metreye çeviren kalibrasyon faktörü.
 * @param intrinsicsProvider  CameraCharacteristics/ARCore tabanlı intrinsik kaynağı.
 */
class DepthToPointsUseCase(
    private val depthScaleEstimator: DepthScaleEstimator,
    private val intrinsicsProvider: CameraIntrinsicsProvider? = null,
) {

    companion object {
        private const val TAG = "DepthToPointsUseCase"
        private const val STRIDE = 3 // Downsampling to keep memory and CPU low

        // Strateji C — metrik depth filtre sınırları (metre).
        // ARCore depth'te 50mm altı gürültü kabul edilir; 20m üstü güvenilmez/uzak sayılır.
        private const val METRIC_MIN_METERS = 0.05f
        private const val METRIC_MAX_METERS = 20f

        // Normalize depth filtre sınırları ([0,1] aralığı).
        private const val NORMALIZED_MIN = 0.01f
        private const val NORMALIZED_MAX = 0.99f
    }

    /**
     * Back-projects 2D depth map to 3D world points using camera pose and RGB colors.
     *
     * Bu overload normalize depth ([0,1]) kabul eder; ölçek [depthScaleEstimator]'dan
     * gelir. Mevcut imza korunur (geriye dönük uyum / birim testler).
     *
     * @param intrinsics Açıkça verilen intrinsikler; null ise [intrinsicsProvider]'dan
     *                   okunur; o da null dönerse [CameraIntrinsics.SAFE_DEFAULT] kullanılır.
     */
    fun execute(
        depths: FloatArray,
        width: Int,
        height: Int,
        pose: CameraPose?,
        rgbBitmap: Bitmap,
        intrinsics: CameraIntrinsics? = null,
    ): List<Point3D> {
        val metersScale = depthScaleEstimator.estimateScale().takeIf { it > 0f }
            ?: DepthScaleEstimator.DEFAULT_METERS_FALLBACK
        val resolvedIntrinsics = intrinsics
            ?: intrinsicsProvider?.getIntrinsics()
            ?: CameraIntrinsics.SAFE_DEFAULT

        return backProject(
            depths = depths,
            width = width,
            height = height,
            pose = pose,
            rgbBitmap = rgbBitmap,
            resolvedIntrinsics = resolvedIntrinsics,
            isMetric = false,
            metersPerUnit = metersScale,
            // Bu legacy overload depthMap taşımaz → kaynak çözünürlüğü bilinmiyor;
            // intrinsics kendi sourceWidth/sourceHeight bilgisini taşıyorsa ölçeklenir.
            depthSourceWidth = 0,
            depthSourceHeight = 0,
        )
    }

    /**
     * Faz 4 / Strateji C — [DepthMap] tabanlı overload.
     *
     * Hem metrik (ARCore) hem normalize (TFLite) depth'i destekler:
     *  - **Metrik**: `depth < 0.05m` (50mm altı gürültü) ve `depth > 20m` atlanır;
     *    `zCamera = depth` (metre, scale uygulanmaz).
     *  - **Normalize**: `depth <= 0.01 || depth >= 0.99` atlanır;
     *    `zCamera = depth * metersPerUnit`.
     *
     * Intrinsikler: `depthMap.intrinsics ?: intrinsicsProvider ?: SAFE_DEFAULT`.
     *
     * @param depthMap  Metrik veya normalize depth haritası.
     * @param pose      Kamera pozu (null = identity, çeviri/rotasyon yok).
     * @param rgbBitmap Depth çözünürlüğüne ölçeklenerek renk örneklenen kaynak bitmap.
     */
    fun execute(
        depthMap: DepthMap,
        pose: CameraPose?,
        rgbBitmap: Bitmap,
    ): List<Point3D> {
        val resolvedIntrinsics = depthMap.intrinsics
            ?: intrinsicsProvider?.getIntrinsics()
            ?: CameraIntrinsics.SAFE_DEFAULT

        // Metrik kaynakta metersPerUnit her zaman 1f'dir; normalize kaynakta kalibre edilir.
        val metersPerUnit = if (depthMap.isMetric) {
            1f
        } else {
            depthMap.metersPerUnit.takeIf { it > 0f } ?: DepthScaleEstimator.DEFAULT_METERS_FALLBACK
        }

        return backProject(
            depths = depthMap.depths,
            width = depthMap.width,
            height = depthMap.height,
            pose = pose,
            rgbBitmap = rgbBitmap,
            resolvedIntrinsics = resolvedIntrinsics,
            isMetric = depthMap.isMetric,
            metersPerUnit = metersPerUnit,
            // DepthMap üzerindeki source çözünürlüğü, intrinsics kendi kaynak
            // uzayını taşımıyorsa fallback olarak kullanılır.
            depthSourceWidth = depthMap.sourceWidth,
            depthSourceHeight = depthMap.sourceHeight,
        )
    }

    /**
     * Ortak 3D geri yansıtma mantığı.
     *
     * Her iki depth tipi (metrik / normalize) aynı projeksiyon, quaternion rotasyon,
     * çeviri ve RGB renk okumasını paylaşır; yalnızca filtre sınırları ve
     * `zCamera` hesabı tip bazında farklılaşır.
     */
    private fun backProject(
        depths: FloatArray,
        width: Int,
        height: Int,
        pose: CameraPose?,
        rgbBitmap: Bitmap,
        resolvedIntrinsics: CameraIntrinsics,
        isMetric: Boolean,
        metersPerUnit: Float,
        depthSourceWidth: Int,
        depthSourceHeight: Int,
    ): List<Point3D> {
        // B10: depth haritası boyutu grid ile eşleşmiyorsa geri yansıtma anlamsızdır —
        // yanlış intrinsik/projeksiyon üretmek yerine boş liste dön.
        if (depths.size != width * height) {
            Log.w(TAG, "backProject: depths.size=${depths.size} != ${width}x$height")
            return emptyList()
        }

        val points = mutableListOf<Point3D>()
        val scaledRgb = Bitmap.createScaledBitmap(rgbBitmap, width, height, false)

        // İntrinsikler depth grid'ine normalize edilir — fx_scaled = fx * depthW / sourceW.
        //  - sourceWidth/sourceHeight öncelikle intrinsics'in kendi kaynak uzayından gelir
        //    (ör. ARCore imageIntrinsics RGB kamera 640x480, CameraCharacteristics full-sensor
        //    4096x3072). DepthMap.sourceWidth/sourceHeight ise fallback'tir (0 = bilinmiyor).
        //  - Kaynak çözünürlük 0 ise ölçekleme yapılmaz (intrinsikler zaten depth grid'inde).
        val sourceW = resolvedIntrinsics.sourceWidth.takeIf { it > 0 } ?: depthSourceWidth
        val sourceH = resolvedIntrinsics.sourceHeight.takeIf { it > 0 } ?: depthSourceHeight
        val scaleX = if (sourceW > 0) width.toFloat() / sourceW.toFloat() else 1f
        val scaleY = if (sourceH > 0) height.toFloat() / sourceH.toFloat() else 1f
        val fx = resolvedIntrinsics.fx * scaleX
        val fy = resolvedIntrinsics.fy * scaleY
        val cx = resolvedIntrinsics.cx * scaleX
        val cy = resolvedIntrinsics.cy * scaleY

        val translation = pose?.translation ?: floatArrayOf(0f, 0f, 0f)
        val rotation = pose?.rotationQuaternion ?: floatArrayOf(0f, 0f, 0f, 1f)

        for (y in 0 until height step STRIDE) {
            for (x in 0 until width step STRIDE) {
                val idx = y * width + x
                val depth = depths[idx]

                // 1. Gürültü / uç değer filtreleri (tip bazında).
                if (isMetric) {
                    // Metrik: 50mm altı gürültü, 20m üstü güvenilmez.
                    if (depth < METRIC_MIN_METERS || depth > METRIC_MAX_METERS) continue
                } else {
                    // Normalize: [0,1] dışına yakın değerler gürültü.
                    if (depth <= NORMALIZED_MIN || depth >= NORMALIZED_MAX) continue
                }

                // 2. Camera space projection (normalize depth → metre × metersPerUnit;
                //    metrik depth doğrudan metre). Ölçeklenmiş intrinsikler kullanılır.
                val zCamera = if (isMetric) depth else depth * metersPerUnit
                val xCamera = (x - cx) * zCamera / fx
                val yCamera = (y - cy) * zCamera / fy

                // 3. Rotate vector using camera quaternion pose
                val cameraPoint = floatArrayOf(xCamera, yCamera, zCamera)
                val rotatedPoint = rotateVectorByQuaternion(cameraPoint, rotation)

                // 4. Translate to world coordinates
                val xWorld = rotatedPoint[0] + translation[0]
                val yWorld = rotatedPoint[1] + translation[1]
                val zWorld = rotatedPoint[2] + translation[2]

                // 5. Get RGB color from Bitmap
                val color = scaledRgb.getPixel(x, y)
                val r = Color.red(color)
                val g = Color.green(color)
                val b = Color.blue(color)

                points.add(Point3D(xWorld, yWorld, zWorld, r, g, b))
            }
        }

        return points
    }

    /**
     * Quaternion ile vektör döndürür.
     * Testability: birim testler doğrudan çağırabilsin diye internal.
     */
    internal fun rotateVectorByQuaternion(v: FloatArray, q: FloatArray): FloatArray {
        val qx = q[0]
        val qy = q[1]
        val qz = q[2]
        val qw = q[3]

        // Quaternion-Vector cross products
        val ix = qw * v[0] + qy * v[2] - qz * v[1]
        val iy = qw * v[1] + qz * v[0] - qx * v[2]
        val iz = qw * v[2] + qx * v[1] - qy * v[0]
        val iw = -qx * v[0] - qy * v[1] - qz * v[2]

        val rx = ix * qw + iw * -qx + iy * -qz - iz * -qy
        val ry = iy * qw + iw * -qy + iz * -qx - ix * -qz
        val rz = iz * qw + iw * -qz + ix * -qy - iy * -qx

        return floatArrayOf(rx, ry, rz)
    }
}
