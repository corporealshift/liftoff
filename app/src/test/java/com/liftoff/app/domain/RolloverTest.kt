package com.liftoff.app.domain

import com.liftoff.app.data.Mission
import com.liftoff.app.data.MissionStatus
import com.liftoff.app.data.Sortie
import com.liftoff.app.data.SortieState
import com.liftoff.app.data.SortieType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class RolloverTest {

    @Test
    fun rolloverScrubsOpenSortiesWithWeekEnded() {
        val weekStart = LocalDate.of(2026, 1, 5) // Monday
        val today = LocalDate.of(2026, 1, 13) // Next Monday → week has ended

        val mission = Mission(
            id = 1, weekStart = weekStart, pattern = "RLR",
            status = MissionStatus.ACTIVE, outlineNotes = null
        )

        val sorties = listOf(
            Sortie(0, 1, 0, SortieType.RUN, "easy", null, SortieState.PENDING, null, null, null, null, null, null),
            Sortie(0, 1, 1, SortieType.LIFT, "full body", null, SortieState.PLANNED, null, null, null, null, null, null),
            Sortie(0, 1, 2, SortieType.RUN, "easy", null, SortieState.IN_FLIGHT, 100L, null, null, null, null, null),
            Sortie(0, 1, 3, SortieType.LIFT, "full body", null, SortieState.LANDED, 50L, 60L, null, null, null, null),
        )

        val result = rollover(mission, sorties, today)!!

        assertEquals(MissionStatus.CLOSED, result.mission.status)
        assertEquals(3, result.scrubbed.size)

        // The landed sortie is untouched (not in scrubbed list)
        assertEquals(SortieState.SCRUBBED, result.scrubbed[0].state)
        assertEquals(WEEK_ENDED, result.scrubbed[0].scrubReason)
        assertEquals(SortieState.SCRUBBED, result.scrubbed[1].state)
        assertEquals(WEEK_ENDED, result.scrubbed[1].scrubReason)
        assertEquals(SortieState.SCRUBBED, result.scrubbed[2].state)
        assertEquals(WEEK_ENDED, result.scrubbed[2].scrubReason)
    }

    @Test
    fun rolloverDoesNotCarryOver() {
        val weekStart = LocalDate.of(2026, 1, 5)
        val today = LocalDate.of(2026, 1, 13)

        val mission = Mission(
            id = 1, weekStart = weekStart, pattern = "RLR",
            status = MissionStatus.ACTIVE, outlineNotes = null
        )

        val sorties = listOf(
            Sortie(0, 1, 0, SortieType.RUN, "easy", null, SortieState.PENDING, null, null, null, null, null, null),
        )

        val result = rollover(mission, sorties, today)!!

        // Only the mission and scrubbed sorties are returned — no new mission created
        assertEquals(MissionStatus.CLOSED, result.mission.status)
        assertEquals(1, result.scrubbed.size)
        assertEquals(1, result.mission.id) // original id preserved
    }

    @Test
    fun currentWeekMissionIsNotRolledOver() {
        val weekStart = LocalDate.of(2026, 1, 12) // This Monday
        val today = LocalDate.of(2026, 1, 18) // Still same week (Sunday)

        val mission = Mission(
            id = 1, weekStart = weekStart, pattern = "R",
            status = MissionStatus.ACTIVE, outlineNotes = null
        )

        val sorties = listOf(
            Sortie(0, 1, 0, SortieType.RUN, "easy", null, SortieState.PENDING, null, null, null, null, null, null),
        )

        val result = rollover(mission, sorties, today)
        assertNull(result)
    }

    @Test
    fun closedMissionReturnsNull() {
        val weekStart = LocalDate.of(2026, 1, 5)
        val today = LocalDate.of(2026, 1, 13)

        val mission = Mission(
            id = 1, weekStart = weekStart, pattern = "R",
            status = MissionStatus.CLOSED, outlineNotes = null
        )

        val result = rollover(mission, emptyList(), today)
        assertNull(result)
    }

    @Test
    fun draftMissionClosesOnRollover() {
        val weekStart = LocalDate.of(2026, 1, 5)
        val today = LocalDate.of(2026, 1, 13)

        val mission = Mission(
            id = 1, weekStart = weekStart, pattern = "R",
            status = MissionStatus.DRAFT, outlineNotes = null
        )

        val result = rollover(mission, emptyList(), today)!!

        assertEquals(MissionStatus.CLOSED, result.mission.status)
        assertEquals(emptyList<Sortie>(), result.scrubbed)
    }
}
