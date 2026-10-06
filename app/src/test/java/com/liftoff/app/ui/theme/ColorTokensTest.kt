package com.liftoff.app.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class ColorTokensTest {

    @Test
    fun tokensMatchDesignReadme() {
        // Gradle unit tests run with the app/ module as working directory.
        val readme = File("../design/README.md")
            .takeIf { it.exists() }
            ?: File("design/README.md")

        require(readme.exists()) { "design/README.md not found" }

        // Parse the color table rows: | `name` | `#XXXXXX` | ...
        val lines = readme.readLines()
        var inTable = false
        val tokens = mutableMapOf<String, String>()

        for (line in lines) {
            if (line.trimStart().startsWith("| Token | Hex")) {
                inTable = true
                continue
            }
            if (inTable && line.trimStart().startsWith("|---|")) continue
            if (inTable && line.trimStart().startsWith("|")) {
                val parts = line.split('|').map { it.trim() }.filter { it.isNotEmpty() }
                if (parts.size >= 2) {
                    val name = parts[0].removeSurrounding("`")
                    val hex = parts[1].removeSurrounding("`").uppercase()
                    tokens[name] = hex
                }
            }
            if (inTable && !line.trimStart().startsWith("|")) break
        }

        assertEquals("Found exactly 12 color tokens in README", 12, tokens.size)

        val nameToColor = mapOf(
            "cream" to ::Cream,
            "paper" to ::Paper,
            "sand" to ::Sand,
            "ink" to ::Ink,
            "red" to ::Red,
            "red_pressed" to ::RedPressed,
            "mustard" to ::Mustard,
            "teal" to ::Teal,
            "teal_light" to ::TealLight,
            "muted" to ::Muted,
            "rule" to ::Rule,
            "white" to ::White,
        )

        for ((name, hex) in tokens) {
            val colorFn = nameToColor[name]
            assertEquals("Token $name should exist in LiftoffColors", true, colorFn != null)

            val expectedArgb = (0xFF000000L or hex.substring(1).toLong(16)).toInt()
            val actual = colorFn!!().hashCode()
            assertEquals("Token $name hex mismatch", expectedArgb, actual)
        }
    }
}
