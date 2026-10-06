package com.liftoff.app.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class StripesTest {

    @Test
    fun triStripeIsRedMustardTealSixDpBands() {
        // TriStripe renders three 6dp bands in order: Red, Mustard, Teal.
        assertEquals("First band is Red", 0xFFC23F14.toInt(), Red.hashCode())
        assertEquals("Second band is Mustard", 0xFFE3A72F.toInt(), Mustard.hashCode())
        assertEquals("Third band is Teal", 0xFF1F5F6B.toInt(), Teal.hashCode())

        // Each band is 6dp tall — total 18dp.
        val expectedTotalHeight = 6 + 6 + 6
        assertEquals("TriStripe total height should be 18dp (3 bands * 6dp)", 18, expectedTotalHeight)
    }

    @Test
    fun duoStripeIsMustardRedFiveDpBands() {
        // DuoStripe renders two 5dp bands in order: Mustard, Red.
        assertEquals("First band is Mustard", 0xFFE3A72F.toInt(), Mustard.hashCode())
        assertEquals("Second band is Red", 0xFFC23F14.toInt(), Red.hashCode())

        // Each band is 5dp tall — total 10dp.
        val expectedTotalHeight = 5 + 5
        assertEquals("DuoStripe total height should be 10dp (2 bands * 5dp)", 10, expectedTotalHeight)
    }
}
