package com.magicv3.scanner3d.infra.camera

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.StreamConfigurationMap
import android.graphics.ImageFormat
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Rational
import android.util.Size
import android.view.Surface
import android.view.WindowManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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

/**
 * Aux kamera kaynaklarının SIRALI kapanış sözleşmesi.
 *
 * Canonical order (Honor HAL `endConfigure:905 ... Unsupported set of inputs/outputs`
 * asenkron yarışını önlemek için ZORUNLU):
 *   1. captureSession.close() → StateCallback.onClosed BEKLENİR (2sn zaman aşımı)
 *   2. imageReader.close()
 *   3. cameraDevice.close()
 *
 * Neden bu sıra: Bazı HAL'ler (Honor Magic V3) session onClosed callback'i gelmeden
 * ImageReader surface'ı yok edilirse `endConfigure:905` hatası üretir. Session tamamen
 * kapanana kadar surface yaşatılır, sonra reader ve device kapatılır.
 *
 * Arayüz olarak ayrılmasının nedeni test edilebilirliktir: birim testler gerçek
 * CameraCaptureSession olmadan kapanış sırasını doğrulayabilir.
 */
internal interface AuxResourceTeardown {
    /**
     * captureSession'i kapat ve StateCallback.onClosed gelene kadar blokla.
     * [timeoutMs] içinde onClosed gelmezse veya close() atarsa `false` döner
     * (çağıran yine de reader/device kapanışına devam eder — kilitlenme olmaz).
     */
    fun closeSession(timeoutMs: Long): Boolean

    /** imageReader.close(). */
    fun closeReader()

    /** cameraDevice.close(). */
    fun closeDevice()
}

/** Canonical kapanış adımları (test doğrulaması için). */
internal enum class TeardownStep { SESSION, READER, DEVICE }

/** Session onClosed için azami bekleme süresi (ms). */
internal const val SESSION_CLOSE_TIMEOUT_MS = 2_000L

/**
 * Kapanış zincirini session → reader → device sırasıyla çalıştırır ve çalıştırılan
 * adımların sırasını döndürür (birim test assertion'ları için).
 *
 * closeSession `false` dönse bile (onClosed gelmedi / zaman aşımı / close() hatası)
 * kapanış devam eder: kilitlenme olmaz, reader ve device her koşulda kapatılır.
 */
internal fun closeInOrder(
    teardown: AuxResourceTeardown,
    timeoutMs: Long = SESSION_CLOSE_TIMEOUT_MS,
): List<TeardownStep> {
    val steps = mutableListOf<TeardownStep>()

    steps += TeardownStep.SESSION
    teardown.closeSession(timeoutMs)

    steps += TeardownStep.READER
    teardown.closeReader()

    steps += TeardownStep.DEVICE
    teardown.closeDevice()

    return steps
}

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

    private var captureSize: Size? = null

    /**
     * F1/MEDIUM-1: Session StateCallback.onClosed tetiklendiğinde çağrılır.
     * closeQuietly, `close()`'un asenkron onClosed'unu CountDownLatch ile beklemek
     * için bu listener'ı latch.countDown()'a bağlar.
     *
     * MEDIUM-1: Listener PER-SESSION kimlikle bağlanır — imza `((CameraCaptureSession) -> Unit)?`
     * yapıldı. Bağlama sırasında (closeSession) beklenen session yakalanır; kimliği
     * eşleşmeyen stale/geç onClosed'lar latch'i sayamaz (çapraz countDown yarışı önlenir).
     *
     * `@Volatile`: closeSession/open (IO thread) yazarken, StateCallback.onClosed handler
     * thread'inde okuyabilir → cross-thread görünürlük için zorunlu.
     */
    @Volatile
    private var onSessionClosedListener: ((CameraCaptureSession) -> Unit)? = null

    /**
     * MEDIUM-1: Per-session onClosed latch'i bağlar.
     *
     * [expectedSession] kimliğiyle gelen onClosed latch'i sayar; farklı bir session'ın
     * stale/geç onClosed'u (ör. A session'ının onClosed'u B kapanırken ulaşırsa) latch'i
     * sayamaz → HAL `endConfigure:905` yarışı yeniden tetiklenmez.
     *
     * Test edilebilirlik için internal (RawAuxCaptureSessionTest simüle eder).
     */
    internal fun bindSessionClosedLatch(
        latch: CountDownLatch,
        expectedSession: CameraCaptureSession,
    ) {
        onSessionClosedListener = { s -> if (s === expectedSession) latch.countDown() }
    }

    /**
     * StateCallback.onClosed yolunu tetikler. Production'da open() içindeki callback'ten
     * çağrılır; birim testler farklı session nesneleriyle simüle eder.
     */
    internal fun notifySessionClosed(session: CameraCaptureSession) {
        onSessionClosedListener?.invoke(session)
    }

    /**
     * MEDIUM-2 yardımcısı: [imageDeferred]'e tamamlanmış ama hiçbir consumer'ın
     * okumadığı bir Image kalmışsa kapatır. Consumer iptal/zaman aşımı nedeniyle
     * await()'ten dönemeden deferred complete edilmiş olabilir; bu native buffer
     * sızıntısını önler. (getCompletedOrNull 1.8.1'de yok — isCompleted && !isCancelled
     * guard'lı getCompleted() kullanılır; tamamlanmış deferred sonradan cancel olamaz,
     * dolayısıyla race-free'dir.)
     */
    private fun closeAbandonedImage(imageDeferred: kotlinx.coroutines.CompletableDeferred<Image>) {
        if (imageDeferred.isCompleted && !imageDeferred.isCancelled) {
            runCatching { imageDeferred.getCompleted().close() }
        }
    }

    /**
     * Open the aux camera, take ONE still frame, encode to JPEG, save to filesDir, return the File.
     * All resources closed in finally {} regardless of success or failure.
     */
    suspend fun captureSingleFrame(
        manualIso: Int? = null,
        manualExposureTimeNs: Long? = null,
        manualFocusDistance: Float? = null,
        manualEv: Float? = null,
        manualColorTemperature: Int? = null,
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            open()
            val file = captureFrame(manualIso, manualExposureTimeNs, manualFocusDistance, manualEv, manualColorTemperature)
            Result.success(file)
        } catch (t: Throwable) {
            Log.e(TAG, "[$cameraId] capture failed: ${t.javaClass.simpleName}: ${t.message}", t)
            Result.failure(t)
        } finally {
            close()
        }
    }

    /**
     * Phase 2.1.1 — StickySession.
     * Opens the camera device, checks output YUV size, initializes ImageReader and creates capture session.
     */
    suspend fun open(): Unit = withContext(Dispatchers.IO) {
        startHandlerThread()
        val cam = openCameraDirect()
        val size = pickYuvSize(cam)
        captureSize = size
        Log.i(TAG, "[$cameraId] Sticky Session: camera opened, YUV size selected = ${size.width}x${size.height}")

        val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, MAX_IMAGES)
        imageReader = reader

        // F1: Yeni session açılırken eski session'ın stale onClosed listener'ını temizle.
        onSessionClosedListener = null

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

                    override fun onClosed(s: CameraCaptureSession) {
                        // F1/MEDIUM-1: Sıralı kapanış zinciri bu callback'i bekler
                        // (close(callback) public API değil; bu yüzden listener üzerinden bağlanır).
                        // Kimlik (`s`) listener'a iletilir; latch'i yalnızca closeSession'ın
                        // beklediği session sayar (başka session'ın stale onClosed'u sayamaz).
                        Log.d(TAG, "[$cameraId] session onClosed — yüzey artık güvenle yok edilebilir")
                        notifySessionClosed(s)
                    }
                },
                handler
            )
        }
        captureSession = session
    }

    /**
     * Phase 2.1.1 — StickySession.
     * Captures a single frame using the already configured captureSession.
     */
    suspend fun captureFrame(
        manualIso: Int? = null,
        manualExposureTimeNs: Long? = null,
        manualFocusDistance: Float? = null,
        manualEv: Float? = null,
        manualColorTemperature: Int? = null,
    ): File = withContext(Dispatchers.IO) {
        val cam = cameraDevice ?: throw IllegalStateException("[$cameraId] Camera not opened. Call open() first.")
        val session = captureSession ?: throw IllegalStateException("[$cameraId] Capture session not active. Call open() first.")
        val reader = imageReader ?: throw IllegalStateException("[$cameraId] ImageReader not initialized. Call open() first.")
        val size = captureSize ?: throw IllegalStateException("[$cameraId] Capture size not selected. Call open() first.")

        val imageDeferred = kotlinx.coroutines.CompletableDeferred<Image>()

        // MEDIUM-2: Deferred iptal edilirse (timeout / dış iptal) içinde tamamlanmış ama
        // hiçbir consumer'ın okumadığı Image varsa kapatılır → ~18MB'lık 12MP YUV native
        // buffer sızdırılmaz. Normal başarı yolunda cause == null → dokunulmaz (tüketici
        // finally'de kapatır). `cancel()` sonrası isCompleted == true olduğu için geç gelen
        // kare onImageAvailable else dalında (img.close()) kapanır.
        imageDeferred.invokeOnCompletion { cause ->
            if (cause != null) {
                closeAbandonedImage(imageDeferred)
            }
        }

        reader.setOnImageAvailableListener({ r ->
            val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            if (!imageDeferred.isCompleted) {
                Log.d(TAG, "[$cameraId] ImageReader.onImageAvailable → handing frame to encoder")
                imageDeferred.complete(img)
            } else {
                img.close()
            }
        }, handler)

        // Build still capture request — target the ImageReader surface
        val requestBuilder = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(reader.surface)

            // AE control
            if (manualIso != null && manualExposureTimeNs != null) {
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                set(CaptureRequest.SENSOR_SENSITIVITY, manualIso)
                set(CaptureRequest.SENSOR_EXPOSURE_TIME, manualExposureTimeNs)
                Log.i(TAG, "[$cameraId] Pozlama kilitlendi: ISO=$manualIso, Enstantane=${manualExposureTimeNs}ns")
            } else {
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)
            }

            // EV kompanzasyonu — yalnızca AE AUTO iken uygulanır.
            // (Manual ISO + enstantane verildiğinde AE OFF olduğundan EV işlevsizdir;
            // bu yüzden manualEv değeri o durumda yok sayılır — mevcut manuel dal değişmez.)
            if ((manualIso == null || manualExposureTimeNs == null) && manualEv != null) {
                val step = ProCameraCapabilities.readAeCompensationStep(cameraManager, cameraId)
                val range = ProCameraCapabilities.readAeCompensationRange(cameraManager, cameraId)
                val units = ProCameraCapabilities.evToCameraUnits(manualEv, step, range.first, range.last)
                set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, units)
                Log.i(TAG, "[$cameraId] EV uygulandı: $manualEv EV → $units")
            }

            // WB / renk sıcaklığı (manuel Kelvin) — AWB OFF + transform matrisi.
            // null iken AWB otomatik kalır (AWB anahtarlarına dokunulmaz).
            if (manualColorTemperature != null) {
                val (rGain, bGain) = ProCameraCapabilities.kelvinToRgbGains(manualColorTemperature)
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
                set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
                set(CaptureRequest.COLOR_CORRECTION_GAINS, RggbChannelVector(rGain, 1f, 1f, bGain))
                set(
                    CaptureRequest.COLOR_CORRECTION_TRANSFORM,
                    ColorSpaceTransform(
                        arrayOf(
                            Rational(1, 1), Rational(0, 1), Rational(0, 1),
                            Rational(0, 1), Rational(1, 1), Rational(0, 1),
                            Rational(0, 1), Rational(0, 1), Rational(1, 1),
                        )
                    )
                )
                Log.i(TAG, "[$cameraId] WB manuel: ${manualColorTemperature}K → gains(r=$rGain,b=$bGain)")
            }

            // AF control
            if (manualFocusDistance != null) {
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                set(CaptureRequest.LENS_FOCUS_DISTANCE, manualFocusDistance)
                Log.i(TAG, "[$cameraId] Odak kilitlendi: LENS_FOCUS_DISTANCE=$manualFocusDistance")
            } else {
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }

            set(CaptureRequest.JPEG_ORIENTATION, currentDisplayRotationDegrees())
        }

        val file = withTimeoutOrNull(CAPTURE_TIMEOUT_MS) {
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

            // MEDIUM-2: await iptal edilirse (timeout / dış iptal → CancellationException)
            // deferred'e tamamlanmış ama okunmamış Image kalmışsa kapatılır; deferred iptal
            // edilir ki geç gelen kare onImageAvailable else dalında (img.close()) kapansın.
            val image = try {
                imageDeferred.await()
            } catch (e: CancellationException) {
                imageDeferred.cancel(e)
                closeAbandonedImage(imageDeferred)
                throw e
            }

            // MEDIUM-2: JPEG kodlama/save başarılı olsun ya da olmasın (veya tam bu sırada
            // timeout iptali olsa bile) native Image buffer finally'de kapatılır — mevcut
            // `image.close()` çağrısı buraya taşındı, tek sefer çalışır (idempotent değil;
            // bu yüzden only-here).
            try {
                val jpegBytes = yuv420ToJpeg(image, size.width, size.height)
                saveJpeg(jpegBytes, cameraId)
            } finally {
                runCatching { image.close() }
            }
        } ?: run {
            // MEDIUM-2: Timeout yolunda deferred hâlâ aktifse iptal et → geç kareler else
            // dalında kapanır; tamamlanmış okunmamış Image varsa burada kapatılır.
            // (Tekrarlı timeout'ta native buffer sızıntısı önlenir.)
            if (imageDeferred.isActive) imageDeferred.cancel()
            closeAbandonedImage(imageDeferred)
            Log.e(TAG, "[$cameraId] capture timed out after ${CAPTURE_TIMEOUT_MS}ms")
            throw IOException("capture timeout for id=$cameraId")
        }

        Log.i(TAG, "[$cameraId] ✅ JPEG saved: ${file.absolutePath} (${file.length()} bytes)")
        file
    }

    /**
     * Phase 2.1.1 — StickySession.
     * Releases all camera resources and stops the background handler thread.
     */
    fun close() {
        closeQuietly()
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



    // ───────────────────────── 5. YUV_420_888 → NV21 → JPEG ─────────────────────────

    /**
     * YUV_420_888 → NV21 byte düzeni.
     *
     * B6: U ve V plane'lerinin rowStride/pixelStride'i ayrı ayrı kullanılır;
     * bounds check U için uBuf.capacity(), V için vBuf.capacity() ile yapılır
     * (U/V stride'ları farklıysa chroma doğru konumlanır).
     * Testability: Robolectric NV21 düzenini byte-byte doğrulayabilsin diye internal.
     */
    internal fun yuv420ToNv21(image: Image, width: Int, height: Int): ByteArray {
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

        // B6: U ve V için ayrı stride/pixelStride; U bounds uBuf, V bounds vBuf.
        val uRowStride = uPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vRowStride = vPlane.rowStride
        val vPixelStride = vPlane.pixelStride
        var uvOffset = width * height

        // UV Interleaved (NV21 formatında V önce, U sonra gelir)
        for (row in 0 until height / 2) {
            for (col in 0 until width / 2) {
                val vIdx = row * vRowStride + col * vPixelStride
                val uIdx = row * uRowStride + col * uPixelStride
                val vVal = if (vIdx < vBuf.capacity()) vBuf.get(vIdx) else 0.toByte()
                val uVal = if (uIdx < uBuf.capacity()) uBuf.get(uIdx) else 0.toByte()
                nv21[uvOffset++] = vVal
                nv21[uvOffset++] = uVal
            }
        }

        return nv21
    }

    /**
     * YUV_420_888 → NV21 → JPEG.
     *
     * NV21 düzeni [yuv420ToNv21]'de üretilir; kodlama `YuvImage.compressToJpeg` ile yapılır.
     * Testability: birim testler doğrudan çağırabilsin diye internal.
     */
    internal fun yuv420ToJpeg(image: Image, width: Int, height: Int): ByteArray {
        val nv21 = yuv420ToNv21(image, width, height)

        val yuvImage = android.graphics.YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        val compressed = yuvImage.compressToJpeg(
            android.graphics.Rect(0, 0, width, height),
            92,
            out,
        )
        if (!compressed) {
            throw IllegalStateException("compressToJpeg failed for ${width}x$height")
        }
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
        // F1: SIRALI kapanış — session.onClosed BEKLENEREK yüzey yarışı önlenir.
        // Honor HAL'inde `endConfigure:905 ... Unsupported set of inputs/outputs` hatası,
        // session onClosed gelmeden ImageReader surface'ı yok edilirse oluşur.
        // Sıra: captureSession → imageReader → cameraDevice.
        closeInOrder(
            teardown = object : AuxResourceTeardown {
                override fun closeSession(timeoutMs: Long): Boolean {
                    val session = captureSession ?: return true
                    captureSession = null
                    val onClosed = CountDownLatch(1)
                    // MEDIUM-1: latch yalnızca BEKLENEN session'ın onClosed'u ile sayılır.
                    // Önceki tek global `{ onClosed.countDown() }` bağlama, A session'ının
                    // geç onClosed'u B session'ı kapanırken ulaşırsa B'nin latch'ini erken
                    // sayabiliyordu (reader/device henüz hazır değilken kapanıyordu).
                    // Per-session identity (`s === session`) ile stale callback'ler yok sayılır.
                    // Not: onClosed içinde `s === captureSession` kontrolü işe YARAMAZ çünkü
                    // burada captureSession alanı zaten null yapıldı; kontrol beklenen session'a
                    // (yerel değişken) karşı yapılır.
                    bindSessionClosedLatch(onClosed, session)
                    return try {
                        // Asenkron: onClosed handler thread'de tetiklenir, bu thread (IO)
                        // latch.await ile bekler. Kilitlenme yok: onClosed gelmezse timeout.
                        session.close()
                        val closed = onClosed.await(timeoutMs, TimeUnit.MILLISECONDS)
                        if (closed) {
                            Log.d(TAG, "[$cameraId] session kapanışı onClosed ile onaylandı")
                        } else {
                            Log.w(TAG, "[$cameraId] session onClosed $timeoutMs ms içinde gelmedi (zaman aşımı) — devam")
                        }
                        closed
                    } catch (e: Throwable) {
                        Log.w(TAG, "[$cameraId] session.close() failed: ${e.message}")
                        false
                    } finally {
                        onSessionClosedListener = null
                    }
                }

                override fun closeReader() {
                    try {
                        imageReader?.let {
                            runCatching { it.close() }
                                .onFailure { e -> Log.w(TAG, "imageReader.close() failed: ${e.message}") }
                        }
                        imageReader = null
                    } catch (e: Throwable) {
                        Log.w(TAG, "imageReader close threw: ${e.message}")
                    }
                }

                override fun closeDevice() {
                    try {
                        cameraDevice?.let { cam ->
                            runCatching { cam.close() }
                                .onFailure { e -> Log.w(TAG, "cameraDevice.close() failed: ${e.message}") }
                        }
                        cameraDevice = null
                    } catch (e: Throwable) {
                        Log.w(TAG, "cameraDevice close threw: ${e.message}")
                    }
                }
            }
        )

        // Handler thread'in kapanışı 500ms ertelenir: capture/close callbacks hâlâ
        // bu thread'de dönüyor olabilir. Mevcut davranış korunur.
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
