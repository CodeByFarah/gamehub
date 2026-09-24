package com.gamehub.android.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Material 3 with dynamic colour where the platform supports it.
 *
 * Dynamic colour means the app adopts the wallpaper palette on Android 12 and
 * above, which is what makes it feel native rather than branded-at. The static
 * scheme below is the fallback, not the default.
 */

private val Indigo = Color(0xFF4F46E5)
private val IndigoLight = Color(0xFFA5B4FC)
private val Teal = Color(0xFF14B8A6)
private val Amber = Color(0xFFF59E0B)

private val DarkScheme = darkColorScheme(
    primary = IndigoLight,
    secondary = Teal,
    tertiary = Amber,
)

private val LightScheme = lightColorScheme(
    primary = Indigo,
    secondary = Teal,
    tertiary = Amber,
)

@Composable
fun GameHubTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> DarkScheme
        else -> LightScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = GameHubTypography,
        content = content,
    )
}
