package com.liftoff.app.domain

import com.liftoff.app.data.GenerationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The derivation called directly over plain values: a status or no row, an error or null, the offline mark, the session id, the settings, a plan to re-fly from. */
class SortieGenerationStatusTest {

    // A QUEUED row and a RUNNING row, the offline mark clear, the coach configured: planning.
    @Test
    fun anOpenFlightPlanRowThatIsNotMarkedOfflineIsPlanning() {
        for (open in listOf(GenerationStatus.QUEUED, GenerationStatus.RUNNING)) {
            val answer = pending(open)
            assertEquals("$open with the offline mark clear", SortieGenerationStatus.Planning, answer.status)
        }
    }

    // A QUEUED and a RUNNING row each carrying Generation.COACH_OFFLINE answer offline. A generation that gave up
    // on an unreachable Mission Control answers failed, not offline.
    @Test
    fun anOpenRowCarryingTheOfflineMarkIsOfflineAndAFailedRowCarryingItIsFailed() {
        for (open in listOf(GenerationStatus.QUEUED, GenerationStatus.RUNNING)) {
            val answer = pending(open, COACH_OFFLINE_MARK, newestGenerationIsCoachOffline = true)
            assertEquals("$open carrying the offline mark", SortieGenerationStatus.Offline, answer.status)
        }

        val gaveUp = pending(GenerationStatus.FAILED, "coach unreachable")
        assertEquals(
            "a generation that gave up answers failed with the reason it wrote when it gave up",
            SortieGenerationStatus.Failed("coach unreachable"),
            gaveUp.status,
        )

        val markedThenFailed = pending(GenerationStatus.FAILED, COACH_OFFLINE_MARK, newestGenerationIsCoachOffline = true)
        assertEquals(
            "the mark a row carried while it retried does not make a FAILED row offline",
            SortieGenerationStatus.Failed(COACH_OFFLINE_MARK),
            markedThenFailed.status,
        )
    }

    // The exact string the row carries comes back as the reason. A FAILED row answers on its own even with a blank
    // host or token.
    @Test
    fun aFailedRowReportsFailedWithItsReasonWordForWord() {
        for (reason in listOf("coach unreachable", "the daemon closed before the reply ended")) {
            for (configured in listOf(true, false)) {
                val answer = pending(GenerationStatus.FAILED, reason, coachConfigured = configured)
                assertEquals("$reason with the coach configured = $configured", SortieGenerationStatus.Failed(reason), answer.status)
                assertEquals(
                    "the reason as the row carries it",
                    reason,
                    (answer.status as SortieGenerationStatus.Failed).reason,
                )
            }
        }
    }

    // The stored text is what GenerationWorker writes today: "invalid output: " plus the validator's errors joined
    // at "; ", on one line. The answer is failed, the reason is the lead, and every validator line is kept, in the
    // order the worker wrote them.
    @Test
    fun theValidatorErrorsStoredOnARejectedReplyAreSplitOutOfTheReasonInOrder() {
        val lines = listOf(
            "sorties: expected 3 sorties for the pattern RLR, got 2",
            "sorties[1].index: must be 1",
            "sorties[1].type: must be 'Run' at position 1 of the pattern RLR",
        )
        val answer = pending(GenerationStatus.FAILED, SortieGenerationStatus.INVALID_OUTPUT_LEAD + lines.joinToString("; "))

        assertEquals("a refused reply answers failed", SortieGenerationStatus.Failed("invalid output", lines), answer.status)
        assertEquals(
            "the reason is the lead sentence, not the whole stored line",
            "invalid output",
            (answer.status as SortieGenerationStatus.Failed).reason,
        )
        assertEquals(
            "the validator's lines, split out in the order the worker wrote them",
            lines,
            (answer.status as SortieGenerationStatus.Failed).validatorErrors,
        )
    }

    // A FAILED row whose error is null or blank still answers failed, with a non-empty reason.
    @Test
    fun aFailedRowWithNoErrorTextStillReportsFailedWithAFallbackReason() {
        for (blank in listOf(null, "", "   ")) {
            val answer = pending(GenerationStatus.FAILED, blank)
            assertTrue("a FAILED row carrying $blank still answers failed: ${answer.status}", answer.status is SortieGenerationStatus.Failed)
            assertTrue(
                "a FAILED row carrying $blank gets a reason",
                (answer.status as SortieGenerationStatus.Failed).reason.isNotBlank(),
            )
        }
    }

    // No row at all and the coach configured: failed, with a reason that says the coach was never asked.
    @Test
    fun aSortieWithNoGenerationRowAndAConfiguredCoachIsFailedWithAReason() {
        val answer = pending(coachConfigured = true)
        assertTrue("no row and a configured coach answers failed: ${answer.status}", answer.status is SortieGenerationStatus.Failed)
        assertTrue(
            "the reason says something the owner can act on",
            (answer.status as SortieGenerationStatus.Failed).reason.isNotBlank(),
        )
    }

    // No row and the coach not set up — a blank host and a blank token each leave coachIsConfigured false.
    @Test
    fun aSortieWithNoGenerationRowAndABlankHostOrTokenIsNotConfigured() {
        for (settings in listOf("" to "token", "rho.example.test" to "", "   " to "token")) {
            val configured = coachIsConfigured(settings.first, settings.second)
            assertFalse("${settings.first} / ${settings.second} is not a configured coach", configured)
            val answer = pending(coachConfigured = configured)
            assertEquals("no row and $settings", SortieGenerationStatus.NotConfigured, answer.status)
        }
    }

    // A SUCCEEDED row and a CANCELLED row are each answered the way no row is: failed with a reason when the coach
    // is configured, not configured when it is not.
    @Test
    fun aSucceededOrCancelledNewestRowIsAnsweredAsIfThereWereNoRow() {
        for (ended in listOf(GenerationStatus.SUCCEEDED, GenerationStatus.CANCELLED)) {
            val configured = pending(ended, coachConfigured = true)
            assertEquals(
                "$ended with the coach configured answers as no row does",
                pending(coachConfigured = true).status,
                configured.status,
            )
            val blankSettings = pending(ended, "coach unreachable", coachConfigured = false)
            assertEquals(
                "$ended with a blank host or token answers as no row does",
                pending(coachConfigured = false).status,
                blankSettings.status,
            )
        }
    }

    // A PLANNED sortie with a QUEUED row and with a RUNNING row each answer re-planning.
    @Test
    fun aPlannedSortieWithAnOpenRowIsRePlanning() {
        for (open in listOf(GenerationStatus.QUEUED, GenerationStatus.RUNNING)) {
            val answer = planned(open)
            assertEquals("$open for a PLANNED sortie", SortieGenerationStatus.RePlanning, answer.status)
        }
    }

    // A FAILED row, a SUCCEEDED row and no row each answer idle for a PLANNED sortie.
    @Test
    fun aPlannedSortieWithAFailedOrSucceededRowOrNoRowIsIdle() {
        for (ended in listOf(GenerationStatus.FAILED, GenerationStatus.SUCCEEDED, null)) {
            val answer = planned(ended)
            assertEquals("$ended for a PLANNED sortie", SortieGenerationStatus.Idle, answer.status)
        }
    }

    // A row with nabuSessionId hands out that exact id; a row without one, and no row at all, hand out none.
    @Test
    fun theRhoSessionIdIsHandedOutWhenTheRowHasOneAndNoneWhenItDoesNot() {
        assertEquals(
            "an open row's session",
            "sess-7",
            pending(GenerationStatus.RUNNING, newestGenerationSessionId = "sess-7").rhoSessionId,
        )
        assertEquals(
            "a FAILED row's session",
            "sess-7",
            pending(GenerationStatus.FAILED, "coach unreachable", newestGenerationSessionId = "sess-7").rhoSessionId,
        )
        assertEquals(
            "a row that never got one hands out none",
            null,
            pending(GenerationStatus.RUNNING).rhoSessionId,
        )
        assertEquals("no row hands out none", null, pending().rhoSessionId)
        assertEquals("a PLANNED card carries the same id", "sess-7", planned(GenerationStatus.QUEUED, "sess-7").rhoSessionId)
    }

    // §5.4: with a source plan, each of failed, offline and not configured offers Re-fly.
    @Test
    fun aSourcePlanOffersReFlyWhenTheGenerationFailedIsOfflineOrUnconfigured() {
        val failed = pending(GenerationStatus.FAILED, "coach unreachable", hasReFlySourcePlan = true)
        assertTrue("failed answers ${failed.status}", failed.offersReFly)

        val offline = pending(GenerationStatus.RUNNING, COACH_OFFLINE_MARK, newestGenerationIsCoachOffline = true, hasReFlySourcePlan = true)
        assertEquals(SortieGenerationStatus.Offline, offline.status)
        assertTrue("offline answers ${offline.status}", offline.offersReFly)

        val unconfigured = pending(coachConfigured = false, hasReFlySourcePlan = true)
        assertEquals(SortieGenerationStatus.NotConfigured, unconfigured.status)
        assertTrue("not configured answers ${unconfigured.status}", unconfigured.offersReFly)
    }

    // §5.4: with a source plan and an open row that is NOT marked offline, the answer is planning and Re-fly is
    // refused — the coach is already doing the work.
    @Test
    fun aSourcePlanDoesNotOfferReFlyWhileAGenerationIsOpen() {
        for (open in listOf(GenerationStatus.QUEUED, GenerationStatus.RUNNING)) {
            val answer = pending(open, hasReFlySourcePlan = true)
            assertEquals("$open with a source plan", SortieGenerationStatus.Planning, answer.status)
            assertFalse("Re-fly is refused while the generation is $open", answer.offersReFly)
        }
    }

    // §5.4: "only when a source plan exists". With none, Re-fly is refused under every PENDING answer.
    @Test
    fun noSourcePlanOffersReFlyUnderNoAnswer() {
        val answers = listOf(
            pending(GenerationStatus.RUNNING),
            pending(GenerationStatus.RUNNING, COACH_OFFLINE_MARK, newestGenerationIsCoachOffline = true),
            pending(GenerationStatus.FAILED, "coach unreachable"),
            pending(coachConfigured = false),
            planned(GenerationStatus.QUEUED),
        )
        for (answer in answers) {
            assertFalse("${answer.status} with no plan to re-fly from", answer.offersReFly)
        }
    }

    private fun pending(
        newestGenerationStatus: GenerationStatus? = null,
        newestGenerationError: String? = null,
        newestGenerationIsCoachOffline: Boolean = false,
        newestGenerationSessionId: String? = null,
        coachConfigured: Boolean = true,
        hasReFlySourcePlan: Boolean = false,
    ): SortieGenerationAnswer = derivePendingSortieGenerationStatus(
        newestGenerationStatus,
        newestGenerationError,
        newestGenerationIsCoachOffline,
        newestGenerationSessionId,
        coachConfigured,
        hasReFlySourcePlan,
    )

    private fun planned(
        newestGenerationStatus: GenerationStatus? = null,
        newestGenerationSessionId: String? = null,
    ): SortieGenerationAnswer = derivePlannedSortieGenerationStatus(newestGenerationStatus, newestGenerationSessionId)

    private companion object {
        // Generation.COACH_OFFLINE, spelled out so this class reads no Room entity.
        const val COACH_OFFLINE_MARK = "Mission Control offline"
    }
}
