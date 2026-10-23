package com.example.rizomamovil.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val TerminalColorScheme = darkColorScheme(
    primary = RizomaRed,
    secondary = TerminalBorderLight,
    tertiary = TerminalTextMuted,
    background = TerminalBackground,
    surface = SurfaceDark,
    onPrimary = TerminalText,
    onSecondary = TerminalText,
    onBackground = TerminalText,
    onSurface = TerminalText
)

@Composable
fun RizomaMovilTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(colorScheme = TerminalColorScheme, typography = Typography, content = content)
}
