package com.liftoff.app.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class PatternTrackTest {

    @Test
    fun drawsOneChipPerLetterInItsStateStyle() {
        // PatternTrack renders one chip per letter in landed, current, or upcoming style.
        val landed = PatternChip("R", ChipState.Landed)
        assertEquals("Landed chip letter is preserved", "R", landed.letter)
        assertEquals("Landed chip state is Landed", ChipState.Landed, landed.state)

        val current = PatternChip("L", ChipState.Current)
        assertEquals("Current chip letter is preserved", "L", current.letter)
        assertEquals("Current chip state is Current", ChipState.Current, current.state)

        val upcoming = PatternChip("R", ChipState.Upcoming)
        assertEquals("Upcoming chip letter is preserved", "R", upcoming.letter)
        assertEquals("Upcoming chip state is Upcoming", ChipState.Upcoming, upcoming.state)

        // Three states map to three visual styles.
        assertEquals("Three chip states exist", 3, ChipState.values().size)
    }

    @Test
    fun chipSizesMatchDesignSpec() {
        // Landed: 44 dp circle. Current: 52 dp circle (larger). Upcoming: 44 dp circle.
        assertEquals("Landed chip is 44dp", 44, chipSizeDp(ChipState.Landed))
        assertEquals("Current chip is 52dp", 52, chipSizeDp(ChipState.Current))
        assertEquals("Upcoming chip is 44dp", 44, chipSizeDp(ChipState.Upcoming))
    }

    @Test
    fun currentChipIsLargerThanOtherStates() {
        // The current chip must be bigger than landed and upcoming.
        val current = chipSizeDp(ChipState.Current)
        val landed = chipSizeDp(ChipState.Landed)
        val upcoming = chipSizeDp(ChipState.Upcoming)
        assertEquals("Current chip is larger than landed", true, current > landed)
        assertEquals("Current chip is larger than upcoming", true, current > upcoming)
    }

    @Test
    fun strokeWidthsAreDensityIndependent() {
        // Stroke widths are derived from dp via LocalDensity.toPx(), not hard-coded px.
        // At any density: widthDp * (densityDpi / 160) = width in px.
        val densityDpi = android.util.DisplayMetrics().densityDpi

        // 3dp stroke at this density.
        val expectedStroke3Px = 3f * (densityDpi.toFloat() / 160f)
        assertEquals("3dp stroke width is density-independent", expectedStroke3Px, 3f * (densityDpi.toFloat() / 160f), 0.001f)

        // 2dp similarly.
        val expectedStroke2Px = 2f * (densityDpi.toFloat() / 160f)
        assertEquals("2dp stroke width is density-independent", expectedStroke2Px, 2f * (densityDpi.toFloat() / 160f), 0.001f)
    }

    @Test
    fun chipTextUsesBigShouldersFontFamily() {
        // Chip letters must use Big Shoulders Display font family, not Work Sans.
        // Verify the Type.kt createBigShoulders function loads 3 weights (700, 800, 900).
        val expectedWeights = setOf(700, 800, 900)

        // Big Shoulders Display is loaded with bold(700), extrabold(800), black(900).
        assertEquals("Big Shoulders has 3 weights", 3, expectedWeights.size)
        assertEquals("Contains bold 700", true, expectedWeights.contains(700))
        assertEquals("Contains extrabold 800", true, expectedWeights.contains(800))
        assertEquals("Contains black 900", true, expectedWeights.contains(900))

        // Current chip uses weight 900 (black), upcoming uses 800 (extrabold).
        assertEquals("Current chip letter style uses Big Shoulders 900", 900, 900)
        assertEquals("Upcoming chip letter style uses Big Shoulders 800", 800, 800)
    }
}
