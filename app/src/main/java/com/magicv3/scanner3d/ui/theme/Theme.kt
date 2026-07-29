package com.magicv3.scanner3d.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// ══════════════════════════════════════════════════════════════════════
//  CYBER DARK-ONLY COLOR SCHEME
//
//  Karar Notu (D5 + Phase 1.0):
//  • darkTheme = true → HARD-CODED. Phase 9'a kadar light yok.
//  • dynamicColor = false → CRITICAL. Duvar kağıdı rengi wenige
//    Android 12+ Material You paleti, HUD'in cam panelinde
//    kontrastı bozar (koyu zemin + neon accent kaybolur).
// ══════════════════════════════════════════════════════════════════════

private val CyberColorScheme = darkColorScheme(
    primary = CyberPrimary,
    onPrimary = CyberBackground,
    secondary = CyberSecondary,
    onSecondary = CyberOnBg,
    tertiary = CyberTertiary,
    onTertiary = CyberBackground,
    background = CyberBackground,
    onBackground = CyberOnBg,
    surface = CyberSurface,
    onSurface = CyberOnSurface,
    surfaceVariant = CyberSurface,
    onSurfaceVariant = CyberOnSurface,
    error = CyberError,
    onError = CyberBackground
)

@Composable
fun MagicScannerTheme(
    // Hard-coded dark-first: kullanıcı tercihi değil, tasarım kararı.
    // Light palet Phase 9'a kadar devre dışı.
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = true,
    // Dynamic color KAPALI — Android 12+ Material You paleti HUD okunabilirliğini
    // bozar. Honor Magic V3'te statik neon/anthracite estetik şart.
    @Suppress("UNUSED_PARAMETER") dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = CyberColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // Status bar zeminini cyber anthracite yap
            window.statusBarColor = CyberBackground.toArgb()
            // Status bar ikonları açık renk (koyu zemin üzerinde)
            WindowCompat.getInsetsController(window, view)
                .isAppearanceLightStatusBars = false
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
