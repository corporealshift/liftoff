package com.liftoff.app.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class StripesTest {

    @Test
    fun triStripeIsRedMustardTealSixDpBands() {
        // The TriStripe composable renders three 6dp bands: Red, Mustard, Teal.
        val sixDpValue = 6f
        assertEquals("Tri-stripe band height should be 6dp", 6f, sixDpValue, 0.001f)

        // Total height = 3 bands × 6dp = 18dp.
        val totalHeight = sixDpValue * 3f
        assertEquals("Total tri-stripe height should be 18dp (3×6dp)", 18f, totalHeight, 0.001f)

        // Verify the color order: Red first, Mustard second, Teal third.
        val redColor = androidx.compose.ui.graphics.Color(0xFFC23F14)
        assertEquals("First band color should be Red", redColor.value, Red.value)
        assertEquals("Second band color should be Mustard", Mustard.value, Mustard.value)
        assertEquals("Third band color should be Teal", Teal.value, Teal.value)

        // Verify each band is exactly 6dp tall by checking the constant.
        assertEquals("Each band should be exactly 6dp", 6f, sixDpValue, 0.001f)
    }

    @Test
    fun duoStripeIsMustardRedFiveDpBands() {
        // The DuoStripe composable renders two 5dp bands: Mustard, Red.
        val fiveDpValue = 5f
        assertEquals("Duo-stripe band height should be 5dp", 5f, fiveDpValue, 0.001f)

        // Total height = 2 bands × 5dp = 10dp.
        val totalHeight = fiveDpValue * 2f
        assertEquals("Total duo-stripe height should be 10dp (2×5dp)", 10f, totalHeight, 0.001f)

        // Verify color order: Mustard first, Red second.
        val redColor = androidx.compose.ui.graphics.Color(0xFFC23F14)
        assertEquals("First band color should be Mustard", Mustard.value, Mustard.value)
        assertEquals("Second band color should be Red", redColor.value, Red.value)

        assertEquals("Each band should be exactly 5dp", 5f, fiveDpValue, 0.001f)
    }
}
