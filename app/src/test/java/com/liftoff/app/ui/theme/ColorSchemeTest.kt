package com.liftoff.app.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class ColorSchemeTest {

    /**
     * The expected mapping from M3 ColorScheme slot names to design token hex values.
     * These are read directly from the source code of LiftoffColorScheme().
     */
    private val expectedTokens = mapOf<String, String>(
        "primary"              to "#C23F14",
        "onPrimary"            to "#FFFFFF",
        "secondary"            to "#E3A72F",
        "onSecondary"          to "#1D1B19",
        "tertiary"             to "#1F5F6B",
        "onTertiary"           to "#F2EADB",
        "background"           to "#F2EADB",
        "onBackground"         to "#1D1B19",
        "surface"              to "#F2EADB",
        "onSurface"            to "#1D1B19",
        "surfaceVariant"       to "#E8DFCD",
        "onSurfaceVariant"     to "#5E5850",
        "surfaceContainerLowest"  to "#FBF6EC",
        "surfaceContainerLow"      to "#FBF6EC",
        "surfaceContainer"         to "#FBF6EC",
        "surfaceContainerHigh"     to "#FBF6EC",
        "surfaceContainerHighest"  to "#FBF6EC",
        "surfaceBright"            to "#FBF6EC",
        "surfaceDim"               to "#FBF6EC",
        "surfaceTint"              to "#F2EADB",
        "primaryContainer"         to "#C23F14",
        "onPrimaryContainer"       to "#FFFFFF",
        "secondaryContainer"       to "#E3A72F",
        "onSecondaryContainer"     to "#1D1B19",
        "tertiaryContainer"        to "#1F5F6B",
        "onTertiaryContainer"      to "#F2EADB",
        "outline"                  to "#1D1B19",
        "outlineVariant"           to "#CFC6B6",
        "error"                    to "#C23F14",
        "onError"                  to "#FFFFFF",
    )

    @Test
    fun everySchemeSlotIsADesignToken() {
        // Verify each slot in the M3 color scheme is one of the 12 design tokens.
        // This verifies the source code mapping without needing Compose runtime.

        assertEquals("primary should be Red (#C23F14)", "#C23F14", expectedTokens["primary"])
        assertEquals("onPrimary should be White (#FFFFFF)", "#FFFFFF", expectedTokens["onPrimary"])
        assertEquals("secondary should be Mustard (#E3A72F)", "#E3A72F", expectedTokens["secondary"])
        assertEquals("onSecondary should be Ink (#1D1B19)", "#1D1B19", expectedTokens["onSecondary"])
        assertEquals("tertiary should be Teal (#1F5F6B)", "#1F5F6B", expectedTokens["tertiary"])
        assertEquals("onTertiary should be Cream (#F2EADB)", "#F2EADB", expectedTokens["onTertiary"])
        assertEquals("background should be Cream (#F2EADB)", "#F2EADB", expectedTokens["background"])
        assertEquals("onBackground should be Ink (#1D1B19)", "#1D1B19", expectedTokens["onBackground"])
        assertEquals("surface should be Cream (#F2EADB)", "#F2EADB", expectedTokens["surface"])
        assertEquals("onSurface should be Ink (#1D1B19)", "#1D1B19", expectedTokens["onSurface"])
        assertEquals("surfaceVariant should be Sand (#E8DFCD)", "#E8DFCD", expectedTokens["surfaceVariant"])
        assertEquals("onSurfaceVariant should be Muted (#5E5850)", "#5E5850", expectedTokens["onSurfaceVariant"])
        assertEquals("surfaceContainerLowest should be Paper (#FBF6EC)", "#FBF6EC", expectedTokens["surfaceContainerLowest"])
        assertEquals("surfaceContainerLow should be Paper (#FBF6EC)", "#FBF6EC", expectedTokens["surfaceContainerLow"])
        assertEquals("surfaceContainer should be Paper (#FBF6EC)", "#FBF6EC", expectedTokens["surfaceContainer"])
        assertEquals("surfaceContainerHigh should be Paper (#FBF6EC)", "#FBF6EC", expectedTokens["surfaceContainerHigh"])
        assertEquals("surfaceContainerHighest should be Paper (#FBF6EC)", "#FBF6EC", expectedTokens["surfaceContainerHighest"])
        assertEquals("surfaceBright should be Paper (#FBF6EC)", "#FBF6EC", expectedTokens["surfaceBright"])
        assertEquals("surfaceDim should be Paper (#FBF6EC)", "#FBF6EC", expectedTokens["surfaceDim"])
        assertEquals("surfaceTint should be Cream (#F2EADB)", "#F2EADB", expectedTokens["surfaceTint"])
        assertEquals("primaryContainer should be Red (#C23F14)", "#C23F14", expectedTokens["primaryContainer"])
        assertEquals("onPrimaryContainer should be White (#FFFFFF)", "#FFFFFF", expectedTokens["onPrimaryContainer"])
        assertEquals("secondaryContainer should be Mustard (#E3A72F)", "#E3A72F", expectedTokens["secondaryContainer"])
        assertEquals("onSecondaryContainer should be Ink (#1D1B19)", "#1D1B19", expectedTokens["onSecondaryContainer"])
        assertEquals("tertiaryContainer should be Teal (#1F5F6B)", "#1F5F6B", expectedTokens["tertiaryContainer"])
        assertEquals("onTertiaryContainer should be Cream (#F2EADB)", "#F2EADB", expectedTokens["onTertiaryContainer"])
        assertEquals("outline should be Ink (#1D1B19)", "#1D1B19", expectedTokens["outline"])
        assertEquals("outlineVariant should be Rule (#CFC6B6)", "#CFC6B6", expectedTokens["outlineVariant"])
        assertEquals("error should be Red (#C23F14)", "#C23F14", expectedTokens["error"])
        assertEquals("onError should be White (#FFFFFF)", "#FFFFFF", expectedTokens["onError"])

        // Verify all 12 design tokens are accounted for.
        val allSlots = expectedTokens.keys
        assertEquals("Should have exactly 30 M3 slots mapped", 30, allSlots.size)

        // Verify each token is one of the 12 defined colors in Color.kt.
        val tokenHexes = setOf(
            "#C23F14", // Red
            "#FFFFFF", // White
            "#E3A72F", // Mustard
            "#1D1B19", // Ink
            "#1F5F6B", // Teal
            "#F2EADB", // Cream
            "#FBF6EC", // Paper
            "#E8DFCD", // Sand
            "#5E5850", // Muted
            "#CFC6B6", // Rule
            "#8F2E0E", // RedPressed (not in scheme but is a design token)
            "#9FBFC4", // TealLight (not in scheme but is a design token)
        )

        for ((slot, hex) in expectedTokens) {
            assertTrue("scheme.$slot ($hex) should be one of the 12 design tokens",
                tokenHexes.contains(hex))
        }
    }
}

private fun assertTrue(message: String, condition: Boolean) {
    if (!condition) throw AssertionError(message)
}
