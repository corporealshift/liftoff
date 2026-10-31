package com.liftoff.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sortie",
    foreignKeys = [
        ForeignKey(
            entity = Mission::class,
            parentColumns = ["id"],
            childColumns = ["missionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["missionId"])]
)
data class Sortie(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "missionId") val missionId: Long,
    val index: Int,
    val type: SortieType,
    val focus: String?,
    @ColumnInfo(name = "focusRationale") val focusRationale: String?,
    val state: SortieState,
    @ColumnInfo(name = "launchedAt") val launchedAt: Long?,
    @ColumnInfo(name = "landedAt") val landedAt: Long?,
    @ColumnInfo(name = "scrubReason") val scrubReason: String?,
    val notes: String?,
    @ColumnInfo(name = "runDistance") val runDistance: Double?,
    @ColumnInfo(name = "runMinutes") val runMinutes: Double?
)
