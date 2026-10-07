package com.liftoff.app.coach

object ExerciseNames {

    fun normalize(name: String): String =
        name.lowercase()
            .map { if (it.isWhitespace()) ' ' else it }
            .filter { it.isLetterOrDigit() || it == ' ' || it == '-' }
            .joinToString("")
            .replace(Regex("\\s+"), " ")
            .trim()
}
