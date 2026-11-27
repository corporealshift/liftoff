package com.liftoff.app.ui.inflight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `parseRunEntry` on its own — no Android, no Compose, no Room. */
class RunLandingTest {

    private fun valid(distance: Double?, minutes: Double?) = RunEntry.Valid(distance, minutes)

    // ── blank fields ──────────────────────────────────────────────────

    // Both fields empty still lands, with nothing stored (Decision 12).
    @Test
    fun blankFieldsParseToNulls() {
        assertEquals(valid(null, null), parseRunEntry("", ""))
    }

    @Test
    fun whitespaceOnlyFieldsParseToNulls() {
        assertEquals(valid(null, null), parseRunEntry("   ", "\t \n"))
    }

    @Test
    fun missingFieldsParseToNulls() {
        assertEquals(valid(null, null), parseRunEntry(null, null))
    }

    // A field left blank next to a typed one is null, not 0.
    @Test
    fun blankFieldBesideATypedOneIsNotAnError() {
        assertEquals(valid(5.2, null), parseRunEntry("5.2", ""))
        assertEquals(valid(null, 31.0), parseRunEntry(null, "31"))
    }

    // ── numbers ───────────────────────────────────────────────────────

    @Test
    fun decimalDistanceIsAccepted() {
        assertEquals(valid(5.2, 31.0), parseRunEntry("5.2", "31"))
    }

    @Test
    fun wholeNumberMinutesIsAccepted() {
        assertEquals(valid(null, 31.0), parseRunEntry("", "31"))
    }

    // The keypad's separator follows the owner's locale, so `31,5` is 31.5 (Decision 3).
    @Test
    fun commaDecimalSeparatorIsAccepted() {
        assertEquals(valid(3.1, 31.5), parseRunEntry("3,1", "31,5"))
    }

    // Surrounding spaces are the owner's, not part of the number.
    @Test
    fun fieldsAreTrimmedBeforeParsing() {
        assertEquals(valid(5.2, 31.5), parseRunEntry(" 5.2 ", " 31,5 "))
    }

    // ── rejections ────────────────────────────────────────────────────

    // A user reads `0` as "I typed nothing"; it is refused either way (Decision 4).
    @Test
    fun zeroDistanceIsRejected() {
        val entry = parseRunEntry("0", "")
        assertTrue("$entry should be rejected", entry is RunEntry.Invalid)
        assertNotNull((entry as RunEntry.Invalid).distanceError)
        assertNull(entry.minutesError)
    }

    @Test
    fun negativeDistanceIsRejected() {
        val entry = parseRunEntry("-3", "")
        assertTrue("$entry should be rejected", entry is RunEntry.Invalid)
        assertNotNull((entry as RunEntry.Invalid).distanceError)
        assertNull(entry.minutesError)
    }

    @Test
    fun textDistanceIsRejected() {
        val entry = parseRunEntry("abc", "")
        assertTrue("$entry should be rejected", entry is RunEntry.Invalid)
        assertNotNull((entry as RunEntry.Invalid).distanceError)
        assertNull(entry.minutesError)
    }

    // A number that is not finite is refused, not stored as an unreachable distance.
    @Test
    fun notANumberIsRejected() {
        val entry = parseRunEntry("NaN", "")
        assertTrue("$entry should be rejected", entry is RunEntry.Invalid)
        assertNotNull((entry as RunEntry.Invalid).distanceError)
        assertNull(entry.minutesError)
    }

    @Test
    fun infiniteDistanceIsRejected() {
        val entry = parseRunEntry("Infinity", "")
        assertTrue("$entry should be rejected", entry is RunEntry.Invalid)
        assertNotNull((entry as RunEntry.Invalid).distanceError)
        assertNull(entry.minutesError)
    }

    @Test
    fun negativeMinutesAreRejected() {
        val entry = parseRunEntry("5.2", "-31,5")
        assertTrue("$entry should be rejected", entry is RunEntry.Invalid)
        assertNull((entry as RunEntry.Invalid).distanceError)
        assertNotNull(entry.minutesError)
    }

    // Both problems show at once, so the owner fixes the dialog in one pass.
    @Test
    fun bothFieldsBadReportBothErrors() {
        val entry = parseRunEntry("abc", "0")
        assertTrue("$entry should be rejected", entry is RunEntry.Invalid)
        assertNotNull((entry as RunEntry.Invalid).distanceError)
        assertNotNull(entry.minutesError)
    }

    // ── seconds ───────────────────────────────────────────────────────

    // Seconds are whole and 0-59: `90` and `60` are not rolled into the minutes, and a negative, a
    // fraction or text are refused. Each raises the seconds error alone, so the owner sees which of
    // the three fields to fix.
    @Test
    fun secondsOutsideZeroToFiftyNineAreRejectedWithTheSecondsError() {
        for (bad in listOf("90", "60", "-5", "0.5", "abc")) {
            val entry = parseRunEntry("5.2", "27", bad)
            assertTrue("$bad should be rejected, got $entry", entry is RunEntry.Invalid)
            val invalid = entry as RunEntry.Invalid
            assertNotNull("$bad must raise the seconds error", invalid.secondsError)
            assertNull("$bad must not raise the distance error", invalid.distanceError)
            assertNull("$bad must not raise the duration error", invalid.minutesError)
        }

        // A whole 0-59 beside minutes is one duration: minutes + seconds/60, so 27 min 34 s is the
        // 27.566666... a sortie stores, not 27 and not 28.
        assertEquals(valid(5.2, 27.0 + 34.0 / 60.0), parseRunEntry("5.2", "27", "34"))
        assertEquals(valid(5.2, 27.0 + 59.0 / 60.0), parseRunEntry("5.2", "27", "59"))
        // `0` is a legal seconds value, so it adds nothing to the minutes.
        assertEquals(valid(5.2, 31.0), parseRunEntry("5.2", "31", "0"))
    }

    // The seconds field can carry the whole duration: minutes blank or `0` beside 45 s is 0.75 min.
    // The above-0 rule moved to the combined total, so 0 minutes with 0 seconds is still refused —
    // with the duration error, because a zero duration is the duration field's problem, not the
    // seconds field's.
    @Test
    fun secondsWithNoMinutesIsValidAndZeroMinutesWithZeroSecondsIsRejected() {
        assertEquals(valid(5.2, 45.0 / 60.0), parseRunEntry("5.2", "", "45"))
        assertEquals(valid(5.2, 45.0 / 60.0), parseRunEntry("5.2", "0", "45"))
        assertEquals(valid(3.1, 30.0 / 60.0), parseRunEntry("3.1", "  ", "30"))

        for (zeroDuration in listOf("0", "")) {
            val entry = parseRunEntry("5.2", zeroDuration, "0")
            assertTrue("0 min + 0 s should be rejected, got $entry", entry is RunEntry.Invalid)
            val invalid = entry as RunEntry.Invalid
            assertNotNull("0 min + 0 s must raise the duration error", invalid.minutesError)
            assertNull("0 min + 0 s must not raise the distance error", invalid.distanceError)
            assertNull("0 min + 0 s must not raise the seconds error", invalid.secondsError)
        }
    }
}
