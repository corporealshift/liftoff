package com.liftoff.app.data

import androidx.room.*

@Dao
interface GenerationDao {

    @Insert
    suspend fun insert(generation: Generation): Long

    @Query("SELECT * FROM generation WHERE id = :id")
    suspend fun get(id: Long): Generation?

    @Update
    suspend fun update(generation: Generation)

    @Delete
    suspend fun delete(generation: Generation)
}
