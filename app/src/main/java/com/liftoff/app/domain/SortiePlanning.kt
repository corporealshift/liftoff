package com.liftoff.app.domain

import com.liftoff.app.data.FlightPlanDraft
import com.liftoff.app.data.FlightPlanSource
import com.liftoff.app.data.Sortie
import com.liftoff.app.data.SortieType

sealed interface Preparation {
    data class SimplePlan(val draft: FlightPlanDraft) : Preparation
    data object AwaitGeneration : Preparation
}

fun prepareCurrent(sortie: Sortie, generateRunPlans: Boolean): Preparation =
    if (sortie.type == SortieType.RUN && !generateRunPlans) {
        Preparation.SimplePlan(simpleRunPlan(sortie))
    } else {
        // Generation will be queued here when the coach is available.
        Preparation.AwaitGeneration
    }

fun simpleRunPlan(sortie: Sortie): FlightPlanDraft =
    FlightPlanDraft(
        source = FlightPlanSource.SIMPLE_RUN,
        title = "Run",
        estimatedMinutes = null,
        warmup = null,
        notes = sortie.focus,
        runKind = null,
        targetDistance = null,
        targetPace = null,
        rawJson = "{}",
        exercises = emptyList(),
        segments = emptyList()
    )
