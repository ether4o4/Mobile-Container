package com.ether4o4.mobilecontainer.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColors = darkColorScheme(
    primary = Primary,
    onPrimary = Bg,
    primaryContainer = PrimaryDark,
    onPrimaryContainer = Text,
    secondary = Accent,
    onSecondary = Bg,
    secondaryContainer = AccentDark,
    onSecondaryContainer = Text,
    tertiary = Accent,
    background = Bg,
    onBackground = Text,
    surface = Surface,
    onSurface = Text,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = TextDim,
    surfaceTint = SurfaceTint,
    error = Error,
    onError = Bg,
    outline = TextMuted,
    outlineVariant = SurfaceVariant
)

@Composable
fun MCTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = DarkColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Bg.toArgb()
            window.navigationBarColor = Bg.toArgb()
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = false
            controller.isAppearanceLightNavigationBars = false
        }
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content
    )
}
