package com.magicv3.scanner3d.ui.scan

import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.magicv3.scanner3d.domain.ar.CameraPose
import com.magicv3.scanner3d.domain.ar.DepthSourceState
import com.magicv3.scanner3d.ui.capture.CaptureButton
import com.magicv3.scanner3d.ui.hud.SystemHud

@Composable
fun ScanScreen(
    viewModel: ScanViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current

    // Collect UI state from ViewModel
    val captureState by viewModel.captureState.collectAsStateWithLifecycle()
    val lastCaptureLog by viewModel.lastCaptureLog.collectAsStateWithLifecycle()
    val proControlsEnabled by viewModel.proControlsEnabled.collectAsStateWithLifecycle()
    val isSettingsLocked by viewModel.isSettingsLocked.collectAsStateWithLifecycle()
    val isoValue by viewModel.isoValue.collectAsStateWithLifecycle()
    val shutterFraction by viewModel.shutterFraction.collectAsStateWithLifecycle()
    val focusDistanceValue by viewModel.focusDistanceValue.collectAsStateWithLifecycle()
    val evValue by viewModel.evValue.collectAsStateWithLifecycle()
    val colorTempValue by viewModel.colorTempValue.collectAsStateWithLifecycle()
    val isoRange by viewModel.isoRange.collectAsStateWithLifecycle()
    val evRange by viewModel.evRange.collectAsStateWithLifecycle()
    val evStep by viewModel.evStep.collectAsStateWithLifecycle()
    val multiLensMode by viewModel.multiLensMode.collectAsStateWithLifecycle()
    val showMyScans by viewModel.showMyScans.collectAsStateWithLifecycle()
    val openedSession by viewModel.openedSession.collectAsStateWithLifecycle()
    val zipShareState by viewModel.zipShareState.collectAsStateWithLifecycle()
    val plyExportState by viewModel.plyExportState.collectAsStateWithLifecycle()
    val isThermalThrottled by viewModel.isThermalThrottled.collectAsStateWithLifecycle()
    val currentSocTemp by viewModel.currentSocTemp.collectAsStateWithLifecycle()
    val isThermalWarned by viewModel.isThermalWarned.collectAsStateWithLifecycle()
    val yoloModelLoaded by viewModel.yoloModelLoaded.collectAsStateWithLifecycle()

    // B-3: ARCore kullanılabilirlik durumu (renderer oluşturma öncesi kontrol).
    val arCoreState by viewModel.arCoreState.collectAsStateWithLifecycle()

    // F2: aktif depth kaynağı durumu (dürüst izleme — ArGlRenderer'a bildirilir).
    val depthSourceState by viewModel.depthSourceState.collectAsStateWithLifecycle()

    // AI states
    val aiPreviewMode by viewModel.aiPreviewMode.collectAsStateWithLifecycle()
    val aiStats by viewModel.aiStats.collectAsStateWithLifecycle()
    val depthHeatmap by viewModel.depthHeatmap.collectAsStateWithLifecycle()
    val yoloDetections by viewModel.yoloDetections.collectAsStateWithLifecycle()
    val pointCount by viewModel.pointCount.collectAsStateWithLifecycle()

    val progressState by viewModel.orchestrator.progress.collectAsStateWithLifecycle()
    val ingestionState by viewModel.ingestionQueue.queueState.collectAsStateWithLifecycle()

    var latestCameraPose by remember { mutableStateOf<CameraPose?>(null) }

    // B-3: ARCore desteklenmiyorsa AR surface view oluşturulmaz — fallback mesajı gösterilir.
    val arSurfaceView = remember(arCoreState) {
        if (arCoreState == ArCoreAvailabilityState.Available) {
            ArPointCloudSurfaceView(context) { pose ->
                latestCameraPose = pose
            }.also { surface ->
                surface.renderer.onArCoreUnavailable = { reason ->
                    viewModel.reportArCoreUnavailable(reason)
                }
                // F2: renderer depth kaynağı durumunu loglar; bu callback ile üst katmana
                // (denetim izi / gelecekte UI göstergesi) iletir. Sessiz yutma yok.
                surface.renderer.onDepthSourceStateChanged = { state ->
                    Log.d("ScanScreen", "Depth source state: ${state.name}")
                }
            }
        } else {
            null
        }
    }

    // F2: ViewModel hangi depth kaynağının ürettiğini renderer'a bildirir; renderer
    // durumu loglar ve kendi callback'i ile üst katmana iletir. (UI göstergesi şart
    // değildir — loglama + durum alanı yeterli, bkz. ArGlRenderer.updateDepthSourceState.)
    LaunchedEffect(depthSourceState) {
        arSurfaceView?.renderer?.updateDepthSourceState(depthSourceState)
    }

    // O-5: GLSurfaceView onPause/onResume lifecycle'a bağlanır — uygulama arka
    // plana gidince GL thread durur (WHEN_DIRTY + paused = pil tasarrufu), geri
    // gelince render döngüsü yeniden başlar.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(arSurfaceView, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> arSurfaceView?.onPause()
                Lifecycle.Event.ON_RESUME -> arSurfaceView?.onResume()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        if (arSurfaceView != null) {
            arSurfaceView.renderer.onFrameAvailable = { frame ->
                viewModel.onFrameAvailable(frame)
            }
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            arSurfaceView?.renderer?.onFrameAvailable = null
            arSurfaceView?.renderer?.onArCoreUnavailable = null
            arSurfaceView?.renderer?.onDepthSourceStateChanged = null
            arSurfaceView?.onDestroy()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // AR core mevcutken AR overlay'i çiz; değilse yalnızca fallback banner görünür.
        if (arSurfaceView != null) {
            AndroidView(
                factory = { arSurfaceView },
                modifier = Modifier.fillMaxSize()
            )
        }

        // YOLOv8 Bounding Boxes Canvas Overlay
        if (aiPreviewMode == AIPreviewMode.YOLO) {
            if (!yoloModelLoaded) {
                // YOLOv8 modeli yüklü değil — sahte tespit kutusu gösterilmez.
                Text(
                    text = "⚠️ YOLOv8 modeli yüklü değil — gerçek tespit kapalı",
                    color = Color(0xFFFFCC00),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 64.dp)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            } else {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val canvasW = size.width
                    val canvasH = size.height

                    yoloDetections.forEach { det ->
                        val rect = det.boundingBox
                        val left = rect.left * canvasW
                        val top = rect.top * canvasH
                        val right = rect.right * canvasW
                        val bottom = rect.bottom * canvasH

                        // Draw cyan stroke box
                        drawRect(
                            color = Color.Cyan,
                            topLeft = Offset(left, top),
                            size = Size(right - left, bottom - top),
                            style = Stroke(width = 3.dp.toPx())
                        )

                        // Draw label text
                        val label = "Target Object (${(det.confidence * 100).toInt()}%)"
                        val paint = android.graphics.Paint().apply {
                            color = android.graphics.Color.CYAN
                            textSize = 38f
                            isAntiAlias = true
                            typeface = android.graphics.Typeface.DEFAULT_BOLD
                        }
                        drawIntoCanvas { canvas ->
                            canvas.nativeCanvas.drawText(label, left + 8f, top + 42f, paint)
                        }
                    }
                }
            }
        }

        // Termal uyarı banner'ı (çekim engellenmez, yalnızca AI hızı düşürülür).
        if (isThermalWarned && !isThermalThrottled) {
            Text(
                text = "🌡️ Sıcaklık yüksek (%.0f°C) — AI hızı düşürüldü".format(currentSocTemp),
                color = Color(0xFFFFCC00),
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 108.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }

        SystemHud(
            context = context,
            modifier = Modifier
                .padding(8.dp)
                .align(Alignment.TopStart)
        )

        // AI Stats HUD
        aiStats?.let { stats ->
            Text(
                text = "⚡ $stats",
                color = Color.Yellow,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 12.dp, top = 96.dp)
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }

        // Clear Accumulated Points Cyberpunk Button (Depth mode & pointCount > 0)
        if (aiPreviewMode == AIPreviewMode.DEPTH && pointCount > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 12.dp, top = 132.dp)
                    .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(6.dp))
                    .border(width = 1.dp, color = Color.Cyan, shape = RoundedCornerShape(6.dp))
                    .clickable { viewModel.clearAccumulatedPoints() }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(Color.Cyan, CircleShape)
                    )
                    Text(
                        text = "Noktaları Temizle ($pointCount)",
                        color = Color.Cyan,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }

        // Toggle Mode Selector (TopCenter)
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 24.dp)
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                    shape = RoundedCornerShape(20.dp)
                )
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(20.dp)
                )
                .clickable { viewModel.setMultiLensMode(!multiLensMode) }
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            color = if (multiLensMode) Color.Cyan else Color.Green,
                            shape = CircleShape
                        )
                )
                Text(
                    text = if (multiLensMode) "MODE: MULTI-LENS (TELE + UW)" else "MODE: BURST ×3 (TELE)",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }

        // AI Mode Toggle Row (TopCenter under main mode toggle)
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 72.dp)
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                    shape = RoundedCornerShape(20.dp)
                )
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(20.dp)
                )
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                listOf(
                    AIPreviewMode.NONE to "AI: OFF",
                    AIPreviewMode.DEPTH to "DEPTH",
                    AIPreviewMode.YOLO to "YOLOv8"
                ).forEach { (modeOpt, label) ->
                    val isSelected = aiPreviewMode == modeOpt
                    Text(
                        text = label,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .clickable { viewModel.setAiPreviewMode(modeOpt) }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }

        // Back Button (TopEnd)
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 24.dp, end = 24.dp)
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                    shape = CircleShape
                )
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                    shape = CircleShape
                )
        ) {
            Icon(
                imageVector = Icons.Default.ArrowBack,
                contentDescription = "Geri",
                tint = MaterialTheme.colorScheme.primary
            )
        }

        // Pro Mode Toggle Button (TopEnd under Geri button)
        IconButton(
            onClick = { viewModel.setProControlsEnabled(!proControlsEnabled) },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 80.dp, end = 24.dp)
                .background(
                    color = if (proControlsEnabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.8f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                    shape = CircleShape
                )
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                    shape = CircleShape
                )
        ) {
            Icon(
                imageVector = Icons.Default.Tune,
                contentDescription = "Pro Kontroller",
                tint = if (proControlsEnabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
            )
        }

        // Pro Camera Control Panel (Sağ Kenar)
        if (proControlsEnabled) {
            ProControlPanel(
                isoValue = isoValue,
                shutterFraction = shutterFraction,
                focusDistanceValue = focusDistanceValue,
                isSettingsLocked = isSettingsLocked,
                onIsoChange = { viewModel.setIsoValue(it) },
                onShutterChange = { viewModel.setShutterFraction(it) },
                onFocusChange = { viewModel.setFocusDistanceValue(it) },
                onLockToggle = { viewModel.setSettingsLocked(!isSettingsLocked) },
                evValue = evValue,
                onEvChange = { viewModel.setEvValue(it) },
                colorTempValue = colorTempValue,
                onColorTempChange = { viewModel.setColorTemperature(it) },
                isoRange = isoRange,
                evRange = evRange,
                evStep = evStep,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 16.dp)
            )
        }

        // Pro ayarlar durum çipi (Batch-3) — mevcut banner'ların altında, üst-orta.
        // Yalnızca pro panel açıkken gösterilir (pro modu kapalıyken ARCore banner'ı ile çakışmayı önler).
        if (proControlsEnabled) {
            Text(
                text = if (isSettingsLocked) viewModel.appliedSettingsSummary() else String.format(java.util.Locale.US, "EV %+.1f", evValue),
                color = if (isSettingsLocked) Color(0xFFFFCC00) else Color.White,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 150.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }

        // Floating Depth Heatmap Picture-in-Picture overlay (BottomLeft)
        if (aiPreviewMode == AIPreviewMode.DEPTH) {
            depthHeatmap?.let { bitmap ->
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 24.dp, bottom = 48.dp)
                        .size(130.dp)
                        .background(
                            color = Color.Black,
                            shape = RoundedCornerShape(12.dp)
                        )
                        .border(
                            width = 1.5.dp,
                            color = MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(12.dp)
                        )
                ) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Depth Map",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
            }
        }

        // B-3: ARCore yokken kullanıcıya anlamlı fallback durumu göster (sessiz yutma yok).
        when (val state = arCoreState) {
            is ArCoreAvailabilityState.Unavailable -> {
                Text(
                    text = "⚠ ARCore kullanılamıyor: ${state.reason}",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 120.dp)
                        .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
            ArCoreAvailabilityState.Checking -> {
                Text(
                    text = "ARCore kontrol ediliyor…",
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 120.dp)
                        .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
            ArCoreAvailabilityState.Available -> Unit
        }

        // Capture Button
        CaptureButton(
            state = captureState,
            onClick = {
                viewModel.triggerCapture(
                    pauseArCallback = { arSurfaceView?.onPause() },
                    resumeArCallback = { arSurfaceView?.onResume() },
                    latestCameraPose = latestCameraPose
                )
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp)
        )

        // Capture Progress Indicator
        CaptureProgressBar(
            progressState = progressState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )

        // Capture Logs Text
        lastCaptureLog?.let { log ->
            Text(
                text = log,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 132.dp)
            )
        }
    }

    // "Taramalarım" Screen Dialog
    if (showMyScans) {
        MyScansScreen(
            store = viewModel.sessionFrameStore,
            onClose = { viewModel.setShowMyScans(false) },
            onOpen = { session ->
                viewModel.setShowMyScans(false)
                viewModel.setOpenedSession(session)
            }
        )
    }

    // Detail Screen Overlay
    openedSession?.let { session ->
        ScanDetailScreen(
            session = session,
            onClose = { viewModel.setOpenedSession(null) },
            onShareZip = { s -> viewModel.triggerZipShare(s) },
            onResumeCapture = { viewModel.setOpenedSession(null) },
            onStart3DRender = { viewModel.enqueue3DRender(session) },
            onExportPly = { s -> viewModel.triggerPlyExport(s) }
        )
    }

    // ZIP Share Progress dialogs
    ZipShareDialog(
        zipShareState = zipShareState,
        onDismiss = { viewModel.resetZipShareState() }
    )

    // Ingestion Queue Status Dialogs
    IngestionDialogs(
        ingestionState = ingestionState,
        onDismiss = { viewModel.resetIngestionToIdle() }
    )

    // PLY Export Progress dialogs
    PlyExportDialog(
        exportState = plyExportState,
        onDismiss = { viewModel.resetPlyExportState() }
    )

    // Thermal Throttling Protection Dialog
    ThermalWarningDialog(
        isThrottled = isThermalThrottled,
        currentTemp = currentSocTemp,
        onDismiss = { viewModel.resetThermalThrottled() }
    )
}
