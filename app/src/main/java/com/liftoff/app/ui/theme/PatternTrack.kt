package com.liftoff.app.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class ChipState { Landed, Current, Upcoming, Scrubbed }
data class PatternChip(val letter: String, val state: ChipState)

/** Chip diameters. */
internal val ChipSizeLandedDp = 44.dp
internal val ChipSizeCurrentDp = 52.dp
internal val ChipSizeUpcomingDp = 44.dp
internal val ChipSizeScrubbedDp = 44.dp

/** The track is as tall as the widest chip, so its height is that chip's, not a second 52. */
internal val PatternTrackHeightDp = ChipSizeCurrentDp

/** Ring widths, and the connector line drawn between chips. */
internal val ChipRingCurrentWidthDp = 3.dp
internal val ChipRingUpcomingWidthDp = 2.dp
internal val ChipRingScrubbedWidthDp = 2.dp
internal val ConnectorWidthDp = 2.dp
internal val ConnectorInsetDp = 22.dp

/** The check mark drawn inside a Landed chip. */
internal val LandedCheckSizeDp = 20.dp

/** Circle diameter for each chip state. */
internal fun chipSizeDp(state: ChipState): Dp = when (state) {
    ChipState.Landed -> ChipSizeLandedDp
    ChipState.Current -> ChipSizeCurrentDp
    ChipState.Upcoming -> ChipSizeUpcomingDp
    ChipState.Scrubbed -> ChipSizeScrubbedDp
}

@Composable
fun PatternTrack(
    chips: List<PatternChip>,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(PatternTrackHeightDp),
    ) {
        if (chips.size >= 2) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .matchParentSize(),
            ) {
                val insetStart = density.run { ConnectorInsetDp.toPx() }
                val insetEnd = size.width - density.run { ConnectorInsetDp.toPx() }
                drawLine(
                    color = Ink,
                    strokeWidth = density.run { ConnectorWidthDp.toPx() },
                    start = Offset(insetStart, size.height / 2),
                    end = Offset(insetEnd, size.height / 2),
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.Center),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            chips.forEach { chip ->
                PatternChipView(chip)
            }
        }
    }
}

@Composable
private fun PatternChipView(chip: PatternChip) {
    val sizeDp = chipSizeDp(chip.state)
    val density = LocalDensity.current

    Box(
        modifier = Modifier.size(sizeDp),
        contentAlignment = Alignment.Center,
    ) {
        when (chip.state) {
            ChipState.Landed -> {
                Canvas(modifier = Modifier.size(sizeDp)) {
                    drawCircle(color = Ink)
                }
                Icon(
                    painter = LiftoffIcons.check(),
                    contentDescription = null,
                    modifier = Modifier.size(LandedCheckSizeDp),
                    tint = Cream,
                )
            }
            ChipState.Current -> {
                Canvas(modifier = Modifier.size(sizeDp)) {
                    drawCircle(color = Red)
                    drawCircle(
                        color = Ink,
                        style = Stroke(width = density.run { ChipRingCurrentWidthDp.toPx() }),
                    )
                }
                Text(
                    text = chip.letter,
                    color = White,
                    fontFamily = BigShoulders,
                    fontWeight = FontWeight.W900,
                    fontSize = 26.sp,
                )
            }
            ChipState.Upcoming -> {
                Canvas(modifier = Modifier.size(sizeDp)) {
                    drawCircle(color = Cream)
                    drawCircle(
                        color = Ink,
                        style = Stroke(width = density.run { ChipRingUpcomingWidthDp.toPx() }),
                    )
                }
                Text(
                    text = chip.letter,
                    color = Ink,
                    fontFamily = BigShoulders,
                    fontWeight = FontWeight.W800,
                    fontSize = 22.sp,
                )
            }
            ChipState.Scrubbed -> {
                Canvas(modifier = Modifier.size(sizeDp)) {
                    drawCircle(color = Sand)
                    drawCircle(
                        color = Ink,
                        style = Stroke(width = density.run { ChipRingScrubbedWidthDp.toPx() }),
                    )
                }
                Text(
                    text = chip.letter,
                    color = Muted,
                    fontFamily = BigShoulders,
                    fontWeight = FontWeight.W800,
                    fontSize = 22.sp,
                )
            }
        }
    }
}

@Preview
@Composable
private fun PatternTrackPreview() {
    if (LocalInspectionMode.current) {
        Column(modifier = Modifier.background(Cream).padding(20.dp)) {
            val sampleChips = listOf(
                PatternChip("R", ChipState.Landed),
                PatternChip("L", ChipState.Current),
                PatternChip("R", ChipState.Upcoming),
                PatternChip("L", ChipState.Upcoming),
            )
            PatternTrack(chips = sampleChips)
        }
    }
}
