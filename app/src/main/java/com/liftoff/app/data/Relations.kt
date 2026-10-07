package com.liftoff.app.data

import androidx.room.Embedded
import androidx.room.Relation

data class MissionWithSorties(
    @Embedded val mission: Mission,
    @Relation(
        parentColumn = "id",
        entityColumn = "missionId"
    )
    val sorties: List<Sortie>
)
