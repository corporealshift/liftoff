package com.liftoff.app.ui.inflight

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.liftoff.app.ui.control.LabeledMultiLineTextField
import com.liftoff.app.ui.theme.*

/**
 * Themed note editor shown over the In-Flight list. The shell matches StepperDialog;
 * the draft lives in [NoteDialogContent] so the field can be tested outside a Dialog.
 */
@Composable
internal fun NoteDialog(
    title: String,
    initial: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        NoteDialogContent(
            title = title,
            initial = initial,
            onSave = onSave,
            onDismiss = onDismiss,
        )
    }
}

@Composable
internal fun NoteDialogContent(
    title: String,
    initial: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember(title) { mutableStateOf(initial) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Paper, CardShape)
            .border(BorderStroke(2.dp, Ink), CardShape)
            .padding(16.dp),
    ) {
        Text(
            text = title,
            style = LiftoffType.cardTitle(),
            color = Ink,
            modifier = Modifier.padding(bottom = 16.dp),
        )
        LabeledMultiLineTextField(
            label = "Note",
            value = draft,
            onChange = { draft = it },
        )
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Both buttons fillMaxWidth() after the caller's modifier, so each needs a weight.
            PrimaryButton(
                text = "Save",
                height = 48.dp,
                onClick = { onSave(draft) },
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
