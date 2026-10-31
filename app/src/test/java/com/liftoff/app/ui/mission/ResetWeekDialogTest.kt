package com.liftoff.app.ui.mission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResetWeekDialogTest {

    @Test
    fun `nothing recorded says only that the week is deleted`() {
        val message = resetWeekMessage(0)
        assertFalse(message.contains("landed or scrubbed"))
        assertTrue(message.contains("can't be undone"))
    }

    @Test
    fun `recorded sorties are counted, one and many`() {
        assertTrue(resetWeekMessage(1).contains("including 1 landed or scrubbed sortie."))
        assertTrue(resetWeekMessage(3).contains("including 3 landed or scrubbed sorties."))
    }

    @Test
    fun `the message says the week goes back to a draft`() {
        assertEquals(1, Regex("back to a draft").findAll(resetWeekMessage(2)).count())
    }
}
