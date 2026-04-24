package com.buddy.assistant.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = BuddyColors.Accent,
    onPrimary = BuddyColors.Ink,
    secondary = BuddyColors.AuroraLavender,
    onSecondary = BuddyColors.Ink,
    tertiary = BuddyColors.AuroraPeach,
    onTertiary = BuddyColors.Ink,
    background = BuddyColors.Bg,
    onBackground = BuddyColors.Ink,
    surface = BuddyColors.Surface,
    onSurface = BuddyColors.Ink,
    surfaceVariant = BuddyColors.SurfaceHi,
    onSurfaceVariant = BuddyColors.InkDim,
    error = BuddyColors.Error,
    onError = BuddyColors.Ink,
    outline = BuddyColors.InkGhost,
)

@Composable
fun BuddyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = MaterialTheme.typography,
        content = content
    )
}
