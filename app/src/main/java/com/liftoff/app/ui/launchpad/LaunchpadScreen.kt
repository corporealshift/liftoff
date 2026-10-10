package com.liftoff.app.ui.launchpad

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.liftoff.app.AppContainer
import com.liftoff.app.data.*
import com.liftoff.app.ui.PlaceholderScreen
import com.liftoff.app.ui.sortie.FlightPlanSection
import com.liftoff.app.ui.sortie.deriveChips
import com.liftoff.app.ui.sortie.formatWeekDate
import com.liftoff.app.ui.theme.*

@Composable
fun LaunchpadScreen(
    container: AppContainer,
    onOpenInFlight: (Long) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val viewModel = remember(container) {
        LaunchpadViewModel(container.database, container.missionManager, scope)
    }

    val s by viewModel.state.collectAsState()

    Scaffold(
        modifier = Modifier.background(Cream),
        content = { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 20.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                when (s) {
                    is LaunchpadState.Loading -> Unit
                    is LaunchpadState.Draft -> DraftContent(s as LaunchpadState.Draft, viewModel)
                    is LaunchpadState.Planned -> PlannedContent(s as LaunchpadState.Planned, viewModel)
                    is LaunchpadState.Pending -> PendingContent(s as LaunchpadState.Pending, viewModel)
                    is LaunchpadState.InFlight -> InFlightContent(s as LaunchpadState.InFlight, viewModel, onOpenInFlight)
                    is LaunchpadState.Closed -> ClosedContent(s as LaunchpadState.Closed)
                }
            }
        },
        bottomBar = {
            ActionButtons(
                state = s,
                scrubbing = viewModel.scrubbing,
                scrubReason = viewModel.scrubReason,
                onToggleChip = { viewModel.toggleChip(it) },
                onAddChip = { viewModel.addChip() },
                onRemoveChip = { viewModel.removeChip() },
                onConfirm = { viewModel.confirm() },
                onLaunch = { viewModel.launch(onOpenInFlight) },
                onRequestScrub = { viewModel.requestScrub() },
                onCancelScrub = { viewModel.cancelScrub() },
                onConfirmScrub = { viewModel.confirmScrub() },
            )
        },
    )

    // Scrub confirmation dialog.
    val scrubState = s
    val showScrubDialog = when (scrubState) {
        is LaunchpadState.Planned -> viewModel.scrubbing
        is LaunchpadState.Pending -> viewModel.scrubbing
        is LaunchpadState.InFlight -> viewModel.scrubbing
        else -> false
    }
    if (showScrubDialog) {
        val sortieIndex = when (scrubState) {
            is LaunchpadState.Planned -> scrubState.sortieIndex
            is LaunchpadState.Pending -> scrubState.sortieIndex
            is LaunchpadState.InFlight -> scrubState.sortieIndex
            else -> 0
        }
        ScrubDialog(
            sortieIndex = sortieIndex,
            reason = viewModel.scrubReason,
            onReasonChange = { viewModel.setScrubReason(it) },
            onConfirm = { viewModel.confirmScrub() },
            onCancel = { viewModel.cancelScrub() },
        )
    }
}

@Composable
private fun DraftContent(draft: LaunchpadState.Draft, viewModel: LaunchpadViewModel) {
    TitleBlock(
        eyebrow = "WEEK OF ${formatWeekDate(draft.weekStart)} · DRAFT",
        title = "New Mission",
    )
    Spacer(Modifier.height(16.dp))

    DraftPatternChips(
        pattern = draft.pattern,
        onToggle = { viewModel.toggleChip(it) },
        onAdd = { viewModel.addChip() },
        onRemove = { viewModel.removeChip() },
    )

    Spacer(Modifier.height(20.dp))
}

@Composable
private fun PlannedContent(state: LaunchpadState.Planned, viewModel: LaunchpadViewModel) {
    TitleBlock(eyebrow = state.eyebrow, title = state.title)
    Spacer(Modifier.height(16.dp))
    PatternTrack(chips = state.chips)
    Spacer(Modifier.height(20.dp))
    FlightPlanSection(plan = state.plan, sortieType = state.sortieType)
}

@Composable
private fun PendingContent(state: LaunchpadState.Pending, viewModel: LaunchpadViewModel) {
    TitleBlock(eyebrow = state.eyebrow, title = state.title)
    Spacer(Modifier.height(16.dp))
    PatternTrack(chips = state.chips)
    Spacer(Modifier.height(20.dp))
    Text(
        text = "Flight Plan not ready yet.",
        style = LiftoffType.textButton().copy(color = Muted),
    )
}

@Composable
private fun InFlightContent(state: LaunchpadState.InFlight, viewModel: LaunchpadViewModel, onOpenInFlight: (Long) -> Unit) {
    TitleBlock(eyebrow = state.eyebrow, title = state.title)
    Spacer(Modifier.height(16.dp))
    PatternTrack(chips = state.chips)
    if (state.plan != null) {
        Spacer(Modifier.height(20.dp))
        FlightPlanSection(plan = state.plan, sortieType = state.sortieType)
    }
}

@Composable
private fun ClosedContent(state: LaunchpadState.Closed) {
    TitleBlock(
        eyebrow = "WEEK OF ${formatWeekDate(state.weekStart)} · MISSION CLOSED",
        title = "Mission complete",
    )
    Spacer(Modifier.height(16.dp))
    Text(
        text = "${state.landedCount} of ${state.total} sorties landed.",
        style = LiftoffType.note().copy(color = Muted),
    )
    Spacer(Modifier.height(20.dp))
    PatternTrack(chips = state.chips)
}

@Composable
private fun ActionButtons(
    state: LaunchpadState,
    scrubbing: Boolean,
    scrubReason: String,
    onToggleChip: (Int) -> Unit,
    onAddChip: () -> Unit,
    onRemoveChip: () -> Unit,
    onConfirm: () -> Unit,
    onLaunch: () -> Unit,
    onRequestScrub: () -> Unit,
    onCancelScrub: () -> Unit,
    onConfirmScrub: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 14.dp),
    ) {
        when (state) {
            is LaunchpadState.Draft -> DraftActions(state, onToggleChip, onAddChip, onRemoveChip, onConfirm)
            is LaunchpadState.Planned -> PlannedActions(onLaunch, onRequestScrub)
            is LaunchpadState.Pending -> PendingActions(onRequestScrub)
            is LaunchpadState.InFlight -> InFlightActions(onLaunch)
            is LaunchpadState.Closed -> Unit
            is LaunchpadState.Loading -> Unit
        }
    }
}

@Composable
private fun DraftActions(
    draft: LaunchpadState.Draft,
    onToggleChip: (Int) -> Unit,
    onAddChip: () -> Unit,
    onRemoveChip: () -> Unit,
    onConfirm: () -> Unit,
) {
    val count = draft.pattern.length
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InkButton(
            text = "−",
            onClick = onRemoveChip,
            modifier = Modifier.width(48.dp),
            height = 40.dp,
        )
        Text(
            text = "$count sorties",
            style = LiftoffType.note().copy(color = Muted),
        )
        InkButton(
            text = "+",
            onClick = onAddChip,
            modifier = Modifier.width(48.dp),
            height = 40.dp,
        )
    }
    Spacer(Modifier.height(12.dp))
    PrimaryButton(text = "Confirm", onClick = onConfirm)
}

@Composable
private fun PlannedActions(onLaunch: () -> Unit, onRequestScrub: () -> Unit) {
    PrimaryButton(
        text = "Launch",
        onClick = onLaunch,
        icon = LiftoffIcons.rocket(),
        iconAtEnd = true,
    )
    Spacer(Modifier.height(12.dp))
    UnderlinedTextButton(text = "Scrub", onClick = onRequestScrub)
}

@Composable
private fun PendingActions(onRequestScrub: () -> Unit) {
    UnderlinedTextButton(text = "Scrub", onClick = onRequestScrub)
}

@Composable
private fun InFlightActions(onLaunch: () -> Unit) {
    PrimaryButton(
        text = "Resume",
        onClick = onLaunch,
        icon = LiftoffIcons.rocket(),
        iconAtEnd = true,
    )
}

/** Scrub confirmation dialog built from a Paper card inside a Dialog. */
@Composable
private fun ScrubDialog(
    sortieIndex: Int,
    reason: String,
    onReasonChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    Dialog(onDismissRequest = { /* no dismiss outside buttons */ }) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Paper, CardShape)
                .border(BorderStroke(2.dp, Ink), CardShape)
                .padding(16.dp),
        ) {
            Text(
                text = "Scrub sortie ${sortieIndex + 1}?",
                style = LiftoffType.sectionHead(),
                color = Ink,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            OutlinedTextField(
                value = reason,
                onValueChange = onReasonChange,
                label = { Text("Reason (optional)") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PrimaryButton(text = "Scrub", onClick = onConfirm, height = 48.dp)
                UnderlinedTextButton(text = "Cancel", onClick = onCancel)
            }
        }
    }
}

/** Editable pattern chips for Draft state — copied from MissionControlScreen's PatternChips. */
@Composable
private fun DraftPatternChips(
    pattern: String,
    onToggle: (Int) -> Unit,
    onAdd: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (i in pattern.indices) {
                val chip = pattern[i]
                val isSelected = chip == 'R'
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(
                            if (isSelected) Red else Paper,
                            RoundedCornerShape(50),
                        )
                        .border(BorderStroke(2.dp, Ink), RoundedCornerShape(50))
                        .clickable { onToggle(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = chip.toString(),
                        style = LiftoffType.index(),
                        color = if (isSelected) White else Ink,
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedButton(
                onClick = onRemove,
                enabled = pattern.length > 1,
                shape = ButtonShape,
                border = BorderStroke(2.dp, Ink),
                modifier = Modifier.size(44.dp),
                contentPadding = PaddingValues(0.dp),
            ) {
                Text("-", style = LiftoffType.index(), color = Ink)
            }
            OutlinedButton(
                onClick = onAdd,
                enabled = pattern.length < 7,
                shape = ButtonShape,
                border = BorderStroke(2.dp, Ink),
                modifier = Modifier.size(44.dp),
                contentPadding = PaddingValues(0.dp),
            ) {
                Text("+", style = LiftoffType.index(), color = Ink)
            }
        }
    }
}


