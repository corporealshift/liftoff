package com.liftoff.app.domain

import com.liftoff.app.data.GenerationStatus

/**
 * What the draft says about Mission Control, read off its newest outline row and the settings. Each variant
 * names the actions it offers.
 */
sealed interface CoachStatus {
    val offersConfirm: Boolean
    val offersRetry: Boolean
    val offersContinueWithoutOutline: Boolean

    /** Nothing is open and the coach is set up: the pattern is editable and Confirm is the way on. */
    data object Editable : CoachStatus {
        override val offersConfirm = true
        override val offersRetry = false
        override val offersContinueWithoutOutline = false
    }

    /** An outline generation is open and the coach is answering it. */
    data object Planning : CoachStatus {
        override val offersConfirm = false
        override val offersRetry = false
        override val offersContinueWithoutOutline = true
    }

    /** An open row is waiting on a Mission Control it cannot reach. */
    data object Offline : CoachStatus {
        override val offersConfirm = false
        override val offersRetry = false
        override val offersContinueWithoutOutline = true
    }

    /** The row's own reason, kept word for word. */
    data class Failed(val reason: String) : CoachStatus {
        override val offersConfirm = false
        override val offersRetry = true
        override val offersContinueWithoutOutline = true
    }

    /** No row is worth reporting and Mission Control isn't set up. */
    data object NotConfigured : CoachStatus {
        override val offersConfirm = false
        override val offersRetry = false
        override val offersContinueWithoutOutline = true
    }

    companion object {
        // markFailed always writes a reason, so this line is for an imported or hand-written row.
        const val FAILED_WITHOUT_REASON = "outline generation failed"
    }
}

/**
 * The draft's coach status from the newest non-cancelled outline row's plain values — or no row at all — plus
 * whether the coach is configured. A FAILED or open row answers on its own; the settings answer only when no
 * row is worth reporting. A CANCELLED status is no row.
 */
fun deriveCoachStatus(
    newestOutlineStatus: GenerationStatus?,
    newestOutlineError: String?,
    newestOutlineIsCoachOffline: Boolean,
    coachConfigured: Boolean,
): CoachStatus =
    when (newestOutlineStatus) {
        GenerationStatus.QUEUED, GenerationStatus.RUNNING ->
            if (newestOutlineIsCoachOffline) CoachStatus.Offline else CoachStatus.Planning

        GenerationStatus.FAILED ->
            CoachStatus.Failed(newestOutlineError?.takeIf { it.isNotBlank() } ?: CoachStatus.FAILED_WITHOUT_REASON)

        // A SUCCEEDED outline activated the mission in the same transaction, so a draft carries no outline to show.
        GenerationStatus.SUCCEEDED, GenerationStatus.CANCELLED, null ->
            if (coachConfigured) CoachStatus.Editable else CoachStatus.NotConfigured
    }
