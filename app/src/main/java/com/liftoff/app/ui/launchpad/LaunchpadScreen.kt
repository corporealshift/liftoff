package com.liftoff.app.ui.launchpad

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.liftoff.app.AppContainer
import com.liftoff.app.data.*
import com.liftoff.app.ui.PlaceholderScreen
import com.liftoff.app.ui.inflight.ScrubDialog
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
    LaunchpadContent(viewModel = viewModel, onOpenInFlight = onOpenInFlight)
}

/** The screen body, apart from AppContainer so a test can render it on its own view model. */
@Composable
internal fun LaunchpadContent(
    viewModel: LaunchpadViewModel,
    onOpenInFlight: (Long) -> Unit,
) {
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
                onToggleChip = { viewModel.toggleChip(it) },
                onAddChip = { viewModel.addChip() },
                onRemoveChip = { viewModel.removeChip() },
                onConfirm = { viewModel.confirm() },
                onLaunch = { viewModel.launch(onOpenInFlight) },
                onRequestScrub = { viewModel.requestScrub() },
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
            sortieNumber = sortieIndex + 1,
            onConfirm = { viewModel.confirmScrub(it) },
            onDismiss = { viewModel.cancelScrub() },
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
    )

    Spacer(Modifier.height(20.dp))
}

@Composable
private fun PlannedContent(state: LaunchpadState.Planned, viewModel: LaunchpadViewModel) {
    TitleBlock(eyebrow = state.eyebrow, title = state.title)
    Spacer(Modifier.height(16.dp))
    PatternTrack(chips = state.chips)
    Spacer(Modifier.height(20.dp))
    FlightPlanSection(plan = state.plan, sortieType = state.sortieType, sortieFocus = state.sortieFocus)
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
        FlightPlanSection(plan = state.plan, sortieType = state.sortieType, sortieFocus = state.sortieFocus)
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
    onToggleChip: (Int) -> Unit,
    onAddChip: () -> Unit,
    onRemoveChip: () -> Unit,
    onConfirm: () -> Unit,
    onLaunch: () -> Unit,
    onRequestScrub: () -> Unit,
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
        DraftStepButton(
            text = "−",
            enabled = count > 1,
            onClick = onRemoveChip,
            modifier = Modifier.width(48.dp),
        )
        Text(
            text = "$count sorties",
            style = LiftoffType.note().copy(color = Muted),
        )
        DraftStepButton(
            text = "+",
            enabled = count < 7,
            onClick = onAddChip,
            modifier = Modifier.width(48.dp),
        )
    }
    Spacer(Modifier.height(12.dp))
    PrimaryButton(text = "Confirm", onClick = onConfirm)
}

// InkButton has no enabled parameter and ui.theme is out of scope for this brief, so the draft's
// stepper is drawn here. The width arrives through modifier: a fillMaxWidth() inside would swallow
// the SpaceBetween row.
@Composable
private fun DraftStepButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 40.dp,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed = interactionSource.collectIsPressedAsState()
    val shadow = if (!enabled) Rule else if (pressed.value) RedPressed else Red
    OffsetShadowBox(
        color = shadow,
        offset = 4.dp,
        modifier = modifier.height(height),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(if (enabled) Ink else Muted, ButtonShape)
                .border(BorderStroke(2.dp, Ink), ButtonShape)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = enabled,
                    role = Role.Button,
                    onClick = onClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, style = LiftoffType.landLabel().copy(color = if (enabled) Cream else Sand))
        }
    }
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

/** Editable pattern chips for Draft state — copied from MissionControlScreen's PatternChips. */
@Composable
private fun DraftPatternChips(
    pattern: String,
    onToggle: (Int) -> Unit,
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
    }
}


