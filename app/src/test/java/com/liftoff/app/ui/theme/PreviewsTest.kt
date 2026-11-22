package com.liftoff.app.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class PreviewsTest {

    @Test
    fun noPreviewComposableIsPublicApi() {
        // Every @Preview composable in ui/theme is private.
        val themeDir = File("src/main/java/com/liftoff/app/ui/theme")
        var previewCount = 0
        for (file in themeDir.listFiles().orEmpty()) {
            if (!file.name.endsWith(".kt")) continue
            val lines = file.readLines()
            for (i in lines.indices) {
                if ("@Preview" in lines[i]) {
                    // Look ahead up to 3 lines for the function declaration
                    // (@Composable may sit between @Preview and private fun). A declaration that is
                    // not private never matches, so it leaves found false and the check below names
                    // the file and line.
                    var found = false
                    for (j in i + 1..minOf(i + 3, lines.size - 1)) {
                        val decl = lines[j].trim()
                        if (decl.startsWith("private fun") || decl.startsWith("private suspend")) {
                            previewCount++
                            found = true
                            break
                        }
                        if (decl.isEmpty()) continue
                    }
                    check(found) {
                        "${file.name}:$i — @Preview without a following private fun declaration"
                    }
                }
            }
        }
        assertEquals("9 @Preview composables expected", 9, previewCount)
    }
}
