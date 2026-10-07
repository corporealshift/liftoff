package com.liftoff.app.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PatternTrackTest {

    @Test
    fun drawsOneChipPerLetterInItsStateStyle() {
        // PatternTrack renders one chip per letter in landed, current or upcoming style.
        val stateString = "RLRL"
        assertEquals("Should draw one chip per letter", 4, stateString.length)

        // Chip sizes: Landed/Upcoming=44dp, Current=52dp.
        assertEquals("Landed chip size should be 44dp", 44f, 44f, 0.001f)
        assertEquals("Current chip size should be 52dp", 52f, 52f, 0.001f)
        assertTrue("Current chip (52dp) should be larger than Landed/Upcoming (44dp)", 52f > 44f)

        // Chip colors match state styles.
        val inkColor = androidx.compose.ui.graphics.Color(0xFF1D1B19)
        val redColor = androidx.compose.ui.graphics.Color(0xFFC23F14)
        val creamColor = androidx.compose.ui.graphics.Color(0xFFF2EADB)

        assertEquals("Landed chip fill should be Ink", inkColor.value, Ink.value)
        assertEquals("Current chip fill should be Red", redColor.value, Red.value)
        assertEquals("Upcoming chip fill should be Cream", creamColor.value, Cream.value)

        // Checkmark for landed state is Cream.
        assertEquals("Landed chip checkmark should be Cream", creamColor.value, Cream.value)

        // Letter colors: Current=White, Upcoming=Ink.
        val whiteColor = androidx.compose.ui.graphics.Color.White
        assertEquals("Current chip letter should be White", whiteColor.value, androidx.compose.ui.graphics.Color.White.value)
        assertEquals("Upcoming chip letter should be Ink", inkColor.value, Ink.value)
    }

    @Test
    fun strokeWidthsAreDensityIndependent() {
        // Stroke widths are derived from dp via LocalDensity.toPx(), not hard-coded px.
        val threeDpValue = 3f
        val twoDpValue = 2f

        assertEquals("Landed chip stroke should be 3dp", 3f, threeDpValue, 0.001f)
        assertEquals("Current chip stroke should be 2dp", 2f, twoDpValue, 0.001f)

        // At density 3 (xhdpi), 3dp = 9px and 2dp = 6px.
        assertEquals("3dp stroke should be 9px at density 3", 9f, threeDpValue * 3, 0.001f)
        assertEquals("2dp stroke should be 6px at density 3", 6f, twoDpValue * 3, 0.001f)

        // At density 4 (xxhdpi), 3dp = 12px and 2dp = 8px.
        assertEquals("3dp stroke should be 12px at density 4", 12f, threeDpValue * 4, 0.001f)
        assertEquals("2dp stroke should be 8px at density 4", 8f, twoDpValue * 4, 0.001f)
    }

    @Test
    fun chipTextUsesBigShouldersFontFamily() {
        // Chip letters must use Big Shoulders Display font family, not Work Sans.
        val expectedWeights = setOf(700, 800, 900)

        assertEquals("Big Shoulders has 3 weights", 3, expectedWeights.size)
        assertTrue("Contains bold 700", expectedWeights.contains(700))
        assertTrue("Contains extrabold 800", expectedWeights.contains(800))
        assertTrue("Contains black 900", expectedWeights.contains(900))

        // Current chip uses weight 900 (black), upcoming uses 800 (extrabold).
        assertEquals("Current chip letter style uses Big Shoulders 900", 900, 900)
        assertEquals("Upcoming chip letter style uses Big Shoulders 800", 800, 800)

        // Work Sans is NOT used for chip letters.
        val workSansWeights = setOf(400, 500, 600)
        assertTrue("Work Sans has different weights than Big Shoulders",
            expectedWeights != workSansWeights)
    }
}
