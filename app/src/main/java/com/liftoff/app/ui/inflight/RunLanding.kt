package com.liftoff.app.ui.inflight

/**
 * What the Land dialog's two fields add up to, decided before anything is landed.
 * Pure Kotlin: the view model can be tested without a screen.
 */
sealed interface RunEntry {
    /** Both fields are blank or a positive number; a blank field is null (Decision 12). */
    data class Valid(val distance: Double?, val minutes: Double?) : RunEntry

    /** Both bad fields are reported together, so the dialog shows every problem at once. */
    data class Invalid(val distanceError: String?, val minutesError: String?) : RunEntry
}

private const val DISTANCE_ERROR = "Distance must be a number above 0"
private const val MINUTES_ERROR = "Duration must be a number above 0"

/**
 * Trim each field; blank becomes null. Otherwise both `.` and `,` are the decimal separator
 * (Decision 3), and anything that is not a finite number strictly above zero is rejected
 * (Decision 4) — including `0`, a negative, text, `NaN` and `Infinity`.
 */
fun parseRunEntry(distanceText: String?, minutesText: String?): RunEntry {
    val distance = parseAmount(distanceText)
    val minutes = parseAmount(minutesText)

    val distanceError = if (distance is Amount.Rejected) DISTANCE_ERROR else null
    val minutesError = if (minutes is Amount.Rejected) MINUTES_ERROR else null
    if (distanceError != null || minutesError != null) {
        return RunEntry.Invalid(distanceError, minutesError)
    }

    return RunEntry.Valid(
        distance = (distance as? Amount.Given)?.value,
        minutes = (minutes as? Amount.Given)?.value,
    )
}

private sealed interface Amount {
    data class Given(val value: Double) : Amount
    object Blank : Amount
    object Rejected : Amount
}

private fun parseAmount(text: String?): Amount {
    val trimmed = text?.trim().orEmpty()
    if (trimmed.isEmpty()) return Amount.Blank
    // A keypad whose separator follows the owner's locale types `3,1`; `1,2,3` still fails.
    val value = trimmed.replace(',', '.').toDoubleOrNull()
    return if (value != null && value.isFinite() && value > 0.0) Amount.Given(value) else Amount.Rejected
}
