package com.liftoff.app.ui.mission

import com.liftoff.app.data.MissionStatus
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class MissionTabStateTest {

    private fun week(status: MissionStatus) = MissionTabState.Week(
        missionId = 1,
        weekStart = LocalDate.of(2026, 10, 26),
        status = status,
        chips = emptyList(),
        outlineNotes = null,
        rows = emptyList(),
    )

    @Test
    fun `reset is offered on a confirmed week and not on a draft`() {
        assertEquals(false, week(MissionStatus.DRAFT).canReset)
        assertEquals(true, week(MissionStatus.ACTIVE).canReset)
        assertEquals(true, week(MissionStatus.CLOSED).canReset)
    }
}
