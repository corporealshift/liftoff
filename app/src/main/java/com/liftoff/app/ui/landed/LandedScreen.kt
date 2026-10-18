package com.liftoff.app.ui.landed

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.liftoff.app.AppContainer
import com.liftoff.app.ui.inflight.NoteDialog
import com.liftoff.app.ui.theme.ButtonShape
import com.liftoff.app.ui.theme.CardShape
import com.liftoff.app.ui.theme.Cream
import com.liftoff.app.ui.theme.Ink
import com.liftoff.app.ui.theme.InkRuledListRow
import com.liftoff.app.ui.theme.LiftoffType
import com.liftoff.app.ui.theme.Mustard
import com.liftoff.app.ui.theme.Muted
import com.liftoff.app.ui.theme.Paper
import com.liftoff.app.ui.theme.Red
import com.liftoff.app.ui.theme.Rule
import com.liftoff.app.ui.theme.Sand
import com.liftoff.app.ui.theme.Teal
import com.liftoff.app.ui.theme.TitleBlock
import com.liftoff.app.ui.theme.UnderlinedTextButton

@Composable
fun LandedScreen(
    container: AppContainer,
) {
    val scope = rememberCoroutineScope()
    val viewModel = remember(container) {
        LandedViewModel(container.database, container.settingsStore, scope)
    }

    val history by viewModel.history.collectAsStateWithLifecycle()

    // Sortie detail selection (survives configuration changes).
    var selectedSortieId: Long? by rememberSaveable { mutableStateOf(null) }

    // Composed after the shell's BackHandler, so this tab's handler is the one the system back hits.
    BackHandler(enabled = selectedSortieId != null) {
        selectedSortieId = null
    }

    if (selectedSortieId != null) {
        val sortieId = selectedSortieId!!
        val detail by remember(sortieId) { viewModel.observeDetail(sortieId) }
            .collectAsStateWithLifecycle(LandedDetail.Loading)
        LandedDetailView(
            detail = detail,
            onSaveNote = { text -> viewModel.saveNote(sortieId, text) },
            onBack = { selectedSortieId = null },
        )
    } else {
        LandedHistoryContent(state = history, onSelect = { selectedSortieId = it })
    }
}

@Composable
private fun LandedHistoryContent(state: LandedHistoryState, onSelect: (Long) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Cream)
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        // The title block is composed even while the first read is running, so the empty state
        // cannot flash over real data.
        TitleBlock(eyebrow = "History", title = "Landed")
        Spacer(Modifier.height(16.dp))

        when (state) {
            LandedHistoryState.Loading -> Unit

            LandedHistoryState.Empty -> Text(
                text = "No history yet.",
                style = LiftoffType.note().copy(color = Muted),
            )

            is LandedHistoryState.Weeks -> state.weeks.forEach { week ->
                WeekSection(week = week, onSelect = onSelect)
            }
        }
    }
}

@Composable
private fun WeekSection(week: HistoryWeek, onSelect: (Long) -> Unit) {
    Text(text = week.head, style = LiftoffType.sectionHead(), color = Ink)
    Spacer(Modifier.height(4.dp))

    week.rows.forEach { row ->
        val reason = row.scrubReason
        // The reason is a note, not a second row: it sits between the row and its ink rule.
        val actions: (@Composable RowScope.() -> Unit)? = if (reason == null) null else {
            {
                Text(
                    text = reason,
                    style = LiftoffType.note().copy(color = Muted),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        InkRuledListRow(
            index = row.indexLabel,
            title = row.title,
            detail = "${row.dateLabel} · ${row.typeLabel} · ${row.stateLabel}",
            modifier = Modifier.clickable { onSelect(row.sortieId) },
            actions = actions,
        )
    }
    Spacer(Modifier.height(20.dp))
}

// ── Detail ───────────────────────────────────────────────────────

@Composable
private fun LandedDetailView(
    detail: LandedDetail,
    onSaveNote: (String) -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Cream)
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        when (detail) {
            LandedDetail.Loading -> Unit

            is LandedDetail.Lift -> LiftRecord(detail = detail, onSaveNote = onSaveNote)

            is LandedDetail.Run -> RunRecord(detail = detail, onSaveNote = onSaveNote)

            is LandedDetail.Scrubbed -> ScrubbedRecord(detail = detail, onSaveNote = onSaveNote)
        }

        Spacer(Modifier.height(20.dp))
        UnderlinedTextButton(text = "Back", onClick = onBack)
    }
}

@Composable
private fun DetailHead(eyebrow: String, title: String, dateLabel: String?) {
    TitleBlock(eyebrow = eyebrow, title = title)
    Spacer(Modifier.height(12.dp))
    if (dateLabel != null) {
        Text(text = "LANDED $dateLabel", style = LiftoffType.note().copy(color = Muted))
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun LiftRecord(detail: LandedDetail.Lift, onSaveNote: (String) -> Unit) {
    DetailHead(eyebrow = detail.eyebrow, title = detail.title, dateLabel = detail.dateLabel)
    CoachNote(note = detail.planNote, title = detail.title)

    if (detail.exercises.isEmpty()) {
        NoPlanLine()
    }
    detail.exercises.forEach { exercise ->
        ExerciseRecordCard(exercise = exercise)
        Spacer(Modifier.height(12.dp))
    }

    SortieNoteSection(note = detail.sortieNote, onSave = onSaveNote)
}

@Composable
private fun RunRecord(detail: LandedDetail.Run, onSaveNote: (String) -> Unit) {
    DetailHead(eyebrow = detail.eyebrow, title = detail.title, dateLabel = detail.dateLabel)

    val planNote = detail.planNote?.takeIf { it != detail.title }
    if (detail.kind == null && detail.targetLine == null && detail.segments.isEmpty() && planNote == null) {
        NoPlanLine()
    } else {
        PaperCard(head = "FLIGHT PLAN") {
            Column(modifier = Modifier.padding(14.dp)) {
                if (detail.kind != null) {
                    Text(text = detail.kind, style = LiftoffType.load(), color = Ink)
                }
                if (detail.targetLine != null) {
                    Text(
                        text = detail.targetLine,
                        style = LiftoffType.note().copy(color = Muted),
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                detail.segments.forEach { segment ->
                    Spacer(Modifier.height(10.dp))
                    val segmentDetail = segment.detail
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = segment.description,
                            style = LiftoffType.exerciseName(),
                            color = Ink,
                            modifier = Modifier.weight(1f),
                        )
                        if (segmentDetail != null) {
                            Text(
                                text = segmentDetail,
                                style = LiftoffType.note().copy(color = Muted),
                            )
                        }
                    }
                }
                if (planNote != null) {
                    Spacer(Modifier.height(12.dp))
                    CoachLine(text = planNote)
                }
            }
        }
    }

    Spacer(Modifier.height(20.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        BigValue(label = "DISTANCE", value = detail.recordedDistance ?: "—", modifier = Modifier.weight(1f))
        BigValue(label = "TIME", value = detail.recordedDuration ?: "—", modifier = Modifier.weight(1f))
    }
    Spacer(Modifier.height(20.dp))

    SortieNoteSection(note = detail.sortieNote, onSave = onSaveNote)
}

@Composable
private fun ScrubbedRecord(detail: LandedDetail.Scrubbed, onSaveNote: (String) -> Unit) {
    // Decision 2: a scrub recorded no timestamp, so a scrubbed detail carries no date line.
    DetailHead(eyebrow = detail.eyebrow, title = detail.title, dateLabel = null)

    PaperCard(head = "REASON") {
        Text(
            text = detail.reason ?: "No reason given",
            style = LiftoffType.note().copy(color = Muted),
            modifier = Modifier.padding(14.dp),
        )
    }

    if (detail.setCount > 0) {
        Spacer(Modifier.height(12.dp))
        Text(
            text = "${detail.doneCount} of ${detail.setCount} sets checked before the scrub.",
            style = LiftoffType.note().copy(color = Muted),
        )
    }
    Spacer(Modifier.height(12.dp))

    if (detail.exercises.isEmpty()) {
        NoPlanLine()
    }
    detail.exercises.forEach { exercise ->
        ExerciseRecordCard(exercise = exercise)
        Spacer(Modifier.height(12.dp))
    }

    SortieNoteSection(note = detail.sortieNote, onSave = onSaveNote)
}

// ── Cards ────────────────────────────────────────────────────────

/** The paper card In-Flight's FullCard builds, without its editing cells. */
@Composable
private fun PaperCard(head: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Paper, CardShape)
            .border(BorderStroke(2.dp, Ink), CardShape),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Ink)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(text = head, style = LiftoffType.cardTitle().copy(color = Cream))
        }
        content()
    }
}

@Composable
private fun ExerciseRecordCard(exercise: RecordedExercise) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Paper, CardShape)
            .border(BorderStroke(2.dp, Ink), CardShape),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Ink)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "%02d".format(exercise.number), style = LiftoffType.index().copy(color = Mustard))
            val nameStyle = LiftoffType.cardTitle().copy(color = Cream)
            Text(
                text = exercise.name.uppercase(),
                style = if (exercise.exerciseSkipped) {
                    nameStyle.copy(textDecoration = TextDecoration.LineThrough)
                } else {
                    nameStyle
                },
                modifier = Modifier.weight(1f),
            )
            if (exercise.exerciseSkipped) {
                Text(
                    text = "SKIPPED",
                    style = LiftoffType.barLabel().copy(color = Rule, fontSize = 12.sp),
                )
            }
        }

        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
            if (exercise.sets.isEmpty()) {
                Text(text = "No sets.", style = LiftoffType.note().copy(color = Muted))
            }
            exercise.sets.forEachIndexed { index, set ->
                if (index > 0) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(Rule),
                    )
                }
                SetRecordRow(set = set)
            }
        }

        val note = exercise.note
        if (note != null) {
            Text(
                text = note,
                style = LiftoffType.note().copy(color = Muted),
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 10.dp),
            )
        }
    }
}

@Composable
private fun SetRecordRow(set: RecordedSet) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = set.label,
            style = LiftoffType.barLabel().copy(
                color = Muted,
                fontSize = 12.sp,
                fontWeight = FontWeight.W600,
                letterSpacing = 0.1.em,
            ),
            modifier = Modifier.width(52.dp),
        )

        val actual = set.actualLabel
        when {
            actual != null && (set.weightDeviates || set.countDeviates) -> {
                Text(
                    text = actual,
                    style = LiftoffType.setValue().copy(color = Red),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "of ${set.plannedLabel}",
                    style = LiftoffType.barLabel().copy(
                        color = Muted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.W600,
                    ),
                    modifier = Modifier.padding(start = 6.dp),
                )
            }

            actual != null -> Text(
                text = actual,
                style = LiftoffType.setValue().copy(color = Ink),
                modifier = Modifier.weight(1f),
            )

            // Nothing was recorded, so the planned value is shown as what did not happen.
            else -> Text(
                text = set.plannedLabel,
                style = LiftoffType.setValue().copy(color = Muted),
                modifier = Modifier.weight(1f),
            )
        }

        if (set.mark != SetMark.DONE) {
            Text(
                text = if (set.mark == SetMark.SKIPPED) "SKIPPED" else "NOT DONE",
                style = LiftoffType.barLabel().copy(
                    color = Muted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.W600,
                ),
            )
        }
        if (set.added) {
            Text(
                text = "ADDED",
                style = LiftoffType.barLabel().copy(
                    color = Teal,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.W600,
                ),
            )
        }
    }
}

@Composable
private fun BigValue(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(text = label, style = LiftoffType.barLabel().copy(color = Muted))
        Spacer(Modifier.height(4.dp))
        Text(text = value, style = LiftoffType.setValue().copy(color = Ink))
    }
}

// ── Notes ────────────────────────────────────────────────────────

@Composable
private fun CoachNote(note: String?, title: String) {
    val text = note?.takeIf { it != title } ?: return
    Spacer(Modifier.height(4.dp))
    CoachLine(text = text)
    Spacer(Modifier.height(12.dp))
}

/** The coach's plan note, in the same shape FlightPlanSection gives it. */
@Composable
private fun CoachLine(text: String) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            text = "Coach: ",
            style = LiftoffType.note().copy(color = Teal, fontWeight = FontWeight.W600),
        )
        Text(text = text, style = LiftoffType.note().copy(color = Muted))
    }
}

@Composable
private fun NoPlanLine() {
    Text(text = "No Flight Plan yet.", style = LiftoffType.textButton().copy(color = Muted))
    Spacer(Modifier.height(12.dp))
}

/** The only thing a finished sortie can still be edited on (DESIGN §5.1): its note. */
@Composable
private fun SortieNoteSection(note: String?, onSave: (String) -> Unit) {
    var editing by rememberSaveable { mutableStateOf(false) }

    if (note != null) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Sand, ButtonShape)
                .padding(12.dp),
        ) {
            Text(text = note, style = LiftoffType.note().copy(color = Muted))
        }
        Spacer(Modifier.height(8.dp))
    }

    UnderlinedTextButton(text = "Edit note", onClick = { editing = true })

    if (editing) {
        NoteDialog(
            title = "Sortie note",
            initial = note.orEmpty(),
            onSave = { text ->
                onSave(text)
                editing = false
            },
            onDismiss = { editing = false },
        )
    }
}
