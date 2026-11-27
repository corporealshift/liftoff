package com.liftoff.app.ui.inflight

/**
 * What the Land dialog's three fields add up to, decided before anything is landed.
 * Pure Kotlin: the view model can be tested without a screen.
 */
sealed interface RunEntry {
    /** Every field is blank or a valid number; a blank field is null (Decision 12). */
    data class Valid(val distance: Double?, val minutes: Double?) : RunEntry

    /** Every bad field is reported together, so the dialog shows every problem at once. */
    data class Invalid(val distanceError: String?, val minutesError: String?, val secondsError: String?) : RunEntry
}

private const val DISTANCE_ERROR = "Distance must be a number above 0"
private const val MINUTES_ERROR = "Duration must be a number above 0"
private const val SECONDS_ERROR = "Seconds must be a whole number from 0 to 59"

/**
 * Trim each field; blank becomes null. Otherwise both `.` and `,` are the decimal separator
 * (Decision 3), and anything that is not a finite number is rejected (Decision 4) — a negative,
 * text, `NaN` and `Infinity`.
 *
 * The duration is two fields that add up to one number: [minutesText] and [secondsText] combine into
 * the one `minutes` a sortie stores, so the "above 0" rule moved from each field to their total. A
 * `0` in the minutes field is therefore a number now, not a rejection — but a duration that adds up
 * to nothing is still refused, with the duration error. Seconds are whole and 0-59; `90`, `60`,
 * `-5`, `0.5` and text are refused rather than rolled into the minutes, because a slip is more
 * likely there than a request for 1 min 30 s.
 */
fun parseRunEntry(distanceText: String?, minutesText: String?, secondsText: String? = null): RunEntry {
    val distance = parseAmount(distanceText, allowZero = false)
    val minutes = parseAmount(minutesText, allowZero = true)
    val seconds = parseSeconds(secondsText)

    val distanceError = if (distance is Amount.Rejected) DISTANCE_ERROR else null
    var minutesError = if (minutes is Amount.Rejected) MINUTES_ERROR else null
    val secondsError = if (seconds is Amount.Rejected) SECONDS_ERROR else null

    val minutesValue = (minutes as? Amount.Given)?.value
    val secondsValue = (seconds as? Amount.Given)?.value
    // Both fields blank is "record nothing", which is still valid; a typed 0 is only carried by the
    // other duration field.
    val typedADuration = minutesValue != null || secondsValue != null
    if (minutesError == null && secondsError == null && typedADuration &&
        (minutesValue ?: 0.0) + (secondsValue ?: 0.0) / 60.0 == 0.0
    ) {
        minutesError = MINUTES_ERROR
    }

    if (distanceError != null || minutesError != null || secondsError != null) {
        return RunEntry.Invalid(distanceError, minutesError, secondsError)
    }

    return RunEntry.Valid(
        distance = (distance as? Amount.Given)?.value,
        minutes = if (!typedADuration) null else (minutesValue ?: 0.0) + (secondsValue ?: 0.0) / 60.0,
    )
}

private sealed interface Amount {
    data class Given(val value: Double) : Amount
    object Blank : Amount
    object Rejected : Amount
}

private fun parseAmount(text: String?, allowZero: Boolean): Amount {
    val trimmed = text?.trim().orEmpty()
    if (trimmed.isEmpty()) return Amount.Blank
    // A keypad whose separator follows the owner's locale types `3,1`; `1,2,3` still fails.
    val value = trimmed.replace(',', '.').toDoubleOrNull()
    if (value == null || !value.isFinite()) return Amount.Rejected
    // A duration field may hold 0 because the seconds field can carry the whole duration; a distance may not.
    return if (value > 0.0 || (allowZero && value == 0.0)) Amount.Given(value) else Amount.Rejected
}

private fun parseSeconds(text: String?): Amount {
    val trimmed = text?.trim().orEmpty()
    if (trimmed.isEmpty()) return Amount.Blank
    val value = trimmed.replace(',', '.').toDoubleOrNull()
    if (value == null || !value.isFinite()) return Amount.Rejected
    return if (value >= 0.0 && value <= 59.0 && value % 1.0 == 0.0) Amount.Given(value) else Amount.Rejected
}
