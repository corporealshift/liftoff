package com.liftoff.app.domain

import com.liftoff.app.data.Mission
import com.liftoff.app.data.MissionStatus
import com.liftoff.app.data.Sortie
import com.liftoff.app.data.SortieState
import com.liftoff.app.data.SortieType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate

/**
 * The domain half of the mid-week type swap (DESIGN.md §4.1, §5.1, §9, amended 2026-11-23):
 * one sortie's type, the letter at its own index, and the state move the swap needs.
 */
class SortieSwapTest {

    @Test
    fun swapSortieTypeFlipsTheTypeAndThePatternLetterOfTheSameIndex() {
        // R L R L R, and the current sortie is index 1: the lift day becomes a run day.
        val week = activeMission("RLRLR")
        val lift = sortieAt(week.pattern, 1, SortieState.PENDING)

        val (swappedWeek, swapped) = swapSortieType(week, lift)

        assertEquals(SortieType.RUN, swapped.type)
        assertEquals("RRRLR", swappedWeek.pattern)
        assertEquals(week.pattern.length, swappedWeek.pattern.length)
        // Every letter except the one at sortie.index is the letter it was.
        for (index in week.pattern.indices) {
            val expected = if (index == lift.index) 'R' else week.pattern[index]
            assertEquals("letter at index $index", expected, swappedWeek.pattern[index])
        }
        // The same Mission row, still ACTIVE; only its pattern moved.
        assertEquals(week.id, swappedWeek.id)
        assertEquals(week.weekStart, swappedWeek.weekStart)
        assertEquals(MissionStatus.ACTIVE, swappedWeek.status)
        // PENDING in, PENDING out.
        assertEquals(SortieState.PENDING, swapped.state)

        // The other direction, at a run index: R L R L R index 2 → R L L L R.
        val run = sortieAt(week.pattern, 2, SortieState.PENDING)
        val (liftedWeek, lifted) = swapSortieType(week, run)
        assertEquals(SortieType.LIFT, lifted.type)
        assertEquals("RLLLR", liftedWeek.pattern)

        // A PLANNED sortie comes out PENDING too — that is the edge the swap added.
        val plannedWeek = activeMission("LLRR")
        val planned = sortieAt(plannedWeek.pattern, 0, SortieState.PLANNED)
        val (plannedSwapped, fromPlanned) = swapSortieType(plannedWeek, planned)
        assertEquals(SortieState.PENDING, fromPlanned.state)
        assertEquals("RLRR", plannedSwapped.pattern)

        // Nothing in the input moved.
        assertEquals("RLRLR", week.pattern)
        assertEquals(SortieType.LIFT, lift.type)
        assertEquals(SortieState.PENDING, lift.state)
    }

    @Test
    fun swapSortieTypeSetsTheNewTypesDefaultFocusAndClearsTheOldRationale() {
        // A lift day whose focus and rationale were edited by hand: as a run day it gets the run default.
        val week = activeMission("RL")
        val lift = sortieAt(
            week.pattern, 1, SortieState.PENDING,
            focus = "chest and legs", rationale = "shoulders were sore",
        )

        val (_, toRun) = swapSortieType(week, lift)
        assertEquals(SortieType.RUN, toRun.type)
        assertEquals("easy", toRun.focus)
        assertNull(toRun.focusRationale)

        // And back the other way: a hand-picked run focus is replaced by the lift default.
        val run = sortieAt(
            week.pattern, 0, SortieState.PLANNED,
            focus = "tempo, 20 minutes", rationale = "legs felt springy",
        )
        val (_, toLift) = swapSortieType(week, run)
        assertEquals(SortieType.LIFT, toLift.type)
        assertEquals("full body", toLift.focus)
        assertNull(toLift.focusRationale)

        // The same two defaults "Continue without outline" writes (§4.2).
        val (_, confirmed) = confirmWithoutOutline(newDraft(LocalDate.of(2026, 11, 23), "RL"))
        assertEquals(confirmed[0].focus, toRun.focus)
        assertEquals(confirmed[1].focus, toLift.focus)
    }

    @Test
    fun plannedToPendingIsALegalTransitionAndEveryOtherIllegalTransitionIsStillRejected() {
        assertTrue(SortieTransitions.canTransition(SortieState.PLANNED, SortieState.PENDING))

        // The swap is what needs the edge, and it is the swap that uses it.
        val week = activeMission("LRR")
        val (swappedWeek, swapped) = swapSortieType(week, sortieAt(week.pattern, 0, SortieState.PLANNED))
        assertEquals(SortieState.PENDING, swapped.state)
        assertEquals("RRR", swappedWeek.pattern)

        // The table now holds 8 legal pairs: the 7 that were always legal plus this one.
        val legal = mutableSetOf<Pair<SortieState, SortieState>>()
        var rejected = 0
        for (from in SortieState.values()) {
            for (to in SortieState.values()) {
                if (throwsFor(from, to)) rejected++ else legal += (from to to)
            }
        }
        assertEquals(17, rejected)
        assertEquals(
            setOf(
                SortieState.PENDING to SortieState.PLANNED,
                SortieState.PENDING to SortieState.SCRUBBED,
                SortieState.PLANNED to SortieState.PENDING,
                SortieState.PLANNED to SortieState.IN_FLIGHT,
                SortieState.PLANNED to SortieState.SCRUBBED,
                SortieState.IN_FLIGHT to SortieState.LANDED,
                SortieState.IN_FLIGHT to SortieState.SCRUBBED,
                SortieState.IN_FLIGHT to SortieState.PLANNED,
            ),
            legal,
        )

        // A swap is offered on PENDING and PLANNED only; every other state is still refused.
        val swapWeek = activeMission("RL")
        for (state in listOf(SortieState.IN_FLIGHT, SortieState.LANDED, SortieState.SCRUBBED)) {
            try {
                swapSortieType(swapWeek, sortieAt(swapWeek.pattern, 0, state))
                fail("Expected a refusal for a swap of a $state sortie")
            } catch (e: IllegalTransitionException) {
                // Expected
            }
        }
    }

    private fun throwsFor(from: SortieState, to: SortieState): Boolean =
        try {
            SortieTransitions.requireTransition(from, to)
            false
        } catch (e: IllegalTransitionException) {
            true
        }

    private fun activeMission(pattern: String): Mission =
        Mission(
            id = 1,
            weekStart = LocalDate.of(2026, 11, 23),
            pattern = pattern,
            status = MissionStatus.ACTIVE,
            outlineNotes = null,
        )

    // The sortie's type is the letter at its index, the way a confirmed pattern builds it.
    private fun sortieAt(
        pattern: String,
        index: Int,
        state: SortieState,
        focus: String? = null,
        rationale: String? = null,
    ): Sortie =
        Sortie(
            id = 100L + index,
            missionId = 1,
            index = index,
            type = if (pattern[index] == 'R') SortieType.RUN else SortieType.LIFT,
            focus = focus,
            focusRationale = rationale,
            state = state,
            launchedAt = null,
            landedAt = null,
            scrubReason = null,
            notes = null,
            runDistance = null,
            runMinutes = null,
        )
}
