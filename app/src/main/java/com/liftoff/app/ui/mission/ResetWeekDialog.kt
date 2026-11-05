package com.liftoff.app.ui.mission

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.liftoff.app.ui.theme.CardShape
import com.liftoff.app.ui.theme.Ink
import com.liftoff.app.ui.theme.LiftoffType
import com.liftoff.app.ui.theme.Paper
import com.liftoff.app.ui.theme.PrimaryButton
import com.liftoff.app.ui.theme.UnderlinedTextButton

/** Confirmation for Reset week, in the same Paper-card shell as the In-Flight ScrubDialog. */
@Composable
internal fun ResetWeekDialog(
    recordedCount: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        ResetWeekDialogContent(recordedCount, onConfirm, onDismiss)
    }
}

/** Apart from the Dialog so the wording can be tested, as ScrubDialogContent is. */
@Composable
internal fun ResetWeekDialogContent(
    recordedCount: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Paper, CardShape)
            .border(BorderStroke(2.dp, Ink), CardShape)
            .padding(16.dp),
    ) {
        Text(
            text = "Reset this week?",
            style = LiftoffType.cardTitle(),
            color = Ink,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        Text(
            text = resetWeekMessage(recordedCount),
            style = LiftoffType.note(),
            color = Ink,
        )
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Both buttons fillMaxWidth() after the caller's modifier, so each needs a weight.
            PrimaryButton(
                text = "Reset",
                height = 48.dp,
                onClick = onConfirm,
                modifier = Modifier.weight(1f),
            )
            UnderlinedTextButton(
                text = "Cancel",
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Names what is lost when anything has been landed or scrubbed, because that part is history. */
internal fun resetWeekMessage(recordedCount: Int): String {
    val lost = when (recordedCount) {
        0 -> "Every sortie and Flight Plan this week is deleted."
        1 -> "Every sortie and Flight Plan this week is deleted, including 1 landed or scrubbed sortie."
        else -> "Every sortie and Flight Plan this week is deleted, including $recordedCount landed or scrubbed sorties."
    }
    return "$lost The week goes back to a draft, so you can change the pattern and Confirm again. " +
        "This can't be undone."
}
