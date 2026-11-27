package com.liftoff.app.ui.mission

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.liftoff.app.data.SortieType
import com.liftoff.app.ui.theme.LiftoffTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * AddSortieDialogContent in the main window rather than inside a Dialog, the shape LandScrubDialogTest uses:
 * the type is chosen by the buttons themselves, so what each one hands back is the thing to prove. The labels are
 * matched ignoring case because PrimaryButton and UnderlinedTextButton draw their text uppercased.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h2000dp")
class AddSortieDialogTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val note = "It joins the end of the pattern as a new sortie. Its Flight Plan is prepared when it " +
        "becomes the current sortie."

    @Test
    fun theDialogNamesTheWeekAndSaysWhereTheNewSortieGoes() {
        composeRule.setContent {
            LiftoffTheme {
                AddSortieDialogContent(onAdd = {}, onDismiss = {})
            }
        }

        composeRule.onNode(hasText("Add a sortie to this week?")).assertIsDisplayed()
        composeRule.onNode(hasText(note)).assertIsDisplayed()
    }

    @Test
    fun eachButtonAddsItsOwnTypeAndCancelAddsNothing() {
        val added = mutableListOf<SortieType>()
        var dismissed = 0

        composeRule.setContent {
            LiftoffTheme {
                AddSortieDialogContent(
                    onAdd = { added += it },
                    onDismiss = { dismissed++ },
                )
            }
        }

        composeRule.onNode(hasText("Add lift", ignoreCase = true)).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { added.isNotEmpty() }
        composeRule.onNode(hasText("Add run", ignoreCase = true)).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { added.size > 1 }

        assertEquals(listOf(SortieType.LIFT, SortieType.RUN), added)
        assertEquals(0, dismissed)

        composeRule.onNode(hasText("Cancel", ignoreCase = true)).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { dismissed > 0 }
        assertEquals(1, dismissed)
        assertEquals(listOf(SortieType.LIFT, SortieType.RUN), added)
    }
}
