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

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
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
    private external fun processFrameNative(
        depthDirectBuf: java.nio.ByteBuffer, depthRowStride: Int, depthPixelStride: Int,
        confDirectBuf: java.nio.ByteBuffer?, confRowStride: Int, confPixelStride: Int,
        yDirectBuf: java.nio.ByteBuffer?, uDirectBuf: java.nio.ByteBuffer?, vDirectBuf: java.nio.ByteBuffer?,
        yRowStride: Int, uvRowStride: Int, uvPixelStride: Int,
        cameraToWorld: FloatArray?,
        fx: Float, fy: Float, cx: Float, cy: Float,
        width: Int, height: Int,
        imgW: Int, imgH: Int,
        isScanning: Boolean,
        outDepthPixels: IntArray?,
        outSegmentedPixels: IntArray?
    ): Float
    private external fun setTargetObjectROINative(normX: Float, normY: Float)
    private external fun clearTargetObjectROINative()
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
    private var segmentedBitmap by mutableStateOf<Bitmap?>(null)
    private var depthPixels: IntArray? = null
    private var segmentedPixels: IntArray? = null
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
            val depthBuffer = depthPlanes.buffer
            val depthRowStride = depthPlanes.rowStride
            val depthPixelStride = depthPlanes.pixelStride

            val confBuffer = confidenceImage?.planes?.get(0)?.buffer
            val confRowStride = confidenceImage?.planes?.get(0)?.rowStride ?: 0
            val confPixelStride = confidenceImage?.planes?.get(0)?.pixelStride ?: 0

            val cameraImage = try { frame.acquireCameraImage() } catch(e: Exception) { null }

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

            var cameraToWorldMatrix: FloatArray? = null
            var fx = 0f; var fy = 0f; var cx = 0f; var cy = 0f

            if (isScanning) {
                val intrinsics = frame.camera.imageIntrinsics
                fx = intrinsics.focalLength[0]
                fy = intrinsics.focalLength[1]
                cx = intrinsics.principalPoint[0]
                cy = intrinsics.principalPoint[1]

                cameraToWorldMatrix = FloatArray(16)
                frame.camera.pose.toMatrix(cameraToWorldMatrix, 0)
            }

            var dPix: IntArray? = null
            var sPix: IntArray? = null

            val currentTime = System.currentTimeMillis()
            if (currentTime - lastPreviewUpdateTime > 100) {
                lastPreviewUpdateTime = currentTime
                if (depthPixels == null || depthPixels!!.size != width * height) {
                    depthPixels = IntArray(width * height)
                    segmentedPixels = IntArray(width * height)
                }
                dPix = depthPixels
                sPix = segmentedPixels
            }

            // Zero-Copy Native İşleme
            val avgDist = processFrameNative(
                depthBuffer, depthRowStride, depthPixelStride,
                confBuffer, confRowStride, confPixelStride,
                yBuf, uBuf, vBuf,
                yRowStride, uvRowStride, uvPixelStride,
                cameraToWorldMatrix,
                fx, fy, cx, cy,
                width, height,
                imgW, imgH,
                isScanning,
                dPix, sPix
            )

            depthImage.close()
            confidenceImage?.close()
            cameraImage?.close()

            if (dPix != null) {
                val rawDBmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                rawDBmp.setPixels(dPix, 0, width, 0, 0, width, height)

                val rawSBmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                rawSBmp.setPixels(sPix!!, 0, width, 0, 0, width, height)

                // Dikey (Portrait) ekran konumuna uyarlamak için 90 derece döndür
                val matrix = android.graphics.Matrix().apply { postRotate(90f) }
                val rotatedDBmp = Bitmap.createBitmap(rawDBmp, 0, 0, width, height, matrix, true)
                val rotatedSBmp = Bitmap.createBitmap(rawSBmp, 0, 0, width, height, matrix, true)

                runOnUiThread {
                    depthBitmap = rotatedDBmp
                    segmentedBitmap = rotatedSBmp
                }
            }

            if (currentTime - lastUiUpdateTime > 500) {
                lastUiUpdateTime = currentTime
                val currentTotal = getAccumulatedPointCount()
                runOnUiThread {
                    pointCount = currentTotal
                    centerDistance = avgDist
                }
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
                    color = Color.Black
                ) {
                    val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        display?.rotation ?: Surface.ROTATION_0
                    } else {
                        @Suppress("DEPRECATION")
                        windowManager.defaultDisplay.rotation
                    }

                    val glSurfaceView = remember {
                        GLSurfaceView(this).apply {
                            preserveEGLContextOnPause = true
                            setEGLContextClientVersion(2)
                            val renderer = CameraRenderer(arSession!!) { frame ->
                                onARCoreFrame(frame)
                            }
                            renderer.displayRotation = rotation
                            setRenderer(renderer)
                            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                        }
                    }

                    ScannerUI(
                        isEngineReady = isEngineReady,
                        isScanning = isScanning,
                        pointCount = pointCount,
                        trackingState = trackingStateString,
                        depthBitmap = depthBitmap,
                        segmentedBitmap = segmentedBitmap,
                        centerDistance = centerDistance,
                        glSurfaceView = glSurfaceView,
                        onStartScan = {
                            isScanning = true
                            clearTemporalBuffer()
                            Toast.makeText(this@MainActivity, "Tarama başlatıldı.", Toast.LENGTH_SHORT).show()
                        },
                        onStopScan = {
                            isScanning = false
                            Toast.makeText(this@MainActivity, "Tarama durduruldu.", Toast.LENGTH_SHORT).show()
                        },
                        onClearScan = {
                            isScanning = false
                            clearAccumulatedPoints()
                            clearTargetObjectROINative()
                            pointCount = 0
                            Toast.makeText(this@MainActivity, "Veriler temizlendi.", Toast.LENGTH_SHORT).show()
                        },
                        onExportMesh = {
                            val outputPath = "${externalCacheDir?.absolutePath}/scan_model.obj"
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
                            val w = 64; val h = 64; val size = w * h
                            val dummyArDepth = FloatArray(size) { 1.5f }
                            val dummyArConf = FloatArray(size) { 0.8f }
                            val dummyStereoDepth = FloatArray(size) { 1.48f }
                            val dummyRgb = ByteArray(size * 3) { 128.toByte() }
                            val output = FloatArray(size)
                            fuseDepthMapsNative(dummyArDepth, dummyArConf, dummyStereoDepth, dummyRgb, output, w, h)
                            Toast.makeText(this@MainActivity, "Füzyon Tamamlandı", Toast.LENGTH_SHORT).show()
                        },
                        onShowPreview3D = {
                            val pts = getAccumulatedPoints()
                            if (pts.isNotEmpty()) {
                                accumulatedPointsArray = pts
                                show3dPreviewDialog = true
                            } else {
                                Toast.makeText(this@MainActivity, "Önizlenecek nokta yok!", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onSetTargetROI = { normU, normV ->
                            setTargetObjectROINative(normU, normV)
                        }
                    )

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
    segmentedBitmap: Bitmap?,
    centerDistance: Float,
    glSurfaceView: GLSurfaceView,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onClearScan: () -> Unit,
    onExportMesh: () -> Unit,
    onTestFusion: () -> Unit,
    onShowPreview3D: () -> Unit,
    onSetTargetROI: (Float, Float) -> Unit
) {
    val isTracking = trackingState.startsWith("Aktif")
    var roiTargetPoint by remember { mutableStateOf<Offset?>(null) }

    // Tarama animasyonu
    val infiniteTransition = rememberInfiniteTransition(label = "scan_pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(800, easing = FastOutSlowInEasing), RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    roiTargetPoint = offset
                    val normU = offset.x / size.width.toFloat()
                    val normV = offset.y / size.height.toFloat()
                    onSetTargetROI(normU, normV)
                }
            }
    ) {

        // ── 3 KAMERA PRO HİBRİT PANEL DÜZENİ ───────────────────
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 65.dp, bottom = 12.dp, start = 4.dp, end = 60.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // ÜST SIRA (2 EŞİT KART: RGB KAMERA vs DERİNLİK MASKESİ)
            Row(
                modifier = Modifier
                    .weight(1.3f)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // PANEL 1: ANA RGB KAMERA (ARCore GLSurfaceView)
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.Black),
                    border = BorderStroke(1.5.dp, Color(0xFF00FFCC).copy(alpha = 0.8f))
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        AndroidView(
                            factory = { glSurfaceView },
                            modifier = Modifier.fillMaxSize()
                        )
                        Surface(
                            color = Color.Black.copy(alpha = 0.6f),
                            shape = RoundedCornerShape(bottomEnd = 12.dp),
                            modifier = Modifier.align(Alignment.TopStart)
                        ) {
                            Text(
                                "📷 ANA KAMERA (RGB)",
                                color = Color(0xFF00FFCC),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                // PANEL 2: CANLI ODAK & DERİNLİK MASKESİ
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF111116)),
                    border = BorderStroke(1.5.dp, Color(0xFFFF3366).copy(alpha = 0.8f))
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        depthBitmap?.let { bmp ->
                            androidx.compose.foundation.Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = null,
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } ?: Text(
                            "Derinlik Haritası Bekleniyor...",
                            color = Color.White.copy(alpha = 0.4f),
                            fontSize = 10.sp
                        )

                        Surface(
                            color = Color.Black.copy(alpha = 0.6f),
                            shape = RoundedCornerShape(bottomEnd = 12.dp),
                            modifier = Modifier.align(Alignment.TopStart)
                        ) {
                            Text(
                                "🎯 ODAK & DERİNLİK MASKESİ",
                                color = Color(0xFFFF3366),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }

            // ALT SIRA: PANEL 3 (SEÇİLİ OBJE İZOLASYONU - ARKA PLAN SİLİNMİŞ)
            Card(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF111116)),
                border = BorderStroke(1.5.dp, Color(0xFF007AFF).copy(alpha = 0.8f))
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    segmentedBitmap?.let { bmp ->
                        androidx.compose.foundation.Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = null,
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } ?: Text(
                        "Obje İzolasyon Akışı Bekleniyor...",
                        color = Color.White.copy(alpha = 0.4f),
                        fontSize = 11.sp
                    )

                    Surface(
                        color = Color.Black.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(bottomEnd = 12.dp),
                        modifier = Modifier.align(Alignment.TopStart)
                    ) {
                        Text(
                            "🔍 SEÇİLİ OBJE İZOLASYONU (ARKA PLAN SİLİNDİ)",
                            color = Color(0xFF007AFF),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }

        // ── Obje Seçim İmleci (ROI Focus Target Indicator) ─────────
        roiTargetPoint?.let { pos ->
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(
                    color = Color(0xFF00FFCC),
                    radius = 28f,
                    center = pos,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f)
                )
                drawCircle(
                    color = Color(0xFFFF3366),
                    radius = 8f,
                    center = pos
                )
            }
        }

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
