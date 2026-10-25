package com.liftoff.app.data

import androidx.room.withTransaction
import com.liftoff.app.coach.CoachLiftPlan
import com.liftoff.app.coach.CoachOutline
import com.liftoff.app.coach.CoachReplyResult
import com.liftoff.app.coach.CoachRunPlan
import com.liftoff.app.domain.MissionLifecycle
import com.liftoff.app.domain.allSortiesDone
import com.liftoff.app.domain.chooseReflySource
import com.liftoff.app.domain.coachIsConfigured
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

/**
 * What a store operation did with the coach result it was handed. A result that arrived after the week moved
 * on is an ordinary outcome of a background job, so it comes back as a value the worker branches on rather
 * than an exception.
 */
sealed interface StoreOutcome {
    data object Stored : StoreOutcome
    data class Refused(val reason: String) : StoreOutcome
}

/**
 * What Confirm did with the week. Asking for an outline is not starting the week, so the outcome names the ask:
 * [Queued] carries the row to schedule, and [CoachNotConfigured] is the caller being told Mission Control isn't
 * set up.
 */
sealed interface ConfirmOutcome {
    data class Queued(val generationId: Long) : ConfirmOutcome
    data object CoachNotConfigured : ConfirmOutcome
}

class MissionManager(
    private val db: LiftoffDatabase,
    private val settingsStore: SettingsStore,
    private val clock: Clock = Clock.systemUTC(),
    private val zone: () -> ZoneId = { ZoneId.systemDefault() }
) {

    val currentWeekStart = MutableStateFlow<LocalDate?>(null)

    /** Whether Mission Control has a host and token, for a draft to derive its coach status from. */
    val coachConfigured: Flow<Boolean> = settingsStore.settings
        .map { coachIsConfigured(it.daemonHost, it.daemonToken) }
        .distinctUntilChanged()

    suspend fun onAppOpen(): Mission {
        val settings = settingsStore.settings.first()
        val weekStart = currentWeekOf(clock, zone)
        val mission = db.withTransaction {
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
        currentWeekStart.value = weekStart
        return mission
    }

    /**
     * Edit the week's pattern. An outline generation the coach is still working on freezes it: the outline is
     * being written against the pattern the draft holds right now.
     */
    suspend fun setPattern(missionId: Long, pattern: String) {
        db.withTransaction {
            val mission = db.missionDao().get(missionId)!!
            check(db.generationDao().getOpenOutlineForMission(missionId).isEmpty()) {
                "Pattern can't be changed while Mission Control is planning this week's outline"
            }
            val updated = overridePattern(mission, pattern)
            db.missionDao().update(updated)
        }
    }

    /**
     * Ask Mission Control for the week's outline (DESIGN.md §4.2): insert one QUEUED OUTLINE [Generation] row for
     * the current draft and return its id, which is what the caller hands to the enqueuer once the transaction
     * has committed. Nothing is activated and no sortie is inserted — the outline the worker stores back is what
     * turns the draft into a week. The open row is the freeze on the pattern; a second Confirm on the same draft
     * reuses its id, because the same draft asked for twice is the same request.
     *
     * With no host or token set nothing is queued and the caller is told, so the owner chooses 'continue without
     * outline' rather than having a week started without an outline.
     */
    suspend fun confirm(missionId: Long): ConfirmOutcome {
        val settings = settingsStore.settings.first()
        if (!coachIsConfigured(settings.daemonHost, settings.daemonToken)) {
            return ConfirmOutcome.CoachNotConfigured
        }
        return db.withTransaction {
            val weekStart = currentWeekOf(clock, zone)
            val mission = db.missionDao().get(missionId)!!
            check(mission.status == MissionStatus.DRAFT && mission.weekStart == weekStart) {
                "Can only confirm the current week's draft"
            }
            val open = db.generationDao().getOpenOutlineForMission(missionId)
            if (open.isNotEmpty()) return@withTransaction ConfirmOutcome.Queued(open.first().id)
            val generationId = db.generationDao().insert(
                Generation(
                    kind = GenerationKind.OUTLINE,
                    missionId = missionId,
                    status = GenerationStatus.QUEUED,
                    createdAt = clock.millis(),
                )
            )
            ConfirmOutcome.Queued(generationId)
        }
    }

    /**
     * Ask Mission Control for the week's outline again after the newest one FAILED (DESIGN.md §4.2: the Mission
     * stays a draft with an error and a retry). Same configured check and same QUEUED OUTLINE row as [confirm],
     * and the same refusal shape: anything else — a week that isn't the current draft, or an outline that was
     * never asked for, is still open, or already succeeded — is an [IllegalStateException]. The new row is now
     * the mission's newest non-cancelled outline, so a second Retry refuses until this one finishes.
     */
    suspend fun retryOutline(missionId: Long): ConfirmOutcome {
        val settings = settingsStore.settings.first()
        if (!coachIsConfigured(settings.daemonHost, settings.daemonToken)) {
            return ConfirmOutcome.CoachNotConfigured
        }
        return db.withTransaction {
            val weekStart = currentWeekOf(clock, zone)
            val mission = db.missionDao().get(missionId)!!
            check(mission.status == MissionStatus.DRAFT && mission.weekStart == weekStart) {
                "Can only retry the outline for the current week's draft"
            }
            val newest = db.generationDao().getNewestNonCancelledOutlineForMission(missionId)
            check(newest != null && newest.status == GenerationStatus.FAILED) {
                "The outline can only be retried after the newest one has failed"
            }
            val generationId = db.generationDao().insert(
                Generation(
                    kind = GenerationKind.OUTLINE,
                    missionId = missionId,
                    status = GenerationStatus.QUEUED,
                    createdAt = clock.millis(),
                )
            )
            ConfirmOutcome.Queued(generationId)
        }
    }

    /**
     * Activate the current week's draft with no outline — the escape hatch DESIGN.md §4.2 draws out of DRAFT, and
     * what [confirm] did until the coach was asked for an outline. Returns the ids of the mission's outline
     * generations that were still open, read in this transaction, for the caller to cancel.
     */
    suspend fun continueWithoutOutline(missionId: Long): List<Long> {
        val settings = settingsStore.settings.first()
        return db.withTransaction {
            val weekStart = currentWeekOf(clock, zone)
            val mission = db.missionDao().get(missionId)!!
            check(mission.status == MissionStatus.DRAFT && mission.weekStart == weekStart) {
                "Can only continue with the current week's draft"
            }
            val openOutlineIds = db.generationDao().getOpenOutlineForMission(missionId).map { it.id }
            val (activeMission, rawSorties) = confirmWithoutOutline(mission)
            val sorties = rawSorties.map { it.copy(missionId = missionId) }
            db.missionDao().update(activeMission)
            db.sortieDao().insertAll(sorties)
            advance(missionId, settings)
            openOutlineIds
        }
    }

    /**
     * Store a validated coach outline on a DRAFT Mission: activate it with its notes and with one sortie per
     * pattern letter, in the pattern's own order, each carrying the focus and rationale of the outline entry at
     * the same position; then advance, which is what makes sortie 1 current and prepares it (DESIGN.md §4.2).
     *
     * The three refusals — not a DRAFT, the week has ended, the outline no longer matches the pattern — change
     * nothing, because an outline can arrive after the week moved on. A reply that is not an outline is a
     * caller bug, so it throws rather than refusing.
     */
    suspend fun storeOutline(missionId: Long, valid: CoachReplyResult.Valid): StoreOutcome {
        val settings = settingsStore.settings.first()
        return db.withTransaction {
            val mission = db.missionDao().get(missionId)
                ?: return@withTransaction StoreOutcome.Refused("mission $missionId no longer exists")
            val outline = when (val plan = valid.plan) {
                is CoachOutline -> plan
                else -> throw IllegalArgumentException("${plan::class.simpleName} is not an outline")
            }
            if (mission.status != MissionStatus.DRAFT) {
                return@withTransaction StoreOutcome.Refused("mission is ${mission.status}, not a DRAFT")
            }
            if (weekHasEnded(mission.weekStart, clock.instant().atZone(zone()).toLocalDate())) {
                return@withTransaction StoreOutcome.Refused("the week of ${mission.weekStart} has ended")
            }
            if (!outlineMatchesPattern(outline, mission.pattern)) {
                return@withTransaction StoreOutcome.Refused(
                    "the outline no longer matches the mission's pattern ${mission.pattern}"
                )
            }
            val (active, sorties) = activateWithOutline(mission, outline)
            db.missionDao().update(active)
            db.sortieDao().insertAll(sorties)
            advance(missionId, settings)
            StoreOutcome.Stored
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

    suspend fun land(
        sortieId: Long,
        runDistance: Double? = null,
        runMinutes: Double? = null
    ) {
        val settings = settingsStore.settings.first()
        db.withTransaction {
            val sortie = db.sortieDao().get(sortieId)!!
            check(sortie.state == SortieState.IN_FLIGHT) { "Sortie must be IN_FLIGHT" }
            // The run's actuals travel with the state change, so a landed run is never half-written.
            val landed = land(sortie, clock.millis())
                .copy(runDistance = runDistance, runMinutes = runMinutes)
            db.sortieDao().update(landed)
            db.flightPlanDao().markOpenSetsNotDone(sortieId)
            advance(sortie.missionId, settings)
        }
    }

    /**
     * Copy the most recent landed Flight Plan of this sortie's type onto the current PENDING sortie, and
     * move it to PLANNED. No generation is queued, so it works with the coach unreachable.
     */
    suspend fun refly(sortieId: Long) {
        db.withTransaction {
            val sortie = db.sortieDao().get(sortieId)!!
            val mission = db.missionDao().get(sortie.missionId)!!
            check(mission.status == MissionStatus.ACTIVE) { "Mission must be ACTIVE" }
            val sorties = db.sortieDao().getForMission(mission.id)
            val current = currentSortie(sorties)
            check(current != null && current.id == sortieId) { "Sortie must be current" }
            check(sortie.state == SortieState.PENDING) { "Sortie must be PENDING" }

            val source = chooseReflySource(sortie, reflyCandidates(db.sortieDao(), db.flightPlanDao(), sortie))
            check(source != null) { "No landed Flight Plan to re-fly" }

            val sourcePlan = db.flightPlanDao().getPlan(source.sortie.id)!!
            db.flightPlanDao().writePlan(sortieId, reflyDraft(sourcePlan))
            // markPlanned, not advance: the copy is what prepares this sortie, and advance would prepare the next one.
            db.sortieDao().update(markPlanned(sortie))
        }
    }

    /**
     * Store a validated coach plan on [sortieId]. `writePlan` replaces whatever plan that sortie already
     * holds — a repair round or a retry is the normal reason a second plan arrives — and the sortie moves to
     * PLANNED only when it was PENDING, so a plan that lands on a PLANNED sortie leaves it PLANNED.
     *
     * A plan for a sortie that is no longer current, or that the owner has already launched, landed or
     * scrubbed, changes nothing and says why: a generation can outlive the moment it was asked for. A plan of
     * the wrong type for the sortie is a caller bug — the validator already rejected that mismatch — so it
     * throws rather than refusing.
     */
    suspend fun storePlan(sortieId: Long, valid: CoachReplyResult.Valid): StoreOutcome =
        db.withTransaction {
            val sortie = db.sortieDao().get(sortieId)
                ?: return@withTransaction StoreOutcome.Refused("sortie $sortieId no longer exists")
            val mission = db.missionDao().get(sortie.missionId)
                ?: return@withTransaction StoreOutcome.Refused("sortie $sortieId has no mission")

            val draft = when (val plan = valid.plan) {
                is CoachLiftPlan -> {
                    require(sortie.type == SortieType.LIFT) {
                        "a lift plan cannot be stored on the ${sortie.type} sortie $sortieId"
                    }
                    coachLiftDraft(plan, valid.json)
                }
                is CoachRunPlan -> {
                    require(sortie.type == SortieType.RUN) {
                        "a run plan cannot be stored on the ${sortie.type} sortie $sortieId"
                    }
                    coachRunDraft(plan, valid.json)
                }
                else -> throw IllegalArgumentException(
                    "${plan::class.simpleName} is not a flight plan"
                )
            }

            if (mission.status != MissionStatus.ACTIVE) {
                return@withTransaction StoreOutcome.Refused("mission is ${mission.status}")
            }
            if (sortie.state != SortieState.PENDING && sortie.state != SortieState.PLANNED) {
                return@withTransaction StoreOutcome.Refused("sortie is ${sortie.state}")
            }
            val current = currentSortie(db.sortieDao().getForMission(mission.id))
            if (current == null || current.id != sortieId) {
                return@withTransaction StoreOutcome.Refused("sortie $sortieId is not the current sortie")
            }

            db.flightPlanDao().writePlan(sortieId, draft)
            if (sortie.state == SortieState.PENDING) db.sortieDao().update(markPlanned(sortie))
            StoreOutcome.Stored
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

    private fun currentWeekOf(clock: Clock, zone: () -> ZoneId): LocalDate =
        weekStartOf(clock.instant().atZone(zone()).toLocalDate())
}
