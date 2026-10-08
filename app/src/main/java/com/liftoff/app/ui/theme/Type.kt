package com.liftoff.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.em
import com.liftoff.app.R

// Top-level FontFamily vals — computed once, not per-recomposition.
val BigShoulders = FontFamily(
    Font(R.font.big_shoulders_display_bold, FontWeight.W700),
    Font(R.font.big_shoulders_display_extrabold, FontWeight.W800),
    Font(R.font.big_shoulders_display_black, FontWeight.W900),
)

val WorkSans = FontFamily(
    Font(R.font.work_sans_regular, FontWeight.W400),
    Font(R.font.work_sans_medium, FontWeight.W500),
    Font(R.font.work_sans_semibold, FontWeight.W600),
)

object LiftoffType {
    // Static factory methods — take a FontFamily parameter.
    fun wordmark(fs: FontFamily) = TextStyle(
        fontFamily = fs, fontWeight = FontWeight.W900, fontSize = 24.sp,
        letterSpacing = 0.14.em,
    )
    fun screenTitle(fs: FontFamily) = TextStyle(
        fontFamily = fs, fontWeight = FontWeight.W900, fontSize = 68.sp,
        lineHeight = 0.9.em,
    )
    fun headerTitle(fs: FontFamily) = TextStyle(
        fontFamily = fs, fontWeight = FontWeight.W900, fontSize = 44.sp,
    )
    fun launchLabel(fs: FontFamily) = TextStyle(
        fontFamily = fs, fontWeight = FontWeight.W900, fontSize = 34.sp,
        letterSpacing = 0.16.em,
    )
    fun landLabel(fs: FontFamily) = TextStyle(
        fontFamily = fs, fontWeight = FontWeight.W900, fontSize = 30.sp,
        letterSpacing = 0.16.em,
    )
    fun sectionHead(fs: FontFamily) = TextStyle(
        fontFamily = fs, fontWeight = FontWeight.W800, fontSize = 20.sp,
        letterSpacing = 0.1.em,
    )
    fun cardTitle(fs: FontFamily) = TextStyle(
        fontFamily = fs, fontWeight = FontWeight.W800, fontSize = 22.sp,
        letterSpacing = 0.04.em,
    )
    fun setValue(fs: FontFamily) = TextStyle(
        fontFamily = fs, fontWeight = FontWeight.W800, fontSize = 24.sp,
    )
    fun load(fs: FontFamily) = TextStyle(
        fontFamily = fs, fontWeight = FontWeight.W800, fontSize = 20.sp,
    )
    fun index(fs: FontFamily) = TextStyle(
        fontFamily = fs, fontWeight = FontWeight.W800, fontSize = 16.sp,
    )
    fun barLabel(fs: FontFamily) = TextStyle(
        fontFamily = fs, fontWeight = FontWeight.W800, fontSize = 14.sp,
        letterSpacing = 0.12.em,
    )
    fun eyebrow(ws: FontFamily) = TextStyle(
        fontFamily = ws, fontWeight = FontWeight.W600, fontSize = 12.sp,
        letterSpacing = 0.16.em,
    )
    fun exerciseName(ws: FontFamily) = TextStyle(
        fontFamily = ws, fontWeight = FontWeight.W500, fontSize = 16.sp,
    )
    fun textButton(ws: FontFamily) = TextStyle(
        fontFamily = ws, fontWeight = FontWeight.W600, fontSize = 15.sp,
    )
    fun note(ws: FontFamily) = TextStyle(
        fontFamily = ws, fontWeight = FontWeight.W500, fontSize = 13.sp,
    )

    // Convenience helpers that use the top-level FontFamily vals.
    @Composable fun wordmark(): TextStyle = wordmark(BigShoulders)
    @Composable fun screenTitle(): TextStyle = screenTitle(BigShoulders)
    @Composable fun headerTitle(): TextStyle = headerTitle(BigShoulders)
    @Composable fun launchLabel(): TextStyle = launchLabel(BigShoulders)
    @Composable fun landLabel(): TextStyle = landLabel(BigShoulders)
    @Composable fun sectionHead(): TextStyle = sectionHead(BigShoulders)
    @Composable fun cardTitle(): TextStyle = cardTitle(BigShoulders)
    @Composable fun setValue(): TextStyle = setValue(BigShoulders)
    @Composable fun load(): TextStyle = load(BigShoulders)
    @Composable fun index(): TextStyle = index(BigShoulders)
    @Composable fun barLabel(): TextStyle = barLabel(BigShoulders)
    @Composable fun eyebrow(): TextStyle = eyebrow(WorkSans)
    @Composable fun exerciseName(): TextStyle = exerciseName(WorkSans)
    @Composable fun textButton(): TextStyle = textButton(WorkSans)
    @Composable fun note(): TextStyle = note(WorkSans)
}

fun LiftoffTypography(): Typography {
    val base = Typography()
    return Typography(
        displayLarge = LiftoffType.screenTitle(BigShoulders),
        displayMedium = LiftoffType.headerTitle(BigShoulders),
        headlineLarge = LiftoffType.sectionHead(BigShoulders),
        titleLarge = LiftoffType.cardTitle(BigShoulders),
        bodyLarge = LiftoffType.exerciseName(WorkSans),
        bodyMedium = LiftoffType.note(WorkSans),
        labelLarge = LiftoffType.textButton(WorkSans),
        labelSmall = LiftoffType.barLabel(BigShoulders),
        // Fill the remaining roles so text fields and dialogs never fall back to the system font.
        displaySmall = base.displaySmall.copy(fontFamily = BigShoulders, fontWeight = FontWeight.W800),
        headlineMedium = base.headlineMedium.copy(fontFamily = BigShoulders, fontWeight = FontWeight.W800),
        headlineSmall = base.headlineSmall.copy(fontFamily = BigShoulders, fontWeight = FontWeight.W800),
        titleMedium = base.titleMedium.copy(fontFamily = BigShoulders, fontWeight = FontWeight.W800),
        titleSmall = base.titleSmall.copy(fontFamily = BigShoulders, fontWeight = FontWeight.W800),
        bodySmall = base.bodySmall.copy(fontFamily = WorkSans),
        labelMedium = base.labelMedium.copy(fontFamily = WorkSans),
    )
}
