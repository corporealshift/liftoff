package com.liftoff.app.domain

import com.liftoff.app.data.GenerationStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/** The derivation called directly over plain values: a status or no row, an error or null, the offline mark, the settings. */
class CoachStatusTest {

    // No row at all, and the coach set up: editable, and Confirm is the only action it offers.
    @Test
    fun noOutlineRowWithAConfiguredCoachIsEditableAndOffersConfirmAlone() {
        val status = derived(coachConfigured = true)
        assertEquals(CoachStatus.Editable, status)
        assertActions(status, confirm = true, retry = false, continueWithoutOutline = false)
    }

    // The newest row SUCCEEDED and the coach set up: editable again, Confirm alone.
    @Test
    fun aSucceededOutlineRowWithAConfiguredCoachIsEditableAndOffersConfirmAlone() {
        val status = derived(GenerationStatus.SUCCEEDED, coachConfigured = true)
        assertEquals(CoachStatus.Editable, status)
        assertActions(status, confirm = true, retry = false, continueWithoutOutline = false)
    }

    // Both open statuses, each with the offline mark clear: planning. The pattern is frozen while a
    // generation is open, so Confirm is not offered; "Continue without outline" is.
    @Test
    fun queuedAndRunningOutlineRowsArePlanningAndOfferContinueWithoutOutlineButNotConfirm() {
        for (open in listOf(GenerationStatus.QUEUED, GenerationStatus.RUNNING)) {
            val status = derived(open, coachConfigured = true)
            assertEquals("$open with the offline mark clear", CoachStatus.Planning, status)
            assertActions(status, confirm = false, retry = false, continueWithoutOutline = true)
        }
    }

    // A QUEUED row and a RUNNING row each carrying the mark a worker writes while it waits on the coach:
    // offline, not planning.
    @Test
    fun anOpenOutlineRowCarryingTheOfflineMarkIsOfflineRatherThanPlanning() {
        for (open in listOf(GenerationStatus.QUEUED, GenerationStatus.RUNNING)) {
            val status = derived(open, COACH_OFFLINE_MARK, newestOutlineIsCoachOffline = true, coachConfigured = true)
            assertEquals("$open carrying the offline mark", CoachStatus.Offline, status)
            assertActions(status, confirm = false, retry = false, continueWithoutOutline = true)
        }
    }

    // The reason on the row, word for word: one of the kind a worker writes, one of the kind a validator
    // writes and the worker joins. Retry and "Continue without outline" are offered, Confirm is not.
    @Test
    fun aFailedOutlineRowReportsFailedWithItsReasonWordForWordAndOffersRetryAndContinueWithoutOutline() {
        val workerReason = "coach unreachable"
        val validatorReason = "invalid output: sorties: expected 3 sorties for the pattern RLR, got 2; " +
            "exercises[0].sets[1]: reps and seconds cannot both be set"
        for (reason in listOf(workerReason, validatorReason)) {
            val status = derived(GenerationStatus.FAILED, reason, coachConfigured = true)
            assertEquals(CoachStatus.Failed(reason), status)
            assertEquals("the reason as the row carries it", reason, (status as CoachStatus.Failed).reason)
            assertActions(status, confirm = false, retry = true, continueWithoutOutline = true)
        }
    }

    // No row and the coach not set up: not configured. Confirm would only come back as "Mission Control
    // isn't set up", so "Continue without outline" is the action on offer.
    @Test
    fun aBlankHostOrTokenWithNoOutlineRowIsNotConfiguredAndOffersContinueWithoutOutlineButNotConfirm() {
        val status = derived(coachConfigured = false)
        assertEquals(CoachStatus.NotConfigured, status)
        assertActions(status, confirm = false, retry = false, continueWithoutOutline = true)
    }

    // Plan Decision: an open row decides the answer even with a blank host and token, and so does its
    // offline mark. Never not configured.
    @Test
    fun anOpenOutlineRowOutranksBlankSettingsAndIsPlanningOrOffline() {
        for (open in listOf(GenerationStatus.QUEUED, GenerationStatus.RUNNING)) {
            val planning = derived(open, coachConfigured = false)
            assertEquals("$open with the settings blank", CoachStatus.Planning, planning)
            assertActions(planning, confirm = false, retry = false, continueWithoutOutline = true)

            val offline = derived(open, COACH_OFFLINE_MARK, newestOutlineIsCoachOffline = true, coachConfigured = false)
            assertEquals("$open with the mark and the settings blank", CoachStatus.Offline, offline)
            assertActions(offline, confirm = false, retry = false, continueWithoutOutline = true)
        }
    }

    // Plan Decision: a CANCELLED status is no row — the DAO never hands one over, but the enum has it, and
    // cancelling means nothing was queued.
    @Test
    fun aCancelledStatusIsTreatedAsNoOutlineRow() {
        val configured = derived(GenerationStatus.CANCELLED, coachConfigured = true)
        assertEquals(CoachStatus.Editable, configured)
        assertActions(configured, confirm = true, retry = false, continueWithoutOutline = false)

        val blankSettings = derived(GenerationStatus.CANCELLED, "coach unreachable", coachConfigured = false)
        assertEquals(CoachStatus.NotConfigured, blankSettings)
        assertActions(blankSettings, confirm = false, retry = false, continueWithoutOutline = true)
    }

    // Plan Decision: a FAILED row with no reason text still reports failed, with the fixed neutral line.
    @Test
    fun aFailedOutlineRowWithNoReasonTextStillReportsFailedAndOffersRetryWithAReason() {
        for (blank in listOf(null, "", "   ")) {
            val status = derived(GenerationStatus.FAILED, blank, coachConfigured = true)
            assertEquals(CoachStatus.Failed(CoachStatus.FAILED_WITHOUT_REASON), status)
            assertEquals(
                "a FAILED row carrying $blank gets the fixed line",
                CoachStatus.FAILED_WITHOUT_REASON,
                (status as CoachStatus.Failed).reason,
            )
            assertActions(status, confirm = false, retry = true, continueWithoutOutline = true)
        }
    }

    // Plan Decision: a FAILED row outranks blank settings, so the reason and the Retry survive them.
    @Test
    fun aFailedOutlineRowOutranksBlankSettingsAndKeepsItsReasonAndRetry() {
        val status = derived(GenerationStatus.FAILED, "coach unreachable", coachConfigured = false)
        assertEquals(CoachStatus.Failed("coach unreachable"), status)
        assertEquals(
            "blank settings do not hide the row's reason",
            "coach unreachable",
            (status as CoachStatus.Failed).reason,
        )
        assertActions(status, confirm = false, retry = true, continueWithoutOutline = true)
    }

    // Plan Decision: a SUCCEEDED row with a blank host or token reads as no row, so it is not configured
    // rather than editable.
    @Test
    fun aSucceededOutlineRowWithBlankSettingsIsNotConfiguredRatherThanEditable() {
        val status = derived(GenerationStatus.SUCCEEDED, coachConfigured = false)
        assertEquals(CoachStatus.NotConfigured, status)
        assertActions(status, confirm = false, retry = false, continueWithoutOutline = true)
    }

    private fun derived(
        newestOutlineStatus: GenerationStatus? = null,
        newestOutlineError: String? = null,
        newestOutlineIsCoachOffline: Boolean = false,
        coachConfigured: Boolean,
    ): CoachStatus =
        deriveCoachStatus(newestOutlineStatus, newestOutlineError, newestOutlineIsCoachOffline, coachConfigured)

    private fun assertActions(status: CoachStatus, confirm: Boolean, retry: Boolean, continueWithoutOutline: Boolean) {
        assertEquals("$status offers Confirm", confirm, status.offersConfirm)
        assertEquals("$status offers Retry", retry, status.offersRetry)
        assertEquals("$status offers 'Continue without outline'", continueWithoutOutline, status.offersContinueWithoutOutline)
    }

    private companion object {
        // Generation.COACH_OFFLINE, spelled out so this class reads no Room entity.
        const val COACH_OFFLINE_MARK = "Mission Control offline"
    }
}
