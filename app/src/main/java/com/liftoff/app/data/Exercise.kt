package com.liftoff.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "exercise",
    indices = [Index(value = ["normalizedName"], unique = true)]
)
data class Exercise(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "normalizedName") val normalizedName: String,
    @ColumnInfo(name = "displayName") val displayName: String
)
