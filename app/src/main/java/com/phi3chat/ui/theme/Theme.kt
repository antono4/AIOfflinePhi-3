package com.phi3chat.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightScheme = lightColorScheme(
    primary = PhiPrimary,
    secondary = PhiSecondary,
    tertiary = PhiTertiary,
    background = SurfaceLight,
    surface = SurfaceLight,
    surfaceVariant = SurfaceVariantLight,
    error = ErrorRed,
)

private val DarkScheme = darkColorScheme(
    primary = PhiPrimaryDark,
    secondary = PhiSecondaryDark,
    tertiary = PhiTertiary,
    background = SurfaceDark,
    surface = SurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    error = ErrorRed,
)

@Composable
fun Phi3ChatTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    useDynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> DarkScheme
        else -> LightScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = PhiTypography,
        content = content,
    )
}

object PhiColors {
    val userBubbleLight = UserBubbleLight
    val userBubbleDark = UserBubbleDark
    val assistantBubbleLight = AssistantBubbleLight
    val assistantBubbleDark = AssistantBubbleDark
    val onUserBubble = Color.White
}
