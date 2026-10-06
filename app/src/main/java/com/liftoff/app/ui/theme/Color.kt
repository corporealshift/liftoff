package com.liftoff.app.ui.theme

import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Cream = Color(0xFFF2EADB)
val Paper = Color(0xFFFBF6EC)
val Sand  = Color(0xFFE8DFCD)
val Ink   = Color(0xFF1D1B19)
val Red   = Color(0xFFC23F14)
val RedPressed = Color(0xFF8F2E0E)
val Mustard = Color(0xFFE3A72F)
val Teal    = Color(0xFF1F5F6B)
val TealLight = Color(0xFF9FBFC4)
val Muted   = Color(0xFF5E5850)
val Rule    = Color(0xFFCFC6B6)
val White   = Color(0xFFFFFFFF)

@Composable
fun LiftoffColorScheme(): androidx.compose.material3.ColorScheme {
    return lightColorScheme(
        primary = Red,
        onPrimary = White,
        secondary = Mustard,
        onSecondary = Ink,
        tertiary = Teal,
        onTertiary = Cream,
        background = Cream,
        onBackground = Ink,
        surface = Cream,
        onSurface = Ink,
        surfaceVariant = Sand,
        onSurfaceVariant = Muted,
        surfaceContainerHighest = Paper,
        surfaceContainerHigh = Paper,
        surfaceContainer = Paper,
        surfaceContainerLow = Paper,
        surfaceContainerLowest = Paper,
        surfaceBright = Paper,
        surfaceDim = Paper,
        surfaceTint = Cream,
        primaryContainer = Red,
        onPrimaryContainer = White,
        secondaryContainer = Mustard,
        onSecondaryContainer = Ink,
        tertiaryContainer = Teal,
        onTertiaryContainer = Cream,
        outline = Ink,
        outlineVariant = Rule,
        error = Red,
        onError = White,
    )
}
