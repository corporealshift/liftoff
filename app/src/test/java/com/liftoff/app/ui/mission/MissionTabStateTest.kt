package com.liftoff.app.ui.mission

import com.liftoff.app.data.MissionStatus
import com.liftoff.app.ui.sortie.deriveChips
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class MissionTabStateTest {

    private fun week(status: MissionStatus, pattern: String = "LLR") = MissionTabState.Week(
        missionId = 1,
        weekStart = LocalDate.of(2026, 10, 26),
        status = status,
        chips = deriveChips(pattern, emptyList()),
        outlineNotes = null,
        rows = emptyList(),
    )

    @Test
    fun `reset is offered on a confirmed week and not on a draft`() {
        assertEquals(false, week(MissionStatus.DRAFT).canReset)
        assertEquals(true, week(MissionStatus.ACTIVE).canReset)
        assertEquals(true, week(MissionStatus.CLOSED).canReset)
    }

    @Test
    fun `add is offered on a confirmed week with room left in the pattern`() {
        assertEquals(true, week(MissionStatus.ACTIVE, "LLR").canAdd)
        assertEquals(true, week(MissionStatus.CLOSED, "LLR").canAdd)
        assertEquals(true, week(MissionStatus.ACTIVE, "L").canAdd)
    }

    @Test
    fun `add is not offered on a draft, which has a pattern to edit instead`() {
        assertEquals(false, week(MissionStatus.DRAFT, "LLR").canAdd)
        assertEquals(false, week(MissionStatus.DRAFT, "L").canAdd)
    }

    @Test
    fun `add is not offered once the week holds its seven sorties`() {
        assertEquals(true, week(MissionStatus.ACTIVE, "LLRLRL").canAdd)
        assertEquals(false, week(MissionStatus.ACTIVE, "LLRLRLR").canAdd)
        assertEquals(false, week(MissionStatus.CLOSED, "LLRLRLR").canAdd)
    }
}
