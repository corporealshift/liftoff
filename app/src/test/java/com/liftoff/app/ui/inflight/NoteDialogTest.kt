package com.liftoff.app.ui.inflight

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.liftoff.app.ui.theme.LiftoffTheme
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h2000dp")
class NoteDialogTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val seeded = "Left shoulder twinge"

    // prefilledFieldSaveHandsBackEditedText — the field starts holding the note being edited;
    // replacing its text and pressing SAVE hands the new text back through onSave, exactly once.
    @Test
    fun prefilledFieldSaveHandsBackEditedText() {
        val saved = mutableListOf<String>()
        var dismissed = 0

        composeRule.setContent {
            LiftoffTheme {
                NoteDialogContent(
                    title = "BENCH PRESS · NOTE",
                    initial = seeded,
                    onSave = { saved += it },
                    onDismiss = { dismissed++ },
                )
            }
        }

        val field = composeRule.onNode(hasSetTextAction())
        field.assertExists()
        field.assertTextEquals(seeded)

        val replacement = "Went too heavy on set 3"
        field.performClick()
        composeRule.waitForIdle()
        field.performTextReplacement(replacement)
        composeRule.waitForIdle()
        field.assertTextEquals(replacement)

        composeRule.onNodeWithText("SAVE").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { saved.isNotEmpty() }

        assertEquals(listOf(replacement), saved)
        assertEquals(0, dismissed)
    }

    // cancelDismissesWithoutSaving — CANCEL is hittable next to SAVE, dismisses and saves nothing.
    @Test
    fun cancelDismissesWithoutSaving() {
        val saved = mutableListOf<String>()
        var dismissed = 0

        composeRule.setContent {
            LiftoffTheme {
                NoteDialogContent(
                    title = "BENCH PRESS · NOTE",
                    initial = seeded,
                    onSave = { saved += it },
                    onDismiss = { dismissed++ },
                )
            }
        }

        composeRule.onNodeWithText("CANCEL").assertIsDisplayed().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { dismissed > 0 }

        assertEquals(1, dismissed)
        assertEquals(emptyList<String>(), saved)
    }
}
