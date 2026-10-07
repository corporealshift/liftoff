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

    @Query(
        "SELECT s.* FROM sortie s " +
        "JOIN mission m ON m.id = s.missionId " +
        "WHERE s.state IN ('LANDED', 'SCRUBBED') " +
        "ORDER BY m.weekStart DESC, s.`index` DESC"
    )
    fun observeHistory(): Flow<List<Sortie>>
}
