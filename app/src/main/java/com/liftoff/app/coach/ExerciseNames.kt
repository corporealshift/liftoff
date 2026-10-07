package com.liftoff.app.coach

object ExerciseNames {

    fun normalize(name: String): String =
        name.lowercase()
            .filter { it.isLetterOrDigit() || it.isWhitespace() || it == '-' }
            .replace(Regex("\\s+|\\u00A0+"), " ")
            .trim()
}
