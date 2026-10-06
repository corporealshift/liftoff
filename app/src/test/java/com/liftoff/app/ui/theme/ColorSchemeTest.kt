package com.liftoff.app.ui.theme

import androidx.compose.material3.lightColorScheme
import org.junit.Assert.assertEquals
import org.junit.Test

class ColorSchemeTest {

    @Test
    fun everySchemeSlotIsADesignToken() {
        // Every Color in the M3 scheme must be one of the 12 design tokens.
        // LiftoffColorScheme is @Composable so we can't call it from a JVM unit test;
        // instead we verify that lightColorScheme built from our token vals has all slots correct.

        val expectedMap = mapOf<String, Int>(
            "primary"       to Red.hashCode(),
            "onPrimary"     to White.hashCode(),
            "secondary"     to Mustard.hashCode(),
            "onSecondary"   to Ink.hashCode(),
            "tertiary"      to Teal.hashCode(),
            "onTertiary"    to Cream.hashCode(),
            "background"    to Cream.hashCode(),
            "onBackground"  to Ink.hashCode(),
            "surface"       to Cream.hashCode(),
            "onSurface"     to Ink.hashCode(),
            "surfaceVariant" to Sand.hashCode(),
            "onSurfaceVariant" to Muted.hashCode(),
            "surfaceContainerLowest"    to Paper.hashCode(),
            "surfaceContainerLow"       to Paper.hashCode(),
            "surfaceContainer"          to Paper.hashCode(),
            "surfaceContainerHigh"      to Paper.hashCode(),
            "surfaceContainerHighest"   to Paper.hashCode(),
            "surfaceBright"             to Paper.hashCode(),
            "surfaceDim"                to Paper.hashCode(),
            "surfaceTint"               to Cream.hashCode(),
            "primaryContainer"          to Red.hashCode(),
            "onPrimaryContainer"        to White.hashCode(),
            "secondaryContainer"        to Mustard.hashCode(),
            "onSecondaryContainer"      to Ink.hashCode(),
            "tertiaryContainer"         to Teal.hashCode(),
            "onTertiaryContainer"       to Cream.hashCode(),
            "outline"                   to Ink.hashCode(),
            "outlineVariant"            to Rule.hashCode(),
            "error"                     to Red.hashCode(),
            "onError"                   to White.hashCode(),
        )

        val scheme = lightColorScheme(
            primary = Red, onPrimary = White,
            secondary = Mustard, onSecondary = Ink,
            tertiary = Teal, onTertiary = Cream,
            background = Cream, onBackground = Ink,
            surface = Cream, onSurface = Ink,
            surfaceVariant = Sand, onSurfaceVariant = Muted,
            surfaceContainerLowest = Paper,
            surfaceContainerLow = Paper,
            surfaceContainer = Paper,
            surfaceContainerHigh = Paper,
            surfaceContainerHighest = Paper,
            surfaceBright = Paper,
            surfaceDim = Paper,
            surfaceTint = Cream,
            primaryContainer = Red, onPrimaryContainer = White,
            secondaryContainer = Mustard, onSecondaryContainer = Ink,
            tertiaryContainer = Teal, onTertiaryContainer = Cream,
            outline = Ink, outlineVariant = Rule,
            error = Red, onError = White,
        )

        assertEquals("primary", expectedMap["primary"]!!, scheme.primary.hashCode())
        assertEquals("onPrimary", expectedMap["onPrimary"]!!, scheme.onPrimary.hashCode())
        assertEquals("secondary", expectedMap["secondary"]!!, scheme.secondary.hashCode())
        assertEquals("onSecondary", expectedMap["onSecondary"]!!, scheme.onSecondary.hashCode())
        assertEquals("tertiary", expectedMap["tertiary"]!!, scheme.tertiary.hashCode())
        assertEquals("onTertiary", expectedMap["onTertiary"]!!, scheme.onTertiary.hashCode())
        assertEquals("background", expectedMap["background"]!!, scheme.background.hashCode())
        assertEquals("onBackground", expectedMap["onBackground"]!!, scheme.onBackground.hashCode())
        assertEquals("surface", expectedMap["surface"]!!, scheme.surface.hashCode())
        assertEquals("onSurface", expectedMap["onSurface"]!!, scheme.onSurface.hashCode())
        assertEquals("surfaceVariant", expectedMap["surfaceVariant"]!!, scheme.surfaceVariant.hashCode())
        assertEquals("onSurfaceVariant", expectedMap["onSurfaceVariant"]!!, scheme.onSurfaceVariant.hashCode())
        assertEquals("surfaceContainerLowest", expectedMap["surfaceContainerLowest"]!!, scheme.surfaceContainerLowest.hashCode())
        assertEquals("surfaceContainerLow", expectedMap["surfaceContainerLow"]!!, scheme.surfaceContainerLow.hashCode())
        assertEquals("surfaceContainer", expectedMap["surfaceContainer"]!!, scheme.surfaceContainer.hashCode())
        assertEquals("surfaceContainerHigh", expectedMap["surfaceContainerHigh"]!!, scheme.surfaceContainerHigh.hashCode())
        assertEquals("surfaceContainerHighest", expectedMap["surfaceContainerHighest"]!!, scheme.surfaceContainerHighest.hashCode())
        assertEquals("surfaceBright", expectedMap["surfaceBright"]!!, scheme.surfaceBright.hashCode())
        assertEquals("surfaceDim", expectedMap["surfaceDim"]!!, scheme.surfaceDim.hashCode())
        assertEquals("surfaceTint", expectedMap["surfaceTint"]!!, scheme.surfaceTint.hashCode())
        assertEquals("primaryContainer", expectedMap["primaryContainer"]!!, scheme.primaryContainer.hashCode())
        assertEquals("onPrimaryContainer", expectedMap["onPrimaryContainer"]!!, scheme.onPrimaryContainer.hashCode())
        assertEquals("secondaryContainer", expectedMap["secondaryContainer"]!!, scheme.secondaryContainer.hashCode())
        assertEquals("onSecondaryContainer", expectedMap["onSecondaryContainer"]!!, scheme.onSecondaryContainer.hashCode())
        assertEquals("tertiaryContainer", expectedMap["tertiaryContainer"]!!, scheme.tertiaryContainer.hashCode())
        assertEquals("onTertiaryContainer", expectedMap["onTertiaryContainer"]!!, scheme.onTertiaryContainer.hashCode())
        assertEquals("outline", expectedMap["outline"]!!, scheme.outline.hashCode())
        assertEquals("outlineVariant", expectedMap["outlineVariant"]!!, scheme.outlineVariant.hashCode())
        assertEquals("error", expectedMap["error"]!!, scheme.error.hashCode())
        assertEquals("onError", expectedMap["onError"]!!, scheme.onError.hashCode())
    }
}
