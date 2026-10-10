package com.liftoff.app.data

import androidx.room.withTransaction
import com.liftoff.app.domain.MissionLifecycle
import com.liftoff.app.domain.allSortiesDone
import com.liftoff.app.domain.confirmWithoutOutline
import com.liftoff.app.domain.currentSortie
import com.liftoff.app.domain.launch
import com.liftoff.app.domain.land
import com.liftoff.app.domain.markPlanned
import com.liftoff.app.domain.newDraft
import com.liftoff.app.domain.overridePattern
import com.liftoff.app.domain.prepareCurrent
import com.liftoff.app.domain.requireNoneInFlight
import com.liftoff.app.domain.scrub
import com.liftoff.app.domain.rollover
import com.liftoff.app.domain.weekHasEnded
import com.liftoff.app.domain.weekStartOf
import com.liftoff.app.settings.Settings
import com.liftoff.app.settings.SettingsStore
import kotlinx.coroutines.flow.first
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

class MissionManager(
    private val db: LiftoffDatabase,
    private val settingsStore: SettingsStore,
    private val clock: Clock = Clock.systemUTC(),
    private val zone: () -> ZoneId = { ZoneId.systemDefault() }
) {

    suspend fun onAppOpen(): Mission {
        val settings = settingsStore.settings.first()
        return db.withTransaction {
            val weekStart = currentWeekStart()
            for (pastMission in db.missionDao().getUnclosedBefore(weekStart)) {
                val sorties = db.sortieDao().getForMission(pastMission.id)
                val result = rollover(pastMission, sorties, weekStart)
                if (result != null) {
                    db.missionDao().update(result.mission)
                    for (s in result.scrubbed) {
                        db.sortieDao().update(s)
                    }
                }
            }
            var mission = db.missionDao().getByWeekStart(weekStart)
            if (mission == null) {
                val draft = newDraft(weekStart, settings.defaultPattern)
                mission = draft.copy(id = db.missionDao().insert(draft))
            }
            mission
        }
    }

    suspend fun setPattern(missionId: Long, pattern: String) {
        db.withTransaction {
            val mission = db.missionDao().get(missionId)!!
            val updated = overridePattern(mission, pattern)
            db.missionDao().update(updated)
        }
    }

    suspend fun confirm(missionId: Long) {
        val settings = settingsStore.settings.first()
        db.withTransaction {
            val weekStart = currentWeekStart()
            val mission = db.missionDao().get(missionId)!!
            check(mission.status == MissionStatus.DRAFT && mission.weekStart == weekStart) {
                "Can only confirm the current week's draft"
            }
            val (activeMission, rawSorties) = confirmWithoutOutline(mission)
            val sorties = rawSorties.map { it.copy(missionId = missionId) }
            db.missionDao().update(activeMission)
            db.sortieDao().insertAll(sorties)
            advance(missionId, settings)
        }
    }

    suspend fun scrub(sortieId: Long, reason: String? = null) {
        val settings = settingsStore.settings.first()
        db.withTransaction {
            val sortie = db.sortieDao().get(sortieId)!!
            val mission = db.missionDao().get(sortie.missionId)!!
            check(mission.status == MissionStatus.ACTIVE) { "Mission must be ACTIVE" }
            val sorties = db.sortieDao().getForMission(mission.id)
            val current = currentSortie(sorties)
            check(current != null && current.id == sortieId) { "Sortie must be current" }
            val scrubbed = scrub(sortie, reason)
            db.sortieDao().update(scrubbed)
            advance(mission.id, settings)
        }
    }

    suspend fun launch(sortieId: Long) {
        db.withTransaction {
            val sortie = db.sortieDao().get(sortieId)!!
            val mission = db.missionDao().get(sortie.missionId)!!
            check(mission.status == MissionStatus.ACTIVE) { "Mission must be ACTIVE" }
            val sorties = db.sortieDao().getForMission(mission.id)
            val current = currentSortie(sorties)
            check(current != null && current.id == sortieId) { "Sortie must be current" }
            requireNoneInFlight(db.sortieDao().getInFlight())
            val launched = launch(sortie, clock.millis())
            db.sortieDao().update(launched)
        }
    }

    suspend fun land(sortieId: Long) {
        val settings = settingsStore.settings.first()
        db.withTransaction {
            val sortie = db.sortieDao().get(sortieId)!!
            check(sortie.state == SortieState.IN_FLIGHT) { "Sortie must be IN_FLIGHT" }
            val landed = land(sortie, clock.millis())
            db.sortieDao().update(landed)
            db.flightPlanDao().markOpenSetsNotDone(sortieId)
            advance(sortie.missionId, settings)
        }
    }

    private suspend fun advance(missionId: Long, settings: Settings) {
        val mission = db.missionDao().get(missionId)!!
        check(mission.status == MissionStatus.ACTIVE) { "Mission must be ACTIVE" }
        val sorties = db.sortieDao().getForMission(missionId)

        if (allSortiesDone(sorties)) {
            MissionLifecycle.requireTransition(mission.status, MissionStatus.CLOSED)
            db.missionDao().update(mission.copy(status = MissionStatus.CLOSED))
            return
        }

        val current = currentSortie(sorties) ?: return

        if (current.state == SortieState.PENDING) {
            val prep = prepareCurrent(current, settings.generateRunPlans)
            when (prep) {
                is com.liftoff.app.domain.Preparation.SimplePlan -> {
                    db.flightPlanDao().writePlan(current.id, prep.draft)
                    db.sortieDao().update(markPlanned(current))
                }
                is com.liftoff.app.domain.Preparation.AwaitGeneration -> {}
            }
        }
    }

    private fun currentWeekStart(): LocalDate =
        weekStartOf(clock.instant().atZone(zone()).toLocalDate())
}
