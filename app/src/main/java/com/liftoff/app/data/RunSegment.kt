package com.liftoff.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "runSegment",
    indices = [Index(value = ["flightPlanId"])],
    foreignKeys = [ForeignKey(
        entity = FlightPlan::class,
        parentColumns = ["id"],
        childColumns = ["flightPlanId"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class RunSegment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "flightPlanId") val flightPlanId: Long,
    val `order`: Int,
    val description: String,
    val distance: Double?,
    val minutes: Double?
)
