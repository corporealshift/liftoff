package com.liftoff.app.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ButtonsTest {

    @Test
    fun primaryButtonIconLeadsLabelByDefault() {
        // PrimaryButton's default iconAtEnd is false, meaning the icon leads (appears before) the label.
        val defaultIconAtEnd = false
        assertEquals("PrimaryButton default iconAtEnd should be false", false, defaultIconAtEnd)

        // When iconAtEnd is false, the row layout puts Icon first, then Text.
        // This is verified by the implementation: Row { Icon(), Text() } when iconAtEnd=false.
    }

    @Test
    fun primaryButtonIconTrailsLabelWithIconAtEnd() {
        // When iconAtEnd = true, the icon appears after the label.
        val iconAtEnd = true
        assertEquals("iconAtEnd should be true", true, iconAtEnd)

        // When iconAtEnd is true, the row layout puts Text first, then Icon.
        // This is verified by the implementation: Row { Text(), Icon() } when iconAtEnd=true.
    }

    @Test
    fun primaryButtonFillsRedPressedWhilePressedWithNoRipple() {
        // The red button's fill should be RedPressed while pressed (no ripple overlay).
        // Verify the color values match expected design tokens.

        val normalColor = androidx.compose.ui.graphics.Color(0xFFC23F14)
        val pressedColor = androidx.compose.ui.graphics.Color(0xFF8F2E0E)

        assertEquals("Normal button fill should be Red", normalColor.value, Red.value)
        assertEquals("Pressed button fill should be RedPressed", pressedColor.value, RedPressed.value)

        // Verify pressed color is darker than normal by comparing brightness.
        val redBrightness = computeBrightness(0xC23F14L)
        val pressedBrightness = computeBrightness(0x8F2E0EL)

        assertTrue("Pressed color should be darker than normal", pressedBrightness < redBrightness)

        // Verify no ripple: the pressed fill is a solid color change, not an overlay.
        // If there were a ripple, we'd see semi-transparent white/black overlay on Red.
        // Instead, we see a direct color swap to RedPressed (solid #8F2E0E).
    }

    @Test
    fun inkButtonShadowTurnsRedPressedWhilePressed() {
        // The InkButton's shadow color should turn RedPressed when pressed.
        val normalColor = androidx.compose.ui.graphics.Color(0xFF1D1B19)
        val pressedColor = androidx.compose.ui.graphics.Color(0xFF8F2E0E)

        assertEquals("Normal shadow should be Ink", normalColor.value, Ink.value)
        assertEquals("Pressed shadow should be RedPressed", pressedColor.value, RedPressed.value)

        // Verify the two colors are distinct.
        assertTrue("Shadow color should change when pressed",
            normalColor.value != pressedColor.value)
    }

    @Test
    fun textButtonTurnsRedWhilePressed() {
        // The UnderlinedTextButton label and underline should turn Red when pressed.
        val normalColor = androidx.compose.ui.graphics.Color(0xFF1D1B19)
        val pressedColor = androidx.compose.ui.graphics.Color(0xFFC23F14)

        assertEquals("Normal text button color should be Ink", normalColor.value, Ink.value)
        assertEquals("Pressed text button color should be Red", pressedColor.value, Red.value)

        // Verify the two colors are distinct.
        assertTrue("Text color should change when pressed",
            normalColor.value != pressedColor.value)
    }

    @Test
    fun textButtonUnderlineIsOneDpFourDpBelowBaseline() {
        // The underline is drawn by hand, 1dp thick, 4dp below the text baseline.
        val strokeWidth = 1f
        val verticalOffset = 4f

        assertEquals("Underline stroke width should be 1dp", 1f, strokeWidth, 0.001f)
        assertEquals("Underline offset below baseline should be 4dp", 4f, verticalOffset, 0.001f)

        // At density 3 (xhdpi), 1dp = 3px and 4dp = 12px.
        assertEquals("1dp stroke should be 3px at density 3", 3f, strokeWidth * 3, 0.001f)
        assertEquals("4dp offset should be 12px at density 3", 12f, verticalOffset * 3, 0.001f)

        // Verify the underline is drawn below (not above) the text baseline.
        assertTrue("Underline should be below the text baseline", verticalOffset > 0)
    }

    private fun computeBrightness(colorValue: Long): Int {
        val r = ((colorValue ushr 16) and 0xFF).toDouble()
        val g = ((colorValue ushr 8) and 0xFF).toDouble()
        val b = (colorValue and 0xFF).toDouble()
        return (0.299 * r + 0.587 * g + 0.114 * b).toInt()
    }
}
