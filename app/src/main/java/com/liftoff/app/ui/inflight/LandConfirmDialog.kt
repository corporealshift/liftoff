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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.liftoff.app.ui.theme.CardShape
import com.liftoff.app.ui.theme.Ink
import com.liftoff.app.ui.theme.LiftoffType
import com.liftoff.app.ui.theme.Muted
import com.liftoff.app.ui.theme.Paper
import com.liftoff.app.ui.theme.PrimaryButton
import com.liftoff.app.ui.theme.UnderlinedTextButton

/**
 * Confirmation asked before landing a sortie that still has open sets. Same Paper-card shell as
 * StepperDialog / NoteDialogContent, plain values and callbacks and no view model, so the Launchpad
 * can adopt the same dialog later. The card itself is [LandConfirmDialogContent], so its wording can
 * be tested outside a Dialog.
 */
@Composable
internal fun LandConfirmDialog(
    openCount: Int,
    sortieNumber: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        LandConfirmDialogContent(
            openCount = openCount,
            sortieNumber = sortieNumber,
            onConfirm = onConfirm,
            onDismiss = onDismiss,
        )
    }
}

@Composable
internal fun LandConfirmDialogContent(
    openCount: Int,
    sortieNumber: Int,
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
            text = "Land sortie $sortieNumber?",
            style = LiftoffType.cardTitle(),
            color = Ink,
            modifier = Modifier.padding(bottom = 16.dp),
        )
        // Singular for one set: "1 sets unchecked." reads wrong.
        Text(
            text = if (openCount == 1) "1 set unchecked." else "$openCount sets unchecked.",
            style = LiftoffType.exerciseName(),
            color = Ink,
        )
        Text(
            text = "They will be recorded as not done.",
            style = LiftoffType.note(),
            color = Muted,
            modifier = Modifier.padding(top = 4.dp),
        )
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Both buttons fillMaxWidth() after the caller's modifier, so each needs a weight.
            PrimaryButton(
                text = "Land",
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
