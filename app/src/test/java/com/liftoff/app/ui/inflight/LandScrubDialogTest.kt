package com.liftoff.app.ui.inflight

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.liftoff.app.ui.theme.LiftoffTheme
import org.robolectric.annotation.Config

/**
 * The two confirmation dialogs, tested through their content composables in the main window:
 * a text field inside a Dialog never lets Compose go idle under Robolectric, so the Dialog shell
 * itself is not opened here (the shape NoteDialogTest uses).
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h2000dp")
class LandScrubDialogTest {

    @get:Rule
    val composeRule = createComposeRule()

    // landConfirmStatesTheOpenCountAndSingularForOneSet — the dialog names how many sets landing
    // would record as not done, in the plural for three and the singular for one.
    @Test
    fun landConfirmStatesTheOpenCountAndSingularForOneSet() {
        composeRule.setContent {
            LiftoffTheme {
                Column {
                    LandConfirmDialogContent(
                        openCount = 3, sortieNumber = 2,
                        onConfirm = {}, onDismiss = {},
                    )
                    LandConfirmDialogContent(
                        openCount = 1, sortieNumber = 4,
                        onConfirm = {}, onDismiss = {},
                    )
                }
            }
        }

        composeRule.onNodeWithText("Land sortie 2?").assertIsDisplayed()
        composeRule.onNodeWithText("3 sets unchecked.").assertIsDisplayed()
        composeRule.onNodeWithText("Land sortie 4?").assertIsDisplayed()
        composeRule.onNodeWithText("1 set unchecked.").assertIsDisplayed()
        composeRule.onNodeWithText("1 sets unchecked.").assertDoesNotExist()
        // Both cards carry the promise, and only the count line differs between them.
        composeRule.onAllNodesWithText("They will be recorded as not done.").assertCountEquals(2)
    }

    // landConfirmButtonsCallConfirmOrDismiss — LAND only confirms, CANCEL only dismisses, and
    // neither does anything to the sortie by itself.
    @Test
    fun landConfirmButtonsCallConfirmOrDismiss() {
        var confirmed = 0
        var dismissed = 0

        composeRule.setContent {
            LiftoffTheme {
                LandConfirmDialogContent(
                    openCount = 2, sortieNumber = 1,
                    onConfirm = { confirmed++ }, onDismiss = { dismissed++ },
                )
            }
        }

        composeRule.onNodeWithText("LAND").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { confirmed > 0 }
        composeRule.onNodeWithText("CANCEL").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { dismissed > 0 }

        assertEquals(1, confirmed)
        assertEquals(1, dismissed)
    }

    // scrubDialogSendsTheTypedReasonOnConfirm — the reason the owner typed reaches onConfirm, and
    // the dialog titles itself with the sortie number the owner sees, not the row id.
    @Test
    fun scrubDialogSendsTheTypedReasonOnConfirm() {
        val reasons = mutableListOf<String>()
        var dismissed = 0

        composeRule.setContent {
            LiftoffTheme {
                ScrubDialogContent(
                    sortieNumber = 3,
                    onConfirm = { reasons += it },
                    onDismiss = { dismissed++ },
                )
            }
        }

        composeRule.onNodeWithText("Scrub sortie 3?").assertIsDisplayed()

        val field = composeRule.onNode(hasSetTextAction())
        field.performClick()
        composeRule.waitForIdle()
        field.performTextInput("Felt sick")
        composeRule.waitForIdle()

        composeRule.onNodeWithText("SCRUB").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { reasons.isNotEmpty() }

        assertEquals(listOf("Felt sick"), reasons)
        assertEquals(0, dismissed)
    }

    // scrubDialogCancelDismissesWithoutConfirming — CANCEL dismisses and scrubs nothing; an empty
    // reason is still handed over, because blank-becomes-null is the view model's rule, not the dialog's.
    @Test
    fun scrubDialogCancelDismissesWithoutConfirming() {
        val reasons = mutableListOf<String>()
        var dismissed = 0

        composeRule.setContent {
            LiftoffTheme {
                ScrubDialogContent(
                    sortieNumber = 1,
                    onConfirm = { reasons += it },
                    onDismiss = { dismissed++ },
                )
            }
        }

        composeRule.onNodeWithText("CANCEL").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { dismissed > 0 }

        assertEquals(1, dismissed)
        assertEquals(emptyList<String>(), reasons)
    }
}
