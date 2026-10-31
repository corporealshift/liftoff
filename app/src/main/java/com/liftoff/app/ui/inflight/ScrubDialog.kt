package com.liftoff.app.ui.inflight

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.liftoff.app.ui.control.LabeledTextField
import com.liftoff.app.ui.theme.CardShape
import com.liftoff.app.ui.theme.Ink
import com.liftoff.app.ui.theme.LiftoffType
import com.liftoff.app.ui.theme.Paper
import com.liftoff.app.ui.theme.PrimaryButton
import com.liftoff.app.ui.theme.UnderlinedTextButton

/**
 * Confirmation for scrubbing a sortie, with the optional reason the owner gives it.
 * Same Paper-card shell as StepperDialog / NoteDialogContent, plain values and callbacks and no
 * view model, so the Launchpad can adopt the same dialog later. The name does not clash with
 * LaunchpadScreen's private ScrubDialog, which lives in another package.
 */
@Composable
internal fun ScrubDialog(
    sortieNumber: Int,
    onConfirm: (reason: String) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        ScrubDialogContent(
            sortieNumber = sortieNumber,
            onConfirm = onConfirm,
            onDismiss = onDismiss,
        )
    }
}

/** The draft lives here so the reason field can be tested outside a Dialog, as NoteDialogContent is. */
@Composable
internal fun ScrubDialogContent(
    sortieNumber: Int,
    onConfirm: (reason: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var reason by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Paper, CardShape)
            .border(BorderStroke(2.dp, Ink), CardShape)
            .padding(16.dp),
    ) {
        Text(
            text = "Scrub sortie $sortieNumber?",
            style = LiftoffType.cardTitle(),
            color = Ink,
            modifier = Modifier.padding(bottom = 16.dp),
        )
        LabeledTextField(
            label = "Reason (optional)",
            value = reason,
            onChange = { reason = it },
        )
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Both buttons fillMaxWidth() after the caller's modifier, so each needs a weight.
            PrimaryButton(
                text = "Scrub",
                height = 48.dp,
                onClick = { onConfirm(reason) },
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
