package com.magicv3.scanner3d.ui.capture

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.magicv3.scanner3d.ui.theme.CyberPrimary
import com.magicv3.scanner3d.ui.theme.HudCrit
import com.magicv3.scanner3d.ui.theme.HudGood

/**
 * Kamera deklanşör (shutter) butonu — BottomCenter overlay.
 *
 * Görsel durumlar:
 *   IDLE      → CyberPrimary ring + solid cyan core
 *   CAPTURING → HudGood ring + pulsing core (alpha 0.4↔1.0, 600ms)
 *   DONE      → HudGood ring + tam dolu core (caller kısa süre yazar)
 *   ERROR     → HudCrit ring + kırmızı core (caller kısa süre yazar)
 *
 * Etkileşim:
 *   • Tek tap → onClick() (sadece IDLE'de aktif)
 *   • Pressed → scale 0.9 spring animasyonu
 *   • HapticFeedback.Confirm on tap
 *
 * @param state Mevcut CaptureState — görsel + enabled bunu izler
 * @param onClick IDLE'de tıklanınca çağrılır (CAPTURING pasif)
 * @param modifier Caller pozisyon/sizing verir (align + padding)
 */
@Composable
fun CaptureButton(
    state: CaptureState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val haptic = LocalHapticFeedback.current

    // Basınç → scale animasyonu (sadece IDLE'de — capture sırasında değişmesin)
    val scale by animateFloatAsState(
        targetValue = if (isPressed && state == CaptureState.IDLE) 0.9f else 1f,
        animationSpec = spring(dampingRatio = 0.4f, stiffness = Spring.StiffnessMedium),
        label = "capture_scale"
    )

    // CAPTURING → pulsing alpha (sonsuz döngü, CAPTURING dışında durur)
    val infiniteTransition = rememberInfiniteTransition(label = "capture_pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    // Ring rengi — state'e göre
    val ringColor = when (state) {
        CaptureState.CAPTURING -> HudGood
        CaptureState.ERROR -> HudCrit
        else -> CyberPrimary
    }
    // Core doluluk — CAPTURING'de pulse, DONE'da tam, diğerleri 0.85
    val coreAlpha = when (state) {
        CaptureState.CAPTURING -> pulseAlpha
        CaptureState.DONE -> 1f
        else -> 0.85f
    }

    Box(
        modifier = modifier
            .size(72.dp)
            .scale(scale)
            .clip(CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null, // custom görsel var — ripple istemiyoruz
                enabled = state == CaptureState.IDLE
            ) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        // Dış ring — 3.dp border
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .border(width = 3.dp, color = ringColor, shape = CircleShape)
        )
        // İç çekirdek — solid dolu daire
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(CyberPrimary.copy(alpha = coreAlpha))
        )
    }
}
