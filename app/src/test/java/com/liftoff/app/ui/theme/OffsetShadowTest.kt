package com.liftoff.app.ui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * What the offset shadow is made of, and what it costs in layout.
 *
 * `offsetShadow` (OffsetShadow.kt:29) and `OffsetShadowBox` (OffsetShadow.kt:67) both take their
 * defaults from the two named constants in that file, so the first case reads them from there.
 * The second case renders: it measures a node that asked for a known size and a node that sizes
 * itself to its content while painting the shadow, which is what "takes no extra layout space
 * (like CSS box-shadow)" has to mean.
 *
 * What this file deliberately does not claim: where the content of an `OffsetShadowBox` sits.
 * OffsetShadow.kt:87 defines a private no-op `Modifier.offset(Dp)` that is the only `offset` in
 * scope at its own call site (OffsetShadow.kt:81), so the content is not shifted today, and
 * design/README.md:78 asks for a shadow copy rather than a shifted content anyway. Nothing here
 * asserts either way. The shadow's own paint is a `drawBehind` fill with no node in the semantics
 * tree, so its colour is pinned as the token it defaults to; [ColorTokensTest] pins that token
 * against the table in `design/README.md`.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h2000dp")
class OffsetShadowTest {

    @get:Rule
    val composeRule = createComposeRule()

    // offsetShadowDefaultsAreFourDp — design/README.md:78 asks for "a solid ink copy of the shape,
    // 4 dp right and 4 dp down, no blur": the offset OffsetShadow.kt's two functions default to is
    // 4 dp, the corner of the shape it copies is 4 dp, the two are the same 4 dp, and the colour is
    // ink.
    @Test
    fun offsetShadowDefaultsAreFourDp() {
        val offset = OffsetShadowDefaultOffsetDp
        val corner = OffsetShadowDefaultCornerRadiusDp

        assertEquals("the shadow is offset 4 dp right and 4 dp down", 4.dp, offset)
        assertEquals("the shadow copies a 4 dp corner", 4.dp, corner)
        assertEquals("the offset and the corner it copies are the same 4 dp", offset, corner)
        assertTrue("the offset is not zero: the shadow is offset, not stacked", offset > 0.dp)
        assertTrue("the corner is round, not a hard cut: corner=$corner", corner > 0.dp)

        // The corner that reaches the drawing is the one offsetShadow builds its path from: it calls
        // shape.createOutline(size, layoutDirection, this) (OffsetShadow.kt:34) and fills that
        // outline, so the radius is read off the outline itself. The buttons OffsetShadowBox wraps
        // are filled with ButtonShape (Buttons.kt:60, 123), and a shadow is a copy of the shape it
        // sits behind, so the two corners belong to each other.
        val probe = Size(120f, 60f)
        val shadowCorner = cornerOf(RoundedCornerShape(corner), probe, Density(1f))
        val buttonCorner = cornerOf(ButtonShape, probe, Density(1f))
        assertEquals("the corner offsetShadow fills is the corner the constant names", 4.0f, shadowCorner, 0.001f)
        assertEquals("the shadow behind a button copies that button's corner", buttonCorner, shadowCorner, 0.001f)

        // Both functions default their colour to Ink (OffsetShadow.kt:30, 68) — the design's ink,
        // which is what every border in this theme is drawn with.
        val color = Ink
        assertEquals("the shadow's colour is the design's ink", Color(0xFF1D1B19), color)
        assertTrue("ink is dark enough to read as ink: $color", luminanceOf(color) < 0.2)
        assertTrue(
            "the shadow is darker than the cream paper it is drawn on: ink=${luminanceOf(color)} cream=${luminanceOf(Cream)}",
            luminanceOf(color) < luminanceOf(Cream),
        )
        assertTrue(
            "the shadow is darker than the red fill it sits behind: ink=${luminanceOf(color)} red=${luminanceOf(Red)}",
            luminanceOf(color) < luminanceOf(Red),
        )
        assertTrue("the shadow's colour is not the fill it is drawn behind: $color", color != Red)
        assertTrue("the shadow's colour is not the paper behind it either: $color", color != Cream)
    }

    // aShadowedBoxKeepsTheSizeItAskedFor — a Box that asks for 120 x 60 dp and paints the shadow
    // measures 120 x 60 dp, the same as the identical Box with no shadow at all, and a node that
    // sizes itself to its content while painting the shadow is still exactly the size of that
    // content, even when the shadow is offset 24 dp. The shadow adds no width and no height, and
    // its paint does not move the node's bounds.
    @Test
    fun aShadowedBoxKeepsTheSizeItAskedFor() {
        lateinit var density: Density

        composeRule.setContent {
            density = LocalDensity.current

            // offsetShadow is a paint on the caller's own node: the node keeps the size it asked
            // for and the shadow is drawn from that node's outline.
            Box(
                modifier = Modifier
                    .size(ASKED_WIDTH_DP, ASKED_HEIGHT_DP)
                    .offsetShadow()
                    .testTag(PAINTED_TAG),
            )

            // The same size asked for the way Buttons.kt asks for it: the caller's modifier —
            // width and height included — lands on OffsetShadowBox's outer Box (OffsetShadow.kt:73).
            OffsetShadowBox(
                modifier = Modifier
                    .width(ASKED_WIDTH_DP)
                    .height(ASKED_HEIGHT_DP)
                    .testTag(BOX_TAG),
            ) {
                Box(Modifier.fillMaxSize())
            }

            // The control: the same Box, the same size, no shadow drawn anywhere in it.
            Box(
                modifier = Modifier
                    .size(ASKED_WIDTH_DP, ASKED_HEIGHT_DP)
                    .testTag(PLAIN_TAG),
            )

            // Two nodes that take their size from their own content, with the shadow painted on the
            // outside of that content: if the shadow reserved room for its offset, these nodes would
            // come out larger than the box they hold, the second by six times as much as the first.
            Box(modifier = Modifier.wrapContentSize().offsetShadow().testTag(WRAP_DEFAULT_TAG)) {
                Box(Modifier.size(ASKED_WIDTH_DP, ASKED_HEIGHT_DP))
            }
            Box(modifier = Modifier.wrapContentSize().offsetShadow(offset = WIDE_OFFSET_DP).testTag(WRAP_WIDE_TAG)) {
                Box(Modifier.size(ASKED_WIDTH_DP, ASKED_HEIGHT_DP))
            }
        }

        val askedWidth = with(density) { ASKED_WIDTH_DP.roundToPx() }
        val askedHeight = with(density) { ASKED_HEIGHT_DP.roundToPx() }

        val painted = measuredSize(PAINTED_TAG)
        val shadowBox = measuredSize(BOX_TAG)
        val plain = measuredSize(PLAIN_TAG)
        val wrapDefault = measuredSize(WRAP_DEFAULT_TAG)
        val wrapWide = measuredSize(WRAP_WIDE_TAG)

        assertEquals("Modifier.offsetShadow() keeps the width it asked for", askedWidth, painted.width)
        assertEquals("Modifier.offsetShadow() keeps the height it asked for", askedHeight, painted.height)
        assertEquals("OffsetShadowBox keeps the width its caller asked for", askedWidth, shadowBox.width)
        assertEquals("OffsetShadowBox keeps the height its caller asked for", askedHeight, shadowBox.height)
        assertEquals("the box with no shadow is the same size", askedWidth, plain.width)
        assertEquals("the box with no shadow is the same size", askedHeight, plain.height)

        // The shadow is drawn from the node's own outline, so it changes neither dimension.
        assertEquals("the shadow adds no width: shadowed=$painted plain=$plain", plain.width, painted.width)
        assertEquals("the shadow adds no height: shadowed=$painted plain=$plain", plain.height, painted.height)
        assertEquals("OffsetShadowBox adds no width over the same box: $shadowBox vs $plain", plain.width, shadowBox.width)
        assertEquals("OffsetShadowBox adds no height over the same box: $shadowBox vs $plain", plain.height, shadowBox.height)

        assertEquals("a node wrapping its content and painting the shadow is its content's width", askedWidth, wrapDefault.width)
        assertEquals("a node wrapping its content and painting the shadow is its content's height", askedHeight, wrapDefault.height)
        assertEquals("a 24 dp offset reserves no more room than a 4 dp one", wrapDefault.width, wrapWide.width)
        assertEquals("a 24 dp offset reserves no more room than a 4 dp one", wrapDefault.height, wrapWide.height)

        // The node's bounds are where it is drawn from and to: the shadow is painted outside them
        // without making them any larger.
        val paintedBounds = composeRule.onNodeWithTag(PAINTED_TAG).fetchSemanticsNode().boundsInRoot
        assertEquals(
            "the painted node's bounds are the width it asked for",
            with(density) { ASKED_WIDTH_DP.toPx() },
            paintedBounds.width,
            0.5f,
        )
        assertEquals(
            "the painted node's bounds are the height it asked for",
            with(density) { ASKED_HEIGHT_DP.toPx() },
            paintedBounds.height,
            0.5f,
        )
    }

    /** The corner radius, in px, that a shape hands to the `createOutline` call offsetShadow makes. */
    private fun cornerOf(shape: Shape, size: Size, density: Density): Float {
        val outline = shape.createOutline(size, LayoutDirection.Ltr, density)
        assertTrue("a 4 dp corner is a rounded outline: $outline", outline is Outline.Rounded)
        return (outline as Outline.Rounded).roundRect.topLeftCornerRadius.x
    }

    private fun measuredSize(tag: String) = composeRule.onNodeWithTag(tag).fetchSemanticsNode().size

    private fun luminanceOf(color: Color): Double =
        0.299 * color.red + 0.587 * color.green + 0.114 * color.blue

    private companion object {
        const val PAINTED_TAG = "offset-shadow-painted-box"
        const val BOX_TAG = "offset-shadow-box"
        const val PLAIN_TAG = "plain-box"
        const val WRAP_DEFAULT_TAG = "wrap-default-offset"
        const val WRAP_WIDE_TAG = "wrap-wide-offset"

        val ASKED_WIDTH_DP = 120.dp
        val ASKED_HEIGHT_DP = 60.dp

        /** Far larger than the default 4 dp, so a shadow that reserved space would show it. */
        val WIDE_OFFSET_DP = 24.dp
    }
}
