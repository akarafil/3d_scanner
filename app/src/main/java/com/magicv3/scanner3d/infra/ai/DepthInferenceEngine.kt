package com.magicv3.scanner3d.infra.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

class DepthInferenceEngine(private val context: Context) {

    companion object {
        private const val TAG = "DepthInferenceEngine"
        private const val MODEL_NAME = "depth_anything_small.tflite"
        private const val INPUT_SIZE = 518 // Standard Depth Anything Small resolution
    }

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
                    Log.i(TAG, "DepthInferenceEngine: GPU acceleration active.")
                } catch (t: Throwable) {
                    Log.w(TAG, "DepthInferenceEngine: GPU failed, fallback to CPU. ${t.message}")
                }
                setNumThreads(4)
            }
            interpreter = Interpreter(modelBuffer, options)
            Log.i(TAG, "DepthInferenceEngine: Model $MODEL_NAME loaded successfully.")
        } catch (e: Exception) {
            Log.w(TAG, "DepthInferenceEngine: $MODEL_NAME not found in assets, running in MOCK mode. ${e.message}")
        }
    }

    fun infer(bitmap: Bitmap): FloatArray {
        val inst = interpreter
        if (inst == null) {
            return generateMockDepth(INPUT_SIZE, INPUT_SIZE)
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
            generateMockDepth(INPUT_SIZE, INPUT_SIZE)
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

            // Normalize values matching standard ImageNet normalization
            byteBuffer.putFloat((r - 0.485f) / 0.229f)
            byteBuffer.putFloat((g - 0.456f) / 0.224f)
            byteBuffer.putFloat((b - 0.406f) / 0.225f)
        }
        return byteBuffer
    }

    private fun generateMockDepth(width: Int, height: Int): FloatArray {
        val size = width * height
        val depths = FloatArray(size)
        val timeMs = System.currentTimeMillis()
        val freq = 0.04f
        val phase = (timeMs / 250.0) % (2.0 * Math.PI)

        for (y in 0 until height) {
            for (x in 0 until width) {
                // Synthesize dynamic radial depth wave from center
                val dx = x - width / 2.0
                val dy = y - height / 2.0
                val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                val value = (kotlin.math.sin(dist * freq - phase) + 1.0) / 2.0
                depths[y * width + x] = value.toFloat()
            }
        }
        return depths
    }
}
