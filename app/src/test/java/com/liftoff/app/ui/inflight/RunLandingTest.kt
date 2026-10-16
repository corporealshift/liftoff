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
}
