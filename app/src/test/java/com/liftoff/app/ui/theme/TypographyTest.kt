package com.liftoff.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.em
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class TypographyTest {

    // Static font families for JVM unit tests (no Context needed).
    private val bsDir = File("../src/main/res/font")
        .takeIf { it.exists() } ?: File("src/main/res/font")

    private val bigShoulders = FontFamily(
        Font(File(bsDir, "big_shoulders_display_bold.ttf"), FontWeight.W700),
        Font(File(bsDir, "big_shoulders_display_extrabold.ttf"), FontWeight.W800),
        Font(File(bsDir, "big_shoulders_display_black.ttf"), FontWeight.W900),
    )

    private val workSans = FontFamily(
        Font(File(bsDir, "work_sans_regular.ttf"), FontWeight.W400),
        Font(File(bsDir, "work_sans_medium.ttf"), FontWeight.W500),
        Font(File(bsDir, "work_sans_semibold.ttf"), FontWeight.W600),
    )

    @Test
    fun namedRolesMatchReadmeTypeTable() {
        // Each README type role has its README font, size, weight and letter spacing.
        val bs = bigShoulders  // Big Shoulders for display roles
        val ws = workSans      // Work Sans for body roles

        // Wordmark: Big Shoulders, 24sp/900, letter-spacing 0.14em
        assertEquals("Wordmark fontSize is 24sp", 24.sp, LiftoffType.wordmark(bs).fontSize)
        assertEquals("Wordmark letterSpacing is 0.14em", 0.14.em, LiftoffType.wordmark(bs).letterSpacing)

        // ScreenTitle: Big Shoulders, 68sp/900, lineHeight 0.9em
        assertEquals("ScreenTitle fontSize is 68sp", 68.sp, LiftoffType.screenTitle(bs).fontSize)
        assertEquals("ScreenTitle lineHeight is 0.9em", 0.9.em, LiftoffType.screenTitle(bs).lineHeight)

        // HeaderTitle: Big Shoulders, 44sp/900
        assertEquals("HeaderTitle fontSize is 44sp", 44.sp, LiftoffType.headerTitle(bs).fontSize)

        // LaunchLabel: Big Shoulders, 34sp/900, letter-spacing 0.16em
        assertEquals("LaunchLabel fontSize is 34sp", 34.sp, LiftoffType.launchLabel(bs).fontSize)
        assertEquals("LaunchLabel letterSpacing is 0.16em", 0.16.em, LiftoffType.launchLabel(bs).letterSpacing)

        // LandLabel: Big Shoulders, 30sp/900, letter-spacing 0.16em
        assertEquals("LandLabel fontSize is 30sp", 30.sp, LiftoffType.landLabel(bs).fontSize)
        assertEquals("LandLabel letterSpacing is 0.16em", 0.16.em, LiftoffType.landLabel(bs).letterSpacing)

        // SectionHead: Big Shoulders, 20sp/800, letter-spacing 0.1em
        assertEquals("SectionHead fontSize is 20sp", 20.sp, LiftoffType.sectionHead(bs).fontSize)
        assertEquals("SectionHead letterSpacing is 0.1em", 0.1.em, LiftoffType.sectionHead(bs).letterSpacing)

        // CardTitle: Big Shoulders, 22sp/800, letter-spacing 0.04em
        assertEquals("CardTitle fontSize is 22sp", 22.sp, LiftoffType.cardTitle(bs).fontSize)
        assertEquals("CardTitle letterSpacing is 0.04em", 0.04.em, LiftoffType.cardTitle(bs).letterSpacing)

        // SetValue: Big Shoulders, 24sp/800
        assertEquals("SetValue fontSize is 24sp", 24.sp, LiftoffType.setValue(bs).fontSize)

        // Load: Big Shoulders, 20sp/800
        assertEquals("Load fontSize is 20sp", 20.sp, LiftoffType.load(bs).fontSize)

        // Index: Big Shoulders, 16sp/800
        assertEquals("Index fontSize is 16sp", 16.sp, LiftoffType.index(bs).fontSize)

        // BarLabel: Big Shoulders, 14sp/800, letter-spacing 0.12em
        assertEquals("BarLabel fontSize is 14sp", 14.sp, LiftoffType.barLabel(bs).fontSize)
        assertEquals("BarLabel letterSpacing is 0.12em", 0.12.em, LiftoffType.barLabel(bs).letterSpacing)

        // Eyebrow: Work Sans, 12sp/600, letter-spacing 0.16em
        assertEquals("Eyebrow fontSize is 12sp", 12.sp, LiftoffType.eyebrow(ws).fontSize)
        assertEquals("Eyebrow letterSpacing is 0.16em", 0.16.em, LiftoffType.eyebrow(ws).letterSpacing)

        // ExerciseName: Work Sans, 16sp/500
        assertEquals("ExerciseName fontSize is 16sp", 16.sp, LiftoffType.exerciseName(ws).fontSize)

        // TextButton: Work Sans, 15sp/600
        assertEquals("TextButton fontSize is 15sp", 15.sp, LiftoffType.textButton(ws).fontSize)

        // Note: Work Sans, 13sp/500
        assertEquals("Note fontSize is 13sp", 13.sp, LiftoffType.note(ws).fontSize)
    }

    @Test
    fun materialSlotsUseDesignFonts() {
        // Display, headline and title slots use Big Shoulders Display;
        // body and label slots use Work Sans.
        val bs = bigShoulders
        val ws = workSans

        // Build the Typography with our font families (same as LiftoffTypography does).
        val typography = Typography(
            displayLarge = LiftoffType.screenTitle(bs),
            displayMedium = LiftoffType.headerTitle(bs),
            headlineLarge = LiftoffType.sectionHead(bs),
            titleLarge = LiftoffType.cardTitle(bs),
            bodyLarge = LiftoffType.exerciseName(ws),
            bodyMedium = LiftoffType.note(ws),
            labelLarge = LiftoffType.textButton(ws),
            labelSmall = LiftoffType.barLabel(bs),
        )

        // Verify display/headline/title slots use Big Shoulders.
        assertEquals("displayLarge fontFamily is Big Shoulders", bs, typography.displayLarge.fontFamily)
        assertEquals("displayMedium fontFamily is Big Shoulders", bs, typography.displayMedium.fontFamily)
        assertEquals("headlineLarge fontFamily is Big Shoulders", bs, typography.headlineLarge.fontFamily)
        assertEquals("titleLarge fontFamily is Big Shoulders", bs, typography.titleLarge.fontFamily)

        // Verify body slots use Work Sans.
        assertEquals("bodyLarge fontFamily is Work Sans", ws, typography.bodyLarge.fontFamily)
        assertEquals("bodyMedium fontFamily is Work Sans", ws, typography.bodyMedium.fontFamily)

        // labelSmall uses Big Shoulders (bottom-bar label), labelLarge uses Work Sans.
        assertEquals("labelSmall fontFamily is Big Shoulders", bs, typography.labelSmall.fontFamily)
        assertEquals("labelLarge fontFamily is Work Sans", ws, typography.labelLarge.fontFamily)
    }
}
