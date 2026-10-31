package com.liftoff.app.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class FontLicenseTest {

    @Test
    fun oflTextShipsInApkAssetsPerFamily() {
        // OFL license text files must ship in app/src/main/assets/licenses/, one per family.
        val assetsDir = File("../src/main/assets/licenses")
            .takeIf { it.exists() }
            ?: File("src/main/assets/licenses")

        require(assetsDir.exists()) { "assets/licenses/ not found" }

        // Big Shoulders Display license.
        val bsLicense = File(assetsDir, "OFL-BigShouldersDisplay.txt")
        assertEquals("OFL-BigShouldersDisplay.txt must exist", true, bsLicense.exists())
        val bsText = bsLicense.readText()
        assert(bsText.contains("SIL OPEN FONT LICENSE")) { "Big Shoulders license must contain 'SIL OPEN FONT LICENSE'" }
        assert(bsText.contains("Big Shoulders")) { "Big Shoulders license must mention the font name" }

        // Work Sans license.
        val wsLicense = File(assetsDir, "OFL-WorkSans.txt")
        assertEquals("OFL-WorkSans.txt must exist", true, wsLicense.exists())
        val wsText = wsLicense.readText()
        assert(wsText.contains("SIL OPEN FONT LICENSE")) { "Work Sans license must contain 'SIL OPEN FONT LICENSE'" }
        assert(wsText.contains("Work Sans") || wsText.contains("google")) { "Work Sans license must mention the font or project" }

        // Verify exactly 2 license files.
        val actualFiles = assetsDir.listFiles { f -> f.name.startsWith("OFL-") }?.size ?: 0
        assertEquals("Exactly 2 OFL license files expected", 2, actualFiles)
    }
}
