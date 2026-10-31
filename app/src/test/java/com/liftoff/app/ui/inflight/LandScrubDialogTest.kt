package com.liftoff.app.ui.inflight

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.liftoff.app.settings.DistanceUnit
import com.liftoff.app.ui.theme.LiftoffTheme
import org.robolectric.annotation.Config

/**
 * The two confirmation dialogs and the run landing card, tested through their content composables in
 * the main window: a text field inside a Dialog never lets Compose go idle under Robolectric, so the
 * Dialog shell itself is not opened here (the shape NoteDialogTest uses).
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

    // runLandDialogLabelsDistanceWithTheConfiguredUnit — the distance field carries the unit the owner
    // configured and the duration field carries minutes; both fields are blank when the card is shown.
    @Test
    fun runLandDialogLabelsDistanceWithTheConfiguredUnit() {
        composeRule.setContent {
            LiftoffTheme {
                Column {
                    RunLandDialogContent(
                        sortieNumber = 2, distanceUnit = DistanceUnit.MI, errors = null,
                        onLand = { _, _ -> }, onDismiss = {},
                    )
                    RunLandDialogContent(
                        sortieNumber = 5, distanceUnit = DistanceUnit.KM, errors = null,
                        onLand = { _, _ -> }, onDismiss = {},
                    )
                }
            }
        }

        composeRule.onNodeWithText("Land sortie 2?").assertIsDisplayed()
        composeRule.onNodeWithText("Distance (mi)").assertIsDisplayed()
        composeRule.onNodeWithText("Land sortie 5?").assertIsDisplayed()
        composeRule.onNodeWithText("Distance (km)").assertIsDisplayed()
        composeRule.onAllNodesWithText("Duration (min)").assertCountEquals(2)
        // The label comes from the unit, so neither card carries the other unit's word.
        composeRule.onAllNodesWithText("Distance (mi)").assertCountEquals(1)
        composeRule.onAllNodesWithText("Distance (km)").assertCountEquals(1)

        // Two fields per card, every one of them empty.
        composeRule.onAllNodes(hasSetTextAction())
            .assertCountEquals(4)
            .assertAll(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
    }

    // runLandDialogShowsEachFieldErrorUnderItsOwnField — the errors landRun raises (RunLanding's own
    // strings, read back out of parseRunEntry) sit under the field they belong to, and a bad distance
    // says nothing under the duration field. The mirror, then both at once.
    @Test
    fun runLandDialogShowsEachFieldErrorUnderItsOwnField() {
        val distanceOnly = parseRunEntry("abc", "") as RunEntry.Invalid
        val durationOnly = parseRunEntry("5.2", "-3") as RunEntry.Invalid
        val bothBad = parseRunEntry("abc", "0") as RunEntry.Invalid
        val distanceError = distanceOnly.distanceError!!
        val durationError = durationOnly.minutesError!!
        val errors = mutableStateOf<RunEntry.Invalid?>(distanceOnly)

        composeRule.setContent {
            LiftoffTheme {
                RunLandDialogContent(
                    sortieNumber = 1, distanceUnit = DistanceUnit.MI, errors = errors.value,
                    onLand = { _, _ -> }, onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithText(distanceError).assertIsDisplayed()
        composeRule.onNodeWithText(durationError).assertDoesNotExist()

        composeRule.runOnIdle { errors.value = durationOnly }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(durationError).assertIsDisplayed()
        composeRule.onNodeWithText(distanceError).assertDoesNotExist()

        composeRule.runOnIdle { errors.value = bothBad }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(distanceError).assertIsDisplayed()
        composeRule.onNodeWithText(durationError).assertIsDisplayed()

        // Each error is drawn between its own field and the next one down, not under the other field.
        val fields = composeRule.onAllNodes(hasSetTextAction()).assertCountEquals(2)
        val distanceField = fields[0].getUnclippedBoundsInRoot()
        val durationField = fields[1].getUnclippedBoundsInRoot()
        val distanceLine = composeRule.onNodeWithText(distanceError).getUnclippedBoundsInRoot()
        val durationLine = composeRule.onNodeWithText(durationError).getUnclippedBoundsInRoot()
        assertTrue(
            "the distance error must sit under the distance field",
            distanceLine.top >= distanceField.bottom && distanceLine.bottom <= durationField.top,
        )
        assertTrue(
            "the duration error must sit under the duration field",
            durationLine.top >= durationField.bottom,
        )
    }
}
