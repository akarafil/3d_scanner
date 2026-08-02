package com.magicv3.scanner3d.ui.scan

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.magicv3.scanner3d.infra.camera.MultiLensCaptureOrchestrator.CaptureProgress

@Composable
fun CaptureProgressBar(
    progressState: CaptureProgress,
    modifier: Modifier = Modifier
) {
    when (progressState) {
        is CaptureProgress.FrameStarted ->
            LinearProgressIndicator(
                progress = { (progressState.index + 1f) / progressState.total },
                modifier = modifier
                    .padding(bottom = 124.dp)
                    .fillMaxWidth(0.6f)
            )
        is CaptureProgress.FrameSuccess ->
            LinearProgressIndicator(
                progress = { (progressState.index + 1f) / progressState.total },
                modifier = modifier
                    .padding(bottom = 124.dp)
                    .fillMaxWidth(0.6f)
            )
        is CaptureProgress.FrameFailure ->
            LinearProgressIndicator(
                progress = { (progressState.index + 1f) / progressState.total },
                modifier = modifier
                    .padding(bottom = 124.dp)
                    .fillMaxWidth(0.6f)
            )
        else -> {}
    }
}
