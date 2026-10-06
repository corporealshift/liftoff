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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class ChipState { Landed, Current, Upcoming }
data class PatternChip(val letter: String, val state: ChipState)

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
                val insetStart = with(density) { 22.dp.toPx() }
                val insetEnd = size.width - with(density) { 22.dp.toPx() }
                drawLine(
                    color = Ink,
                    strokeWidth = with(density) { 2.dp.toPx() },
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
    Box(
        modifier = Modifier.size(44.dp),
        contentAlignment = Alignment.Center,
    ) {
        when (chip.state) {
            ChipState.Landed -> {
                Canvas(modifier = Modifier.size(44.dp)) {
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
                Canvas(modifier = Modifier.size(52.dp)) {
                    drawCircle(color = Red)
                    drawCircle(color = Ink, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f))
                }
                Text(
                    text = chip.letter,
                    color = White,
                    fontWeight = FontWeight.W900,
                    fontSize = 26.sp,
                )
            }
            ChipState.Upcoming -> {
                Canvas(modifier = Modifier.size(44.dp)) {
                    drawCircle(color = Cream)
                    drawCircle(color = Ink, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f))
                }
                Text(
                    text = chip.letter,
                    color = Ink,
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
