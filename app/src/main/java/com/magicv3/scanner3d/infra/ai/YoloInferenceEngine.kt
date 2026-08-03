package com.magicv3.scanner3d.infra.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

class YoloInferenceEngine(private val context: Context) {

    companion object {
        private const val TAG = "YoloInferenceEngine"
        private const val MODEL_NAME = "yolov8s.tflite"
        private const val INPUT_SIZE = 640
        private const val CONFIDENCE_THRESHOLD = 0.35f
        private const val NMS_THRESHOLD = 0.45f
    }

    /**
     * Model çıktı formatı. Qualcomm YOLOv8 3-ayrı çıktı (boxes, scores, class_idx) üretirken,
     * legacy YOLOv8 modelleri tek çıktı tensor'unda [1,84,8400] veya [1,8400,84] döndürür.
     */
    private enum class OutputFormat { QUALCOMM_MULTI_OUTPUT, LEGACY_CHANNELS_FIRST, LEGACY_CHANNELS_LAST, UNKNOWN }

    data class Detection(
        val classIndex: Int,
        val confidence: Float,
        val boundingBox: RectF
    )

    private var interpreter: Interpreter? = null
    private var nnApiDelegate: NnApiDelegate? = null
    private var gpuDelegate: GpuDelegate? = null
    private var outputFormat = OutputFormat.UNKNOWN
    var isNpuOrGpuAccelerated = false
        private set

    /** Model assets'ten başarıyla yüklendi mi? — gerçek AI çıktısı üretilebilir. */
    val isModelLoaded: Boolean get() = interpreter != null

    init {
        try {
            val modelBuffer = loadModelFile(context, MODEL_NAME)
            val options = Interpreter.Options().apply {
                try {
                    val delegate = NnApiDelegate()
                    addDelegate(delegate)
                    nnApiDelegate = delegate
                    isNpuOrGpuAccelerated = true
                    Log.i(TAG, "YoloInferenceEngine: NPU (NNAPI / Hexagon) acceleration active.")
                } catch (t: Throwable) {
                    Log.w(TAG, "YoloInferenceEngine: NPU (NNAPI) failed, trying GPU fallback. ${t.message}")
                    try {
                        val delegate = GpuDelegate()
                        addDelegate(delegate)
                        gpuDelegate = delegate
                        isNpuOrGpuAccelerated = true
                        Log.i(TAG, "YoloInferenceEngine: GPU acceleration active.")
                    } catch (t2: Throwable) {
                        Log.w(TAG, "YoloInferenceEngine: GPU failed, fallback to CPU. ${t2.message}")
                    }
                }
                setNumThreads(4)
            }
            interpreter = Interpreter(modelBuffer, options)
            Log.i(TAG, "YoloInferenceEngine: Model $MODEL_NAME loaded successfully.")

            // Çıktı formatını tespit et — Qualcomm 3-output mu, legacy tek-tensor mu?
            detectOutputFormat()
        } catch (e: Throwable) {
            // Throwable catches both Exception (file not found, IO) and Error (UnsatisfiedLinkError
            // in test environments where TFLite native libs are unavailable).
            Log.w(TAG, "YoloInferenceEngine: $MODEL_NAME load/init failed, running with no detections. ${e.message}")
        }
    }

    /**
     * Interpreter'ın çıktı tensor şekillerini inceleyerek outputFormat enum'unu belirler.
     * Qualcomm YOLOv8: 3 çıktı → boxes[1,8400,4], scores[1,8400], class_idx[1,8400]
     * Legacy channels-first: 1 çıktı → [1,84,8400]
     * Legacy channels-last:  1 çıktı → [1,8400,84]
     */
    private fun detectOutputFormat() {
        val inst = interpreter ?: return
        val numOut = inst.outputTensorCount
        val shapes = (0 until numOut).map { inst.getOutputTensor(it).shape().toList() }
        outputFormat = when {
            numOut == 3 && shapes[0] == listOf(1, 8400, 4) && shapes[1] == listOf(1, 8400) && shapes[2] == listOf(1, 8400) -> OutputFormat.QUALCOMM_MULTI_OUTPUT
            numOut == 1 && shapes[0] == listOf(1, 84, 8400) -> OutputFormat.LEGACY_CHANNELS_FIRST
            numOut == 1 && shapes[0] == listOf(1, 8400, 84) -> OutputFormat.LEGACY_CHANNELS_LAST
            else -> OutputFormat.UNKNOWN
        }
        Log.i(TAG, "Output format detected: $outputFormat, shapes: $shapes")
    }

    fun infer(bitmap: Bitmap): List<Detection> {
        val inst = interpreter ?: run {
            Log.w(TAG, "Model not loaded — returning no detections.")
            return emptyList()
        }

        val scaled = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        val inputBuffer = convertBitmapToByteBuffer(scaled)

        return try {
            when (outputFormat) {
                OutputFormat.QUALCOMM_MULTI_OUTPUT -> {
                    // Qualcomm YOLOv8: 3 ayrı çıktı tensor — boxes, scores, class_idx
                    val boxesOut = Array(1) { Array(8400) { FloatArray(4) } }
                    val scoresOut = Array(1) { FloatArray(8400) }
                    val classOut = Array(1) { FloatArray(8400) }
                    val outputs = mapOf(0 to boxesOut, 1 to scoresOut, 2 to classOut)
                    inst.runForMultipleInputsOutputs(arrayOf(inputBuffer), outputs)
                    parseQualcommDetections(boxesOut[0], scoresOut[0], classOut[0])
                }
                OutputFormat.LEGACY_CHANNELS_FIRST -> {
                    // Legacy: tek çıktı [1, 84, 8400] — channel-first layout
                    val outputArray = Array(1) { Array(84) { FloatArray(8400) } }
                    inst.run(inputBuffer, outputArray)
                    parseDetections(outputArray[0])
                }
                OutputFormat.LEGACY_CHANNELS_LAST -> {
                    // Legacy: tek çıktı [1, 8400, 84] — channel-last layout
                    val outputArray = Array(1) { Array(8400) { FloatArray(84) } }
                    inst.run(inputBuffer, outputArray)
                    parseDetectionsTransposed(outputArray[0])
                }
                OutputFormat.UNKNOWN -> {
                    Log.w(TAG, "Unknown output format — returning no detections.")
                    emptyList()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "YOLO inference error: ${e.message}", e)
            emptyList()
        }
    }

    fun close() {
        interpreter?.close()
        nnApiDelegate?.close()
        gpuDelegate?.close()
    }

    private fun loadModelFile(context: Context, modelPath: String): ByteBuffer {
        val fileDescriptor = context.assets.openFd(modelPath)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
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

            byteBuffer.putFloat(r)
            byteBuffer.putFloat(g)
            byteBuffer.putFloat(b)
        }
        return byteBuffer
    }

    /**
     * YOLOv8 çıktısını (1, 84, 8400) bounding box listesine çevirir.
     * Testability: birim testler doğrudan çağırabilsin diye internal.
     */
    internal fun parseDetections(output: Array<FloatArray>): List<Detection> {
        val candidates = mutableListOf<Detection>()

        // index 0..3: cx, cy, w, h
        // index 4..83: class confidence
        for (i in 0 until 8400) {
            var maxConfidence = 0.0f
            var maxClassIndex = -1
            for (c in 4 until 84) {
                val conf = output[c][i]
                if (conf > maxConfidence) {
                    maxConfidence = conf
                    maxClassIndex = c - 4
                }
            }

            if (maxConfidence > CONFIDENCE_THRESHOLD) {
                val cx = output[0][i] / INPUT_SIZE
                val cy = output[1][i] / INPUT_SIZE
                val w = output[2][i] / INPUT_SIZE
                val h = output[3][i] / INPUT_SIZE

                val rect = RectF(
                    (cx - w / 2f).coerceIn(0f, 1f),
                    (cy - h / 2f).coerceIn(0f, 1f),
                    (cx + w / 2f).coerceIn(0f, 1f),
                    (cy + h / 2f).coerceIn(0f, 1f)
                )
                candidates.add(Detection(maxClassIndex, maxConfidence, rect))
            }
        }

        return applyNms(candidates)
    }


    /**
     * Qualcomm YOLOv8 3-output format parser'ı.
     * @param boxes   [8400][4] — XYXY koordinatları (piksel veya normalize)
     * @param scores  [8400]   — her anchor'ın confidence skoru
     * @param classIdx [8400]  — her anchor için tamsayı class index (float olarak)
     *
     * Normalize guard: Qualcomm çıktısı input-piksel uzayında (0..640).
     * Eğer tüm kutu koordinatları <= 1.001 ise, zaten normalize edilmiş kabul edilir.
     */
    internal fun parseQualcommDetections(
        boxes: Array<FloatArray>,
        scores: FloatArray,
        classIdx: FloatArray
    ): List<Detection> {
        // Normalize guard: eğer max koordinat 1.5'ten büyükse piksel uzayı (INPUT_SIZE'a böl).
        val maxCoord = boxes.maxOfOrNull { box -> box.max() } ?: 0f
        val scaleFactor = if (maxCoord > 1.5f) INPUT_SIZE.toFloat() else 1.0f

        val candidates = mutableListOf<Detection>()
        for (i in boxes.indices) {
            val score = scores[i]
            if (score <= CONFIDENCE_THRESHOLD) continue
            val classId = classIdx[i].toInt()
            val x1 = (boxes[i][0] / scaleFactor).coerceIn(0f, 1f)
            val y1 = (boxes[i][1] / scaleFactor).coerceIn(0f, 1f)
            val x2 = (boxes[i][2] / scaleFactor).coerceIn(0f, 1f)
            val y2 = (boxes[i][3] / scaleFactor).coerceIn(0f, 1f)
            if (x2 <= x1 || y2 <= y1) continue  // dejenere kutu — atla
            candidates.add(Detection(classId, score, RectF(x1, y1, x2, y2)))
        }
        return applyNms(candidates)
    }

    /**
     * Legacy channels-last parser: output[8400][84] — index 0..3 = cx,cy,w,h; index 4..83 = class confidences.
     * Testability: birim testler doğrudan çağırabilsin diye internal.
     */
    internal fun parseDetectionsTransposed(output: Array<FloatArray>): List<Detection> {
        val candidates = mutableListOf<Detection>()
        for (i in 0 until 8400) {
            var maxConfidence = 0.0f
            var maxClassIndex = -1
            for (c in 4 until 84) {
                val conf = output[i][c]
                if (conf > maxConfidence) {
                    maxConfidence = conf
                    maxClassIndex = c - 4
                }
            }
            if (maxConfidence > CONFIDENCE_THRESHOLD) {
                val cx = output[i][0] / INPUT_SIZE
                val cy = output[i][1] / INPUT_SIZE
                val w = output[i][2] / INPUT_SIZE
                val h = output[i][3] / INPUT_SIZE
                val rect = RectF(
                    (cx - w / 2f).coerceIn(0f, 1f),
                    (cy - h / 2f).coerceIn(0f, 1f),
                    (cx + w / 2f).coerceIn(0f, 1f),
                    (cy + h / 2f).coerceIn(0f, 1f)
                )
                candidates.add(Detection(maxClassIndex, maxConfidence, rect))
            }
        }
        return applyNms(candidates)
    }

    /**
     * Class-bazlı Non-Max Suppression uygular.
     * Testability: birim testler doğrudan çağırabilsin diye internal.
     */
    internal fun applyNms(detections: List<Detection>): List<Detection> {
        val result = mutableListOf<Detection>()
        val classGroups = detections.groupBy { it.classIndex }

        for (classIndex in classGroups.keys) {
            val group = classGroups[classIndex]?.sortedByDescending { it.confidence } ?: continue
            val visited = BooleanArray(group.size)

            for (i in group.indices) {
                if (visited[i]) continue
                val best = group[i]
                result.add(best)

                for (j in i + 1 until group.size) {
                    if (visited[j]) continue
                    val current = group[j]
                    if (calculateIoU(best.boundingBox, current.boundingBox) > NMS_THRESHOLD) {
                        visited[j] = true
                    }
                }
            }
        }
        return result
    }

    private fun calculateIoU(box1: RectF, box2: RectF): Float {
        val intersectionLeft = kotlin.math.max(box1.left, box2.left)
        val intersectionTop = kotlin.math.max(box1.top, box2.top)
        val intersectionRight = kotlin.math.min(box1.right, box2.right)
        val intersectionBottom = kotlin.math.min(box1.bottom, box2.bottom)

        val intersectionArea = kotlin.math.max(0f, intersectionRight - intersectionLeft) *
                kotlin.math.max(0f, intersectionBottom - intersectionTop)

        val box1Area = (box1.right - box1.left) * (box1.bottom - box1.top)
        val box2Area = (box2.right - box2.left) * (box2.bottom - box2.top)
        val unionArea = box1Area + box2Area - intersectionArea

        return if (unionArea > 0f) intersectionArea / unionArea else 0f
    }
}
