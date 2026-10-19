package com.liftoff.app.data

import androidx.room.*

@Dao
interface GenerationDao {

    @Insert
    suspend fun insert(generation: Generation): Long

    @Query("SELECT * FROM generation WHERE id = :id")
    suspend fun get(id: Long): Generation?

    // The export's whole-table read.
    @Query("SELECT * FROM generation ORDER BY id")
    suspend fun getAll(): List<Generation>

    // Import's bulk insert and emptiness read; a generation row points at a Mission, so it goes in last.
    @Insert
    suspend fun insertAll(generations: List<Generation>)

    @Query("SELECT COUNT(*) FROM generation")
    suspend fun countAll(): Long

    @Update
    suspend fun update(generation: Generation)

    @Delete
    suspend fun delete(generation: Generation)
}
