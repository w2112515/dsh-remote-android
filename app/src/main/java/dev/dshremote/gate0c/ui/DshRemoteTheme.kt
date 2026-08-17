package dev.dshremote.gate0c.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

internal object DshColors {
    val Ink = Color(0xFF0F1216)
    val Raised = Color(0xFF151A21)
    val Hairline = Color(0xFF232B36)
    val Text = Color(0xFFE5EAF1)
    val Muted = Color(0xFF99A5B4)
    val Accent = Color(0xFF8EA4FB)
    val AccentStrong = Color(0xFF4060F0)
    val Success = Color(0xFF4CC38A)
    val Warning = Color(0xFFE0A63E)
    val Danger = Color(0xFFE5695E)

    val LightBase = Color(0xFFF5F7FA)
    val LightRaised = Color(0xFFFFFFFF)
    val LightHairline = Color(0xFFDDE3EB)
    val LightText = Color(0xFF17202B)
    val LightMuted = Color(0xFF637083)
}

private val DarkScheme = darkColorScheme(
    primary = DshColors.Accent,
    onPrimary = DshColors.Ink,
    primaryContainer = Color(0xFF202946),
    onPrimaryContainer = Color(0xFFDCE3FF),
    background = DshColors.Ink,
    onBackground = DshColors.Text,
    surface = DshColors.Raised,
    onSurface = DshColors.Text,
    surfaceVariant = Color(0xFF1A2028),
    onSurfaceVariant = DshColors.Muted,
    outline = DshColors.Hairline,
    error = DshColors.Danger,
)

private val LightScheme = lightColorScheme(
    primary = DshColors.AccentStrong,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE2E7FF),
    onPrimaryContainer = Color(0xFF182557),
    background = DshColors.LightBase,
    onBackground = DshColors.LightText,
    surface = DshColors.LightRaised,
    onSurface = DshColors.LightText,
    surfaceVariant = Color(0xFFEDF1F6),
    onSurfaceVariant = DshColors.LightMuted,
    outline = DshColors.LightHairline,
    error = Color(0xFFB33C34),
)

@Composable
internal fun DshRemoteTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme,
        content = content,
    )
}
