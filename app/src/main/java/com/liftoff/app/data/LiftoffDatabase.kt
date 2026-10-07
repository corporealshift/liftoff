package com.liftoff.app.data

import androidx.room.*

@Database(
    entities = [
        Exercise::class,
        Equipment::class,
        Generation::class,
        Mission::class,
        Sortie::class,
        FlightPlan::class,
        PlannedExercise::class,
        PlannedSet::class,
        RunSegment::class
    ],
    version = 1,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class LiftoffDatabase : RoomDatabase() {

    // The phone holds the only copy of user data — never set up destructive migration.
    abstract fun exerciseDao(): ExerciseDao
    abstract fun equipmentDao(): EquipmentDao
    abstract fun generationDao(): GenerationDao
    abstract fun missionDao(): MissionDao
    abstract fun sortieDao(): SortieDao
    abstract fun flightPlanDao(): FlightPlanDao
}
