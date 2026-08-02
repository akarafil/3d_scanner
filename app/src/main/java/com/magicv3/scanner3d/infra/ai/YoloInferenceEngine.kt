package com.magicv3.scanner3d.infra.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.PriorityQueue

class YoloInferenceEngine(private val context: Context) {

    companion object {
        private const val TAG = "YoloInferenceEngine"
        private const val MODEL_NAME = "yolov8n.tflite"
        private const val INPUT_SIZE = 640
        private const val CONFIDENCE_THRESHOLD = 0.35f
        private const val NMS_THRESHOLD = 0.45f
    }

    data class Detection(
        val classIndex: Int,
        val confidence: Float,
        val boundingBox: RectF
    )

    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    var isNpuOrGpuAccelerated = false
        private set

    init {
        try {
            val modelBuffer = loadModelFile(context, MODEL_NAME)
            val options = Interpreter.Options().apply {
                try {
                    val delegate = GpuDelegate()
                    addDelegate(delegate)
                    gpuDelegate = delegate
                    isNpuOrGpuAccelerated = true
                    Log.i(TAG, "YoloInferenceEngine: GPU acceleration active.")
                } catch (t: Throwable) {
                    Log.w(TAG, "YoloInferenceEngine: GPU failed, fallback to CPU. ${t.message}")
                }
                setNumThreads(4)
            }
            interpreter = Interpreter(modelBuffer, options)
            Log.i(TAG, "YoloInferenceEngine: Model $MODEL_NAME loaded successfully.")
        } catch (e: Exception) {
            Log.w(TAG, "YoloInferenceEngine: $MODEL_NAME not found in assets, running in MOCK mode. ${e.message}")
        }
    }

    fun infer(bitmap: Bitmap): List<Detection> {
        val inst = interpreter ?: return generateMockDetections()

        return try {
            val scaled = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
            val inputBuffer = convertBitmapToByteBuffer(scaled)

            // YOLOv8 output size standard: [1, 84, 8400]
            val outputArray = Array(1) { Array(84) { FloatArray(8400) } }
            inst.run(inputBuffer, outputArray)

            parseDetections(outputArray[0])
        } catch (e: Exception) {
            Log.e(TAG, "YOLO Inference error: ${e.message}", e)
            generateMockDetections()
        }
    }

    fun close() {
        interpreter?.close()
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

    private fun parseDetections(output: Array<FloatArray>): List<Detection> {
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

    private fun applyNms(detections: List<Detection>): List<Detection> {
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

    private fun generateMockDetections(): List<Detection> {
        // Return a mock bounding box around the center that animates slightly
        val timeMs = System.currentTimeMillis()
        val offset = (kotlin.math.sin(timeMs / 1000.0) * 0.05).toFloat()
        val rect = RectF(0.3f + offset, 0.25f, 0.7f + offset, 0.75f)
        return listOf(
            Detection(classIndex = 0, confidence = 0.89f, boundingBox = rect)
        )
    }
}
