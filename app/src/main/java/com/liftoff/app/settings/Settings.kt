package com.liftoff.app.settings

enum class WeightUnit { LB, KG }
enum class DistanceUnit { MI, KM }

data class Settings(
    val daemonHost: String = "",
    val daemonPort: Int = 8737,
    val daemonToken: String = "",
    val coachWorkspacePath: String = "",
    val defaultPattern: String = "RLRLR",
    val sortieLengthMinutes: Int = 60,
    val weightUnit: WeightUnit = WeightUnit.LB,
    val distanceUnit: DistanceUnit = DistanceUnit.MI,
    val historyWindowDays: Int = 28,
    val generateRunPlans: Boolean = false,
    val objectives: String = "",
    val constraints: String = ""
)
