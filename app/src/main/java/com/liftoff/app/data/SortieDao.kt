package com.liftoff.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SortieDao {

    @Insert
    suspend fun insert(sortie: Sortie): Long

    @Insert
    suspend fun insertAll(sorties: List<Sortie>)

    @Update
    suspend fun update(sortie: Sortie)

    @Query("SELECT * FROM sortie WHERE id = :id")
    suspend fun get(id: Long): Sortie?

    @Query("SELECT * FROM sortie WHERE missionId = :missionId ORDER BY `index`")
    suspend fun getForMission(missionId: Long): List<Sortie>

    @Query("SELECT * FROM sortie WHERE state = 'IN_FLIGHT'")
    suspend fun getInFlight(): List<Sortie>

    @Query(
        "SELECT s.* FROM sortie s " +
        "JOIN mission m ON m.id = s.missionId " +
        "WHERE s.state IN ('LANDED', 'SCRUBBED') " +
        "ORDER BY m.weekStart DESC, s.`index` DESC"
    )
    fun observeHistory(): Flow<List<Sortie>>

    // The same rows as observeHistory, read once: the prompt loader windows and re-orders in Kotlin.
    @Query(
        "SELECT s.* FROM sortie s " +
        "JOIN mission m ON m.id = s.missionId " +
        "WHERE s.state IN ('LANDED', 'SCRUBBED') " +
        "ORDER BY m.weekStart DESC, s.`index` DESC"
    )
    suspend fun getFinishedNewestFirst(): List<Sortie>

    @Query("SELECT * FROM sortie WHERE id = :sortieId")
    fun observeById(sortieId: Long): Flow<Sortie?>

    // Notes-only, so a sortie with no Flight Plan can carry a note.
    @Query("UPDATE sortie SET notes = :notes WHERE id = :sortieId")
    suspend fun updateNotes(sortieId: Long, notes: String?)
}
