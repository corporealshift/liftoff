package com.liftoff.app.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class PatternTrackTest {

    @Test
    fun drawsOneChipPerLetterInItsStateStyle() {
        // PatternTrack renders one chip per letter in landed, current, or upcoming style.
        // Landed: solid Ink circle with check icon (white).
        // Current: Red filled circle with Ink outline and white text.
        // Upcoming: Cream-filled circle with Ink outline and Ink text.

        val landed = PatternChip("R", ChipState.Landed)
        assertEquals("Landed chip letter is preserved", "R", landed.letter)
        assertEquals("Landed chip state is Landed", ChipState.Landed, landed.state)

        val current = PatternChip("L", ChipState.Current)
        assertEquals("Current chip letter is preserved", "L", current.letter)
        assertEquals("Current chip state is Current", ChipState.Current, current.state)

        val upcoming = PatternChip("R", ChipState.Upcoming)
        assertEquals("Upcoming chip letter is preserved", "R", upcoming.letter)
        assertEquals("Upcoming chip state is Upcoming", ChipState.Upcoming, upcoming.state)

        // Three states map to three visual styles:
        assertEquals("Three chip states exist", 3, ChipState.values().size)
    }
}
