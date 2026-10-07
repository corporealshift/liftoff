package com.liftoff.app.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
abstract class EquipmentDao {

    private val VALID_KEY_REGEX = Regex("[a-z0-9_]+")

    @Insert
    protected abstract suspend fun insertRaw(equipment: Equipment): Long

    open suspend fun add(equipment: Equipment): Long {
        if (!VALID_KEY_REGEX.matches(equipment.key)) {
            throw IllegalArgumentException("Equipment key must match [a-z0-9_]+, got: '${equipment.key}'")
        }
        return insertRaw(equipment)
    }

    @Query("UPDATE equipment SET name = :name, notes = :notes WHERE id = :id")
    abstract suspend fun edit(id: Long, name: String, notes: String)

    @Query("SELECT * FROM equipment WHERE active = 1 ORDER BY name")
    abstract fun observeActive(): Flow<List<Equipment>>

    @Query("SELECT * FROM equipment ORDER BY name")
    abstract fun observeAll(): Flow<List<Equipment>>

    @Query("UPDATE equipment SET active = 0 WHERE id = :id")
    abstract suspend fun deactivate(id: Long)

    @Query("UPDATE equipment SET active = 1 WHERE id = :id")
    abstract suspend fun reactivate(id: Long)

    @Query("SELECT * FROM equipment WHERE id = :id")
    abstract suspend fun get(id: Long): Equipment?
}
