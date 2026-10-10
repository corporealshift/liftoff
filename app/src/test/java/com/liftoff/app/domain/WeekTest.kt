package com.liftoff.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class WeekTest {

    @Test
    fun everyDayMapsToItsMonday() {
        // Monday stays Monday
        assertEquals(
            LocalDate.of(2026, 1, 5),
            weekStartOf(LocalDate.of(2026, 1, 5)),
        )
        // Tuesday → previous Monday
        assertEquals(
            LocalDate.of(2026, 1, 5),
            weekStartOf(LocalDate.of(2026, 1, 6)),
        )
        // Wednesday
        assertEquals(
            LocalDate.of(2026, 1, 5),
            weekStartOf(LocalDate.of(2026, 1, 7)),
        )
        // Thursday
        assertEquals(
            LocalDate.of(2026, 1, 5),
            weekStartOf(LocalDate.of(2026, 1, 8)),
        )
        // Friday
        assertEquals(
            LocalDate.of(2026, 1, 5),
            weekStartOf(LocalDate.of(2026, 1, 9)),
        )
        // Saturday
        assertEquals(
            LocalDate.of(2026, 1, 5),
            weekStartOf(LocalDate.of(2026, 1, 10)),
        )
        // Sunday → previous Monday
        assertEquals(
            LocalDate.of(2026, 1, 5),
            weekStartOf(LocalDate.of(2026, 1, 11)),
        )
    }

    @Test
    fun mondayBoundaryAcrossTimeZones() {
        // 2026-01-12T03:00Z is Monday morning UTC → week start = 2026-01-12 (Monday)
        val instant = Instant.parse("2026-01-12T03:00:00Z")
        val utcClock = Clock.fixed(instant, ZoneOffset.UTC)
        assertEquals(
            LocalDate.of(2026, 1, 12),
            currentWeekStart(utcClock, ZoneId.of("UTC")),
        )

        // Same instant in America/New_York is Sunday evening (Jan 11, 23:00) → week start = 2026-01-05
        val nyClock = Clock.fixed(instant, ZoneId.of("America/New_York"))
        assertEquals(
            LocalDate.of(2026, 1, 5),
            currentWeekStart(nyClock, ZoneId.of("America/New_York")),
        )
    }
}
