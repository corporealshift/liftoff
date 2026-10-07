package com.liftoff.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "plannedSet",
    indices = [Index(value = ["plannedExerciseId"])],
    foreignKeys = [ForeignKey(
        entity = PlannedExercise::class,
        parentColumns = ["id"],
        childColumns = ["plannedExerciseId"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class PlannedSet(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "plannedExerciseId") val plannedExerciseId: Long,
    val `order`: Int,
    val reps: Int?,
    val seconds: Int?,
    val weight: Double?,
    @ColumnInfo(name = "actualReps") val actualReps: Int?,
    @ColumnInfo(name = "actualSeconds") val actualSeconds: Int?,
    @ColumnInfo(name = "actualWeight") val actualWeight: Double?,
    val status: SetStatus = SetStatus.OPEN,
    val added: Boolean = false
)
