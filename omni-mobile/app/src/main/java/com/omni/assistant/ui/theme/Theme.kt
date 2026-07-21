package com.omni.assistant.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = OmniColors.Accent,
    onPrimary = OmniColors.Ink,
    secondary = OmniColors.AuroraLavender,
    onSecondary = OmniColors.Ink,
    tertiary = OmniColors.AuroraPeach,
    onTertiary = OmniColors.Ink,
    background = OmniColors.Bg,
    onBackground = OmniColors.Ink,
    surface = OmniColors.Surface,
    onSurface = OmniColors.Ink,
    surfaceVariant = OmniColors.SurfaceHi,
    onSurfaceVariant = OmniColors.InkDim,
    error = OmniColors.Error,
    onError = OmniColors.Ink,
    outline = OmniColors.InkGhost,
)

@Composable
fun OmniTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = MaterialTheme.typography,
        content = content
    )
}
