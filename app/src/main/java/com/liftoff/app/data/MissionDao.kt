package com.liftoff.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Dao
abstract class MissionDao {

    @Insert
    abstract suspend fun insert(mission: Mission): Long

    @Update
    abstract suspend fun update(mission: Mission)

    @Query("SELECT * FROM mission WHERE id = :id")
    abstract suspend fun get(id: Long): Mission?

    @Query("SELECT * FROM mission WHERE weekStart = :weekStart")
    abstract suspend fun getByWeekStart(weekStart: java.time.LocalDate): Mission?

    @Query("SELECT * FROM mission WHERE weekStart < :weekStart AND status != 'CLOSED'")
    abstract suspend fun getUnclosedBefore(weekStart: java.time.LocalDate): List<Mission>

    @Query("SELECT * FROM mission WHERE weekStart = :weekStart")
    protected abstract fun observeRaw(weekStart: java.time.LocalDate): Flow<MissionWithSorties?>

    open fun observeWeek(weekStart: java.time.LocalDate): Flow<MissionWithSorties?> {
        return observeRaw(weekStart).map { result ->
            result?.copy(sorties = result.sorties.sortedBy { it.index })
        }
    }
}
