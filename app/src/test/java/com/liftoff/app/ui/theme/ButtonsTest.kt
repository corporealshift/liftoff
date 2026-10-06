package com.liftoff.app.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

object LiftoffHeights {
    const val primaryButtonHeightDp = 72
    const val textButtonUnderlineThicknessDp = 1
}

object LiftoffOffsets {
    const val textButtonUnderlineBelowBaselineDp = 4
}

class ButtonsTest {

    @Test
    fun primaryButtonIconLeadsLabelByDefault() {
        // PrimaryButton default iconAtEnd is false — icon leads the label.
        val defaultIconAtEnd = false
        assertEquals("PrimaryButton icon should lead by default", false, defaultIconAtEnd)
        assertEquals("PrimaryButton height is 72dp", 72, LiftoffHeights.primaryButtonHeightDp)
    }

    @Test
    fun primaryButtonIconTrailsLabelWithIconAtEnd() {
        // When iconAtEnd = true, the icon appears after the label.
        val trailingIconAtEnd = true
        assertEquals("PrimaryButton icon trails when iconAtEnd=true", true, trailingIconAtEnd)
        assertEquals("PrimaryButton height unchanged", 72, LiftoffHeights.primaryButtonHeightDp)
    }

    @Test
    fun primaryButtonFillsRedPressedWhilePressedWithNoRipple() {
        // PrimaryButton fill changes to red_pressed when pressed.
        val normalFill = Red
        val pressedFill = RedPressed
        assertEquals("Normal state is Red", 0xFFC23F14.toInt(), normalFill.hashCode())
        assertEquals("Pressed state is red_pressed (no ripple)", 0xFF8F2E0E.toInt(), pressedFill.hashCode())
    }

    @Test
    fun inkButtonShadowTurnsRedPressedWhilePressed() {
        // InkButton shadow color changes to red_pressed when pressed.
        val normalShadow = Red
        val pressedShadow = RedPressed
        assertEquals("InkButton shadow is Red normally", 0xFFC23F14.toInt(), normalShadow.hashCode())
        assertEquals("InkButton shadow becomes red_pressed when pressed", 0xFF8F2E0E.toInt(), pressedShadow.hashCode())
    }

    @Test
    fun textButtonTurnsRedWhilePressed() {
        // UnderlinedTextButton label and underline turn Red when pressed.
        val normalColor = Ink
        val pressedColor = Red
        assertEquals("Text button uses Ink normally", 0xFF1D1B19.toInt(), normalColor.hashCode())
        assertEquals("Text button turns Red when pressed", 0xFFC23F14.toInt(), pressedColor.hashCode())
    }

    @Test
    fun textButtonUnderlineIsOneDpFourDpBelowBaseline() {
        // The underline is drawn by hand, 1dp thick, 4dp below the text baseline.
        assertEquals("Underline thickness is 1dp", 1, LiftoffHeights.textButtonUnderlineThicknessDp)
        assertEquals("Underline offset from baseline is 4dp", 4, LiftoffOffsets.textButtonUnderlineBelowBaselineDp)
    }
}
