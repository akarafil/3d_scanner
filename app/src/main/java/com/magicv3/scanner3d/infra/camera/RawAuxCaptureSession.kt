package com.magicv3.scanner3d.infra.camera

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.StreamConfigurationMap
import android.graphics.ImageFormat
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.WindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Phase 2.1.0 — Proof-of-Concept capture pipeline.
 *
 * Directly opens a *hidden* physical sensor (default: id=4 = Telephoto on the Honor Magic V3)
 * bypassing Honor's getCameraIdList filter, and grabs a single still frame as JPEG.
 *
 * Flow:  openCamera → createCaptureSession(ImageReader) → single capture →
 *        YUV_420_888 → NV21 → JPEG → write to filesDir.
 *
 * Why raw Camera2 and not CameraX:
 *   CameraX only iterates getCameraIdList() (returns ["0","1"] on this device), so it cannot
 *   select ids 2/4. We must use CameraManager.openCamera(id, ...) directly.
 *
 * Production notes (carry to Phase 2.1.1):
 *   - One persistent HandlerThread kept alive for the entire session lifecycle.
 *   - close() chain is awaited so that CameraDevice's late onClosed callback never lands on a
 *     dead Looper (the legacy bug seen in AuxProbe.logcat).
 *   - Template: TEMPLATE_STILL_CAPTURE, AF=CONTINUOUS_PICTURE, AE auto-flash
 *   - Orientation read from default display at capture time.
 */
class RawAuxCaptureSession(
    private val context: Context,
    private val cameraId: String = AUX_TELEPHOTO_ID,
    private val outputDir: File = File(context.filesDir, "aux_captures").apply { mkdirs() },
) {
    companion object {
        private const val TAG = "RawAuxCapture"

        /** Honor Magic V3 physical sub-sensor IDs (verified OPENABLE via direct openCamera in Phase 2.0.5). */
        const val AUX_TELEPHOTO_ID = "4"   // 14.92mm raw ≈ 70–90mm FF equiv — primary 3D-scan lens
        const val AUX_ULTRAWIDE_ID = "2"   // 1.96mm raw ≈ 14–16mm FF equiv — wide-scene lens

        private const val CAPTURE_TIMEOUT_MS = 5_000L
        private const val MAX_IMAGES = 2
    }

    private val cameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null

    /**
     * Open the aux camera, take ONE still frame, encode to JPEG, save to filesDir, return the File.
     * All resources closed in finally {} regardless of success or failure.
     */
    suspend fun captureSingleFrame(
        manualIso: Int? = null,
        manualExposureTimeNs: Long? = null,
        manualFocusDistance: Float? = null,
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            startHandlerThread()
            val cam = openCameraDirect()
            val captureSize = pickYuvSize(cam)
            Log.i(TAG, "[$cameraId] capture size = ${captureSize.width}x${captureSize.height}")

            val file = withTimeoutOrNull(CAPTURE_TIMEOUT_MS) {
                val image = createSessionAndCapture(cam, captureSize, manualIso, manualExposureTimeNs, manualFocusDistance)
                val jpegBytes = yuv420ToJpeg(image, captureSize.width, captureSize.height)
                image.close()
                saveJpeg(jpegBytes, cameraId)
            } ?: run {
                Log.e(TAG, "[$cameraId] capture timed out after ${CAPTURE_TIMEOUT_MS}ms")
                return@withContext Result.failure(IOException("capture timeout for id=$cameraId"))
            }

            Log.i(TAG, "[$cameraId] ✅ JPEG saved: ${file.absolutePath} (${file.length()} bytes)")
            Result.success(file)
        } catch (t: Throwable) {
            Log.e(TAG, "[$cameraId] capture failed: ${t.javaClass.simpleName}: ${t.message}", t)
            Result.failure(t)
        } finally {
            closeQuietly()
        }
    }

    // ───────────────────────── 1. Handler / lifecycle ─────────────────────────

    private fun startHandlerThread() {
        val thread = HandlerThread("raw-aux-$cameraId").apply { start() }
        handlerThread = thread
        handler = Handler(thread.looper)
    }

    private fun stopHandlerThread() {
        handlerThread?.quitSafely()
        handlerThread = null
        handler = null
    }

    // ───────────────────────── 2. openCamera (bypass) ─────────────────────────

    @SuppressLint("MissingPermission") // CAMERA permission verified by caller before invoke.
    private suspend fun openCameraDirect(): CameraDevice =
        suspendCancellableCoroutine { cont ->
            try {
                cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                    override fun onOpened(cam: CameraDevice) {
                        cameraDevice = cam
                        Log.d(TAG, "[$cameraId] onOpened")
                        if (cont.isActive) cont.resume(cam)
                    }

                    override fun onDisconnected(cam: CameraDevice) {
                        Log.w(TAG, "[$cameraId] onDisconnected")
                        cam.close()
                        cameraDevice = null
                        if (cont.isActive)
                            cont.resumeWithException(IOException("onDisconnected id=$cameraId"))
                    }

                    override fun onError(cam: CameraDevice, err: Int) {
                        cam.close()
                        cameraDevice = null
                        // err=2 (ERROR_CAMERA_DISABLED) → Honor policy rejection; surface to caller.
                        val msg = "onError=$err (ERROR_CAMERA_DISABLED if 2) for id=$cameraId"
                        Log.e(TAG, "[$cameraId] $msg")
                        if (cont.isActive)
                            cont.resumeWithException(IOException(msg))
                        else {
                            // Recovery path if invoked from finally {} safeties elsewhere
                            Log.e(TAG, "[$cameraId] late onError after cont already resumed: $msg")
                        }
                    }

                    override fun onClosed(cam: CameraDevice) {
                        Log.d(TAG, "[$cameraId] onClosed — device released")
                    }
                }, handler)
            } catch (t: Throwable) {
                if (cont.isActive) cont.resumeWithException(t)
            }
        }

    // ───────────────────────── 3. Size selection ─────────────────────────

    private fun pickYuvSize(cam: CameraDevice): Size {
        // Use LENS_FACING / characteristics? Hidden ids report focal via cameraId surface first
        // Best-effort: read full stills config map and pick the largest YUV_420_888 size.
        val characteristics = runCatching { cameraManager.getCameraCharacteristics(cameraId) }
            .getOrNull() ?: return Size(4096, 3072) // Honor Magic V3 Tele default

        val map: StreamConfigurationMap? =
            characteristics.get(android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

        val yuvSizes: Array<Size>? = map?.getOutputSizes(ImageFormat.YUV_420_888)
        // Cap ~12MP to dodge IL/IBs that reject ultra-large; pick largest under 13MP.
        val maxPixels = 4096 * 3072
        return yuvSizes
            ?.filter { it.width * it.height <= maxPixels }
            ?.maxByOrNull { it.width * it.height }
            ?: Size(4096, 3072)
    }

    // ───────────────────────── 4. Session + single capture ─────────────────────────

    private suspend fun createSessionAndCapture(
        cam: CameraDevice,
        size: Size,
        manualIso: Int? = null,
        manualExposureTimeNs: Long? = null,
        manualFocusDistance: Float? = null,
    ): Image {
        val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, MAX_IMAGES)
        imageReader = reader

        val imageDeferred = kotlinx.coroutines.CompletableDeferred<Image>()

        reader.setOnImageAvailableListener({ r ->
            // We want the first frame after a fresh CLEAN capture, not a stale buffer.
            val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            if (!imageDeferred.isCompleted) {
                Log.d(TAG, "[$cameraId] ImageReader.onImageAvailable → handing frame to encoder")
                imageDeferred.complete(img)
            } else {
                img.close()
            }
        }, handler)

        val session = suspendCancellableCoroutine<CameraCaptureSession> { cont ->
            cam.createCaptureSession(
                listOf<Surface>(reader.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        Log.d(TAG, "[$cameraId] session onConfigured")
                        if (cont.isActive) cont.resume(s)
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        val msg = "[$cameraId] onConfigureFailed"
                        Log.e(TAG, msg)
                        if (cont.isActive) cont.resumeWithException(IOException(msg))
                    }

                    override fun onReady(s: CameraCaptureSession) {
                        Log.d(TAG, "[$cameraId] session onReady")
                    }
                },
                handler
            )
        }
        captureSession = session

        // Build still capture request — only target the ImageReader surface (no preview surface here)
        val requestBuilder = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(reader.surface)

            // Pozlama ve ISO Kilidi
            if (manualIso != null && manualExposureTimeNs != null) {
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                set(CaptureRequest.SENSOR_SENSITIVITY, manualIso)
                set(CaptureRequest.SENSOR_EXPOSURE_TIME, manualExposureTimeNs)
                Log.i(TAG, "[$cameraId] Pozlama kilitlendi: ISO=$manualIso, Enstantane=${manualExposureTimeNs}ns")
            } else {
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)
            }

            // Odak Kilidi
            if (manualFocusDistance != null) {
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                set(CaptureRequest.LENS_FOCUS_DISTANCE, manualFocusDistance)
                Log.i(TAG, "[$cameraId] Odak kilitlendi: LENS_FOCUS_DISTANCE=$manualFocusDistance")
            } else {
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }

            set(CaptureRequest.JPEG_ORIENTATION, currentDisplayRotationDegrees())
        }

        session.capture(
            requestBuilder.build(),
            object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureStarted(
                    s: CameraCaptureSession,
                    request: CaptureRequest,
                    timestamp: Long,
                    frameNumber: Long,
                ) {
                    Log.d(TAG, "[$cameraId] onCaptureStarted frame=$frameNumber")
                }

                override fun onCaptureCompleted(
                    s: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult,
                ) {
                    Log.d(TAG, "[$cameraId] onCaptureCompleted frame=${result.frameNumber}")
                }

                override fun onCaptureFailed(
                    s: CameraCaptureSession,
                    request: CaptureRequest,
                    failure: android.hardware.camera2.CaptureFailure,
                ) {
                    val msg = "[$cameraId] onCaptureFailed reason=${failure.reason} frame=${failure.frameNumber}"
                    Log.e(TAG, msg)
                    if (imageDeferred.isActive) {
                        imageDeferred.completeExceptionally(IOException(msg))
                    }
                }
            },
            handler,
        )

        return imageDeferred.await()
    }

    // ───────────────────────── 5. YUV_420_888 → NV21 → JPEG ─────────────────────────

    private fun yuv420ToJpeg(image: Image, width: Int, height: Int): ByteArray {
        require(image.format == ImageFormat.YUV_420_888) { "expected YUV_420_888" }

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuf: ByteBuffer = yPlane.buffer
        val uBuf: ByteBuffer = uPlane.buffer
        val vBuf: ByteBuffer = vPlane.buffer

        // NV21 = Y (width * height bytes) + VU interleaved (width * height / 2 bytes)
        val nv21 = ByteArray(width * height * 3 / 2)

        val yRowStride = yPlane.rowStride
        // Y Plane: rowStride değerine göre satır satır kopyalama yapıyoruz (rowStride != width olabilir)
        for (row in 0 until height) {
            yBuf.position(row * yRowStride)
            yBuf.get(nv21, row * width, width)
        }

        val uvRowStride = vPlane.rowStride
        val uvPixelStride = vPlane.pixelStride
        var uvOffset = width * height

        // UV Interleaved (NV21 formatında V önce, U sonra gelir)
        for (row in 0 until height / 2) {
            for (col in 0 until width / 2) {
                // V byte
                nv21[uvOffset++] = vBuf.get(row * uvRowStride + col * uvPixelStride)
                // U byte
                nv21[uvOffset++] = uBuf.get(row * uvRowStride + col * uvPixelStride)
            }
        }

        val yuvImage = android.graphics.YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(
            android.graphics.Rect(0, 0, width, height),
            92,
            out,
        )
        return out.toByteArray()
    }

    private fun saveJpeg(jpeg: ByteArray, id: String): File {
        val name = "aux_${id}_${System.currentTimeMillis()}.jpg"
        val file = File(outputDir, name)
        FileOutputStream(file).use { it.write(jpeg) }
        return file
    }

    // ───────────────────────── 6. Cleanup ─────────────────────────

    private fun closeQuietly() {
        try {
            captureSession?.let {
                runCatching { it.close() }
                    .onFailure { e -> Log.w(TAG, "session.close() failed: ${e.message}") }
            }
            captureSession = null
        } catch (e: Throwable) {
            Log.w(TAG, "session close threw: ${e.message}")
        }

        try {
            imageReader?.let {
                runCatching { it.close() }
                    .onFailure { e -> Log.w(TAG, "imageReader.close() failed: ${e.message}") }
            }
            imageReader = null
        } catch (e: Throwable) {
            Log.w(TAG, "imageReader close threw: ${e.message}")
        }

        try {
            cameraDevice?.let { cam ->
                runCatching { cam.close() }
                    .onFailure { e -> Log.w(TAG, "cameraDevice.close() failed: ${e.message}") }
            }
            cameraDevice = null
        } catch (e: Throwable) {
            Log.w(TAG, "cameraDevice close threw: ${e.message}")
        }

        handler?.postDelayed({
            stopHandlerThread()
        }, 500L)
    }

    // ───────────────────────── 7. Orientation ─────────────────────────

    private fun currentDisplayRotationDegrees(): Int {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val rotation = wm.defaultDisplay.rotation
        return when (rotation) {
            android.view.Surface.ROTATION_0 -> 0
            android.view.Surface.ROTATION_90 -> 90
            android.view.Surface.ROTATION_180 -> 180
            android.view.Surface.ROTATION_270 -> 270
            else -> 90
        }
    }
}
