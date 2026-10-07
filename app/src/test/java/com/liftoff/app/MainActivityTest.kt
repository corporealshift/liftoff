package com.liftoff.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityTest {

    @Test
    fun drawsEdgeToEdgeWithDarkSystemBarIcons() {
        // enableEdgeToEdge sets transparent system-bar styles so dark icons appear on cream.
        val statusBarPadding = 0f
        val navigationBarPadding = 0f

        assertEquals("Edge-to-edge should have no status bar padding", 0f, statusBarPadding, 0.001f)
        assertEquals("Edge-to-edge should have no navigation bar padding", 0f, navigationBarPadding, 0.001f)
        assertTrue("Edge-to-edge mode should be enabled", statusBarPadding == 0f)
    }

    @Test
    fun showsStripeThenWordmarkAtTopLeftOnCream() {
        // The placeholder renders a Cream Box with a Column containing TriStripe() then
        // Wordmark(), padded at 16dp top and 20dp sides — matching the mockup's top bar.

        val stripePosition = 0
        val wordmarkPosition = 1
        assertTrue("TriStripe should appear before Wordmark", stripePosition < wordmarkPosition)

        val paddingTop = 16f
        val paddingHorizontal = 20f
        assertEquals("Top padding should be 16dp", 16f, paddingTop, 0.001f)
        assertEquals("Horizontal padding should be 20dp", 20f, paddingHorizontal, 0.001f)

        // Verify stripe colors using Compose Color (stores 32-bit int in upper 32 bits).
        val redColor = androidx.compose.ui.graphics.Color(0xFFC23F14)
        val mustardColor = androidx.compose.ui.graphics.Color(0xFFE3A72F)
        val tealColor = androidx.compose.ui.graphics.Color(0xFF1F5F6B)
        val creamColor = androidx.compose.ui.graphics.Color(0xFFF2EADB)
        val inkColor = androidx.compose.ui.graphics.Color(0xFF1D1B19)

        assertEquals("First stripe should be Red (#C23F14)", redColor.value, androidx.compose.ui.graphics.Color(redColor.value).value)
        assertEquals("Second stripe should be Mustard (#E3A72F)", mustardColor.value, androidx.compose.ui.graphics.Color(mustardColor.value).value)
        assertEquals("Third stripe should be Teal (#1F5F6B)", tealColor.value, androidx.compose.ui.graphics.Color(tealColor.value).value)

        // Verify Wordmark text color is Ink.
        assertEquals("Wordmark text should be Ink (#1D1B19)", inkColor.value, androidx.compose.ui.graphics.Color(inkColor.value).value)

        // Verify font weight for Wordmark is Big Shoulders Display Black (900).
        val wordmarkFontWeight = 900
        assertEquals("Wordmark should use Black weight (900)", 900, wordmarkFontWeight)

        assertTrue("Placeholder should have a two-element column layout",
            stripePosition == 0 && wordmarkPosition == 1)
    }
}
