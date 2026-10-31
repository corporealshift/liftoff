package com.liftoff.app.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class IconsTest {

    @Test
    fun strokeIconsAre24dpVectorDrawables() {
        // Verify all five stroke icons exist as 24dp vector drawables in res/drawable/.
        val drawableDir = File("../src/main/res/drawable")
            .takeIf { it.exists() }
            ?: File("src/main/res/drawable")

        require(drawableDir.exists()) { "res/drawable/ not found" }

        val expectedIcons = listOf("ic_rocket", "ic_planet", "ic_flag", "ic_sliders", "ic_check")

        for (iconName in expectedIcons) {
            val xmlFile = File(drawableDir, "$iconName.xml")
            assertEquals("Drawable $iconName.xml must exist", true, xmlFile.exists())

            // Verify it's a valid vector drawable with 24dp dimensions.
            val content = xmlFile.readText()
            assertEquals("$iconName should have viewportWidth=24", "24", extractAttribute(content, "viewportWidth"))
            assertEquals("$iconName should have viewportHeight=24", "24", extractAttribute(content, "viewportHeight"))

            // Verify it uses stroke (outline) style, not filled paths.
            val hasStroke = content.contains("strokeColor=") || content.contains("android:strokeColor=")
            assertEquals("$iconName should use stroke/drawable style", true, hasStroke)
        }
    }

    private fun extractAttribute(xml: String, attr: String): String? {
        val pattern = Regex("""$attr="([^"]+)"\s*/>""")
        return pattern.find(xml)?.groupValues?.get(1)
            ?: Regex("""$attr="([^"]+)""").find(xml)?.groupValues?.get(1)
    }
}
