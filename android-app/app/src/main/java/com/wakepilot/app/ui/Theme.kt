package com.wakepilot.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val BgDark = Color(0xFF090D16)
val CardDark = Color(0xFF111827)
val BorderDark = Color(0xFF1F2937)
val AccentBlue = Color(0xFF38BDF8)
val AccentPurple = Color(0xFFA78BFA)
val StatusGreen = Color(0xFF22C55E)
val StatusAmber = Color(0xFFF59E0B)
val StatusRed = Color(0xFFEF4444)
val TextPrimary = Color(0xFFF8FAFC)
val TextMuted = Color(0xFF94A3B8)

private val DarkScheme = darkColorScheme(
    primary = AccentBlue,
    secondary = AccentPurple,
    background = BgDark,
    surface = CardDark,
    onPrimary = Color(0xFF020617),
    onBackground = TextPrimary,
    onSurface = TextPrimary,
)

@Composable
fun WakePilotTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkScheme,
        content = content,
    )
}
