package com.liftoff.app.ui.theme

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasParent
import androidx.compose.ui.test.hasTestTag
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

/**
 * PatternTrack's chip diameters, the track it renders, and the widths and colours it draws with.
 *
 * `PatternTrack(chips, modifier)` takes no colour parameters, so the colours it draws with are the
 * package-level tokens it reads directly — Ink, Red, Cream, White, Sand, Muted — which are also
 * what [LiftoffColorScheme] installs in the theme. The colour assertions below read those tokens
 * against the hex values `design/README.md` gives them; [ColorTokensTest] pins the tokens
 * themselves.
 *
 * Only a chip that draws its letter in a `Text` reaches the semantics tree: a Landed chip is a
 * `Canvas` circle plus `Icon(contentDescription = null)`, which carries no node of its own.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h2000dp")
class PatternTrackTest {

    @get:Rule
    val composeRule = createComposeRule()

    // chipSizeFollowsChipState — chipSizeDp returns 52 dp for Current and 44 dp for Landed,
    // Upcoming and Scrubbed, each one read from the constant named for that state, and Current is
    // the larger of the four.
    @Test
    fun chipSizeFollowsChipState() {
        assertEquals("a Landed chip is 44 dp", 44.dp, ChipSizeLandedDp)
        assertEquals("a Current chip is 52 dp", 52.dp, ChipSizeCurrentDp)
        assertEquals("an Upcoming chip is 44 dp", 44.dp, ChipSizeUpcomingDp)
        assertEquals("a Scrubbed chip is 44 dp", 44.dp, ChipSizeScrubbedDp)

        assertEquals("chipSizeDp(Landed) is ChipSizeLandedDp", ChipSizeLandedDp, chipSizeDp(ChipState.Landed))
        assertEquals("chipSizeDp(Current) is ChipSizeCurrentDp", ChipSizeCurrentDp, chipSizeDp(ChipState.Current))
        assertEquals("chipSizeDp(Upcoming) is ChipSizeUpcomingDp", ChipSizeUpcomingDp, chipSizeDp(ChipState.Upcoming))
        assertEquals("chipSizeDp(Scrubbed) is ChipSizeScrubbedDp", ChipSizeScrubbedDp, chipSizeDp(ChipState.Scrubbed))

        assertTrue(
            "Current is larger than Landed: current=$ChipSizeCurrentDp landed=$ChipSizeLandedDp",
            ChipSizeCurrentDp > ChipSizeLandedDp,
        )
        assertTrue(
            "Current is larger than Upcoming: current=$ChipSizeCurrentDp upcoming=$ChipSizeUpcomingDp",
            ChipSizeCurrentDp > ChipSizeUpcomingDp,
        )
        assertTrue(
            "Current is larger than Scrubbed: current=$ChipSizeCurrentDp scrubbed=$ChipSizeScrubbedDp",
            ChipSizeCurrentDp > ChipSizeScrubbedDp,
        )
        assertEquals("Landed and Upcoming are the same size", ChipSizeLandedDp, ChipSizeUpcomingDp)
        assertEquals("Landed and Scrubbed are the same size", ChipSizeLandedDp, ChipSizeScrubbedDp)
    }

    // trackDrawsOneChipPerLetterAndMeasuresItsHeight — a rendered track of "D" (landed), "R"
    // (current), "L" (upcoming) and "S" (scrubbed) shows one semantics node per letter the tree
    // can see, and measures PatternTrackHeightDp tall. That height is a Current chip's diameter by
    // construction (PatternTrack.kt), and chipSizeFollowsChipState pins that at 52 dp.
    @Test
    fun trackDrawsOneChipPerLetterAndMeasuresItsHeight() {
        val chips = listOf(
            PatternChip(LANDED_LETTER, ChipState.Landed),
            PatternChip(CURRENT_LETTER, ChipState.Current),
            PatternChip(UPCOMING_LETTER, ChipState.Upcoming),
            PatternChip(SCRUBBED_LETTER, ChipState.Scrubbed),
        )
        lateinit var density: Density

        composeRule.setContent {
            density = LocalDensity.current
            PatternTrack(chips = chips, modifier = Modifier.testTag(TRACK_TAG))
        }

        // What is being counted: the semantics nodes the track itself carries. Current, Upcoming
        // and Scrubbed each draw their letter in a Text, so they contribute one node each; a
        // Landed chip is a Canvas circle plus Icon(contentDescription = null), which carries no
        // semantics at all, so four chips put three letters in the tree.
        composeRule.onAllNodes(hasParent(hasTestTag(TRACK_TAG))).assertCountEquals(3)

        // One node per letter: assertExists fails on no match and on more than one.
        composeRule.onNodeWithText(CURRENT_LETTER, substring = false).assertExists()
        composeRule.onNodeWithText(UPCOMING_LETTER, substring = false).assertExists()
        composeRule.onNodeWithText(SCRUBBED_LETTER, substring = false).assertExists()
        composeRule.onNodeWithText(LANDED_LETTER, substring = false).assertDoesNotExist()

        val heightPx = with(density) { PatternTrackHeightDp.roundToPx() }
        val track = composeRule.onNodeWithTag(TRACK_TAG).fetchSemanticsNode()
        assertEquals(
            "PatternTrack renders PatternTrackHeightDp ($PatternTrackHeightDp) tall " +
                "(${heightPx}px at density ${density.density})",
            heightPx,
            track.size.height,
        )
    }

    // chipRingsAndConnectorAreTheDesignWidths — the rings are 3 dp (Current) and 2 dp (Upcoming,
    // Scrubbed), the connector line is 2 dp wide and inset 22 dp from each end, and the check mark
    // inside a Landed chip is 20 dp — all read from the values PatternTrack.kt draws with.
    @Test
    fun chipRingsAndConnectorAreTheDesignWidths() {
        val currentRing = ChipRingCurrentWidthDp
        val upcomingRing = ChipRingUpcomingWidthDp
        val scrubbedRing = ChipRingScrubbedWidthDp
        val connector = ConnectorWidthDp

        assertEquals("the Current ring is the 3 dp the design gives it", 3.dp, currentRing)
        assertEquals("the Upcoming ring is 2 dp", 2.dp, upcomingRing)
        assertEquals("the Scrubbed ring is 2 dp", 2.dp, scrubbedRing)
        assertEquals("the connector line is 2 dp", 2.dp, connector)

        assertTrue("Current's ring is thicker than Upcoming's: current=$currentRing upcoming=$upcomingRing",
            currentRing > upcomingRing)
        assertTrue("Current's ring is thicker than Scrubbed's: current=$currentRing scrubbed=$scrubbedRing",
            currentRing > scrubbedRing)
        assertTrue("the connector is thinner than the Current ring it runs into: connector=$connector current=$currentRing",
            connector < currentRing)
        // The connector is the same width as the two thinner rings today, so it is no thicker than
        // either of them rather than thinner than both.
        assertTrue("the connector is no thicker than Upcoming's ring: connector=$connector upcoming=$upcomingRing",
            connector <= upcomingRing)
        assertTrue("the connector is no thicker than Scrubbed's ring: connector=$connector scrubbed=$scrubbedRing",
            connector <= scrubbedRing)
        assertEquals("the connector is the same width as Upcoming's ring", upcomingRing, connector)

        assertEquals("the connector inset is 22 dp", 22.dp, ConnectorInsetDp)
        assertEquals(
            "the inset is half a non-Current chip's diameter, so the line starts at that chip's edge",
            ChipSizeLandedDp / 2,
            ConnectorInsetDp,
        )
        assertEquals("the check mark is 20 dp", 20.dp, LandedCheckSizeDp)
        assertTrue(
            "the check mark is smaller than the chip it sits in: check=$LandedCheckSizeDp chip=$ChipSizeLandedDp",
            LandedCheckSizeDp < ChipSizeLandedDp,
        )
    }

    // chipColoursAreTheTokensPatternTrackDrawsWith — the six colours PatternTrack.kt draws with
    // are the theme's tokens: Ink for the connector, every ring, the Landed fill and the Upcoming
    // letter; Red for the Current fill; Cream for the Upcoming fill and the Landed check mark;
    // White for the Current letter; Sand for the Scrubbed fill; Muted for the Scrubbed letter.
    @Test
    fun chipColoursAreTheTokensPatternTrackDrawsWith() {
        assertEquals("the connector, the rings and the Landed fill are Ink", Color(0xFF1D1B19), Ink)
        assertEquals("the Current fill is Red", Color(0xFFC23F14), Red)
        assertEquals("the Upcoming fill and the Landed check mark are Cream", Color(0xFFF2EADB), Cream)
        assertEquals("the Current letter is White", Color(0xFFFFFFFF), White)
        assertEquals("the Scrubbed fill is Sand", Color(0xFFE8DFCD), Sand)
        assertEquals("the Scrubbed letter is Muted", Color(0xFF5E5850), Muted)

        // The chip states are distinguishable from each other, computed from the tokens rather
        // than from the hex values above.
        assertTrue("the Landed fill is not the Current fill: ink=$Ink red=$Red", Ink != Red)
        assertTrue("the Current letter is not the fill behind it: white=$White ink=$Ink", White != Ink)
        assertTrue("the Upcoming fill is not the Scrubbed fill: cream=$Cream sand=$Sand", Cream != Sand)
        assertTrue("the Scrubbed letter is not the Ink every other letter uses: muted=$Muted ink=$Ink", Muted != Ink)
        assertTrue("the Scrubbed fill is not the Current fill: sand=$Sand red=$Red", Sand != Red)
    }

    private companion object {
        const val TRACK_TAG = "pattern-track"
        const val LANDED_LETTER = "D"
        const val CURRENT_LETTER = "R"
        const val UPCOMING_LETTER = "L"
        const val SCRUBBED_LETTER = "S"
    }
}
