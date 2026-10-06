package com.liftoff.app.ui.theme

import android.content.Context
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.em

@Composable
fun createBigShoulders(context: Context): FontFamily {
    return FontFamily(
        androidx.compose.ui.text.font.Font("fonts/big_shoulders_display_bold.ttf", context.assets, FontWeight.W700),
        androidx.compose.ui.text.font.Font("fonts/big_shoulders_display_extrabold.ttf", context.assets, FontWeight.W800),
        androidx.compose.ui.text.font.Font("fonts/big_shoulders_display_black.ttf", context.assets, FontWeight.W900),
    )
}

@Composable
fun createWorkSans(context: Context): FontFamily {
    return FontFamily(
        androidx.compose.ui.text.font.Font("fonts/work_sans_regular.ttf", context.assets, FontWeight.W400),
        androidx.compose.ui.text.font.Font("fonts/work_sans_medium.ttf", context.assets, FontWeight.W500),
        androidx.compose.ui.text.font.Font("fonts/work_sans_semibold.ttf", context.assets, FontWeight.W600),
    )
}

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

    // Convenience helpers that resolve fonts from the current composable context.
    @Composable fun wordmark(): TextStyle = wordmark(createBigShoulders(LocalContext.current))
    @Composable fun screenTitle(): TextStyle = screenTitle(createBigShoulders(LocalContext.current))
    @Composable fun headerTitle(): TextStyle = headerTitle(createBigShoulders(LocalContext.current))
    @Composable fun launchLabel(): TextStyle = launchLabel(createBigShoulders(LocalContext.current))
    @Composable fun landLabel(): TextStyle = landLabel(createBigShoulders(LocalContext.current))
    @Composable fun sectionHead(): TextStyle = sectionHead(createBigShoulders(LocalContext.current))
    @Composable fun cardTitle(): TextStyle = cardTitle(createBigShoulders(LocalContext.current))
    @Composable fun setValue(): TextStyle = setValue(createBigShoulders(LocalContext.current))
    @Composable fun load(): TextStyle = load(createBigShoulders(LocalContext.current))
    @Composable fun index(): TextStyle = index(createBigShoulders(LocalContext.current))
    @Composable fun barLabel(): TextStyle = barLabel(createBigShoulders(LocalContext.current))
    @Composable fun eyebrow(): TextStyle = eyebrow(createWorkSans(LocalContext.current))
    @Composable fun exerciseName(): TextStyle = exerciseName(createWorkSans(LocalContext.current))
    @Composable fun textButton(): TextStyle = textButton(createWorkSans(LocalContext.current))
    @Composable fun note(): TextStyle = note(createWorkSans(LocalContext.current))
}

@Composable
fun LiftoffTypography(): Typography {
    val context = LocalContext.current
    return Typography(
        displayLarge = LiftoffType.screenTitle(createBigShoulders(context)),
        displayMedium = LiftoffType.headerTitle(createBigShoulders(context)),
        headlineLarge = LiftoffType.sectionHead(createBigShoulders(context)),
        titleLarge = LiftoffType.cardTitle(createBigShoulders(context)),
        bodyLarge = LiftoffType.exerciseName(createWorkSans(context)),
        bodyMedium = LiftoffType.note(createWorkSans(context)),
        labelLarge = LiftoffType.textButton(createWorkSans(context)),
        labelSmall = LiftoffType.barLabel(createBigShoulders(context)),
    )
}
