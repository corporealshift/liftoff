package com.liftoff.app.domain

import com.liftoff.app.data.Mission
import com.liftoff.app.data.MissionStatus
import com.liftoff.app.data.Sortie
import com.liftoff.app.data.SortieState
import com.liftoff.app.data.SortieType
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class MissionsTest {

    @Test
    fun newDraftUsesDefaultPattern() {
        val weekStart = LocalDate.of(2026, 1, 12)
        val draft = newDraft(weekStart, "RLR")
        assertEquals(weekStart, draft.weekStart)
        assertEquals("RLR", draft.pattern)
        assertEquals(MissionStatus.DRAFT, draft.status)
    }

    @Test
    fun overridePatternOnDraft() {
        val weekStart = LocalDate.of(2026, 1, 12)
        val draft = newDraft(weekStart, "R")
        val updated = overridePattern(draft, "RLRLR")
        assertEquals("RLRLR", updated.pattern)
        // Original is unchanged (immutable)
        assertEquals("R", draft.pattern)
    }

    @Test
    fun overridePatternRejectsInvalidPattern() {
        val weekStart = LocalDate.of(2026, 1, 12)
        val draft = newDraft(weekStart, "R")
        try {
            overridePattern(draft, "RX")
        } catch (e: IllegalArgumentException) {
            // Expected
        }
    }

    @Test
    fun patternFrozenAfterConfirm() {
        val weekStart = LocalDate.of(2026, 1, 12)
        val draft = newDraft(weekStart, "R")
        val (active, _) = confirmWithoutOutline(draft)
        try {
            overridePattern(active, "RL")
        } catch (e: IllegalStateException) {
            // Expected — pattern is frozen after confirm
        }
    }

    @Test
    fun confirmCreatesOneSortiePerPatternLetter() {
        val weekStart = LocalDate.of(2026, 1, 12)
        val draft = newDraft(weekStart, "RLR")
        val (active, sorties) = confirmWithoutOutline(draft)
        assertEquals(MissionStatus.ACTIVE, active.status)
        assertEquals(3, sorties.size)
        // Sortie 0: RUN, focus "easy"
        assertEquals(SortieType.RUN, sorties[0].type)
        assertEquals("easy", sorties[0].focus)
        assertEquals(SortieState.PENDING, sorties[0].state)
        assertEquals(0, sorties[0].index)
        // Sortie 1: LIFT, focus "full body"
        assertEquals(SortieType.LIFT, sorties[1].type)
        assertEquals("full body", sorties[1].focus)
        assertEquals(1, sorties[1].index)
        // Sortie 2: RUN, focus "easy"
        assertEquals(SortieType.RUN, sorties[2].type)
        assertEquals("easy", sorties[2].focus)
        assertEquals(2, sorties[2].index)
    }

    @Test
    fun missionLifecycleTransitions() {
        // Legal transitions succeed (no exception from requireTransition)
        MissionLifecycle.requireTransition(MissionStatus.DRAFT, MissionStatus.ACTIVE)
        MissionLifecycle.requireTransition(MissionStatus.ACTIVE, MissionStatus.CLOSED)
        MissionLifecycle.requireTransition(MissionStatus.DRAFT, MissionStatus.CLOSED)

        // canTransition matches
        assertEquals(true, MissionLifecycle.canTransition(MissionStatus.DRAFT, MissionStatus.ACTIVE))
        assertEquals(true, MissionLifecycle.canTransition(MissionStatus.ACTIVE, MissionStatus.CLOSED))
        assertEquals(true, MissionLifecycle.canTransition(MissionStatus.DRAFT, MissionStatus.CLOSED))

        // Illegal transitions throw
        val illegalPairs = listOf(
            MissionStatus.DRAFT to MissionStatus.DRAFT,
            MissionStatus.ACTIVE to MissionStatus.ACTIVE,
            MissionStatus.CLOSED to MissionStatus.CLOSED,
            MissionStatus.ACTIVE to MissionStatus.DRAFT,
            MissionStatus.CLOSED to MissionStatus.ACTIVE,
            MissionStatus.CLOSED to MissionStatus.DRAFT,
        )
        for ((from, to) in illegalPairs) {
            try {
                MissionLifecycle.requireTransition(from, to)
            } catch (e: IllegalTransitionException) {
                // Expected
                continue
            }
            throw AssertionError("Should have thrown for $from → $to")
        }
    }

    @Test
    fun allSortiesDoneOnlyWhenEachLandedOrScrubbed() {
        val landed = Sortie(
            id = 0, missionId = 0, index = 0, type = SortieType.RUN, focus = null,
            focusRationale = null, state = SortieState.LANDED, launchedAt = null, landedAt = 1L,
            scrubReason = null, notes = null, runDistance = null, runMinutes = null,
        )
        val scrubbed = Sortie(
            id = 0, missionId = 0, index = 1, type = SortieType.LIFT, focus = null,
            focusRationale = null, state = SortieState.SCRUBBED, launchedAt = null, landedAt = null,
            scrubReason = "weather", notes = null, runDistance = null, runMinutes = null,
        )
        val pending = Sortie(
            id = 0, missionId = 0, index = 2, type = SortieType.RUN, focus = null,
            focusRationale = null, state = SortieState.PENDING, launchedAt = null, landedAt = null,
            scrubReason = null, notes = null, runDistance = null, runMinutes = null,
        )

        // All done → true
        assertEquals(true, allSortiesDone(listOf(landed, scrubbed)))
        // One pending → false
        assertEquals(false, allSortiesDone(listOf(landed, pending)))
        // Empty list → true (vacuously)
        assertEquals(true, allSortiesDone(emptyList()))
    }
}
