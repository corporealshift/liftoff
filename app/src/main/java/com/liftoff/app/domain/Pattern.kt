package com.liftoff.app.domain

import com.liftoff.app.data.SortieType

fun isValidPattern(p: String): Boolean =
    p.length in 1..7 && p.all { it == 'R' || it == 'L' }

fun sortieTypesOf(pattern: String): List<SortieType> =
    pattern.map { if (it == 'R') SortieType.RUN else SortieType.LIFT }
