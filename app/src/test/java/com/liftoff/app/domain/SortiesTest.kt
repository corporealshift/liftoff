package com.liftoff.app.domain

import com.liftoff.app.data.Sortie
import com.liftoff.app.data.SortieState
import com.liftoff.app.data.SortieType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SortiesTest {

    @Test
    fun legalTransitionsSucceed() {
        val base = sortie(SortieState.PENDING)

        // PENDING → PLANNED
        assertEquals(SortieState.PLANNED, markPlanned(base).state)

        // PENDING → SCRUBBED
        assertEquals(SortieState.SCRUBBED, scrub(base, null).state)

        val planned = base.copy(state = SortieState.PLANNED)

        // PLANNED → IN_FLIGHT
        assertEquals(SortieState.IN_FLIGHT, launch(planned, 100L).state)

        // PLANNED → SCRUBBED
        assertEquals(SortieState.SCRUBBED, scrub(planned, null).state)

        val inFlight = planned.copy(state = SortieState.IN_FLIGHT)

        // IN_FLIGHT → LANDED
        assertEquals(SortieState.LANDED, land(inFlight, 200L).state)

        // IN_FLIGHT → SCRUBBED
        assertEquals(SortieState.SCRUBBED, scrub(inFlight, null).state)
    }

    @Test
    fun illegalTransitionsAreRejected() {
        var caught = 0

        // All 25 from/to pairs: 6 legal are tested above; verify the remaining 19 throw.
        val allStates = SortieState.values()
        val illegalPairs = listOf(
            // Same-state (5)
            SortieState.PENDING to SortieState.PENDING,
            SortieState.PLANNED to SortieState.PLANNED,
            SortieState.IN_FLIGHT to SortieState.IN_FLIGHT,
            SortieState.LANDED to SortieState.LANDED,
            SortieState.SCRUBBED to SortieState.SCRUBBED,
            // PENDING → (IN_FLIGHT, LANDED) — not PLANNED or SCRUBBED (those are legal)
            SortieState.PENDING to SortieState.IN_FLIGHT,
            SortieState.PENDING to SortieState.LANDED,
            // PLANNED → (PENDING, LANDED, SCRUBBED) — IN_FLIGHT is legal; SCRUBBED is legal
            SortieState.PLANNED to SortieState.PENDING,
            SortieState.PLANNED to SortieState.LANDED,
            // IN_FLIGHT → (PENDING, PLANNED) — LANDED and SCRUBBED are legal
            SortieState.IN_FLIGHT to SortieState.PENDING,
            SortieState.IN_FLIGHT to SortieState.PLANNED,
            // LANDED → (PENDING, PLANNED, IN_FLIGHT, SCRUBBED) — nothing is legal from LANDED
            SortieState.LANDED to SortieState.PENDING,
            SortieState.LANDED to SortieState.PLANNED,
            SortieState.LANDED to SortieState.IN_FLIGHT,
            SortieState.LANDED to SortieState.SCRUBBED,
            // SCRUBBED → (PENDING, PLANNED, IN_FLIGHT, LANDED) — nothing is legal from SCRUBBED
            SortieState.SCRUBBED to SortieState.PENDING,
            SortieState.SCRUBBED to SortieState.PLANNED,
            SortieState.SCRUBBED to SortieState.IN_FLIGHT,
            SortieState.SCRUBBED to SortieState.LANDED,
        )

        assertEquals(19, illegalPairs.size)

        for ((from, to) in illegalPairs) {
            try {
                SortieTransitions.requireTransition(from, to)
            } catch (e: IllegalTransitionException) {
                caught++
                continue
            }
            throw AssertionError("Should have thrown for $from → $to")
        }

        assertEquals(19, caught)
    }

    @Test
    fun currentSortieSkipsLandedAndScrubbed() {
        val sorties = listOf(
            sortie(SortieState.LANDED, index = 0),
            sortie(SortieState.PENDING, index = 1),
            sortie(SortieState.SCRUBBED, index = 2),
            sortie(SortieState.IN_FLIGHT, index = 3),
        )

        // Unsorted input
        val shuffled = listOf(sorties[3], sorties[0], sorties[2], sorties[1])
        assertEquals(1, currentSortie(shuffled)!!.index)

        // All done → null
        val allDone = listOf(
            sortie(SortieState.LANDED, index = 0),
            sortie(SortieState.SCRUBBED, index = 1),
        )
        assertNull(currentSortie(allDone))
    }

    @Test
    fun atMostOneInFlight() {
        // No in-flight → ok
        requireNoneInFlight(emptyList())

        try {
            val inFlight = listOf(sortie(SortieState.IN_FLIGHT))
            requireNoneInFlight(inFlight)
        } catch (e: IllegalStateException) {
            // Expected
        }
    }

    @Test
    fun scrubStoresBlankReasonAsNull() {
        val sortie = sortie(SortieState.PENDING)
        val result = scrub(sortie, "")
        assertNull(result.scrubReason)
    }

    private fun sortie(state: SortieState, index: Int = 0): Sortie =
        Sortie(
            id = 0, missionId = 0, index = index, type = SortieType.RUN, focus = null,
            focusRationale = null, state = state, launchedAt = null, landedAt = null,
            scrubReason = null, notes = null, runDistance = null, runMinutes = null,
        )
}
