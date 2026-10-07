package com.liftoff.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "equipment",
    indices = [Index(value = ["key"], unique = true)]
)
data class Equipment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "key") val key: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "notes") val notes: String = "",
    @ColumnInfo(name = "active") val active: Boolean = true
)
