package com.liftoff.app.ui.launchpad

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
 * Confirmation for swapping the current sortie's type, in the same Paper-card shell as ScrubDialog. Plain values
 * and callbacks and no view model, and the content is drawn apart from the Dialog so its wording can be tested
 * outside a Dialog window, as ScrubDialogContent's is.
 */
@Composable
internal fun SwapDialog(
    sortieNumber: Int,
    currentType: SortieType,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        SwapDialogContent(
            sortieNumber = sortieNumber,
            currentType = currentType,
            onConfirm = onConfirm,
            onDismiss = onDismiss,
        )
    }
}

/** §9: the confirmation names the sortie and the type it would become, and says what happens to its plan. */
@Composable
internal fun SwapDialogContent(
    sortieNumber: Int,
    currentType: SortieType,
    onConfirm: () -> Unit,
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
            text = "Swap sortie $sortieNumber to a ${swappedTypeName(currentType)}?",
            style = LiftoffType.cardTitle(),
            color = Ink,
        )
        Text(
            text = "Its Flight Plan is deleted, the pattern's L and R swap places, and the coach is asked for " +
                "a plan for the new type.",
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
                text = "Swap",
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
