package com.liftoff.app.ui.theme

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Stripes' two band heights, and the heights the composables render from them.
 *
 * The band fills and their order are not asserted here: a `Box` sized and filled through
 * `Modifier` carries no semantics node, so the tree cannot see them, and [ColorTokensTest]
 * already pins every token's value against the table in `design/README.md`.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h2000dp")
class StripesTest {

    @get:Rule
    val composeRule = createComposeRule()

    // stripeBandHeightsAreTheValuesTheStripesDraw — TriStripe sizes three band Boxes with
    // TriStripeBandHeightDp and DuoStripe two with DuoStripeBandHeightDp (Stripes.kt). Every
    // assertion below reads one of those two values, so a change at a draw site fails it.
    @Test
    fun stripeBandHeightsAreTheValuesTheStripesDraw() {
        val triBand = TriStripeBandHeightDp
        val duoBand = DuoStripeBandHeightDp

        assertEquals("TriStripe's band is the 6 dp the design gives it", 6.dp, triBand)
        assertEquals("DuoStripe's band is the 5 dp the design gives it", 5.dp, duoBand)
        assertTrue(
            "TriStripe's band is taller than DuoStripe's: tri=$triBand duo=$duoBand",
            triBand > duoBand,
        )
        assertEquals("TriStripe's three equal bands stack to 18 dp", 18.dp, triBand * 3)
        assertEquals("DuoStripe's two equal bands stack to 10 dp", 10.dp, duoBand * 2)
    }

    // stripesRenderAtTheirBandHeights — a rendered TriStripe measures 18 dp tall, three times
    // TriStripeBandHeightDp, and a rendered DuoStripe 10 dp, twice DuoStripeBandHeightDp; both
    // are converted at the density the composition itself is running at.
    @Test
    fun stripesRenderAtTheirBandHeights() {
        lateinit var density: Density

        composeRule.setContent {
            density = LocalDensity.current
            TriStripe(modifier = Modifier.testTag(TRI_TAG))
            DuoStripe(modifier = Modifier.testTag(DUO_TAG))
        }

        // Compose lays out in whole pixels and rounds each band, so the height to expect is the
        // band count times the rounded band — not the rounded total of the unrounded bands.
        val triBandPx = with(density) { TriStripeBandHeightDp.roundToPx() }
        val duoBandPx = with(density) { DuoStripeBandHeightDp.roundToPx() }

        val tri = composeRule.onNodeWithTag(TRI_TAG).fetchSemanticsNode()
        val duo = composeRule.onNodeWithTag(DUO_TAG).fetchSemanticsNode()

        assertEquals(
            "TriStripe renders 3 bands of $TriStripeBandHeightDp " +
                "(${3 * triBandPx}px at density ${density.density})",
            3 * triBandPx,
            tri.size.height,
        )
        assertEquals(
            "DuoStripe renders 2 bands of $DuoStripeBandHeightDp " +
                "(${2 * duoBandPx}px at density ${density.density})",
            2 * duoBandPx,
            duo.size.height,
        )
    }

    private companion object {
        const val TRI_TAG = "tri-stripe"
        const val DUO_TAG = "duo-stripe"
    }
}
