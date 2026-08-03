package com.magicv3.scanner3d.infra.depth

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.util.Log
import com.google.ar.core.Frame
import com.magicv3.scanner3d.domain.depth.DepthMap
import com.magicv3.scanner3d.domain.depth.DepthScaleEstimator
import com.magicv3.scanner3d.domain.depth.DepthSource
import com.magicv3.scanner3d.infra.ai.DepthInferenceEngine
import com.magicv3.scanner3d.infra.ai.YoloInferenceEngine
import java.io.ByteArrayOutputStream

/**
 * Faz 4 / Strateji C — TFLite (Depth Anything) tabanlı normalize depth kaynağı.
 *
 * Strateji C'de aux/tele lens akışı ve ARCore depth hazır olmadığında **yalnızca
 * referans** olarak kullanılır. DepthInferenceEngine normalize `0..1` depth üretir;
 * bu kaynak [DepthMap.isMetric] = false ile sarar ve [DepthMap.metersPerUnit]'i
 * [DepthScaleEstimator] aracılığıyla kalibre eder.
 *
 * YUV→RGB Bitmap zinciri de burada toplanır (O-3): `imageToNv21` → JPEG → decode →
 * `rotateBitmap`. ViewModel bu zinciri doğrudan çağırmaz; [bitmapFromImage] üzerinden
 * tek seferde üretilen rotated bitmap hem [depthFromBitmap] hem de RGB renk örneklemesi
 * için (DepthToPointsUseCase.execute) paylaşılır.
 *
 * @param depthEngine       AiServiceLocator'dan gelen Depth Anything engine; model assets'te
 *                          yoksa dürüst boş sonuç döner (sahte depth üretilmez).
 * @param yoloEngine        AiServiceLocator'dan gelen YOLO engine — ileri fazda ortak YUV
 *                          pipeline / hibrit kaynak için rezerve edilmiştir.
 * @param depthScaleEstimator Normalize depth'i metreye çeviren kalibrasyon faktörü.
 */
class TfliteDepthSource(
    private val depthEngine: DepthInferenceEngine,
    val yoloEngine: YoloInferenceEngine,
    private val depthScaleEstimator: DepthScaleEstimator = DefaultDepthScaleEstimator(),
) : DepthSource {

    /** O-3: YUV→NV21 dönüşümünde her karede büyük ara tamponları yeniden allocate etmeyiz. */
    private var reusableNv21: ByteArray? = null
    private val reusableJpegOut = ByteArrayOutputStream()

    /** Depth engine'in donanım hızlandırması (GPU/NPU) var mı? Stat göstergesi için. */
    val isAccelerated: Boolean
        get() = depthEngine.isNpuOrGpuAccelerated

    /**
     * ARCore karesinden kameranın rotated (portre) RGB bitmap'ini üretir.
     *
     * Zincir: `YUV_420_888` → NV21 → JPEG → `BitmapFactory.decode` → rotate.
     * Image'in SAHİBİ çağırandır: bu yöntem Image'i KAPATMAZ. Tek sahip kapatır —
     * [acquireDepth] kendi `acquireCameraImage` çıktısını kapatır, ScanViewModel
     * ise onFrameAvailable finally bloğunda kapatır. NV21 kopyası tamamlandıktan
     * sonra Image hâlâ açık kalır (çağıranın sorumluluğu).
     *
     * @param rotationDegrees Döndürme açısı (derece). Varsayılan 90° portre arka kamera
     *                        için doğrudur; çağıran (ViewModel) ekran (display) rotasyonuna
     *                        duyarlı bir değer geçebilir — telefon yatay tutulduğunda
     *                        depth/YOLO görüntüsünün kaymasını (kayıklık) önler.
     * @return rotated bitmap; image alınamazsa veya decode başarısızsa null.
     */
    fun bitmapFromImage(image: Image, rotationDegrees: Float = ROTATE_DEGREES): Bitmap? {
        val width = image.width
        val height = image.height
        val nv21Bytes = imageToNv21(image, reusableNv21)
        if (reusableNv21 !== nv21Bytes) reusableNv21 = nv21Bytes

        val jpegBytes = compressToJpeg(nv21Bytes, width, height, reusableJpegOut)
        val bitmap = decodeBitmap(jpegBytes) ?: return null
        return rotateBitmap(bitmap, rotationDegrees)
    }

    /**
     * [DepthSource] arayüzü — kareden depth üretir.
     *
     * `frame.acquireCameraImage()` alır, [bitmapFromImage] ile rotated Bitmap üretir
     * ve [depthFromBitmap] ile normalize depth'e çevirir. Model assets'te yoksa engine
     * dürüst boş sonuç döner ve [depthFromBitmap] null üretir (sahte depth yok).
     */
    override fun acquireDepth(frame: Frame): DepthMap? {
        val cameraImage = runCatching { frame.acquireCameraImage() }.getOrNull() ?: return null
        try {
            val rotatedBitmap = bitmapFromImage(cameraImage) ?: return null
            return depthFromBitmap(rotatedBitmap)
        } finally {
            // Tek sahip burada: bitmapFromImage kapatmaz, bu Image'i bu yöntem kapatır.
            runCatching { cameraImage.close() }
        }
    }

    /**
     * Halihazır üretilmiş rotated bitmap'ten normalize depth haritası üretir.
     *
     * ViewModel DEPTH modunda [acquireDepth]'ı doğrudan çağırmak yerine bu yöntemi
     * kullanır (bitmap zaten [bitmapFromImage] ile üretildiğinden ikinci kez
     * `acquireCameraImage` çekilmez — her karede tek Image alımı korunur).
     *
     * @return [DepthMap]; model assets'te yoksa (engine boş sonuç dönerse) **null** —
     *         sahte/mock depth üretilmez, çağıran kullanıcıya net mesaj gösterir.
     */
    fun depthFromBitmap(bitmap: Bitmap): DepthMap? {
        // Dürüst davranış (F2): inference engine hata fırlatsa bile (model yok,
        // donanım hatası, TFLite native hata vb.) UI'a fırlatmaz — null döner;
        // sahte/mock depth üretilmez ve çağıran (ScanViewModel) kullanıcıya net
        // mesaj gösterir. ARCore RET_CHECK riski altında bu kaynak güvenilir tek
        // depth kaynağı olduğundan crash hiçbir yoldan kabul edilmez.
        val depths = runCatching { depthEngine.infer(bitmap) }.getOrNull()
        if (depths == null || depths.isEmpty()) {
            // Model yokken veya inference hatasında dürüst davranış: null döndür, sahte depth üretme.
            Log.w(TAG, "depthFromBitmap: depth engine boş/hatalı sonuç (model yüklü değil veya inference hatası) — null.")
            return null
        }
        val metersPerUnit = depthScaleEstimator.estimateScale().takeIf { it > 0f }
            ?: DefaultDepthScaleEstimator.DEFAULT_METERS_FALLBACK
        return DepthMap(
            depths = depths,
            width = DEPTH_WIDTH,
            height = DEPTH_HEIGHT,
            isMetric = false,
            metersPerUnit = metersPerUnit,
            // Strateji C / B1: TFLite kendi intrinsik sağlamaz (null); usecase
            // intrinsikleri provider'dan (CameraCharacteristics, full-sensor) okur
            // ve 518x518 depth grid'ine ölçekler. Kaynak çözünürlük alanları depth
            // boyutuna set edilir (teşhis/izlenebilirlik için).
            intrinsics = null,
            sourceName = SOURCE_NAME_TFLITE,
            sourceWidth = DEPTH_WIDTH,
            sourceHeight = DEPTH_HEIGHT,
        )
    }

    /**
     * Normalize depth haritasını görselleştirme amaçlı renkli heatmap'e çevirir.
     * (ScanViewModel'in eski `depthToColormap` yardımcısı buraya taşındı.)
     */
    fun depthToColormap(depths: FloatArray, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        for (i in depths.indices) {
            val d = depths[i].coerceIn(0f, 1f)
            val r = (d * 255).toInt().coerceIn(0, 255)
            val g = ((1f - kotlin.math.abs(d - 0.5f) * 2f) * 255).toInt().coerceIn(0, 255)
            val b = ((1f - d) * 255).toInt().coerceIn(0, 255)
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    /**
     * O-3: YUV_420_888 plane'lerini NV21 ByteArray'e kopyalar.
     *
     * @param target Yeniden kullanılabilir buffer; yeterli boyuttaysa kullanılır,
     *               aksi halde yeni buffer allocate edilir. Yalnızca tek AI coroutine
     *               aktif olduğundan (isAiProcessing guard) reuse thread-safe'dir.
     */
    internal fun imageToNv21(image: Image, target: ByteArray?): ByteArray {
        val width = image.width
        val height = image.height
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val needed = width * height * 3 / 2
        val nv21 = if (target != null && target.size >= needed) target else ByteArray(needed)

        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        var pos = 0

        // Copy Y channel
        for (row in 0 until height) {
            yBuffer.position(row * yRowStride)
            if (yPixelStride == 1) {
                yBuffer.get(nv21, pos, width)
                pos += width
            } else {
                for (col in 0 until width) {
                    nv21[pos++] = yBuffer.get(row * yRowStride + col * yPixelStride)
                }
            }
        }

        // Copy UV channel (interleaved V then U).
        // B6: U ve V plane'lerinin rowStride/pixelStride'i ayrı ayrı kullanılır;
        // bounds check U için uBuffer.capacity(), V için vBuffer.capacity().
        val uRowStride = uPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vRowStride = vPlane.rowStride
        val vPixelStride = vPlane.pixelStride
        var uvOffset = width * height
        for (row in 0 until height / 2) {
            for (col in 0 until width / 2) {
                val vIdx = row * vRowStride + col * vPixelStride
                val uIdx = row * uRowStride + col * uPixelStride
                val vVal = if (vIdx < vBuffer.capacity()) vBuffer.get(vIdx) else 0.toByte()
                val uVal = if (uIdx < uBuffer.capacity()) uBuffer.get(uIdx) else 0.toByte()
                nv21[uvOffset++] = vVal
                nv21[uvOffset++] = uVal
            }
        }
        return nv21
    }

    /** NV21 ByteArray'i JPEG'e sıkıştırır ve byte dizisi olarak döndürür. */
    internal fun compressToJpeg(
        nv21: ByteArray,
        width: Int,
        height: Int,
        out: ByteArrayOutputStream,
    ): ByteArray {
        out.reset()
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        yuvImage.compressToJpeg(Rect(0, 0, width, height), JPEG_QUALITY, out)
        return out.toByteArray()
    }

    /** JPEG byte dizisini Bitmap'e decode eder. */
    internal fun decodeBitmap(jpegBytes: ByteArray): Bitmap? =
        BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)

    /**
     * Bitmap'i verilen açı kadar döndürür (portre görünüm için +90°).
     *
     * B8: JPEG decode sonrası gelen kaynak bitmap artık kullanılmaz; rotate
     * sonrası hemen recycle edilir — tepe bellek ~yarıya iner. Dikkat: bitmapFromImage
     * yalnızca decode edilen source bitmap'i recycle eder; çağıranın kendi
     * bitmap'ine (ör. depthToColormap çıktısı) dokunulmaz.
     */
    internal fun rotateBitmap(source: Bitmap, angle: Float): Bitmap {
        val matrix = Matrix().apply { postRotate(angle) }
        val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        // Kaynak (decode edilen) bitmap artık kullanılmıyor — tepe belleği düşür.
        if (rotated !== source) {
            source.recycle()
        }
        return rotated
    }

    companion object {
        private const val TAG = "TfliteDepthSource"

        /** Depth Anything model çıktı boyutu (DepthInferenceEngine.INPUT_SIZE ile eşleşir). */
        const val DEPTH_WIDTH = 518
        const val DEPTH_HEIGHT = 518

        /** Kamera dikey (portre) görünümü için uygulanan rotate açısı. */
        private const val ROTATE_DEGREES = 90f

        /** JPEG sıkıştırma kalitesi (bellek/CPU dengesi). */
        private const val JPEG_QUALITY = 80

        /** DepthMap.sourceName — teşhis/stat için. */
        const val SOURCE_NAME_TFLITE = "tflite"
    }
}
