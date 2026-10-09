package com.liftoff.app.domain

import com.liftoff.app.data.FlightPlanSource
import com.liftoff.app.data.Sortie
import com.liftoff.app.data.SortieType
import org.junit.Assert.assertEquals
import org.junit.Test

class SortiePlanningTest {

    @Test
    fun runWithGenerationOffGetsSimplePlan() {
        val sortie = Sortie(
            id = 0, missionId = 0, index = 0, type = SortieType.RUN, focus = "easy",
            focusRationale = null, state = com.liftoff.app.data.SortieState.PENDING,
            launchedAt = null, landedAt = null, scrubReason = null, notes = null,
            runDistance = null, runMinutes = null,
        )

        val result = prepareCurrent(sortie, generateRunPlans = false)

        assertEquals(Preparation.SimplePlan::class, result::class)
        val plan = (result as Preparation.SimplePlan).draft
        assertEquals(FlightPlanSource.SIMPLE_RUN, plan.source)
        assertEquals("Run", plan.title)
        assertEquals("{}", plan.rawJson)
        assertEquals("easy", plan.notes)
    }

    @Test
    fun liftAndGeneratedRunAwaitGeneration() {
        val sortie = Sortie(
            id = 0, missionId = 0, index = 0, type = SortieType.LIFT, focus = "full body",
            focusRationale = null, state = com.liftoff.app.data.SortieState.PENDING,
            launchedAt = null, landedAt = null, scrubReason = null, notes = null,
            runDistance = null, runMinutes = null,
        )

        // Lift → AwaitGeneration regardless of generation flag
        assertEquals(
            Preparation.AwaitGeneration::class,
            prepareCurrent(sortie, generateRunPlans = false)::class
        )
        assertEquals(
            Preparation.AwaitGeneration::class,
            prepareCurrent(sortie, generateRunPlans = true)::class
        )

        // Run with generation on → AwaitGeneration
        val runSortie = sortie.copy(type = SortieType.RUN, focus = "easy")
        assertEquals(
            Preparation.AwaitGeneration::class,
            prepareCurrent(runSortie, generateRunPlans = true)::class
        )
    }
}
