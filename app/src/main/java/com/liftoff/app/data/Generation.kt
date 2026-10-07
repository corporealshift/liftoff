package com.liftoff.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "generation")
data class Generation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "kind") val kind: GenerationKind,
    @ColumnInfo(name = "missionId") val missionId: Long,
    @ColumnInfo(name = "sortieId") val sortieId: Long? = null,
    @ColumnInfo(name = "status") val status: GenerationStatus,
    @ColumnInfo(name = "nabuSessionId") val nabuSessionId: String? = null,
    @ColumnInfo(name = "lastEventId") val lastEventId: String? = null,
    @ColumnInfo(name = "attempt") val attempt: Int = 0,
    @ColumnInfo(name = "error") val error: String? = null,
    @ColumnInfo(name = "createdAt") val createdAt: Long,
    @ColumnInfo(name = "finishedAt") val finishedAt: Long? = null
)
