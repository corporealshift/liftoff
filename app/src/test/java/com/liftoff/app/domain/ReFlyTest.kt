package com.liftoff.app.domain

import com.liftoff.app.data.Sortie
import com.liftoff.app.data.SortieState
import com.liftoff.app.data.SortieType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// The class name matches the file the run names; the chooser itself is chooseReflySource.
class ReFlyTest {

    // Same type and same focus beats a plan of the same type that landed more recently but carries a
    // different focus. Both are in the fixture, so a chooser that ignored focus would fail here.
    @Test
    fun reflyPrefersSameTypeAndFocusOverAMoreRecentDifferentFocusPlan() {
        val current = sortie(type = SortieType.RUN, focus = "5K")
        val matchingButOlder = candidate(id = 2, focus = " 5k ", landedAt = 100L)

        assertEquals(
            2L,
            sourceId(current, listOf(candidate(id = 3, focus = "recovery", landedAt = 900L), matchingButOlder)),
        )

        // Decision 2: a candidate with no focus is not a match, so it does not outrank the matching one.
        assertEquals(
            2L,
            sourceId(current, listOf(candidate(id = 4, focus = null, landedAt = 900L), matchingButOlder)),
        )
    }

    // With no focus match anywhere, the most recent plan of the same type is used, and a plan of the
    // other type is never used — however recent, and however well its focus reads.
    @Test
    fun reflyFallsBackToTheMostRecentSameTypeWhenNoFocusMatches() {
        // Decision 2: the current sortie has no focus at all, which matches nothing.
        val noFocus = sortie(type = SortieType.RUN, focus = null)
        assertEquals(
            11L,
            sourceId(
                noFocus,
                listOf(
                    candidate(id = 10, focus = "tempo", landedAt = 100L),
                    candidate(id = 11, focus = "easy", landedAt = 300L),
                    candidate(id = 12, type = SortieType.LIFT, focus = "tempo", landedAt = 900L),
                ),
            ),
        )

        // A candidate with a blank focus is no match either. The LIFT plan carries the current focus
        // word-for-word and landed last; the type filter still rules it out.
        val focused = sortie(type = SortieType.RUN, focus = "tempo")
        assertEquals(
            20L,
            sourceId(
                focused,
                listOf(
                    candidate(id = 20, focus = "   ", landedAt = 900L),
                    candidate(id = 21, focus = "easy", landedAt = 300L),
                    candidate(id = 22, type = SortieType.LIFT, focus = "tempo", landedAt = 1000L),
                ),
            ),
        )
    }

    // Among focus-matching candidates the one that landed last is chosen. The candidate carries no plan
    // row timestamp at all, so the sortie's landed time is the only ordering the chooser has.
    @Test
    fun reflyChoosesTheMostRecentlyLandedOfTheFocusMatches() {
        val current = sortie(type = SortieType.RUN, focus = "tempo")
        assertEquals(
            31L,
            sourceId(
                current,
                listOf(
                    candidate(id = 30, focus = "tempo", landedAt = 100L),
                    candidate(id = 31, focus = "Tempo", landedAt = 500L),
                    candidate(id = 32, focus = "tempo", landedAt = null),
                ),
            ),
        )

        // A LANDED candidate with no landed time sorts as oldest, even against the oldest real one.
        assertEquals(
            33L,
            sourceId(
                current,
                listOf(
                    candidate(id = 32, focus = "tempo", landedAt = null),
                    candidate(id = 33, focus = "tempo", landedAt = 1L),
                ),
            ),
        )
    }

    // A SCRUBBED sortie is never a source, even as the only finished sortie of that type with its plan
    // intact; a LANDED sortie with no plan row is never a source either.
    @Test
    fun reflyIgnoresScrubbedSortiesAndFinishedSortiesWithNoPlan() {
        val current = sortie(type = SortieType.RUN, focus = "tempo")

        assertNull(
            chooseReflySource(
                current,
                listOf(candidate(id = 40, focus = "tempo", state = SortieState.SCRUBBED, landedAt = null)),
            ),
        )

        // The two candidates that landed most recently cannot be copied, so the oldest one is used.
        assertEquals(
            43L,
            sourceId(
                current,
                listOf(
                    candidate(id = 41, focus = "tempo", landedAt = 900L, hasPlan = false),
                    candidate(id = 42, focus = "tempo", state = SortieState.SCRUBBED, landedAt = 800L),
                    candidate(id = 43, focus = "easy", landedAt = 100L),
                ),
            ),
        )
    }

    // Nothing of that type has ever landed with a plan: the chooser says nothing, rather than falling
    // back to a plan of the other type or returning a candidate it cannot copy.
    @Test
    fun reflyReturnsNothingWhenNoLandedPlanOfThatTypeExists() {
        val current = sortie(type = SortieType.LIFT, focus = "push")

        val candidates = listOf(
            candidate(id = 50, type = SortieType.RUN, focus = "tempo", landedAt = 900L),
            candidate(id = 51, type = SortieType.LIFT, focus = "push", landedAt = 800L, hasPlan = false),
            candidate(id = 52, type = SortieType.LIFT, focus = "push", state = SortieState.SCRUBBED, landedAt = 700L),
            candidate(id = 53, type = SortieType.LIFT, focus = "push", state = SortieState.PLANNED, landedAt = null),
        )

        assertNull(chooseReflySource(current, candidates))
        assertNull(chooseReflySource(current, emptyList()))
    }

    /** The id of the sortie the chooser picked, or -1 when it picked nothing. */
    private fun sourceId(current: Sortie, candidates: List<ReflyCandidate>): Long =
        chooseReflySource(current, candidates)?.sortie?.id ?: -1L

    private fun sortie(type: SortieType, focus: String?): Sortie =
        Sortie(
            id = 0, missionId = 1, index = 0, type = type, focus = focus,
            focusRationale = null, state = SortieState.PENDING, launchedAt = null, landedAt = null,
            scrubReason = null, notes = null, runDistance = null, runMinutes = null,
        )

    private fun candidate(
        id: Long,
        type: SortieType = SortieType.RUN,
        focus: String? = null,
        state: SortieState = SortieState.LANDED,
        landedAt: Long? = 1000L,
        hasPlan: Boolean = true,
    ): ReflyCandidate =
        ReflyCandidate(
            sortie = Sortie(
                id = id, missionId = 1, index = id.toInt(), type = type, focus = focus,
                focusRationale = null, state = state, launchedAt = null, landedAt = landedAt,
                scrubReason = null, notes = null, runDistance = null, runMinutes = null,
            ),
            hasPlan = hasPlan,
        )
}
