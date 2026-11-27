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
import com.liftoff.app.data.SortieType
import com.liftoff.app.ui.theme.CardShape
import com.liftoff.app.ui.theme.Ink
import com.liftoff.app.ui.theme.LiftoffType
import com.liftoff.app.ui.theme.Paper
import com.liftoff.app.ui.theme.PrimaryButton
import com.liftoff.app.ui.theme.UnderlinedTextButton

/**
 * Confirmation for adding a sortie to the current week, in the same Paper-card shell as ResetWeekDialog. The type
 * is chosen here rather than by the coach, so the two buttons are the answer; the content is drawn apart from the
 * Dialog so its wording can be tested outside a Dialog window, as ScrubDialogContent's is.
 */
@Composable
internal fun AddSortieDialog(
    onAdd: (SortieType) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        AddSortieDialogContent(onAdd = onAdd, onDismiss = onDismiss)
    }
}

@Composable
internal fun AddSortieDialogContent(
    onAdd: (SortieType) -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Paper, CardShape)
            .border(BorderStroke(2.dp, Ink), CardShape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Add a sortie to this week?",
            style = LiftoffType.cardTitle(),
            color = Ink,
        )
        Text(
            text = "It joins the end of the pattern as a new sortie. Its Flight Plan is prepared when it becomes " +
                "the current sortie.",
            style = LiftoffType.note(),
            color = Ink,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Both buttons fillMaxWidth() after the caller's modifier, so each needs a weight.
            PrimaryButton(
                text = "Add lift",
                height = 48.dp,
                onClick = { onAdd(SortieType.LIFT) },
                modifier = Modifier.weight(1f),
            )
            PrimaryButton(
                text = "Add run",
                height = 48.dp,
                onClick = { onAdd(SortieType.RUN) },
                modifier = Modifier.weight(1f),
            )
        }
        UnderlinedTextButton(text = "Cancel", onClick = onDismiss)
    }
}
