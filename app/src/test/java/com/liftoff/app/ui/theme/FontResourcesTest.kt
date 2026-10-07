package com.liftoff.app.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class FontResourcesTest {

    @Test
    fun bundlesStaticBigShouldersAndWorkSansWeights() {
        // All six font TTFs must exist in res/font/ and be valid TrueType files.
        val fontDir = File("../src/main/res/font")
            .takeIf { it.exists() }
            ?: File("src/main/res/font")

        require(fontDir.exists()) { "res/font/ not found" }

        // Expected files with their weights.
        val expectedFonts = mapOf(
            "big_shoulders_display_bold.ttf"       to 700,
            "big_shoulders_display_extrabold.ttf"  to 800,
            "big_shoulders_display_black.ttf"      to 900,
            "work_sans_regular.ttf"                to 400,
            "work_sans_medium.ttf"                 to 500,
            "work_sans_semibold.ttf"               to 600,
        )

        for ((name, weight) in expectedFonts) {
            val file = File(fontDir, name)
            assertEquals("Font $name must exist", true, file.exists())

            // Verify it starts with the TrueType magic bytes (0x00 0x01 0x00 0x00).
            val bytes = file.readBytes().takeIf { it.isNotEmpty() }
                ?: throw AssertionError("Font $name is empty")
            assertEquals("$name must be a valid TTF", 0x00.toByte(), bytes[0])
            assertEquals("$name must be a valid TTF", 0x01.toByte(), bytes[1])
            assertEquals("$name must be a valid TTF", 0x00.toByte(), bytes[2])
            assertEquals("$name must be a valid TTF", 0x00.toByte(), bytes[3])

            // Verify it is not HTML (would indicate a failed download).
            val text = file.readText()
            assertEquals("$name must not be HTML", false, text.contains("<!DOCTYPE") || text.contains("<html"))
        }

        // Verify exactly 6 font files.
        val actualFiles = fontDir.listFiles { f -> f.name.endsWith(".ttf") }?.size ?: 0
        assertEquals("Exactly 6 TTF fonts expected", 6, actualFiles)
    }
}
