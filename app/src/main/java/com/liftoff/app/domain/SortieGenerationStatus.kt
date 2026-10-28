package com.liftoff.app.domain

import com.liftoff.app.data.GenerationStatus

/**
 * What a sortie's card says about its Flight Plan generation, read off its newest non-cancelled generation row's
 * plain values and the settings. Each variant names one answer.
 */
sealed interface SortieGenerationStatus {
    /** A generation is open and Mission Control is answering it. */
    data object Planning : SortieGenerationStatus

    /** An open row is waiting on a Mission Control it cannot reach. */
    data object Offline : SortieGenerationStatus

    /** The row's own reason, kept word for word. A refused reply's validator lines are split out of it. */
    data class Failed(
        val reason: String,
        val validatorErrors: List<String> = emptyList(),
    ) : SortieGenerationStatus

    /** No row is worth reporting and Mission Control isn't set up. */
    data object NotConfigured : SortieGenerationStatus

    /** The sortie holds a plan and a new generation is open for it. */
    data object RePlanning : SortieGenerationStatus

    /** The sortie holds a plan and nothing is being regenerated. */
    data object Idle : SortieGenerationStatus

    companion object {
        // GenerationWorker writes "invalid output: " + the validator's errors joined at "; ", on one line.
        const val INVALID_OUTPUT_LEAD = "invalid output: "
        const val INVALID_OUTPUT = "invalid output"

        // markFailed always writes a reason, so this line is for an imported or hand-written row.
        const val FAILED_WITHOUT_REASON = "flight plan generation failed"

        // The coach was never asked, which is what makes Regenerate the obvious next action.
        const val NO_GENERATION_QUEUED = "no Flight Plan generation was queued"
    }
}

/** The answer plus what the card does with it: the rho session id the row carries, and the §5.4 Re-fly rule. */
data class SortieGenerationAnswer(
    val status: SortieGenerationStatus,
    val rhoSessionId: String?,
    val offersReFly: Boolean,
)

/**
 * A PENDING sortie's status from its newest non-cancelled FLIGHT_PLAN row's plain values — or no row at all — plus
 * whether the coach is configured and whether a plan exists to re-fly from. A FAILED or open row answers on its own;
 * the settings answer only when no row is worth reporting. A SUCCEEDED or CANCELLED status is no row.
 */
fun derivePendingSortieGenerationStatus(
    newestGenerationStatus: GenerationStatus?,
    newestGenerationError: String?,
    newestGenerationIsCoachOffline: Boolean,
    newestGenerationSessionId: String?,
    coachConfigured: Boolean,
    hasReFlySourcePlan: Boolean,
): SortieGenerationAnswer {
    val status = when {
        newestGenerationStatus == null -> noRowAnswer(coachConfigured)

        // A generation that gave up on an unreachable Mission Control reads as a failure, not as offline.
        newestGenerationIsCoachOffline && newestGenerationStatus != GenerationStatus.FAILED ->
            SortieGenerationStatus.Offline

        newestGenerationStatus == GenerationStatus.QUEUED || newestGenerationStatus == GenerationStatus.RUNNING ->
            SortieGenerationStatus.Planning

        newestGenerationStatus == GenerationStatus.FAILED -> failedFrom(newestGenerationError)

        // A SUCCEEDED FLIGHT_PLAN row stored its plan in the same transaction, so the sortie is no longer PENDING.
        else -> noRowAnswer(coachConfigured)
    }
    return SortieGenerationAnswer(status, newestGenerationSessionId, offersReFly(status, hasReFlySourcePlan))
}

/**
 * A PLANNED sortie's status from its newest non-cancelled FLIGHT_PLAN row's plain values — or no row at all. Only
 * re-planning or idle: the plan the sortie already holds is still usable, and Regenerate is already on its card.
 */
fun derivePlannedSortieGenerationStatus(
    newestGenerationStatus: GenerationStatus?,
    newestGenerationSessionId: String?,
): SortieGenerationAnswer =
    SortieGenerationAnswer(
        status = when (newestGenerationStatus) {
            GenerationStatus.QUEUED, GenerationStatus.RUNNING -> SortieGenerationStatus.RePlanning
            GenerationStatus.FAILED,
            GenerationStatus.SUCCEEDED,
            GenerationStatus.CANCELLED,
            null,
            -> SortieGenerationStatus.Idle
        },
        rhoSessionId = newestGenerationSessionId,
        // MissionManager.refly refuses a PLANNED sortie, so this card carries no source for it.
        offersReFly = false,
    )

private fun noRowAnswer(coachConfigured: Boolean): SortieGenerationStatus =
    if (coachConfigured) SortieGenerationStatus.Failed(SortieGenerationStatus.NO_GENERATION_QUEUED)
    else SortieGenerationStatus.NotConfigured

private fun failedFrom(error: String?): SortieGenerationStatus.Failed {
    val text = error?.takeIf { it.isNotBlank() }
        ?: return SortieGenerationStatus.Failed(SortieGenerationStatus.FAILED_WITHOUT_REASON)
    if (!text.startsWith(SortieGenerationStatus.INVALID_OUTPUT_LEAD)) return SortieGenerationStatus.Failed(text)
    return SortieGenerationStatus.Failed(
        reason = SortieGenerationStatus.INVALID_OUTPUT,
        validatorErrors = text
            .removePrefix(SortieGenerationStatus.INVALID_OUTPUT_LEAD)
            .split("; ")
            .filter { it.isNotBlank() },
    )
}

/** §5.4: Re-fly is the way on when the coach cannot do the work, not a second route while it is already doing it. */
private fun offersReFly(status: SortieGenerationStatus, hasReFlySourcePlan: Boolean): Boolean =
    hasReFlySourcePlan && when (status) {
        SortieGenerationStatus.Planning -> false
        SortieGenerationStatus.Offline -> true
        is SortieGenerationStatus.Failed -> true
        SortieGenerationStatus.NotConfigured -> true
        // A PLANNED card's answer never reaches here: refly refuses a PLANNED sortie.
        SortieGenerationStatus.RePlanning, SortieGenerationStatus.Idle -> false
    }
