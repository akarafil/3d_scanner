package com.magicv3.scanner3d.infra.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Depth-Anything-V2 (Small) TFLite inference motoru.
 *
 * Varsayılan üretim kullanımı (AiServiceLocator) yalnızca `context` vererek modeli
 * assets'teki gerçek dosyadan yükler. Birim testleri "model YOKKEN dürüst davranış"ı
 * assets içeriğinden bağımsız garanti etmek için var olmayan bir `modelFileName`
 * geçer — sahte/mock çıktı üretilmez, engine gerçekten boş sonuç verir.
 *
 * @param context       Android context (assets'e erişim için).
 * @param modelFileName Yüklenecek model dosya adı. Varsayılanı [MODEL_NAME].
 */
class DepthInferenceEngine internal constructor(
    private val context: Context,
    private val modelFileName: String = MODEL_NAME,
) {

    companion object {
        private const val TAG = "DepthInferenceEngine"

        // Isim dürüstlüğü: Qualcomm'un sağladığı model Depth-Anything-V2'nin **Small** varyantıdır
        // (24.7M parametre, 518x518 giriş). Sabit, assets'teki gerçek dosya adıyla birebir eşleşir.
        // NOT: ViT-Base varyantı (depth_anything_v2_vitb.tflite) KULLANILMIYOR — eski kod hatalıydı.
        internal const val MODEL_NAME = "depth_anything_v2_small.tflite"
        private const val INPUT_SIZE = 518 // Depth-Anything-V2 Small giriş çözünürlüğü (518x518)

        // Depth-Anything-V2 Small imza beklentileri (NHWC düzeni).
        // init'te gerçek tensörlerle karşılaştırılır; uyumsuzlukta model düşürülmez, uyarı loglanır.
        internal val EXPECTED_INPUT_SHAPE = intArrayOf(1, 518, 518, 3)
        internal val EXPECTED_OUTPUT_SHAPE = intArrayOf(1, 518, 518, 1)
    }

    private var interpreter: Interpreter? = null
    private var nnApiDelegate: NnApiDelegate? = null
    private var gpuDelegate: GpuDelegate? = null
    var isNpuOrGpuAccelerated = false
        private set

    /** Model assets'ten başarıyla yüklendi mi? (dürüst göstergesi — mock/sahte çıktı üretilmez) */
    val isModelLoaded: Boolean
        get() = interpreter != null

    init {
        try {
            val modelBuffer = loadModelFile(context, modelFileName)
            val options = Interpreter.Options().apply {
                try {
                    val delegate = NnApiDelegate()
                    addDelegate(delegate)
                    nnApiDelegate = delegate
                    isNpuOrGpuAccelerated = true
                    Log.i(TAG, "DepthInferenceEngine: NPU (NNAPI / Hexagon) acceleration active.")
                } catch (t: Throwable) {
                    Log.w(TAG, "DepthInferenceEngine: NPU (NNAPI) failed, trying GPU fallback. ${t.message}")
                    try {
                        val delegate = GpuDelegate()
                        addDelegate(delegate)
                        gpuDelegate = delegate
                        isNpuOrGpuAccelerated = true
                        Log.i(TAG, "DepthInferenceEngine: GPU acceleration active.")
                    } catch (t2: Throwable) {
                        Log.w(TAG, "DepthInferenceEngine: GPU failed, fallback to CPU. ${t2.message}")
                    }
                }
                setNumThreads(4)
            }
            interpreter = Interpreter(modelBuffer, options)
            Log.i(TAG, "DepthInferenceEngine: Model $modelFileName loaded successfully.")

            // İmza doğrulama: giriş/çıkış tensör şekilleri Small varyantıyla eşleşiyor mu?
            // Eşleşirse bilgilendirici log; eşleşmezse uyarı (model yine de çalıştırılır ama
            // preprocessing gözden geçirilmelidir). Tensor okuma hatası init'i crash ettirmez.
            interpreter?.let { validateModelSignature(it) }
        } catch (e: Throwable) {
            // Throwable yakalanır: model dosyası yokken IOException (Exception) fırlatır;
            // native TFLite kütüphanesi test JVM'inde yokken UnsatisfiedLinkError (Error)
            // fırlatır. Her iki durumda da dürüst davranış: depth çıktısı yok, crash yok.
            // (YoloInferenceEngine ile aynı desen — bkz. YoloInferenceEngine init.)
            Log.w(TAG, "DepthInferenceEngine: $modelFileName yüklenemedi (assets'te yok veya native TFLite kullanılamıyor) — depth çıktısı üretilmiyor. ${e.message}")
        }
    }

    /**
     * Model giriş/çıkış tensör imzalarını okur ve beklenen Small varyantı şekilleriyle
     * karşılaştırır. init sırasında çağrılır; uyumsuzluk durumunda uyarı loglanır ancak
     * model düşürülmez (inference yine de çalıştırılabilir, preprocessing gözden geçirilmelidir).
     *
     * Bazı sağlayıcılarda getInputTensor/getOutputTensor Exception fırlatabilir; bu durumda
     * crash yerine Log.w ile yutulur ve false döner.
     *
     * @return imzalar beklenen şekillerle birebir uyumluysa true.
     */
    internal fun validateModelSignature(interpreter: Interpreter): Boolean = try {
        val inputShape = interpreter.getInputTensor(0).shape()
        val outputShape = interpreter.getOutputTensor(0).shape()
        val ok = isSignatureExpected(inputShape, outputShape)
        if (ok) {
            Log.i(
                TAG,
                "Depth model imzaları doğrulandı: input=${inputShape.contentToString()}, output=${outputShape.contentToString()}"
            )
        } else {
            Log.w(
                TAG,
                "Depth model imza uyarısı: input=${inputShape.contentToString()}, output=${outputShape.contentToString()} " +
                    "— beklenen input=${EXPECTED_INPUT_SHAPE.contentToString()}, output=${EXPECTED_OUTPUT_SHAPE.contentToString()}. " +
                    "Preprocessing gözden geçirilmelidir."
            )
        }
        ok
    } catch (t: Throwable) {
        Log.w(TAG, "Depth model imza doğrulaması yapılamadı (tensor okuma hatası): ${t.message}")
        false
    }

    /**
     * Verilen giriş/çıkış tensor şekillerinin Depth-Anything-V2 Small imzasına uyup uymadığını
     * söyler. Saf (pure) fonksiyondur — Interpreter/Tensor bağımlılığı yoktur; bu sayede
     * Robolectric birim testlerinde mock/makine gerektirmeden doğrudan test edilebilir.
     *
     * Beklenen imza (NHWC): input=[1, 518, 518, 3], output=[1, 518, 518, 1].
     *
     * @param inputShape  model giriş tensör şekli.
     * @param outputShape model çıkış tensör şekli.
     * @return her iki şekil de beklenenlerle birebir eşleşiyorsa true.
     */
    internal fun isSignatureExpected(inputShape: IntArray, outputShape: IntArray): Boolean =
        inputShape.contentEquals(EXPECTED_INPUT_SHAPE) &&
            outputShape.contentEquals(EXPECTED_OUTPUT_SHAPE)

    fun infer(bitmap: Bitmap): FloatArray {
        val inst = interpreter
        if (inst == null) {
            Log.w(TAG, "Model not loaded — returning empty depth (no fake output).")
            return FloatArray(0)
        }

        return try {
            val scaled = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
            val inputBuffer = convertBitmapToByteBuffer(scaled)
            val outputBuffer = ByteBuffer.allocateDirect(INPUT_SIZE * INPUT_SIZE * 4).apply {
                order(ByteOrder.nativeOrder())
            }

            inst.run(inputBuffer, outputBuffer)

            outputBuffer.rewind()
            val depthMap = FloatArray(INPUT_SIZE * INPUT_SIZE)
            outputBuffer.asFloatBuffer().get(depthMap)
            depthMap
        } catch (e: Exception) {
            Log.e(TAG, "Inference error: ${e.message}", e)
            FloatArray(0)
        }
    }

    fun close() {
        interpreter?.close()
        nnApiDelegate?.close()
        gpuDelegate?.close()
    }

    private fun loadModelFile(context: Context, modelPath: String): ByteBuffer {
        val externalFile = java.io.File(context.filesDir, "models/$modelPath")
        if (externalFile.exists() && externalFile.canRead()) {
            val inputStream = FileInputStream(externalFile)
            val fileChannel = inputStream.channel
            val declaredLength = externalFile.length()
            val buffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, declaredLength)
            inputStream.close()
            return buffer
        }

        val fileDescriptor = context.assets.openFd(modelPath)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        val buffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
        inputStream.close()
        return buffer
    }


    private fun convertBitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val byteBuffer = ByteBuffer.allocateDirect(INPUT_SIZE * INPUT_SIZE * 3 * 4).apply {
            order(ByteOrder.nativeOrder())
        }
        val intValues = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(intValues, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        byteBuffer.rewind()
        for (pixelValue in intValues) {
            val r = (pixelValue shr 16 and 0xFF) / 255.0f
            val g = (pixelValue shr 8 and 0xFF) / 255.0f
            val b = (pixelValue and 0xFF) / 255.0f

            // Normalize values matching standard ImageNet normalization
            byteBuffer.putFloat((r - 0.485f) / 0.229f)
            byteBuffer.putFloat((g - 0.456f) / 0.224f)
            byteBuffer.putFloat((b - 0.406f) / 0.225f)
        }
        return byteBuffer
    }
}
