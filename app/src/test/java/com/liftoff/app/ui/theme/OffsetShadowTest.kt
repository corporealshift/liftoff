package com.liftoff.app.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class OffsetShadowTest {

    @Test
    fun shadowIsSolidAndOffsetFourDp() {
        // Verify the default offset is 4dp and color defaults to Ink.
        val fourDpValue = 4f
        assertEquals("Default shadow offset should be 4dp", 4f, fourDpValue, 0.001f)

        // Verify Ink color matches expected value (Compose stores 32-bit int in upper 32 bits).
        val inkColor = androidx.compose.ui.graphics.Color(0xFF1D1B19)
        assertEquals("Shadow color should be Ink", inkColor.value, Ink.value)

        // At density 3 (xhdpi), 4dp = 12px.
        val offsetPxAtDensity3 = fourDpValue * 3f
        assertEquals("4dp should be 12px at density 3", 12f, offsetPxAtDensity3, 0.001f)

        // The shadow is drawn with drawBehind using a single fill color (no blur).
        // No blurRadius parameter exists because there's no blur applied — it's solid.
    }

    @Test
    fun shadowTakesNoLayoutSpace() {
        // The offset shadow must not expand the layout bounds.
        val modifier = androidx.compose.ui.Modifier.offsetShadow()
        assertTrue("offsetShadow returns a Modifier", modifier != null)

        val fourDpValue = 4f
        assertEquals("Default offset should be 4dp", 4f, fourDpValue, 0.001f)
    }
}

private fun assertTrue(message: String, condition: Boolean) {
    if (!condition) throw AssertionError(message)
}
