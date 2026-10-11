package com.liftoff.app.ui.inflight

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.liftoff.app.AppContainer
import com.liftoff.app.data.SetStatus
import com.liftoff.app.settings.WeightUnit
import com.liftoff.app.ui.sortie.formatWeight
import com.liftoff.app.ui.theme.*

@Composable
fun InFlightScreen(
    sortieId: Long,
    container: AppContainer,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val vm = remember(sortieId) { InFlightViewModel(container.database, container.settingsStore, sortieId, scope) }
    val state by vm.state.collectAsState()

    var expandedSet by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var editingSetId by remember { mutableStateOf<Long?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Cream),
    ) {
        when (state) {
            is InFlightState.Loading -> LoadingContent(onBack)
            is InFlightState.NoChecklist -> NoChecklistContent(state as InFlightState.NoChecklist, expandedSet, onBack)
            is InFlightState.Ready -> ReadyContent(
                state = state as InFlightState.Ready,
                vm = vm,
                expandedSet = expandedSet,
                onExpand = { id -> expandedSet = expandedSet + id },
                onCollapse = { id -> expandedSet = expandedSet - id },
                onEdit = { editingSetId = it },
                onBack = onBack,
            )
        }
    }

    // Stepper dialog
    editingSetId?.let { setId ->
        if (state is InFlightState.Ready) {
            val ready = state as InFlightState.Ready
            val rowAndCard = findRowAndCard(ready, setId)
            if (rowAndCard != null) {
                StepperDialog(
                    card = rowAndCard.card,
                    row = rowAndCard.row,
                    weightUnit = ready.weightUnit,
                    onDismiss = { editingSetId = null },
                    onSave = { reps, seconds, weight ->
                        vm.saveEdit(setId, reps, seconds, weight)
                        editingSetId = null
                    },
                    onSkip = {
                        vm.skipSet(setId)
                        editingSetId = null
                    },
                )
            } else {
                editingSetId = null
            }
        }
    }
}

private data class RowCard(val row: SetRow, val card: ExerciseCard)
private fun findRowAndCard(state: InFlightState.Ready, setId: Long): RowCard? {
    for (card in state.cards) {
        for (row in card.sets) {
            if (row.setId == setId) return RowCard(row, card)
        }
    }
    return null
}

// ── Loading ────────────────────────────────────────────────────────

@Composable
private fun LoadingContent(onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().background(Cream)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Teal)
                .padding(top = 22.dp, start = 20.dp, end = 20.dp, bottom = 18.dp),
        )
        DuoStripe()
        Spacer(Modifier.weight(1f))
        Footer(onBack)
    }
}

// ── No checklist ───────────────────────────────────────────────────

@Composable
private fun NoChecklistContent(noCheck: InFlightState.NoChecklist, expandedSet: Set<Long>, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().background(Cream)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Teal)
                .padding(top = 22.dp, start = 20.dp, end = 20.dp, bottom = 18.dp),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Eyebrow(text = noCheck.eyebrow, color = Mustard)
                Spacer(Modifier.height(4.dp))
                DisplayTitle(text = noCheck.title.uppercase(), color = Cream, style = LiftoffType.headerTitle())
            }
        }
        DuoStripe()
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "No checklist for this sortie.",
                style = LiftoffType.note().copy(color = Muted),
            )
        }
        Footer(onBack)
    }
}

// ── Ready content ──────────────────────────────────────────────────

@Composable
private fun ReadyContent(
    state: InFlightState.Ready,
    vm: InFlightViewModel,
    expandedSet: Set<Long>,
    onExpand: (Long) -> Unit,
    onCollapse: (Long) -> Unit,
    onEdit: (Long) -> Unit,
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().background(Cream)) {
        ReadyHeader(state)
        DuoStripe()

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            itemsIndexed(state.cards) { idx, card ->
                val isExpanded = expandedSet.contains(card.plannedExerciseId)
                when (card.status) {
                    CardStatus.DONE, CardStatus.SKIPPED -> {
                        if (isExpanded) {
                            FullCard(
                                card = card,
                                vm = vm,
                                currentSetId = state.currentSetId,
                                onEdit = onEdit,
                                onCollapse = { onCollapse(card.plannedExerciseId) },
                            )
                        } else {
                            CollapsedRow(card, onExpand)
                        }
                    }
                    else -> {
                        val hasShadow = card.status == CardStatus.ACTIVE
                        FullCard(
                            card = card,
                            vm = vm,
                            currentSetId = state.currentSetId,
                            onEdit = onEdit,
                            hasShadow = hasShadow,
                        )
                    }
                }
            }
        }

        Footer(onBack = onBack)
    }
}

// ── Header ─────────────────────────────────────────────────────────

@Composable
private fun ReadyHeader(state: InFlightState.Ready) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Teal)
            .padding(top = 22.dp, start = 20.dp, end = 20.dp, bottom = 18.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                    Eyebrow(text = state.eyebrow, color = Mustard)
                    Spacer(Modifier.height(4.dp))
                    DisplayTitle(text = state.title.uppercase(), color = Cream, style = LiftoffType.headerTitle())
                }

                if (state.setCount > 0) {
                    val doneStr = "%02d".format(state.doneCount)
                    val totalStr = "%02d".format(state.setCount)
                    val counterText = "$doneStr/$totalStr\nSETS"

                    val style = LiftoffType.headerTitle() // BigShoulders 900, 44 sp base
                    val annotated = AnnotatedString.Builder(counterText).apply {
                        addStyle(
                            SpanStyle(color = Mustard, fontSize = 44.sp),
                            start = 0,
                            end = doneStr.length,
                        )
                        addStyle(
                            SpanStyle(color = Cream, fontSize = 22.sp),
                            start = doneStr.length,
                            end = doneStr.length + 1 + totalStr.length, // "/14"
                        )
                        addStyle(
                            SpanStyle(color = Cream, fontSize = 13.sp),
                            start = doneStr.length + 1 + totalStr.length, // "\nSETS"
                            end = counterText.length,
                        )
                    }.toAnnotatedString()

                    Text(
                        text = annotated,
                        lineHeight = 1.em,
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            if (state.progress.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    for (status in state.progress) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(10.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            when (status) {
                                SetStatus.DONE -> {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(Mustard, ButtonShape),
                                    )
                                }
                                SetStatus.OPEN -> {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .border(BorderStroke(1.5.dp, TealLight), ButtonShape),
                                    )
                                }
                                SetStatus.SKIPPED -> {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(TealLight, ButtonShape),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Collapsed row (done/skipped) ───────────────────────────────────

@Composable
private fun CollapsedRow(card: ExerciseCard, onExpand: (Long) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Sand, ButtonShape)
            .border(BorderStroke(2.dp, Ink), ButtonShape)
            .clickable { onExpand(card.plannedExerciseId) }
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "%02d".format(card.number),
            style = LiftoffType.index().copy(color = Muted),
        )
        Text(
            text = card.name,
            style = LiftoffType.exerciseName().copy(
                color = Muted,
                textDecoration = TextDecoration.LineThrough,
            ),
            modifier = Modifier.weight(1f),
        )

        if (card.status == CardStatus.DONE) {
            Text(
                text = "${card.doneCount}/${card.setCount} LANDED",
                style = LiftoffType.sectionHead().copy(
                    fontSize = 18.sp,
                    letterSpacing = 0.06.em,
                    color = Teal,
                ),
            )
        } else {
            Text(
                text = "SKIPPED",
                style = LiftoffType.sectionHead().copy(
                    fontSize = 18.sp,
                    letterSpacing = 0.06.em,
                    color = Muted,
                ),
            )
        }
    }
}

// ── Full card (active, upcoming, expanded done/skipped) ────────────

@Composable
private fun FullCard(
    card: ExerciseCard,
    vm: InFlightViewModel,
    currentSetId: Long?,
    onEdit: (Long) -> Unit,
    onCollapse: (() -> Unit)? = null,
    hasShadow: Boolean = false,
) {
    val shadowModifier = if (hasShadow) Modifier.offsetShadow(Ink, CardShape) else Modifier

    Column(
        modifier = shadowModifier
            .fillMaxWidth()
            .background(Paper, CardShape)
            .border(BorderStroke(2.dp, Ink), CardShape),
    ) {
        // Header bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Ink)
                .padding(top = 10.dp, start = 14.dp, end = 14.dp),
        ) {
            if (onCollapse != null) {
                Row(
                    modifier = Modifier.clickable { onCollapse() },
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = "%02d".format(card.number), style = LiftoffType.index().copy(color = Mustard))
                    Text(text = card.name.uppercase(), style = LiftoffType.cardTitle().copy(color = Cream))
                }
            } else {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = "%02d".format(card.number), style = LiftoffType.index().copy(color = Mustard))
                    Text(text = card.name.uppercase(), style = LiftoffType.cardTitle().copy(color = Cream))
                }
            }
        }

        // Set rows
        Column(
            modifier = Modifier.padding(top = 4.dp, bottom = 4.dp, start = 14.dp, end = 14.dp),
        ) {
            card.sets.forEachIndexed { idx, row ->
                if (idx > 0) {
                    Divider(color = Rule, thickness = 1.dp)
                }
                SetRowItem(row, card, currentSetId, vm, onEdit)
            }
        }

        // Footer
        CardFooter(card, vm)
    }
}

// ── Set row (58 dp) ────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SetRowItem(
    row: SetRow,
    card: ExerciseCard,
    currentSetId: Long?,
    vm: InFlightViewModel,
    onEdit: (Long) -> Unit,
) {
    val isCurrent = row.setId == currentSetId
    val labelColor = if (isCurrent) Red else Muted

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp)
            .combinedClickable(
                onClick = {},
                onLongClick = { onEdit(row.setId) },
            ),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Label — 52 dp fixed width
        Box(
            modifier = Modifier.width(52.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = row.label,
                style = LiftoffType.barLabel().copy(
                    color = labelColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.W600,
                    letterSpacing = 0.1.em,
                ),
            )
        }

        // Weight
        ValueCell(
            text = row.weightLabel,
            deviates = row.weightDeviates,
            plannedLabel = row.plannedWeightLabel,
            onValueClick = { onEdit(row.setId) },
            modifier = Modifier.weight(1f),
        )

        // Count
        ValueCell(
            text = row.countLabel,
            deviates = row.countDeviates,
            plannedLabel = row.plannedCountLabel,
            onValueClick = { onEdit(row.setId) },
            modifier = Modifier.weight(1f),
        )

        // Check box — 48 dp
        CheckBox(status = row.status, vm = vm, setId = row.setId, cardName = card.name, label = row.label)
    }
}

@Composable
private fun ValueCell(
    text: String,
    deviates: Boolean,
    plannedLabel: String,
    onValueClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.clickable(onClick = onValueClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (deviates) {
            Text(
                text = text,
                style = LiftoffType.setValue().copy(color = Red),
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "of $plannedLabel",
                style = LiftoffType.barLabel().copy(
                    color = Muted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.W600,
                ),
                modifier = Modifier.padding(start = 6.dp),
            )
        } else {
            Text(
                text = text,
                style = LiftoffType.setValue().copy(color = Ink),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun CheckBox(status: SetStatus, vm: InFlightViewModel, setId: Long, cardName: String, label: String) {
    val desc = when (status) {
        SetStatus.OPEN -> "Check off $cardName $label"
        SetStatus.DONE -> "$cardName $label done"
        SetStatus.SKIPPED -> "$cardName $label skipped"
    }

    Box(
        modifier = Modifier
            .size(48.dp)
            .border(BorderStroke(2.dp, Ink), ButtonShape)
            .background(
                when (status) {
                    SetStatus.DONE -> Red
                    else -> White
                },
                ButtonShape,
            )
            .clickable {
                when (status) {
                    SetStatus.OPEN -> vm.check(setId)
                    SetStatus.DONE -> vm.uncheck(setId)
                    SetStatus.SKIPPED -> vm.reopenSet(setId)
                }
            }
            .semantics { contentDescription = desc },
        contentAlignment = Alignment.Center,
    ) {
        when (status) {
            SetStatus.OPEN -> Unit
            SetStatus.DONE -> Icon(
                painter = LiftoffIcons.check(),
                contentDescription = null,
                tint = White,
                modifier = Modifier.size(24.dp),
            )
            SetStatus.SKIPPED -> {
                Box(
                    modifier = Modifier
                        .width(16.dp)
                        .height(3.dp)
                        .background(Muted, ButtonShape),
                )
            }
        }
    }
}

// ── Card footer ────────────────────────────────────────────────────

@Composable
private fun CardFooter(card: ExerciseCard, vm: InFlightViewModel) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Divider(color = Ink, thickness = 2.dp)
        Row(
            modifier = Modifier.fillMaxWidth().height(44.dp),
            horizontalArrangement = Arrangement.spacedBy(0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // + Add set (enabled)
            FooterCell(
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .border(BorderStroke(1.dp, Rule), ButtonShape),
                text = "+ Add set",
                enabled = true,
                onClick = { vm.addSet(card.plannedExerciseId) },
            )

            Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(Ink))

            // Note (disabled)
            FooterCell(
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .border(BorderStroke(1.dp, Rule), ButtonShape),
                text = "Note",
                enabled = false,
                onClick = {},
            )

            Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(Ink))

            // Skip / Unskip
            val isSkippedExpanded = card.status == CardStatus.SKIPPED
            val skipText = if (isSkippedExpanded) "Unskip" else "Skip"
            val skipAction = if (isSkippedExpanded) {
                { vm.unskipExercise(card.plannedExerciseId) }
            } else {
                { vm.skipExercise(card.plannedExerciseId) }
            }
            FooterCell(
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .border(BorderStroke(1.dp, Rule), ButtonShape),
                text = skipText,
                enabled = true,
                onClick = skipAction,
            )
        }
    }
}

@Composable
private fun FooterCell(modifier: Modifier, text: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = if (enabled)
            modifier.clickable(onClick = onClick)
        else
            modifier.semantics { disabled() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text.uppercase(),
            style = LiftoffType.textButton().copy(
                color = if (enabled) Ink else Muted,
            ),
        )
    }
}

// ── Stepper dialog ─────────────────────────────────────────────────

@Composable
private fun StepperDialog(
    card: ExerciseCard,
    row: SetRow,
    weightUnit: WeightUnit,
    onDismiss: () -> Unit,
    onSave: (reps: Int?, seconds: Int?, weight: Double?) -> Unit,
    onSkip: () -> Unit,
) {
    val stepWeight = if (weightUnit == WeightUnit.LB) 5.0 else 2.5
    val stepCount = if (row.plannedSeconds != null || (row.plannedReps == null && row.actualSeconds != null)) 5 else 1

    // For DONE sets, seed from actual values only (null weight → 0 = bodyweight).
    // For OPEN/SKIPPED sets, seed from planned values.
    val seededWeight = if (row.status == SetStatus.DONE) row.actualWeight ?: 0.0 else (row.plannedWeight ?: 0.0)
    val seededReps = if (row.status == SetStatus.DONE) row.actualReps ?: 0 else (row.plannedReps ?: 0)
    val seededSeconds = if (row.status == SetStatus.DONE) row.actualSeconds ?: 0 else (row.plannedSeconds ?: 0)

    var editWeight by remember(row.setId) { mutableStateOf(seededWeight) }
    var editReps by remember(row.setId) {
        mutableStateOf(seededReps)
    }
    var editSeconds by remember(row.setId) {
        mutableStateOf(seededSeconds)
    }

    val isSeconds = row.plannedSeconds != null || (row.plannedReps == null && row.actualSeconds != null)

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Paper, CardShape)
                .border(BorderStroke(2.dp, Ink), CardShape)
                .padding(16.dp),
        ) {
            Text(
                text = "${card.name.uppercase()} · ${row.label}",
                style = LiftoffType.cardTitle(),
                color = Ink,
                modifier = Modifier.padding(bottom = 16.dp),
            )

            // Weight line
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StepperButton("-", onClick = { editWeight = maxOf(0.0, editWeight - stepWeight) })
                Text(
                    text = if (editWeight == 0.0) "BW" else "${formatWeight(editWeight)} ${weightUnit.name}",
                    style = LiftoffType.setValue(),
                    color = Ink,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                StepperButton("+", onClick = { editWeight += stepWeight })
            }

            Spacer(Modifier.height(12.dp))

            // Count line
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StepperButton("-", onClick = {
                    if (isSeconds) editSeconds = maxOf(0, editSeconds - stepCount)
                    else editReps = maxOf(0, editReps - stepCount)
                })
                Text(
                    text = if (isSeconds) "${editSeconds} s" else "× $editReps",
                    style = LiftoffType.setValue(),
                    color = Ink,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                StepperButton("+", onClick = {
                    if (isSeconds) editSeconds += stepCount
                    else editReps += stepCount
                })
            }

            Spacer(Modifier.height(16.dp))

            // Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PrimaryButton(
                    text = "Save",
                    onClick = {
                        val reps = if (isSeconds) null else editReps
                        val seconds = if (isSeconds) editSeconds else null
                        val weight = if (editWeight == 0.0) null else editWeight
                        onSave(reps, seconds, weight)
                    },
                    height = 48.dp,
                )
                UnderlinedTextButton(text = "Skip set", onClick = onSkip)
                UnderlinedTextButton(text = "Cancel", onClick = onDismiss)
            }
        }
    }
}

@Composable
private fun StepperButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .border(BorderStroke(2.dp, Ink), ButtonShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, style = LiftoffType.setValue(), color = Ink)
    }
}

// ── Footer ─────────────────────────────────────────────────────────

@Composable
private fun Footer(onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Divider(color = Ink, thickness = 3.dp)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Cream)
                .padding(start = 20.dp, top = 14.dp, end = 20.dp, bottom = 20.dp),
            contentAlignment = Alignment.Center,
        ) {
            UnderlinedTextButton(text = "Back", onClick = onBack)
        }
    }
}

// ── Previews ───────────────────────────────────────────────────────

@Preview
@Composable
private fun PreviewInFlightReady() {
    if (LocalInspectionMode.current) {
        // Preview placeholder — real previews require a full AppContainer which is not available at preview time.
    }
}
