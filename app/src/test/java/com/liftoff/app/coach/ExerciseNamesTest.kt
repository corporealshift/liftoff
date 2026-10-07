package com.liftoff.app.coach

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class ExerciseNamesTest {

    @Test
    fun equivalentSpellingsNormalizeToBenchPress() {
        assertEquals("bench press", ExerciseNames.normalize("Bench Press"))
        assertEquals("bench press", ExerciseNames.normalize("bench  press"))
        assertEquals("bench press", ExerciseNames.normalize("BENCH PRESS"))
        assertEquals("bench press", ExerciseNames.normalize(" Bench Press. "))
    }

    @Test
    fun hyphenIsKept() {
        assertEquals("bench-press", ExerciseNames.normalize("Bench-Press"))
    }

    @Test
    fun symbolsAreRemoved() {
        assertEquals("smith machine bench", ExerciseNames.normalize("Smith Machine & Bench"))
        assertEquals("push-up weighted", ExerciseNames.normalize("Push-Up (Weighted)"))
        assertEquals("farmers walk", ExerciseNames.normalize("Farmer's Walk"))
    }

    @Test
    fun unicodeLettersAndDigitsAreKept() {
        assertEquals("café curl", ExerciseNames.normalize("Café Curl"))
    }

    @Test
    fun unicodeWhitespaceCollapsesToOneSpace() {
        assertEquals("bench press", ExerciseNames.normalize("Bench\t\nPress"))
        assertEquals("bench press", ExerciseNames.normalize("Bench\u00A0Press"))
    }

    @Test
    fun lowercasingIgnoresDefaultLocale() {
        assertEquals("incline", ExerciseNames.normalize("INCLINE"))
    }

    @Test
    fun symbolsOnlyNameNormalizesToEmpty() {
        assertEquals("", ExerciseNames.normalize("!!!"))
    }

    @Test
    fun coachPackageHasNoAndroidImports() {
        val srcDir = File("../src/main/java/com/liftoff/app/coach")
            .takeIf { it.exists() }
            ?: File("src/main/java/com/liftoff/app/coach")

        require(srcDir.exists()) { "coach source dir not found" }

        val ktFiles = srcDir.listFiles { f -> f.name.endsWith(".kt") } ?: emptyArray()
        for (file in ktFiles) {
            val content = file.readText()
            assertEquals(
                "$file must not import android.",
                false,
                "import android." in content,
            )
            assertEquals(
                "$file must not import androidx.",
                false,
                "import androidx." in content,
            )
        }
    }
}
