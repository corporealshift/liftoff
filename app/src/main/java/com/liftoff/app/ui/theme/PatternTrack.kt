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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class ChipState { Landed, Current, Upcoming }
data class PatternChip(val letter: String, val state: ChipState)

/** Circle diameter for each chip state. */
internal fun chipSizeDp(state: ChipState): Int = when (state) {
    ChipState.Landed -> 44
    ChipState.Current -> 52
    ChipState.Upcoming -> 44
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
            .height(52.dp),
    ) {
        if (chips.size >= 2) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .matchParentSize(),
            ) {
                val insetStart = density.run { 22.dp.toPx() }
                val insetEnd = size.width - density.run { 22.dp.toPx() }
                drawLine(
                    color = Ink,
                    strokeWidth = density.run { 2.dp.toPx() },
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
    val sizeDp = chipSizeDp(chip.state).dp
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
                    modifier = Modifier.size(20.dp),
                    tint = Cream,
                )
            }
            ChipState.Current -> {
                Canvas(modifier = Modifier.size(sizeDp)) {
                    drawCircle(color = Red)
                    drawCircle(
                        color = Ink,
                        style = Stroke(width = density.run { 3.dp.toPx() }),
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
                        style = Stroke(width = density.run { 2.dp.toPx() }),
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
        }
    }
}

@Composable
fun PatternTrackPreview() {
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
