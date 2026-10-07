package com.liftoff.app.data

import androidx.room.*
import com.liftoff.app.coach.ExerciseNames

@Dao
abstract class ExerciseDao {

    @Insert
    abstract suspend fun insert(exercise: Exercise): Long

    @Query("SELECT * FROM exercise WHERE normalizedName = :normalizedName")
    abstract suspend fun findByNormalizedName(normalizedName: String): Exercise?

    @Query("SELECT * FROM exercise ORDER BY normalizedName")
    abstract suspend fun getAll(): List<Exercise>

    @Transaction
    open suspend fun resolve(name: String): Exercise {
        val normalized = ExerciseNames.normalize(name).trim()
        if (normalized.isBlank()) {
            throw IllegalArgumentException("Exercise name cannot be blank or symbols-only: '$name'")
        }
        findByNormalizedName(normalized)?.let { return it }
        val exercise = Exercise(normalizedName = normalized, displayName = name.trim())
        return insert(exercise).let { exercise.copy(id = it) }
    }
}
