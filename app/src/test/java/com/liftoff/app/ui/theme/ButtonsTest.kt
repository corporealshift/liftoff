package com.liftoff.app.ui.theme

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * Buttons.kt's named dimensions, the fill it swaps to while pressed, and where the label ends up
 * when the icon moves.
 *
 * Every size here is a named constant that the composables read as a default parameter, so a draw
 * site that stops reading it changes what these cases say. The colours are package-level tokens:
 * none of the three composables takes a colour parameter, so the tokens they read are the only
 * colours they can draw with. [ColorTokensTest] pins each token against the table in
 * `design/README.md`; the hex values named below are the rows of that table.
 *
 * The rendered case is `labelShiftsWhenTheIconMoves`. `Modifier.clickable` merges its descendants
 * into one node, so the label is only reachable with `useUnmergedTree = true`.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h2000dp")
class ButtonsTest {

    @get:Rule
    val composeRule = createComposeRule()

    // buttonDimensionsAreTheDesignValues — PrimaryButton is 72 dp tall and InkButton 64 dp, both
    // taller than the 44 dp touch minimum UnderlinedTextButton holds itself to; the icon is 26 dp,
    // smaller than every one of those heights; the border is 2 dp, the hard shadow Buttons.kt asks
    // OffsetShadowBox for is OffsetShadow.kt's own default 4 dp, and the underline is 1 dp set 4 dp
    // below the baseline.
    @Test
    fun buttonDimensionsAreTheDesignValues() {
        val primaryHeight = PrimaryButtonHeightDp
        val inkHeight = InkButtonHeightDp
        val touchMinHeight = TextButtonMinHeightDp
        val iconSize = ButtonIconSizeDp
        val iconGap = ButtonIconLabelGapDp
        val border = ButtonBorderWidthDp
        val shadowOffset = OffsetShadowDefaultOffsetDp
        val underlineWidth = TextButtonUnderlineWidthDp
        val underlineGap = TextButtonUnderlineGapDp

        // What design/README.md's "Buttons" section and Buttons.kt's own notes give each one.
        assertEquals("PrimaryButton is 72 dp tall", 72.dp, primaryHeight)
        assertEquals("InkButton is 64 dp tall", 64.dp, inkHeight)
        assertEquals("UnderlinedTextButton holds 44 dp of touch height", 44.dp, touchMinHeight)
        assertEquals("the icon is 26 dp", 26.dp, iconSize)
        assertEquals("the icon-label gap is 12 dp", 12.dp, iconGap)
        assertEquals("the button border is 2 dp", 2.dp, border)
        assertEquals("the hard shadow Buttons.kt asks for is OffsetShadow.kt's default 4 dp", 4.dp, shadowOffset)
        assertEquals("the underline is 1 dp thick", 1.dp, underlineWidth)
        assertEquals("the underline sits 4 dp below the baseline", 4.dp, underlineGap)

        assertTrue(
            "PrimaryButton is taller than InkButton: primary=$primaryHeight ink=$inkHeight",
            primaryHeight > inkHeight,
        )
        assertTrue(
            "PrimaryButton is taller than the text button's touch minimum: primary=$primaryHeight min=$touchMinHeight",
            primaryHeight > touchMinHeight,
        )
        assertTrue(
            "InkButton is taller than the text button's touch minimum: ink=$inkHeight min=$touchMinHeight",
            inkHeight > touchMinHeight,
        )
        assertTrue(
            "the icon is smaller than the button it is drawn inside: icon=$iconSize primary=$primaryHeight",
            iconSize < primaryHeight,
        )
        assertTrue(
            "the icon is smaller than InkButton: icon=$iconSize ink=$inkHeight",
            iconSize < inkHeight,
        )
        assertTrue(
            "the icon is smaller than the 44 dp touch minimum: icon=$iconSize min=$touchMinHeight",
            iconSize < touchMinHeight,
        )
        assertTrue("the icon-label gap is narrower than the icon: gap=$iconGap icon=$iconSize", iconGap < iconSize)
        assertTrue("the border is thinner than the icon beside it: border=$border icon=$iconSize", border < iconSize)
        assertTrue(
            "the shadow is offset further than the border is thick: offset=$shadowOffset border=$border",
            shadowOffset > border,
        )
        assertTrue(
            "the underline is thinner than the button border: underline=$underlineWidth border=$border",
            underlineWidth < border,
        )
        assertTrue(
            "the underline is set below the baseline by more than it is thick: gap=$underlineGap width=$underlineWidth",
            underlineGap > underlineWidth,
        )
    }

    // pressedFillIsComputedFromTheTokens — the fill PrimaryButton swaps to while pressed, and the
    // shadow InkButton swaps to, is RedPressed: a different colour from the rest fill Red, darker
    // than it, and the same red — the same hue and saturation, computed from the two tokens' own
    // channels. Ink is neither: darker than both, and barely a colour at all.
    @Test
    fun pressedFillIsComputedFromTheTokens() {
        val restFill = Red
        val pressedFill = RedPressed
        val inkFill = Ink

        // The three colours Buttons.kt swaps between, against the rows of design/README.md that
        // ColorTokensTest reads.
        assertEquals("the rest fill is the design's red", Color(0xFFC23F14), restFill)
        assertEquals("the pressed fill is the design's red_pressed", Color(0xFF8F2E0E), pressedFill)
        assertEquals("the ink fill is the design's ink", Color(0xFF1D1B19), inkFill)

        // PrimaryButton's fill is Red at rest and RedPressed while pressed (Buttons.kt:60), and
        // InkButton's shadow is the same pair (Buttons.kt:114) — its fill stays Ink in both states.
        // Buttons.kt mixes nothing: RedPressed is its own token, so what the pressed fill has to
        // keep from Red is measured here rather than derived.
        assertTrue("the pressed fill is not the rest fill: pressed=$pressedFill rest=$restFill", pressedFill != restFill)
        assertTrue(
            "the pressed fill is darker than the rest fill: pressed=${luminanceOf(pressedFill)} rest=${luminanceOf(restFill)}",
            luminanceOf(pressedFill) < luminanceOf(restFill),
        )
        assertTrue(
            "the pressed fill is the same red as the rest fill, only darker: pressed hue=${hueOf(pressedFill)} rest hue=${hueOf(restFill)}",
            abs(hueOf(pressedFill) - hueOf(restFill)) < 1.0,
        )
        assertTrue(
            "the pressed fill is as saturated as the rest fill: pressed sat=${saturationOf(pressedFill)} rest sat=${saturationOf(restFill)}",
            abs(saturationOf(pressedFill) - saturationOf(restFill)) < 0.1,
        )

        // Ink is the colour InkButton is filled with and UnderlinedTextButton is written in at rest
        // (Buttons.kt:123, 190). It is not a darkened red, which is what the old "Normal shadow
        // should be Ink" comment took InkButton's rest shadow (Buttons.kt:114) for.
        assertTrue("Ink is not the rest fill: ink=$inkFill rest=$restFill", inkFill != restFill)
        assertTrue("Ink is not the pressed fill either: ink=$inkFill pressed=$pressedFill", inkFill != pressedFill)
        assertTrue(
            "Ink is darker than both reds: ink=${luminanceOf(inkFill)} pressed=${luminanceOf(pressedFill)}",
            luminanceOf(inkFill) < luminanceOf(pressedFill),
        )
        assertTrue(
            "Ink is barely a colour next to either red: ink sat=${saturationOf(inkFill)} pressed sat=${saturationOf(pressedFill)}",
            saturationOf(inkFill) < saturationOf(pressedFill),
        )

        // UnderlinedTextButton swaps the other way: Ink at rest, Red while pressed (Buttons.kt:190,
        // 195), so its pressed state is the lighter of its two colours where the filled buttons get
        // darker.
        assertTrue(
            "the text button's pressed colour is lighter than its rest colour: red=${luminanceOf(restFill)} ink=${luminanceOf(inkFill)}",
            luminanceOf(restFill) > luminanceOf(inkFill),
        )
    }

    // labelShiftsWhenTheIconMoves — PrimaryButton rendered with the same label and the same icon
    // and measured twice, once with the icon before the label and once with iconAtEnd, moves the
    // label's left edge leftward by exactly the icon's width plus the icon-label gap.
    @Test
    fun labelShiftsWhenTheIconMoves() {
        lateinit var density: Density
        // One rendering of one button, with only the icon's side of the label changing.
        val iconAtEnd = mutableStateOf(false)

        composeRule.setContent {
            density = LocalDensity.current
            PrimaryButton(
                text = LABEL,
                onClick = {},
                modifier = Modifier.testTag(BUTTON_TAG),
                icon = LiftoffIcons.check(),
                iconAtEnd = iconAtEnd.value,
            )
        }
        val iconFirst = composeRule.onNodeWithTag(BUTTON_TAG).fetchSemanticsNode()
        val iconFirstWidth = iconFirst.size.width
        val iconFirstHeight = iconFirst.size.height
        val iconFirstLabelLeft = labelLeft()

        composeRule.runOnIdle { iconAtEnd.value = true }

        val iconLast = composeRule.onNodeWithTag(BUTTON_TAG).fetchSemanticsNode()
        val iconLastWidth = iconLast.size.width
        val iconLastHeight = iconLast.size.height
        val iconLastLabelLeft = labelLeft()

        // The label is centred with the icon inside the button, so the two left edges only mean the
        // same thing if the two buttons are the same size.
        assertEquals("both renderings are the same width", iconFirstWidth, iconLastWidth)
        assertEquals("both renderings are the same height", iconFirstHeight, iconLastHeight)
        assertEquals(
            "PrimaryButton renders PrimaryButtonHeightDp ($PrimaryButtonHeightDp) tall",
            with(density) { PrimaryButtonHeightDp.roundToPx() },
            iconFirstHeight,
        )

        val shift = iconLastLabelLeft - iconFirstLabelLeft
        // Each child is measured and placed in whole pixels, so the move is the rounded icon plus
        // the rounded gap, not the rounded sum.
        val expected = with(density) { ButtonIconSizeDp.roundToPx() + ButtonIconLabelGapDp.roundToPx() }
        assertEquals(
            "the label moves by the icon ($ButtonIconSizeDp) plus the gap ($ButtonIconLabelGapDp): " +
                "iconFirst left=$iconFirstLabelLeft iconLast left=$iconLastLabelLeft shift=$shift",
            expected.toFloat(),
            abs(shift),
            0.5f,
        )
        assertTrue(
            "the label moves left by that much, giving the icon the space it had: shift=$shift",
            shift < 0f,
        )
    }

    /** The label's left edge in the unmerged tree: `Modifier.clickable` has merged it into the button. */
    private fun labelLeft(): Float =
        composeRule.onNodeWithText(LABEL, substring = false, useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
            .left

    private fun luminanceOf(color: Color): Double =
        0.299 * color.red + 0.587 * color.green + 0.114 * color.blue

    /** The colour's angle on the colour wheel, in degrees; 0 for a grey, which has no hue. */
    private fun hueOf(color: Color): Double {
        val max = maxOf(color.red, color.green, color.blue).toDouble()
        val min = minOf(color.red, color.green, color.blue).toDouble()
        val delta = max - min
        if (delta == 0.0) return 0.0
        val sector = when (max) {
            color.red.toDouble() -> (color.green - color.blue) / delta
            color.green.toDouble() -> 2.0 + (color.blue - color.red) / delta
            else -> 4.0 + (color.red - color.green) / delta
        }
        return (sector * 60.0 + 360.0) % 360.0
    }

    /** How far the colour is from grey: 0 for a grey, 1 for a fully saturated one. */
    private fun saturationOf(color: Color): Double {
        val max = maxOf(color.red, color.green, color.blue).toDouble()
        val min = minOf(color.red, color.green, color.blue).toDouble()
        return if (max == 0.0) 0.0 else (max - min) / max
    }

    private companion object {
        const val BUTTON_TAG = "primary-button"
        const val LABEL = "LAUNCH"
    }
}
