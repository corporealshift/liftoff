package com.liftoff.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "plannedExercise",
    indices = [
        Index(value = ["flightPlanId"]),
        Index(value = ["exerciseId"])
    ],
    foreignKeys = [ForeignKey(
        entity = FlightPlan::class,
        parentColumns = ["id"],
        childColumns = ["flightPlanId"],
        onDelete = ForeignKey.CASCADE
    ), ForeignKey(
        entity = Exercise::class,
        parentColumns = ["id"],
        childColumns = ["exerciseId"]
    )]
)
data class PlannedExercise(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "flightPlanId") val flightPlanId: Long,
    val `order`: Int,
    @ColumnInfo(name = "exerciseId") val exerciseId: Long,
    @ColumnInfo(name = "equipmentIds") val equipmentIds: List<String>,
    @ColumnInfo(name = "restSeconds") val restSeconds: Int?,
    val notes: String?,
    val skipped: Boolean = false,
    @ColumnInfo(name = "userNotes") val userNotes: String?
)
