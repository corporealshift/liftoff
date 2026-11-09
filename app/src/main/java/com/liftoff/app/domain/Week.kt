package com.liftoff.app.domain

import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

fun weekStartOf(date: LocalDate): LocalDate =
    date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

fun currentWeekStart(clock: Clock, zone: ZoneId): LocalDate =
    weekStartOf(clock.instant().atZone(zone).toLocalDate())

fun weekHasEnded(weekStart: LocalDate, today: LocalDate): Boolean =
    today >= weekStart.plusDays(7)

/** The week the app works in: the calendar week, or the one after it once that has been started early. */
fun effectiveWeekStart(calendarWeek: LocalDate, nextWeekStarted: Boolean): LocalDate =
    if (nextWeekStarted) calendarWeek.plusWeeks(1) else calendarWeek
