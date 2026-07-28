package com.magicv3.scanner3d

import android.os.Bundle
import android.os.Build
import android.view.Surface
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.border
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.activity.result.contract.ActivityResultContracts
import com.google.ar.core.Session
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.TrackingState
import androidx.compose.ui.viewinterop.AndroidView
import android.opengl.GLSurfaceView
import androidx.compose.ui.graphics.Color
import android.media.Image
import java.nio.ByteBuffer
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha







class MainActivity : ComponentActivity() {

    private external fun clearAccumulatedPoints()
    private external fun getAccumulatedPointCount(): Int
    private external fun getAccumulatedPoints(): FloatArray
    private external fun addPointsToAccumulator(x: FloatArray, y: FloatArray, z: FloatArray, nx: FloatArray, ny: FloatArray, nz: FloatArray, r: ByteArray, g: ByteArray, b: ByteArray, size: Int)
    private external fun initNativeEngine(): Boolean
    private external fun bindThreadAffinity(roleIndex: Int)

    private external fun exportPointCloudMesh(filePath: String): Boolean
    private external fun fuseDepthMapsNative(
        arcoreDepth: FloatArray, arcoreConf: FloatArray,
        stereoDepth: FloatArray, rgbImg: ByteArray,
        output: FloatArray, width: Int, height: Int
    )
    // NPU Parazit Temizleme — Temporal buffer sıfırlama
    private external fun clearTemporalBuffer()
    // NPU SOR — Nokta bulutu istatistiksel parazit temizleyici
    private external fun denoisePointCloudNative(): Int


    companion object {
        init {
            System.loadLibrary("magic3d_engine")
        }
    }

    private var arSession: Session? = null
    private var hasCameraPermission by mutableStateOf(false)
    private var isScanning by mutableStateOf(false)
    private var pointCount by mutableStateOf(0)
    private var lastUiUpdateTime = 0L
    private var trackingStateString by mutableStateOf("Başlatılıyor...")
    private var depthBitmap by mutableStateOf<Bitmap?>(null)
    private var lastPreviewUpdateTime = 0L
    private var show3dPreviewDialog by mutableStateOf(false)
    private var accumulatedPointsArray by mutableStateOf(FloatArray(0))
    private var centerDistance by mutableStateOf(0.0f)







    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        hasCameraPermission = isGranted
        if (isGranted) {
            setupARSession()
        } else {
            Toast.makeText(this, "Kamera izni gerekiyor!", Toast.LENGTH_LONG).show()
        }
    }

    private fun checkCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            hasCameraPermission = true
            setupARSession()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun setupARSession() {
        try {
            if (arSession == null) {
                val availability = com.google.ar.core.ArCoreApk.getInstance().checkAvailability(this)
                if (availability.isTransient) {
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ setupARSession() }, 200)
                    return
                }
                if (!availability.isSupported) {
                    Toast.makeText(this, "Bu cihaz ARCore desteklemiyor.", Toast.LENGTH_LONG).show()
                    return
                }
                
                arSession = Session(this)
                val config = Config(arSession)
                if (arSession!!.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                    config.depthMode = Config.DepthMode.AUTOMATIC
                } else {
                    config.depthMode = Config.DepthMode.DISABLED
                }
                arSession!!.configure(config)
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "ARCore setup exception", e)
            Toast.makeText(this, "ARCore başlatılamadı: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        if (hasCameraPermission) {
            try {
                arSession?.resume()
            } catch (e: Exception) {
                Toast.makeText(this, "ARCore resume hatası: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        arSession?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        arSession?.close()
        arSession = null
    }

    private fun onARCoreFrame(frame: Frame) {
        try {
            val stateStr = when (frame.camera.trackingState) {
                TrackingState.TRACKING -> "Aktif (Takip Ediliyor)"
                TrackingState.PAUSED -> "Hazırlanıyor (Cihazı yavaşça hareket ettirin)"
                TrackingState.STOPPED -> "Durduruldu"
                else -> "Bilinmiyor"
            }
            runOnUiThread {
                trackingStateString = stateStr
            }

            if (frame.camera.trackingState != TrackingState.TRACKING) {
                runOnUiThread {
                    depthBitmap = null
                }
                return
            }

            val depthImage = frame.acquireDepthImage16Bits()
            val confidenceImage = try {
                frame.acquireRawDepthConfidenceImage()
            } catch (e: Exception) {
                null
            }

            val width = depthImage.width
            val height = depthImage.height

            val depthPlanes = depthImage.planes[0]
            val depthBuffer = depthPlanes.getBuffer()
            depthBuffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val depthRowStride = depthPlanes.getRowStride()
            val depthPixelStride = depthPlanes.getPixelStride()

            val arcoreDepth = FloatArray(width * height)
            val arcoreConf = FloatArray(width * height)

            if (confidenceImage != null) {
                val confPlanes = confidenceImage.planes[0]
                val confBuffer = confPlanes.getBuffer()
                val confRowStride = confPlanes.getRowStride()
                val confPixelStride = confPlanes.getPixelStride()

                for (y in 0 until height) {
                    for (x in 0 until width) {
                        val idx = y * width + x

                        val depthIndex = y * depthRowStride + x * depthPixelStride
                        val mm = depthBuffer.getShort(depthIndex).toInt() and 0xFFFF
                        arcoreDepth[idx] = mm.toFloat() / 1000.0f

                        val confIndex = y * confRowStride + x * confPixelStride
                        val c = confBuffer.get(confIndex).toInt() and 0xFF
                        arcoreConf[idx] = c.toFloat() / 255.0f
                    }
                }
                confidenceImage.close()
            } else {
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        val idx = y * width + x

                        val depthIndex = y * depthRowStride + x * depthPixelStride
                        val mm = depthBuffer.getShort(depthIndex).toInt() and 0xFFFF
                        arcoreDepth[idx] = mm.toFloat() / 1000.0f

                        arcoreConf[idx] = 1.0f
                    }
                }
            }

            depthImage.close()

            val cameraImage = try { frame.acquireCameraImage() } catch(e: Exception) { null }
            val dummyStereoDepth = FloatArray(width * height)
            val dummyRgb = ByteArray(width * height * 3) // For NPU Engine

            val fusedOutput = FloatArray(width * height)

            fuseDepthMapsNative(arcoreDepth, arcoreConf, dummyStereoDepth, dummyRgb, fusedOutput, width, height)

            if (isScanning) {
                val intrinsics = frame.camera.imageIntrinsics
                val focalLength = intrinsics.focalLength
                val principalPoint = intrinsics.principalPoint
                val fx = focalLength[0]
                val fy = focalLength[1]
                val cx = principalPoint[0]
                val cy = principalPoint[1]

                val cameraPose = frame.camera.pose
                val cameraToWorldMatrix = FloatArray(16)
                cameraPose.toMatrix(cameraToWorldMatrix, 0)

                val step = 8 // Performans için her 8 pikselde bir örnekle
                val xList = ArrayList<Float>()
                val yList = ArrayList<Float>()
                val zList = ArrayList<Float>()
                val nxList = ArrayList<Float>()
                val nyList = ArrayList<Float>()
                val nzList = ArrayList<Float>()
                val rList = ArrayList<Byte>()
                val gList = ArrayList<Byte>()
                val bList = ArrayList<Byte>()

                var yBuf: java.nio.ByteBuffer? = null
                var uBuf: java.nio.ByteBuffer? = null
                var vBuf: java.nio.ByteBuffer? = null
                var yRowStride = 0
                var uvRowStride = 0
                var uvPixelStride = 0
                var imgW = 0
                var imgH = 0

                if (cameraImage != null) {
                    imgW = cameraImage.width
                    imgH = cameraImage.height
                    yBuf = cameraImage.planes[0].buffer
                    uBuf = cameraImage.planes[1].buffer
                    vBuf = cameraImage.planes[2].buffer
                    yRowStride = cameraImage.planes[0].rowStride
                    uvRowStride = cameraImage.planes[1].rowStride
                    uvPixelStride = cameraImage.planes[1].pixelStride
                }

                for (py in 0 until height step step) {
                    for (px in 0 until width step step) {
                        val idx = py * width + px
                        val depth = fusedOutput[idx]

                        if (depth > 0.1f && depth < 5.0f) {
                            val xCam = (px - cx) * depth / fx
                            val yCam = (py - cy) * depth / fy
                            val zCam = depth

                            val xWorld = cameraToWorldMatrix[0] * xCam + cameraToWorldMatrix[4] * yCam + cameraToWorldMatrix[8] * zCam + cameraToWorldMatrix[12]
                            val yWorld = cameraToWorldMatrix[1] * xCam + cameraToWorldMatrix[5] * yCam + cameraToWorldMatrix[9] * zCam + cameraToWorldMatrix[13]
                            val zWorld = cameraToWorldMatrix[2] * xCam + cameraToWorldMatrix[6] * yCam + cameraToWorldMatrix[10] * zCam + cameraToWorldMatrix[14]

                            // Normal hesaplama (Gradient tabanlı)
                            val rightDepth = if (px + step < width) fusedOutput[py * width + px + step] else depth
                            val downDepth = if (py + step < height) fusedOutput[(py + step) * width + px] else depth

                            val p1x = (px + step - cx) * rightDepth / fx
                            val p1y = yCam
                            val p1z = rightDepth

                            val p2x = xCam
                            val p2y = (py + step - cy) * downDepth / fy
                            val p2z = downDepth

                            val v1x = p1x - xCam
                            val v1y = p1y - yCam
                            val v1z = p1z - zCam

                            val v2x = p2x - xCam
                            val v2y = p2y - yCam
                            val v2z = p2z - zCam

                            var nxCam = v1y * v2z - v1z * v2y
                            var nyCam = v1z * v2x - v1x * v2z
                            var nzCam = v1x * v2y - v1y * v2x

                            val len = kotlin.math.sqrt(nxCam * nxCam + nyCam * nyCam + nzCam * nzCam)
                            if (len > 0.0001f) {
                                nxCam /= len; nyCam /= len; nzCam /= len
                            } else {
                                nxCam = 0f; nyCam = 0f; nzCam = -1f
                            }

                            val nxWorld = cameraToWorldMatrix[0] * nxCam + cameraToWorldMatrix[4] * nyCam + cameraToWorldMatrix[8] * nzCam
                            val nyWorld = cameraToWorldMatrix[1] * nxCam + cameraToWorldMatrix[5] * nyCam + cameraToWorldMatrix[9] * nzCam
                            val nzWorld = cameraToWorldMatrix[2] * nxCam + cameraToWorldMatrix[6] * nyCam + cameraToWorldMatrix[10] * nzCam

                            var r = 200
                            var g = 200
                            var b = 200

                            if (cameraImage != null) {
                                // Map depth coords to camera coords
                                val camX = (px.toFloat() / width.toFloat() * imgW).toInt().coerceIn(0, imgW - 1)
                                val camY = (py.toFloat() / height.toFloat() * imgH).toInt().coerceIn(0, imgH - 1)

                                val yIdx = camY * yRowStride + camX
                                val uvIdx = (camY / 2) * uvRowStride + (camX / 2) * uvPixelStride

                                val yVal = (yBuf!!.get(yIdx).toInt() and 0xFF).toFloat()
                                val uVal = (uBuf!!.get(uvIdx).toInt() and 0xFF).toFloat() - 128f
                                val vVal = (vBuf!!.get(uvIdx).toInt() and 0xFF).toFloat() - 128f

                                r = (yVal + 1.370705f * vVal).toInt().coerceIn(0, 255)
                                g = (yVal - 0.337633f * uVal - 0.698001f * vVal).toInt().coerceIn(0, 255)
                                b = (yVal + 1.732446f * uVal).toInt().coerceIn(0, 255)
                            }

                            xList.add(xWorld)
                            yList.add(yWorld)
                            zList.add(zWorld)
                            nxList.add(nxWorld)
                            nyList.add(nyWorld)
                            nzList.add(nzWorld)
                            rList.add(r.toByte())
                            gList.add(g.toByte())
                            bList.add(b.toByte())
                        }
                    }
                }

                if (xList.isNotEmpty()) {
                    val size = xList.size
                    addPointsToAccumulator(xList.toFloatArray(), yList.toFloatArray(), zList.toFloatArray(), nxList.toFloatArray(), nyList.toFloatArray(), nzList.toFloatArray(), rList.toByteArray(), gList.toByteArray(), bList.toByteArray(), size)
                    
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastUiUpdateTime > 500) {
                        lastUiUpdateTime = currentTime
                        val currentTotal = getAccumulatedPointCount()
                        runOnUiThread {
                            pointCount = currentTotal
                        }
                    }
                }
            }

            cameraImage?.close()

            val currentMs = System.currentTimeMillis()
            if (currentMs - lastPreviewUpdateTime > 100) {
                lastPreviewUpdateTime = currentMs
                try {
                    val cx = width / 2
                    val cy = height / 2
                    var sum = 0.0f
                    var count = 0
                    for (dy in -3..3) {
                        for (dx in -3..3) {
                            val px = cx + dx
                            val py = cy + dy
                            if (px in 0 until width && py in 0 until height) {
                                val d = fusedOutput[py * width + px]
                                if (d > 0.1f && d < 5.0f) {
                                    sum += d
                                    count++
                                }
                            }
                        }
                    }
                    val avgDist = if (count > 0) sum / count else 0.0f
                    runOnUiThread {
                        centerDistance = avgDist
                    }

                    val previewBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    val pixels = IntArray(width * height)
                    for (i in 0 until width * height) {
                        val d = fusedOutput[i]
                        if (d <= 0.1f) {
                            pixels[i] = AndroidColor.BLACK
                        } else {
                            val norm = ((d - 0.1f) / (3.0f - 0.1f)).coerceIn(0.0f, 1.0f)
                            val hue = (1.0f - norm) * 240.0f
                            pixels[i] = AndroidColor.HSVToColor(floatArrayOf(hue, 1.0f, 1.0f))
                        }
                    }
                    previewBitmap.setPixels(pixels, 0, width, 0, 0, width, height)
                    runOnUiThread {
                        depthBitmap = previewBitmap
                    }
                } catch (e: Exception) {
                    android.util.Log.e("MainActivity", "Preview render error", e)
                }
            }

            if (System.currentTimeMillis() % 1000 < 50) {
                android.util.Log.i("MainActivity", "ARCore Depth Map Fused: w=$width, h=$height, first_pixel_val=${fusedOutput[0]}m")
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "ARCore Frame exception", e)
            runOnUiThread {
                depthBitmap = null
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Bind camera capture thread to Efficiency Cores (Cortex-A520)
        bindThreadAffinity(0)

        val isEngineReady = initNativeEngine()
        checkCameraPermission()

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Transparent
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        // OpenGL Camera Preview — ekran dolduran, aspect-correct
                        if (hasCameraPermission && arSession != null) {
                            // Modern API: Android R+ WindowMetrics, altı için eski yol
                            val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                display?.rotation ?: Surface.ROTATION_0
                            } else {
                                @Suppress("DEPRECATION")
                                windowManager.defaultDisplay.rotation
                            }
                            AndroidView(
                                factory = { context ->
                                    GLSurfaceView(context).apply {
                                        preserveEGLContextOnPause = true
                                        setEGLContextClientVersion(2)
                                        val renderer = CameraRenderer(arSession!!) { frame ->
                                            onARCoreFrame(frame)
                                        }
                                        renderer.displayRotation = rotation
                                        setRenderer(renderer)
                                        renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                                    }
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        // Transparent Surface for UI layout
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = Color.Transparent
                        ) {
                            ScannerUI(
                                isEngineReady = isEngineReady,
                                isScanning = isScanning,
                                pointCount = pointCount,
                                trackingState = trackingStateString,
                                depthBitmap = depthBitmap,
                                centerDistance = centerDistance,
                                onStartScan = {
                                    isScanning = true
                                    clearTemporalBuffer() // Yeni tarama: NPU temporal buffer'sıfırla
                                    Toast.makeText(this@MainActivity, "Tarama başlatıldı. Cihazı yavaşça hareket ettirin.", Toast.LENGTH_SHORT).show()
                                },
                                onStopScan = {
                                    isScanning = false
                                    Toast.makeText(this@MainActivity, "Tarama durduruldu.", Toast.LENGTH_SHORT).show()
                                },
                                onClearScan = {
                                    isScanning = false
                                    clearAccumulatedPoints()
                                    pointCount = 0
                                    Toast.makeText(this@MainActivity, "Tarama verileri temizlendi.", Toast.LENGTH_SHORT).show()
                                },
                                onExportMesh = {
                                    val outputPath = "${externalCacheDir?.absolutePath}/scan_model.ply"
                                    // NPU-SOR: Mesh kaydetmeden önce nokta bulutunu temizle
                                    val removedCount = denoisePointCloudNative()
                                    if (removedCount > 0) {
                                        Toast.makeText(this@MainActivity, "NPU-SOR: $removedCount parazit nokta temizlendi.", Toast.LENGTH_SHORT).show()
                                    }
                                    val success = exportPointCloudMesh(outputPath)
                                    if (success) {
                                        Toast.makeText(this@MainActivity, "Model kaydedildi: $outputPath", Toast.LENGTH_LONG).show()
                                    } else {
                                        Toast.makeText(this@MainActivity, "Mesh kaydetme başarısız!", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                onTestFusion = {
                                    val w = 64
                                    val h = 64
                                    val size = w * h
                                    val dummyArDepth = FloatArray(size) { 1.5f }
                                    val dummyArConf = FloatArray(size) { 0.8f }
                                    val dummyStereoDepth = FloatArray(size) { 1.48f }
                                    val dummyRgb = ByteArray(size * 3) { 128.toByte() }
                                    val output = FloatArray(size)

                                    fuseDepthMapsNative(dummyArDepth, dummyArConf, dummyStereoDepth, dummyRgb, output, w, h)
                                    val centerVal = output[w * h / 2 + w / 2]
                                    Toast.makeText(this@MainActivity, "Derinlik Füzyonu Tamamlandı (${centerVal}m)", Toast.LENGTH_SHORT).show()
                                },
                                onShowPreview3D = {
                                    val pts = getAccumulatedPoints()
                                    if (pts.isNotEmpty()) {
                                        accumulatedPointsArray = pts
                                        show3dPreviewDialog = true
                                    } else {
                                        Toast.makeText(this@MainActivity, "Önizlenecek nokta yok!", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            )
                        }

                        if (show3dPreviewDialog) {
                            PointCloudPreviewDialog(
                                points = accumulatedPointsArray,
                                onDismiss = { show3dPreviewDialog = false }
                            )
                        }
                    }
                }
            }
        }
    }
}


// ═══════════════════════════════════════════════════════
//  DistanceGuide — Sol kenar mesafe göstergesi
// ═══════════════════════════════════════════════════════
@Composable
fun DistanceGuide(distance: Float) {
    val barColor = when {
        distance <= 0f    -> Color(0xFF555555)
        distance < 0.3f   -> Color(0xFFFF3366)
        distance <= 0.8f  -> Color(0xFF00FFCC)
        else              -> Color(0xFF007AFF)
    }
    val label = when {
        distance <= 0f   -> "---"
        distance < 0.3f  -> "YAKİN"
        distance <= 0.8f -> "MÜKEMMEL"
        else             -> "UZAK"
    }

    Column(
        modifier = Modifier
            .width(52.dp)
            .fillMaxHeight(0.55f)
            .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(26.dp))
            .border(1.dp, barColor.copy(alpha = 0.4f), RoundedCornerShape(26.dp))
            .padding(vertical = 14.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            "▲", color = Color(0xFF007AFF), fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .width(8.dp)
                .padding(vertical = 6.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0xFF007AFF),
                            Color(0xFF00FFCC),
                            Color(0xFFFF3366)
                        )
                    )
                )
        ) {
            val norm = ((distance - 0.1f) / (1.5f - 0.1f)).coerceIn(0f, 1f)
            val bias = 2f * (1f - norm) - 1f
            Box(
                modifier = Modifier
                    .align(BiasAlignment(0f, bias))
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .border(2.dp, barColor, CircleShape)
            )
        }

        Text(
            "▼", color = Color(0xFFFF3366), fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(4.dp))

        Text(
            text = if (distance <= 0f) "---" else String.format("%.2f", distance),
            color = barColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Text(
            text = "m",
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 9.sp
        )
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .background(barColor.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                .padding(horizontal = 4.dp, vertical = 2.dp)
        ) {
            Text(
                text = label,
                color = barColor,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

// ═══════════════════════════════════════════════════════
//  ScannerUI — Ana HUD arayüzü
// ═══════════════════════════════════════════════════════
@Composable
fun ScannerUI(
    isEngineReady: Boolean,
    isScanning: Boolean,
    pointCount: Int,
    trackingState: String,
    depthBitmap: Bitmap?,
    centerDistance: Float,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onClearScan: () -> Unit,
    onExportMesh: () -> Unit,
    onTestFusion: () -> Unit,
    onShowPreview3D: () -> Unit
) {
    val isTracking = trackingState.startsWith("Aktif")

    // Tarama animasyonu
    val infiniteTransition = rememberInfiniteTransition(label = "scan_pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(800, easing = FastOutSlowInEasing), RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Box(modifier = Modifier.fillMaxSize()) {

        // ── Merkez Crosshair ────────────────────────────────
        Canvas(
            modifier = Modifier
                .size(60.dp)
                .align(Alignment.Center)
                .alpha(if (isScanning) pulseAlpha else 0.5f)
        ) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val arm = size.minDimension * 0.35f
            val gap = size.minDimension * 0.12f
            val stroke = Stroke(width = 2.5f, cap = StrokeCap.Round)
            val col = if (isScanning) android.graphics.Color.parseColor("#00FFCC")
                      else            android.graphics.Color.parseColor("#FFFFFF")
            val composeCol = Color(col)
            // Yatay çizgiler
            drawLine(composeCol, Offset(cx - arm, cy), Offset(cx - gap, cy), strokeWidth = 2.5f)
            drawLine(composeCol, Offset(cx + gap, cy), Offset(cx + arm, cy), strokeWidth = 2.5f)
            // Dikey çizgiler
            drawLine(composeCol, Offset(cx, cy - arm), Offset(cx, cy - gap), strokeWidth = 2.5f)
            drawLine(composeCol, Offset(cx, cy + gap), Offset(cx, cy + arm), strokeWidth = 2.5f)
            // Köşeler
            val r = size.minDimension * 0.22f
            drawArc(composeCol, -90f, 60f, false, Offset(cx - r, cy - r),
                    androidx.compose.ui.geometry.Size(r * 2, r * 2), style = stroke)
        }

        // ── Üst Durum Barı ──────────────────────────────────
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 16.dp)
                .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(32.dp))
                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(32.dp))
                .padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Tracking LED
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(
                        if (isTracking) Color(0xFF00FFCC)
                        else Color(0xFFFFCC00)
                    )
                    .alpha(if (isScanning && isTracking) pulseAlpha else 1f)
            )
            Text(
                text = trackingState,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // Ayırıcı
            Box(Modifier.width(1.dp).height(16.dp).background(Color.White.copy(alpha = 0.2f)))
            // Nokta sayısı
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "$pointCount",
                    color = Color(0xFF00FFCC),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                Text("nokta", color = Color.White.copy(alpha = 0.6f), fontSize = 9.sp)
            }
            // Ayırıcı
            Box(Modifier.width(1.dp).height(16.dp).background(Color.White.copy(alpha = 0.2f)))
            // NPU durum
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(8.dp).clip(CircleShape)
                        .background(if (isEngineReady) Color(0xFF00FFCC) else Color(0xFFFF3366))
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    "NPU",
                    color = if (isEngineReady) Color(0xFF00FFCC) else Color(0xFFFF3366),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // ── Sol Mesafe Rehberi ───────────────────────────────
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 12.dp)
        ) {
            DistanceGuide(distance = centerDistance)
        }

        // ── Sağ Kontrol Paneli ──────────────────────────────
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 12.dp)
                .background(Color.Black.copy(alpha = 0.68f), RoundedCornerShape(32.dp))
                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(32.dp))
                .padding(vertical = 20.dp, horizontal = 10.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // BAŞLA / DURDUR
            val scanBtnColor = if (isScanning) Color(0xFFFF3366) else Color(0xFF00FFCC)
            Box(
                modifier = Modifier
                    .size(58.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            listOf(scanBtnColor, scanBtnColor.copy(alpha = 0.6f))
                        )
                    )
                    .border(2.dp, Color.White.copy(alpha = 0.3f), CircleShape)
                    .clickable(enabled = isEngineReady) {
                        if (isScanning) onStopScan() else onStartScan()
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isScanning) "⏸" else "▶",
                    color = Color.Black,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Divider
            Box(Modifier.width(30.dp).height(1.dp).background(Color.White.copy(alpha = 0.15f)))

            // 3D ÖNİZLEME
            ScanActionButton(
                label = "3D",
                emoji = null,
                enabled = !isScanning && pointCount > 0,
                activeColor = Color(0xFFFF9900),
                onClick = onShowPreview3D
            )

            // KAYDET
            ScanActionButton(
                label = null,
                emoji = "💾",
                enabled = !isScanning && pointCount > 0,
                activeColor = Color(0xFF007AFF),
                onClick = onExportMesh
            )

            // SİL
            ScanActionButton(
                label = null,
                emoji = "🗑",
                enabled = pointCount > 0,
                activeColor = Color(0xFFFF4444),
                onClick = onClearScan
            )
        }

        // ── Sol Alt: Derinlik Feed ───────────────────────────
        Card(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 12.dp, bottom = 20.dp)
                .width(148.dp)
                .height(100.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
            colors = CardDefaults.cardColors(containerColor = Color.Black),
            border = BorderStroke(
                1.5.dp,
                if (depthBitmap != null) Color(0xFF00FFCC).copy(alpha = 0.6f)
                else Color(0xFFFF3366).copy(alpha = 0.5f)
            )
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (depthBitmap != null) {
                    Image(
                        bitmap = depthBitmap.asImageBitmap(),
                        contentDescription = "Derinlik Haritası",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                    // Etiket
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .background(Color.Black.copy(alpha = 0.65f))
                            .padding(horizontal = 7.dp, vertical = 3.dp)
                    ) {
                        Text(
                            "DERINLIK",
                            color = Color(0xFF00FFCC),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    }
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text("⚠️", fontSize = 18.sp)
                        Text(
                            "Sinyal Yok",
                            color = Color(0xFFFF3366),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // ── Sağ Alt: Nokta / Mesafe Bilgisi ────────────────
        if (isScanning) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 12.dp, bottom = 20.dp)
                    .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(16.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Yanip sönen kayıt noktası
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFFF3366))
                            .alpha(pulseAlpha)
                    )
                    Text(
                        "TARANIYOR",
                        color = Color(0xFFFF3366),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                }
            }
        }
    }
}

// ── Yardımcı Buton Composable ─────────────────────────
@Composable
fun ScanActionButton(
    label: String?,
    emoji: String?,
    enabled: Boolean,
    activeColor: Color,
    onClick: () -> Unit
) {
    val bg = if (enabled) activeColor.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.08f)
    val contentColor = if (enabled) Color.White else Color.White.copy(alpha = 0.25f)
    Box(
        modifier = Modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(bg)
            .border(1.dp, if (enabled) activeColor.copy(alpha = 0.5f) else Color.Transparent, CircleShape)
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        when {
            emoji != null -> Text(emoji, fontSize = 18.sp)
            label != null -> Text(
                label,
                color = contentColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

// ═══════════════════════════════════════════════════════
//  PointCloudPreviewDialog — Tam Ekran 3D Önİzleme
// ═══════════════════════════════════════════════════════
@Composable
fun PointCloudPreviewDialog(
    points: FloatArray,
    onDismiss: () -> Unit
) {
    var rotX by remember { mutableStateOf(-0.3f) }
    var rotY by remember { mutableStateOf(0f) }
    var zoom by remember { mutableStateOf(160f) }

    val totalPoints = points.size / 3
    val step = (totalPoints / 3000).coerceAtLeast(1)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0E14))
    ) {
        // 3D Canvas
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoomFactor, _ ->
                        rotY += pan.x * 0.008f
                        rotX -= pan.y * 0.008f
                        zoom = (zoom * zoomFactor).coerceIn(40f, 700f)
                    }
                }
        ) {
            val cx = size.width / 2f
            val cy = size.height / 2f

            val cosX = kotlin.math.cos(rotX.toDouble()).toFloat()
            val sinX = kotlin.math.sin(rotX.toDouble()).toFloat()
            val cosY = kotlin.math.cos(rotY.toDouble()).toFloat()
            val sinY = kotlin.math.sin(rotY.toDouble()).toFloat()

            // Grid zemin
            val gridColor = Color.White.copy(alpha = 0.04f)
            for (i in -10..10) {
                drawLine(gridColor, Offset(0f, cy + i * 40f), Offset(size.width, cy + i * 40f), strokeWidth = 1f)
                drawLine(gridColor, Offset(cx + i * 40f, 0f), Offset(cx + i * 40f, size.height), strokeWidth = 1f)
            }

            for (i in 0 until totalPoints step step) {
                val px = points[i * 3 + 0]
                val py = points[i * 3 + 1]
                val pz = points[i * 3 + 2]

                val y1 = py * cosX - pz * sinX
                val z1 = py * sinX + pz * cosX
                val x2 = px * cosY + z1 * sinY
                val z2 = -px * sinY + z1 * cosY

                val dist = 3.0f
                val projZ = z2 + dist
                if (projZ > 0.2f) {
                    val sx = cx + (x2 * zoom) / projZ
                    val sy = cy - (y1 * zoom) / projZ

                    val t = (pz / 3.0f).coerceIn(0f, 1f)
                    val ptColor = Color(
                        red   = t * 0.9f,
                        green = (1f - t) * 0.9f + 0.1f,
                        blue  = 0.9f - t * 0.4f,
                        alpha = 0.92f
                    )
                    drawCircle(ptColor, radius = 1.8f, center = Offset(sx, sy))
                }
            }
        }

        // Üst Bilgi Barı
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 20.dp)
                .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(24.dp))
                .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(24.dp))
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "💫 3D Önİzleme",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            Box(Modifier.width(1.dp).height(14.dp).background(Color.White.copy(alpha = 0.2f)))
            Text(
                "${totalPoints} nokta  •  ${totalPoints / step} gösterilen",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 11.sp
            )
        }

        // İpUcu
        Text(
            text = "Sürükle: Döndür  •  Kistır: Yakınlaştır",
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 90.dp)
                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 6.dp),
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 11.sp
        )

        // Kapat Butonu
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 28.dp)
                .size(width = 140.dp, height = 48.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(
                    Brush.horizontalGradient(
                        listOf(Color(0xFFFF3366), Color(0xFFFF6633))
                    )
                )
                .clickable { onDismiss() },
            contentAlignment = Alignment.Center
        ) {
            Text(
                "←  Kapat",
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
