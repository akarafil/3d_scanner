package com.magicv3.scanner3d.ui.scan

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.magicv3.scanner3d.domain.ar.ArCoreTracker

@Composable
fun LivePointCloudOverlay(
    arTracker: ArCoreTracker,
    modifier: Modifier = Modifier
) {
    val points by arTracker.accumulatedPoints.collectAsState()

    Canvas(modifier = modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height
        val centerX = width / 2f
        val centerY = height / 2f

        // Basit Perspektif Projeksiyonu (3D Point -> 2D Screen)
        points.forEach { point ->
            // Z derinliği kontrollü ölçekleme
            val distance = kotlin.math.max(0.2f, -point.z + 1.5f)
            val scale = 800f / distance 

            val screenX = centerX + (point.x * scale)
            val screenY = centerY - (point.y * scale)

            // Ekran sınırları içerisindeyse çiz
            if (screenX in 0f..width && screenY in 0f..height) {
                // Güvenilirlik/Derinlik derecesine göre renk gradyanı (Canlı Hamur Efekti)
                val alpha = (point.confidence).coerceIn(0.2f, 1.0f)
                val pointColor = Color(0xFF00FF88).copy(alpha = alpha)

                drawCircle(
                    color = pointColor,
                    radius = (3f / distance).coerceIn(1.5f, 6f),
                    center = Offset(screenX, screenY)
                )
            }
        }
    }
}
