package com.liftoff.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "flightPlan",
    indices = [Index(value = ["sortieId"], unique = true)],
    foreignKeys = [ForeignKey(
        entity = Sortie::class,
        parentColumns = ["id"],
        childColumns = ["sortieId"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class FlightPlan(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "sortieId") val sortieId: Long,
    val source: FlightPlanSource,
    val title: String,
    @ColumnInfo(name = "estimatedMinutes") val estimatedMinutes: Int?,
    val warmup: String?,
    val notes: String?,
    @ColumnInfo(name = "runKind") val runKind: String?,
    @ColumnInfo(name = "targetDistance") val targetDistance: Double?,
    @ColumnInfo(name = "targetPace") val targetPace: String?,
    @ColumnInfo(name = "rawJson") val rawJson: String
)
