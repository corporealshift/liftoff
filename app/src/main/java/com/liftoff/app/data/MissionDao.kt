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

    // One read for a whole list of history sorties' missions, so none of them costs a query each.
    @Query("SELECT * FROM mission WHERE id IN (:ids)")
    abstract suspend fun getByIds(ids: List<Long>): List<Mission>

    // The export's whole-table read; id order so two exports of the same data read alike.
    @Query("SELECT * FROM mission ORDER BY id")
    abstract suspend fun getAll(): List<Mission>

    // Import's bulk insert. Room only assigns an id when the incoming one is 0, so a file's ids survive.
    @Insert
    abstract suspend fun insertAll(missions: List<Mission>)

    @Query("SELECT COUNT(*) FROM mission")
    abstract suspend fun countAll(): Long

    // The one Mission delete there is, and the only row it can remove is the DRAFT onAppOpen() makes on a
    // cold start. The status guard is what stops it wiping a week's sortie history through the cascade.
    @Query("DELETE FROM mission WHERE id = :id AND status = 'DRAFT'")
    abstract suspend fun deleteDraft(id: Long)

    @Query("SELECT * FROM mission WHERE weekStart = :weekStart")
    protected abstract fun observeRaw(weekStart: java.time.LocalDate): Flow<MissionWithSorties?>

    open fun observeWeek(weekStart: java.time.LocalDate): Flow<MissionWithSorties?> {
        return observeRaw(weekStart).map { result ->
            result?.copy(sorties = result.sorties.sortedBy { it.index })
        }
    }
}
