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
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.liftoff.app.domain.CoachStatus
import com.liftoff.app.domain.SortieGenerationStatus
import com.liftoff.app.ui.PlaceholderScreen
import com.liftoff.app.ui.inflight.ScrubDialog
import com.liftoff.app.ui.sortie.FlightPlanSection
import com.liftoff.app.ui.sortie.deriveChips
import com.liftoff.app.ui.sortie.formatWeekDate
import com.liftoff.app.ui.theme.*
import kotlinx.coroutines.CoroutineScope

@Composable
fun LaunchpadScreen(
    container: AppContainer,
    onOpenInFlight: (Long) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val viewModel = remember(container) { launchpadViewModel(container, scope) }
    LaunchpadContent(viewModel = viewModel, onOpenInFlight = onOpenInFlight)
}

/**
 * The view model the screen builds, apart from the composable so a test can build the same one. Lambdas,
 * not `::enqueue`: the container asks WorkManager for itself, and a bound reference would ask every time
 * the screen composes — including in the shell tests, which never queue anything and initialize no WorkManager.
 */
internal fun launchpadViewModel(container: AppContainer, scope: CoroutineScope): LaunchpadViewModel =
    LaunchpadViewModel(
        db = container.database,
        manager = container.missionManager,
        scope = scope,
        queueGeneration = { id -> container.generationEnqueuer().enqueue(id) },
        cancelGeneration = { id -> container.generationCanceller().cancel(id) },
    )

/** The screen body, apart from AppContainer so a test can render it on its own view model. */
@Composable
internal fun LaunchpadContent(
    viewModel: LaunchpadViewModel,
    onOpenInFlight: (Long) -> Unit,
) {
    val s by viewModel.state.collectAsState()

    // Keyed on the PENDING card's sortieId, so the reveal resets with the sortie and not with the status line.
    val pendingSortieId = (s as? LaunchpadState.Pending)?.sortieId
    var rhoSessionRevealed by rememberSaveable(pendingSortieId) { mutableStateOf(false) }

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
                    is LaunchpadState.Pending -> PendingContent(s as LaunchpadState.Pending, viewModel, rhoSessionRevealed)
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
                onRetry = { viewModel.retry() },
                onContinueWithoutOutline = { viewModel.continueWithoutOutline() },
                onLaunch = { viewModel.launch(onOpenInFlight) },
                onRequestScrub = { viewModel.requestScrub() },
                onRefly = { viewModel.refly() },
                onRegenerate = { viewModel.regenerate() },
                rhoSessionRevealed = rhoSessionRevealed,
                onRevealRhoSession = { rhoSessionRevealed = true },
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

    val status = draft.coachStatus
    DraftPatternChips(
        pattern = draft.pattern,
        onToggle = { viewModel.toggleChip(it) },
        enabled = status.patternEditable,
    )

    // One line under the pattern, whatever the coach is doing. A Retry that found nothing configured
    // replaces a failed draft's reason rather than adding a second line saying the same thing twice.
    val line = draftStatusLine(status, viewModel.notConfiguredMessage)
    if (line != null) {
        Spacer(Modifier.height(16.dp))
        Text(
            text = line.text,
            style = LiftoffType.note().copy(color = line.color),
        )
    }

    Spacer(Modifier.height(20.dp))
}

private class DraftLine(val text: String, val color: Color)

/** The draft's one status line; no line when the coach has nothing to say about this draft. */
private fun draftStatusLine(status: CoachStatus, report: String?): DraftLine? =
    when (status) {
        CoachStatus.Editable -> null
        CoachStatus.Planning -> DraftLine(PLANNING_LINE, Muted)
        CoachStatus.Offline -> DraftLine(OFFLINE_LINE, Muted)
        // The row's own words, with no label in front of them. The report a Retry left is the same
        // kind of fault, so it takes this slot and this colour.
        is CoachStatus.Failed -> DraftLine(report ?: status.reason, Red)
        CoachStatus.NotConfigured -> DraftLine(NOT_CONFIGURED_MESSAGE, Muted)
    }

/** Read-only only while a generation is open: a failed or unconfigured draft can still be reshaped. */
private val CoachStatus.patternEditable: Boolean
    get() = this != CoachStatus.Planning && this != CoachStatus.Offline

private const val PLANNING_LINE = "Coach is planning the week…"
private const val OFFLINE_LINE = "Mission Control offline — retrying"

private const val RE_PLANNING_LINE = "Coach is re-planning…"

@Composable
private fun PlannedContent(state: LaunchpadState.Planned, viewModel: LaunchpadViewModel) {
    TitleBlock(eyebrow = state.eyebrow, title = state.title)
    Spacer(Modifier.height(16.dp))
    PatternTrack(chips = state.chips)
    Spacer(Modifier.height(20.dp))
    // A PLANNED card's status is only ever RePlanning or Idle; only RePlanning has anything to report.
    if (state.generationStatus == SortieGenerationStatus.RePlanning) {
        Text(
            text = RE_PLANNING_LINE,
            style = LiftoffType.note().copy(color = Muted),
        )
    }
    FlightPlanSection(plan = state.plan, sortieType = state.sortieType, sortieFocus = state.sortieFocus)
}

@Composable
private fun PendingContent(
    state: LaunchpadState.Pending,
    viewModel: LaunchpadViewModel,
    rhoSessionRevealed: Boolean,
) {
    TitleBlock(eyebrow = state.eyebrow, title = state.title)
    Spacer(Modifier.height(16.dp))
    PatternTrack(chips = state.chips)
    Spacer(Modifier.height(20.dp))
    val line = pendingStatusLine(state.generationStatus)
    Text(
        text = line.text,
        style = LiftoffType.note().copy(color = line.color),
    )
    // A refused reply's validator lines, each on its own line under the reason that names them.
    for (error in line.validatorErrors) {
        Spacer(Modifier.height(4.dp))
        Text(
            text = error,
            style = LiftoffType.note().copy(color = Muted),
        )
    }
    // Revealed only on request, and always the id this sortie's generation row carries now.
    val sessionId = state.rhoSessionId
    if (rhoSessionRevealed && sessionId != null) {
        Text(
            text = "rho session $sessionId",
            style = LiftoffType.note().copy(color = Muted),
        )
    }
}

private class PendingLine(val text: String, val color: Color, val validatorErrors: List<String> = emptyList())

/** The PENDING card's one status line. Idle and RePlanning keep the line the card drew before it had a status. */
private fun pendingStatusLine(status: SortieGenerationStatus): PendingLine =
    when (status) {
        SortieGenerationStatus.Planning -> PendingLine(PENDING_PLANNING_LINE, Muted)
        SortieGenerationStatus.Offline -> PendingLine(OFFLINE_LINE, Muted)
        SortieGenerationStatus.NotConfigured -> PendingLine(NOT_CONFIGURED_MESSAGE, Muted)
        // The row's own words, as the domain already split them; the card does not split them again.
        is SortieGenerationStatus.Failed -> PendingLine(status.reason, Red, status.validatorErrors)
        SortieGenerationStatus.Idle, SortieGenerationStatus.RePlanning -> PendingLine(NOT_READY_LINE, Muted)
    }

private const val PENDING_PLANNING_LINE = "Coach is planning…"
private const val NOT_READY_LINE = "Flight Plan not ready yet."

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
    onRetry: () -> Unit,
    onContinueWithoutOutline: () -> Unit,
    onLaunch: () -> Unit,
    onRequestScrub: () -> Unit,
    onRefly: () -> Unit,
    onRegenerate: () -> Unit,
    rhoSessionRevealed: Boolean = false,
    onRevealRhoSession: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 14.dp),
    ) {
        when (state) {
            is LaunchpadState.Draft -> DraftActions(
                state,
                onToggleChip,
                onAddChip,
                onRemoveChip,
                onConfirm,
                onRetry,
                onContinueWithoutOutline,
            )
            is LaunchpadState.Planned -> PlannedActions(state, onLaunch, onRequestScrub, onRegenerate)
            is LaunchpadState.Pending -> PendingActions(
                state, onRequestScrub, onRefly, onRegenerate, rhoSessionRevealed, onRevealRhoSession,
            )
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
    onRetry: () -> Unit,
    onContinueWithoutOutline: () -> Unit,
) {
    val status = draft.coachStatus
    // The stepper reshapes the pattern, so it is drawn only where the pattern is editable.
    if (status.patternEditable) {
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
    }
    if (status.offersConfirm) {
        PrimaryButton(text = "Confirm", onClick = onConfirm)
    }
    // Retry takes Confirm's slot: it is the way on for a draft whose outline generation failed.
    if (status.offersRetry) {
        PrimaryButton(text = "Retry", onClick = onRetry)
    }
    if (status.offersContinueWithoutOutline) {
        if (status.offersConfirm || status.offersRetry) Spacer(Modifier.height(12.dp))
        UnderlinedTextButton(text = "Continue without outline", onClick = onContinueWithoutOutline)
    }
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
private fun PlannedActions(
    planned: LaunchpadState.Planned,
    onLaunch: () -> Unit,
    onRequestScrub: () -> Unit,
    onRegenerate: () -> Unit,
) {
    PrimaryButton(
        text = "Launch",
        onClick = onLaunch,
        icon = LiftoffIcons.rocket(),
        iconAtEnd = true,
    )
    Spacer(Modifier.height(12.dp))
    // An unavailable Regenerate is absent, not dimmed — the rule PendingActions already follows.
    if (planned.regenerateAvailable) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            UnderlinedTextButton(text = "Regenerate", onClick = onRegenerate, modifier = Modifier.weight(1f))
            UnderlinedTextButton(text = "Scrub", onClick = onRequestScrub, modifier = Modifier.weight(1f))
        }
    } else {
        UnderlinedTextButton(text = "Scrub", onClick = onRequestScrub)
    }
}

@Composable
private fun PendingActions(
    pending: LaunchpadState.Pending,
    onRequestScrub: () -> Unit,
    onRefly: () -> Unit,
    onRegenerate: () -> Unit,
    rhoSessionRevealed: Boolean,
    onRevealRhoSession: () -> Unit,
) {
    // Retry only where a regenerate would be accepted: under a failure, and never on a run sortie while
    // 'Generate run plans' is off. PrimaryButton has no enabled parameter, so an unavailable Retry is absent.
    if (pending.generationStatus is SortieGenerationStatus.Failed && pending.regenerateAvailable) {
        PrimaryButton(text = "Retry", onClick = onRegenerate)
        Spacer(Modifier.height(12.dp))
    }
    // Decision 5: no source plan is a line of text, not a disabled control.
    if (pending.reFlyAvailable) {
        UnderlinedTextButton(text = "Re-fly", onClick = onRefly)
        Spacer(Modifier.height(12.dp))
    } else if (pending.generationStatus != SortieGenerationStatus.Planning) {
        // §5.4: under failed, offline or not configured a refusal can only mean no source; while the coach is
        // planning a refusal says nothing about one.
        Text(
            text = "No earlier plan to re-fly.",
            style = LiftoffType.textButton().copy(color = Muted),
        )
        Spacer(Modifier.height(12.dp))
    }
    // Offered whenever this sortie's row carries an id; the tap prints the id and opens nothing.
    if (pending.rhoSessionId != null) {
        UnderlinedTextButton(text = "View in rho", onClick = onRevealRhoSession)
        Spacer(Modifier.height(12.dp))
    }
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
    enabled: Boolean,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (i in pattern.indices) {
                key(i) {
                    val chip = pattern[i]
                    val isSelected = chip == 'R'
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .background(
                                if (isSelected) Red else Paper,
                                RoundedCornerShape(50),
                            )
                            .border(BorderStroke(2.dp, if (enabled) Ink else Rule), RoundedCornerShape(50))
                            // A chip that cannot be toggled is still a chip-shaped control with Enabled=false,
                            // the DraftStepButton shape: no clickable at all would carry no Enabled semantics.
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                enabled = enabled,
                                role = Role.Button,
                                onClick = { onToggle(i) },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = chip.toString(),
                            style = LiftoffType.index(),
                            color = if (isSelected) White else if (enabled) Ink else Muted,
                        )
                    }
                }
            }
        }
    }
}


