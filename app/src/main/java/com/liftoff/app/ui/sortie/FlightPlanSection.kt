package com.liftoff.app.ui.sortie

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.liftoff.app.data.FlightPlanDetail
import com.liftoff.app.data.SortieType
import com.liftoff.app.ui.theme.*

/** Shared composable: FLIGHT PLAN head over ink rule, exercise/run rows, coach note. */
@Composable
fun FlightPlanSection(
    plan: FlightPlanDetail,
    sortieType: SortieType,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        // FLIGHT PLAN head with estimated minutes and set count
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "FLIGHT PLAN",
                style = LiftoffType.sectionHead(),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = formatPlanHead(plan),
                style = LiftoffType.eyebrow(),
                color = Muted,
            )
        }

        // 3 dp ink rule (1 px in the mockup ≈ 1 dp; brief says 3 dp)
        Spacer(Modifier.height(2.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Ink),
        ) {}
        Spacer(Modifier.height(2.dp))

        if (sortieType == SortieType.LIFT) {
            // Exercise rows for lifts
            plan.exercises.forEachIndexed { index, exercise ->
                val load = formatExerciseLoad(exercise)
                InkRuledListRow(
                    index = "%02d".format(index + 1),
                    title = exercise.displayName,
                    detail = load.ifEmpty { null },
                )
            }
        } else {
            // Run summary rows: focus, target distance/pace, segments
            val rowCounter = MutableInt(0)

            val focus = plan.plan.notes?.takeIf { it.isNotBlank() && it != plan.plan.title }
            if (focus != null) {
                InkRuledListRow(
                    index = "%02d".format(rowCounter.value++),
                    title = "Focus",
                    detail = focus,
                )
            }

            val targetDist = plan.plan.targetDistance
            if (targetDist != null) {
                InkRuledListRow(
                    index = "%02d".format(rowCounter.value++),
                    title = "Target distance",
                    detail = "%.2f km".format(targetDist),
                )
            }

            val targetPace = plan.plan.targetPace
            if (targetPace != null) {
                InkRuledListRow(
                    index = "%02d".format(rowCounter.value++),
                    title = "Target pace",
                    detail = targetPace,
                )
            }

            // Segment rows
            plan.segments.forEachIndexed { _, segment ->
                val detailParts = mutableListOf<String>()
                segment.distance?.let { detailParts.add("%.2f km".format(it)) }
                segment.minutes?.let { detailParts.add("%.0f min".format(it)) }
                val detail = detailParts.joinToString(" · ")

                InkRuledListRow(
                    index = "%02d".format(rowCounter.value++),
                    title = segment.description,
                    detail = if (detail.isEmpty()) null else detail,
                )
            }
        }

        // Coach note: "Coach:" in Teal weight 600 + text in Muted with LiftoffType.note()
        val notes = plan.plan.notes?.takeIf { it.isNotBlank() && it != plan.plan.title }
        if (notes != null) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = "Coach: ",
                    style = LiftoffType.note().copy(
                        color = Teal,
                        fontWeight = FontWeight.W600,
                    ),
                )
                Text(
                    text = notes,
                    style = LiftoffType.note().copy(color = Muted),
                )
            }
        }

        // Bottom ink rule
        Spacer(Modifier.height(2.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Ink),
        ) {}
    }
}

// Simple mutable int for row counting inside a composable.
private class MutableInt(var value: Int = 0)

@Preview
@Composable
private fun FlightPlanSectionPreview() {
    if (LocalInspectionMode.current) {
        Column(modifier = Modifier.background(Cream).padding(16.dp)) {
            Text("FLIGHT PLAN", style = LiftoffType.sectionHead())
            Spacer(Modifier.height(20.dp))
            InkRuledListRow(index = "01", title = "Bench Press", detail = "3×8 · 135")
            InkRuledListRow(index = "02", title = "Squat", detail = "3×8 · BW")
        }
    }
}
