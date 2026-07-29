package com.magicv3.scanner3d.ui.theme

import androidx.compose.ui.graphics.Color

// ══════════════════════════════════════════════════════════════════════
//  MAGIC 3D SCANNER v2 — CYBER DARK-FIRST PALETTE
//  Estetik: "Task Manager / HUD" teknolojik görünüm
//  Kontrast: Yüksek (koyu zemin + neon accent) → kamera önizlemesi
//  üstünde okunabilirlik öncelikli
// ══════════════════════════════════════════════════════════════════════

// ── Material3 Scheme Renkleri ──────────────────────────────────────
val CyberBackground = Color(0xFF0A0E14)   // anthracite — ana zemin
val CyberSurface    = Color(0xFF121821)   // panel zemin (HUD, kart)
val CyberPrimary    = Color(0xFF00E5FF)   // cyan neon — birincil accent
val CyberSecondary  = Color(0xFF7C4DFF)   // violet — ikincil accent
val CyberTertiary   = Color(0xFF69F0AE)   // mint green — pozitif/ok
val CyberError      = Color(0xFFFF5252)   // red — termal alarm
val CyberOnBg       = Color(0xFFE3E8EF)   // birincil text
val CyberOnSurface  = Color(0xFFB0BEC5)   // ikincil text (label vb.)

// ── HUD'a Özel Durum Renkleri (SystemHud Phase 1.7'de kullanır) ────
val HudGood = Color(0xFF69F0AE)   // CPU<40%  / RAM<60%  / SoC<45°C
val HudWarn = Color(0xFFFFD740)   // 40-70%   / 60-80%   / 45-55°C
val HudCrit = Color(0xFFFF5252)   // >70%     / >80%     / >55°C
