package com.liftoff.app.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class ThemePackageTest {

    @Test
    fun buildingBlocksAreDeclaredInUiTheme() {
        // All theme and component files must declare package com.liftoff.app.ui.theme.
        // This includes Color, Type, Shape, LiftoffTheme, OffsetShadow, Stripes,
        // Buttons, Headings, PatternTrack, InkRuledListRow, and Icons.
        val srcDir = File("../src/main/java/com/liftoff/app/ui/theme")
            .takeIf { it.exists() }
            ?: File("src/main/java/com/liftoff/app/ui/theme")

        require(srcDir.exists()) { "ui/theme source dir not found" }

        val expectedFiles = listOf(
            "Color.kt",
            "Type.kt",
            "Shape.kt",
            "LiftoffTheme.kt",
            "OffsetShadow.kt",
            "Stripes.kt",
            "Buttons.kt",
            "Headings.kt",
            "PatternTrack.kt",
            "InkRuledListRow.kt",
            "Icons.kt",
        )

        for (fileName in expectedFiles) {
            val file = File(srcDir, fileName)
            assertEquals("$fileName must exist", true, file.exists())

            // Verify the package declaration.
            val content = file.readText()
            val firstLine = content.lines().firstOrNull { it.isNotBlank() } ?: ""
            assertEquals(
                "$fileName first non-empty line must be the package declaration",
                "package com.liftoff.app.ui.theme",
                firstLine,
            )
        }

        // Verify no files outside ui.theme declare this package (all building blocks are here).
        val ktFiles = srcDir.listFiles { f -> f.name.endsWith(".kt") } ?: emptyArray()
        assertEquals("Expected ${expectedFiles.size} .kt files in ui/theme", expectedFiles.size, ktFiles.size)
    }
}
