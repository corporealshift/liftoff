package com.liftoff.app.ui.mission

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.liftoff.app.AppContainer
import com.liftoff.app.data.*
import com.liftoff.app.ui.PlaceholderScreen
import com.liftoff.app.ui.sortie.FlightPlanSection
import com.liftoff.app.ui.sortie.formatWeekDate
import com.liftoff.app.ui.theme.*
import java.time.Instant

@Composable
fun MissionScreen(
    container: AppContainer,
) {
    val scope = rememberCoroutineScope()
    val viewModel = remember(container) {
        MissionViewModel(container.database, container.missionManager, scope)
    }

    val tabState by viewModel.state.collectAsStateWithLifecycle()

    // Sortie detail selection (survives configuration changes).
    var selectedSortieId: Long? by rememberSaveable { mutableStateOf(null) }

    BackHandler(enabled = selectedSortieId != null) {
        selectedSortieId = null
    }

    if (selectedSortieId != null) {
        val detailState by remember(selectedSortieId) {
            viewModel.observeSortie(selectedSortieId!!)
        }.collectAsStateWithLifecycle(SortieDetailState.Loading)
        SortieDetailView(
            state = detailState,
            onBack = { selectedSortieId = null },
        )
    } else {
        MissionTabContent(state = tabState, onSelect = { id -> selectedSortieId = id })
    }
}

@Composable
private fun MissionTabContent(state: MissionTabState, onSelect: (Long) -> Unit = {}) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Cream)
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        when (state) {
            is MissionTabState.Loading -> Unit

            is MissionTabState.Week -> WeekContent(state, onSelect)
        }
    }
}

@Composable
private fun WeekContent(state: MissionTabState.Week, onSelect: (Long) -> Unit = {}) {
    // Title block
    val eyebrow = "WEEK OF ${formatWeekDate(state.weekStart)}"
    TitleBlock(eyebrow = eyebrow, title = "Mission")
    Spacer(Modifier.height(16.dp))

    // Pattern track
    PatternTrack(chips = state.chips)
    Spacer(Modifier.height(20.dp))

    // Outline notes
    if (!state.outlineNotes.isNullOrEmpty()) {
        Text(
            text = state.outlineNotes,
            style = LiftoffType.note().copy(color = Muted),
        )
        Spacer(Modifier.height(20.dp))
    }

    // Draft message
    if (state.status == MissionStatus.DRAFT) {
        Text(
            text = "Not confirmed yet. Confirm this Mission on the Launchpad.",
            style = LiftoffType.note().copy(color = Muted),
        )
        Spacer(Modifier.height(20.dp))
    }

    // Sortie rows
    state.rows.forEach { row ->
        InkRuledListRow(
            index = "%02d".format(row.index + 1),
            title = row.title,
            detail = row.stateLabel,
            modifier = Modifier.clickable { onSelect(row.id) },
        )
    }
}

@Composable
private fun SortieDetailView(state: SortieDetailState, onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Cream)
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        when (state) {
            is SortieDetailState.WithPlan -> {
                TitleBlock(eyebrow = state.eyebrow, title = state.title)
                Spacer(Modifier.height(20.dp))
                FlightPlanSection(plan = state.plan, sortieType = state.sortieType, sortieFocus = state.sortieFocus)
            }

            is SortieDetailState.NoPlan -> {
                TitleBlock(eyebrow = state.eyebrow, title = state.title)
                Spacer(Modifier.height(20.dp))
                Text(
                    text = "No Flight Plan yet.",
                    style = LiftoffType.textButton().copy(color = Muted),
                )
            }

            is SortieDetailState.Landed -> LandedDetailView(state)

            is SortieDetailState.Scrubbed -> ScrubbedDetailView(state)

            SortieDetailState.Loading -> Unit
        }

        Spacer(Modifier.height(20.dp))
        UnderlinedTextButton(text = "Back", onClick = onBack)
    }
}

@Composable
private fun LandedDetailView(state: SortieDetailState.Landed) {
    val landedDate = Instant.ofEpochMilli(state.landedAt)
        .atZone(java.time.ZoneId.systemDefault())
        .toLocalDate()

    TitleBlock(
        eyebrow = state.eyebrow,
        title = "Landed",
    )
    Spacer(Modifier.height(12.dp))

    Text(
        text = "Landed ${formatWeekDate(landedDate)}",
        style = LiftoffType.note().copy(color = Muted),
    )

    if (state.sortieType == SortieType.LIFT && state.planExerciseCount > 0) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = "${state.planLandedCount} of ${state.planExerciseCount} sets landed.",
            style = LiftoffType.note().copy(color = Muted),
        )
    }

    if (state.sortieType == SortieType.RUN) {
        Spacer(Modifier.height(8.dp))
        val distStr = state.runDistance?.let { "%.2f km".format(it) } ?: "—"
        val minStr = state.runMinutes?.let { "%.0f min".format(it) } ?: "—"
        Text(
            text = "$distStr · $minStr",
            style = LiftoffType.note().copy(color = Muted),
        )
    }

    if (!state.notes.isNullOrEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = state.notes,
            style = LiftoffType.note().copy(color = Muted),
        )
    }
}

@Composable
private fun ScrubbedDetailView(state: SortieDetailState.Scrubbed) {
    TitleBlock(
        eyebrow = state.eyebrow,
        title = "Scrubbed",
    )
    Spacer(Modifier.height(12.dp))

    val reason = state.reason ?: "No reason given"
    Text(
        text = reason,
        style = LiftoffType.note().copy(color = Muted),
    )

    if (state.planLandedCount > 0) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = "${state.planLandedCount} of ${state.planExerciseCount} sets landed.",
            style = LiftoffType.note().copy(color = Muted),
        )
    }
}

// ── Previews ──────────────────────────────────────────────────────────

@Preview(showBackground = true)
@Composable
private fun MissionTabPreview() {
    if (LocalInspectionMode.current) {
        LiftoffTheme {
            val previewState = MissionTabState.Week(
                weekStart = java.time.LocalDate.of(2026, 10, 5),
                status = MissionStatus.ACTIVE,
                chips = listOf(
                    com.liftoff.app.ui.theme.PatternChip("R", com.liftoff.app.ui.theme.ChipState.Landed),
                    com.liftoff.app.ui.theme.PatternChip("L", com.liftoff.app.ui.theme.ChipState.Current),
                    com.liftoff.app.ui.theme.PatternChip("R", com.liftoff.app.ui.theme.ChipState.Upcoming),
                ),
                outlineNotes = "Focus on form this week.",
                rows = listOf(
                    SortieRow(1, 0, SortieType.RUN, "Easy", SortieState.LANDED),
                    SortieRow(2, 1, SortieType.LIFT, "Full body", SortieState.PLANNED),
                    SortieRow(3, 2, SortieType.RUN, null, SortieState.PENDING),
                ),
            )
            MissionTabContent(state = previewState)
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SortieDetailPlannedPreview() {
    if (LocalInspectionMode.current) {
        LiftoffTheme {
            val state = SortieDetailState.WithPlan(
                eyebrow = "SORTIE 2 · LIFT · PLANNED",
                title = "Upper Body",
                plan = FlightPlanDetail(
                    plan = FlightPlan(
                        sortieId = 2, source = FlightPlanSource.GENERATED,
                        title = "Upper Body", estimatedMinutes = 45,
                        warmup = null, notes = null, runKind = null,
                        targetDistance = null, targetPace = null, rawJson = "{}"
                    ),
                    exercises = emptyList(),
                    segments = emptyList(),
                ),
                sortieType = SortieType.LIFT,
                sortieFocus = "Bench",
            )
            SortieDetailView(state = state, onBack = {})
        }
    }
}
