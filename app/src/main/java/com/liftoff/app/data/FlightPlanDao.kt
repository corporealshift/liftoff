package com.liftoff.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
abstract class FlightPlanDao(private val db: LiftoffDatabase) {

    @Insert
    abstract suspend fun insertPlan(plan: FlightPlan): Long

    @Insert
    abstract suspend fun insertExercise(e: PlannedExercise): Long

    @Insert
    abstract suspend fun insertSet(s: PlannedSet): Long

    @Insert
    abstract suspend fun insertSegment(s: RunSegment): Long

    @Query("DELETE FROM flightPlan WHERE sortieId = :sortieId")
    abstract suspend fun deleteForSortie(sortieId: Long)

    @Transaction
    open suspend fun writePlan(sortieId: Long, draft: FlightPlanDraft): Long {
        deleteForSortie(sortieId)
        val plan = FlightPlan(
            sortieId = sortieId,
            source = draft.source,
            title = draft.title,
            estimatedMinutes = draft.estimatedMinutes,
            warmup = draft.warmup,
            notes = draft.notes,
            runKind = draft.runKind,
            targetDistance = draft.targetDistance,
            targetPace = draft.targetPace,
            rawJson = draft.rawJson
        )
        val planId = insertPlan(plan)

        for ((i, exerciseDraft) in draft.exercises.withIndex()) {
            val exercise = db.exerciseDao().resolve(exerciseDraft.name)
            val pe = PlannedExercise(
                flightPlanId = planId,
                `order` = i,
                exerciseId = exercise.id,
                equipmentIds = exerciseDraft.equipmentIds,
                restSeconds = exerciseDraft.restSeconds,
                notes = exerciseDraft.notes,
                skipped = false,
                userNotes = null
            )
            val peId = insertExercise(pe)
            for ((j, setDraft) in exerciseDraft.sets.withIndex()) {
                insertSet(
                    PlannedSet(
                        plannedExerciseId = peId,
                        `order` = j,
                        reps = setDraft.reps,
                        seconds = setDraft.seconds,
                        weight = setDraft.weight,
                        actualReps = null,
                        actualSeconds = null,
                        actualWeight = null,
                        status = SetStatus.OPEN,
                        added = false
                    )
                )
            }
        }

        for ((i, segmentDraft) in draft.segments.withIndex()) {
            insertSegment(
                RunSegment(
                    flightPlanId = planId,
                    `order` = i,
                    description = segmentDraft.description,
                    distance = segmentDraft.distance,
                    minutes = segmentDraft.minutes
                )
            )
        }

        return planId
    }

    @Transaction
    open suspend fun getPlan(sortieId: Long): FlightPlanDetail? {
        val plan = getPlanBySortieId(sortieId) ?: return null

        val exerciseRows = getExercisesWithNames(plan.id)
        if (exerciseRows.isEmpty()) {
            return FlightPlanDetail(plan, emptyList(), getSegments(plan.id))
        }

        val setRows = getSetsForPlan(plan.id)
        val setsByExercise = setRows.groupBy { it.plannedExerciseId }

        val exercises = exerciseRows.map { row ->
            PlannedExerciseDetail(
                plannedExercise = row.plannedExercise,
                displayName = row.displayName,
                sets = setsByExercise[row.plannedExercise.id].orEmpty()
            )
        }

        return FlightPlanDetail(plan, exercises, getSegments(plan.id))
    }

    @Query("SELECT * FROM flightPlan WHERE sortieId = :sortieId")
    protected abstract fun getPlanBySortieId(sortieId: Long): FlightPlan?

    @Query(
        """SELECT pe.*, e.displayName AS displayName
           FROM plannedExercise pe
           JOIN exercise e ON e.id = pe.exerciseId
           WHERE pe.flightPlanId = :planId
           ORDER BY pe.`order`"""
    )
    protected abstract fun getExercisesWithNames(planId: Long): List<PlannedExerciseRow>

    @Query(
        """SELECT ps.* FROM plannedSet ps
           JOIN plannedExercise pe ON pe.id = ps.plannedExerciseId
           WHERE pe.flightPlanId = :planId
           ORDER BY ps.`order`"""
    )
    protected abstract fun getSetsForPlan(planId: Long): List<PlannedSet>

    @Query("SELECT * FROM runSegment WHERE flightPlanId = :planId ORDER BY `order`")
    protected abstract fun getSegments(planId: Long): List<RunSegment>

    @Query(
        """SELECT (
               (SELECT COUNT(*) FROM flightPlan) +
               (SELECT COUNT(*) FROM plannedExercise) +
               (SELECT COUNT(*) FROM plannedSet) +
               (SELECT COUNT(*) FROM runSegment)
           )"""
    )
    abstract fun observeChanges(): Flow<Int>

    @Query(
        """UPDATE plannedSet SET actualReps = :actualReps, actualSeconds = :actualSeconds,
           actualWeight = :actualWeight, status = :status
           WHERE id = :setId"""
    )
    abstract suspend fun updateSetActuals(
        setId: Long,
        actualReps: Int?,
        actualSeconds: Int?,
        actualWeight: Double?,
        status: SetStatus
    )

    @Transaction
    open suspend fun addExtraSet(
        plannedExerciseId: Long,
        reps: Int?,
        seconds: Int?,
        weight: Double?
    ): Long {
        val maxOrder = getMaxOrderForExercise(plannedExerciseId)
        return insertSet(
            PlannedSet(
                plannedExerciseId = plannedExerciseId,
                `order` = maxOrder + 1,
                reps = reps,
                seconds = seconds,
                weight = weight,
                actualReps = null,
                actualSeconds = null,
                actualWeight = null,
                status = SetStatus.OPEN,
                added = true
            )
        )
    }

    @Query("SELECT COALESCE(MAX(`order`), -1) FROM plannedSet WHERE plannedExerciseId = :plannedExerciseId")
    protected abstract fun getMaxOrderForExercise(plannedExerciseId: Long): Int

    @Query(
        """UPDATE plannedExercise SET skipped = :skipped, userNotes = :userNotes
           WHERE id = :plannedExerciseId"""
    )
    abstract suspend fun updateExercise(
        plannedExerciseId: Long,
        skipped: Boolean,
        userNotes: String?
    )

    @Query(
        """UPDATE plannedSet SET status = 'SKIPPED'
           WHERE status = 'OPEN'
           AND plannedExerciseId IN (
               SELECT pe.id FROM plannedExercise pe
               JOIN flightPlan fp ON fp.id = pe.flightPlanId
               WHERE fp.sortieId = :sortieId
           )"""
    )
    abstract suspend fun markOpenSetsNotDone(sortieId: Long)
}
