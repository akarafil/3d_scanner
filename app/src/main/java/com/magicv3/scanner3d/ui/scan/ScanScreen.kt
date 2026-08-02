package com.magicv3.scanner3d.ui.scan

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.magicv3.scanner3d.domain.ar.CameraPose
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
    val multiLensMode by viewModel.multiLensMode.collectAsStateWithLifecycle()
    val showMyScans by viewModel.showMyScans.collectAsStateWithLifecycle()
    val openedSession by viewModel.openedSession.collectAsStateWithLifecycle()
    val zipShareState by viewModel.zipShareState.collectAsStateWithLifecycle()

    val progressState by viewModel.orchestrator.progress.collectAsStateWithLifecycle()
    val ingestionState by viewModel.ingestionQueue.queueState.collectAsStateWithLifecycle()

    var latestCameraPose by remember { mutableStateOf<CameraPose?>(null) }
    val arSurfaceView = remember {
        ArPointCloudSurfaceView(context) { pose ->
            latestCameraPose = pose
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            arSurfaceView.onDestroy()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { arSurfaceView },
            modifier = Modifier.fillMaxSize()
        )

        SystemHud(
            context = context,
            modifier = Modifier
                .padding(8.dp)
                .align(Alignment.TopStart)
        )

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
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 16.dp)
            )
        }

        // Capture Button
        CaptureButton(
            state = captureState,
            onClick = {
                viewModel.triggerCapture(
                    pauseArCallback = { arSurfaceView.onPause() },
                    resumeArCallback = { arSurfaceView.onResume() },
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
            onStart3DRender = { viewModel.enqueue3DRender(session) }
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
}
