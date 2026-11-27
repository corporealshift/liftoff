package com.liftoff.app.domain

import com.liftoff.app.data.SortieType

/** A week is planned as at most this many sorties (DESIGN.md §4.1); the pattern's letters are its days. */
const val MAX_PATTERN_LENGTH = 7

fun isValidPattern(p: String): Boolean =
    p.length in 1..MAX_PATTERN_LENGTH && p.all { it == 'R' || it == 'L' }

fun sortieTypesOf(pattern: String): List<SortieType> =
    pattern.map { if (it == 'R') SortieType.RUN else SortieType.LIFT }
