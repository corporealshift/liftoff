package com.liftoff.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDate

@Entity(
    tableName = "mission",
    indices = [Index(value = ["weekStart"], unique = true)]
)
data class Mission(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "weekStart") val weekStart: LocalDate,
    val pattern: String,
    val status: MissionStatus,
    @ColumnInfo(name = "outlineNotes") val outlineNotes: String?
)
