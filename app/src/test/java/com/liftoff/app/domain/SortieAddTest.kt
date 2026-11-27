package com.liftoff.app.domain

import com.liftoff.app.data.Mission
import com.liftoff.app.data.MissionStatus
import com.liftoff.app.data.Sortie
import com.liftoff.app.data.SortieState
import com.liftoff.app.data.SortieType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate

/**
 * The domain half of adding a sortie the week didn't plan (DESIGN.md §4.1, §4.2, §9, amended 2026-11-26):
 * one letter on the end of the pattern, one sortie at the matching index, and the CLOSED → ACTIVE edge
 * a finished week needs to take it.
 */
class SortieAddTest {

    @Test
    fun appendsLetterAndSortieAtEnd() {
        // R L R, and the owner got a lift in the coach never planned: the letter goes on the end.
        val week = mission("RLR", MissionStatus.ACTIVE)

        val (updated, added) = addSortie(week, SortieType.LIFT)

        assertEquals("RLRL", updated.pattern)
        assertEquals(week.id, updated.id)
        assertEquals(week.weekStart, updated.weekStart)
        assertEquals(week.outlineNotes, updated.outlineNotes)
        assertEquals(MissionStatus.ACTIVE, updated.status)

        // The new sortie sits at the index the new letter is at, so pattern and sorties stay the same length.
        assertEquals(week.pattern.length, added.index)
        assertEquals(updated.pattern.length - 1, added.index)
        assertEquals(SortieType.LIFT, added.type)
        assertEquals(SortieState.PENDING, added.state)
        assertEquals(week.id, added.missionId)
        assertNull(added.launchedAt)
        assertNull(added.landedAt)
        assertNull(added.notes)
        assertNull(added.runDistance)
        assertNull(added.runMinutes)

        // A run added to the same week is the letter its type says it is.
        val (runWeek, addedRun) = addSortie(week, SortieType.RUN)
        assertEquals("RLRR", runWeek.pattern)
        assertEquals(SortieType.RUN, addedRun.type)

        // Nothing in the input moved.
        assertEquals("RLR", week.pattern)
        assertEquals(MissionStatus.ACTIVE, week.status)
    }

    @Test
    fun reopensClosedMission() {
        // The case the brief is about: every planned day is done, and a workout got in anyway.
        val week = mission("RLL", MissionStatus.CLOSED)

        val (updated, added) = addSortie(week, SortieType.RUN)

        assertEquals("RLLR", updated.pattern)
        assertEquals(MissionStatus.ACTIVE, updated.status)
        assertEquals(SortieState.PENDING, added.state)
        assertEquals(3, added.index)

        // The edge this needs is the lifecycle table's, not a copy of it.
        assertEquals(true, MissionLifecycle.canTransition(MissionStatus.CLOSED, MissionStatus.ACTIVE))
        assertEquals(MissionStatus.CLOSED, week.status)
    }

    @Test
    fun newSortieTakesTheTypesDefaultFocus() {
        val week = mission("L", MissionStatus.ACTIVE)

        val (_, run) = addSortie(week, SortieType.RUN)
        assertEquals("easy", run.focus)
        assertNull(run.focusRationale)

        val (_, lift) = addSortie(week, SortieType.LIFT)
        assertEquals("full body", lift.focus)
        assertNull(lift.focusRationale)

        // The same two defaults "Continue without outline" writes (§4.2), so an added sortie reads like a confirmed one.
        val (_, confirmed) = confirmWithoutOutline(newDraft(LocalDate.of(2026, 11, 23), "RL"))
        assertEquals(confirmed[0].focus, run.focus)
        assertEquals(confirmed[1].focus, lift.focus)
    }

    @Test
    fun refusesDraftMission() {
        val draft = newDraft(LocalDate.of(2026, 11, 23), "RLR")

        try {
            addSortie(draft, SortieType.LIFT)
            fail("Expected a refusal to add a sortie to a draft week")
        } catch (e: IllegalArgumentException) {
            assertEquals("Can only add a sortie to a confirmed week", e.message)
        }
    }

    @Test
    fun refusesEighthSortie() {
        // A pattern is one to seven letters (Pattern.kt), so a seven-sortie week has no room left.
        val full = mission("RLRLRLR", MissionStatus.ACTIVE)
        val closedFull = mission("RRRRLLL", MissionStatus.CLOSED)

        for (week in listOf(full, closedFull)) {
            try {
                addSortie(week, SortieType.LIFT)
                fail("Expected a refusal to add an eighth sortie to '${week.pattern}'")
            } catch (e: IllegalArgumentException) {
                assertEquals("Pattern '${week.pattern}' already holds 7 sorties", e.message)
            }
        }
        assertEquals("RLRLRLR", full.pattern)
        assertEquals(MissionStatus.CLOSED, closedFull.status)
    }

    @Test
    fun leavesExistingSortiesAlone() {
        // Two days landed, one scrubbed, one still to come — the added sortie goes after all of them.
        val week = mission("RLLR", MissionStatus.ACTIVE)
        val existing = week.pattern.mapIndexed { index, ch ->
            sortieAt(week.pattern, index, if (index < 3) SortieState.LANDED else SortieState.PENDING)
        }

        val (updated, added) = addSortie(week, SortieType.LIFT)
        val all = existing + added

        // Every earlier letter keeps its type, so every earlier sortie still matches its own row.
        val before = sortieTypesOf(week.pattern)
        val after = sortieTypesOf(updated.pattern)
        assertEquals(before, after.take(before.size))
        assertEquals(SortieType.LIFT, after.last())
        assertEquals(updated.pattern.length, all.size)
        assertEquals(listOf(0, 1, 2, 3, 4), all.map { it.index })

        // The current sortie is the one that was current before the add, not the new one.
        assertEquals(existing[3].id, currentSortie(all)!!.id)
    }

    private fun mission(pattern: String, status: MissionStatus): Mission =
        Mission(
            id = 1,
            weekStart = LocalDate.of(2026, 11, 23),
            pattern = pattern,
            status = status,
            outlineNotes = "legs on Tuesday",
        )

    // The sortie's type is the letter at its index, the way a confirmed pattern builds it.
    private fun sortieAt(pattern: String, index: Int, state: SortieState): Sortie =
        Sortie(
            id = 100L + index,
            missionId = 1,
            index = index,
            type = if (pattern[index] == 'R') SortieType.RUN else SortieType.LIFT,
            focus = null,
            focusRationale = null,
            state = state,
            launchedAt = null,
            landedAt = if (state == SortieState.LANDED) 1L else null,
            scrubReason = null,
            notes = null,
            runDistance = null,
            runMinutes = null,
        )
}
