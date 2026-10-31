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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.liftoff.app.settings.DistanceUnit
import com.liftoff.app.ui.control.LabeledTextField
import com.liftoff.app.ui.theme.CardShape
import com.liftoff.app.ui.theme.Ink
import com.liftoff.app.ui.theme.LiftoffType
import com.liftoff.app.ui.theme.Paper
import com.liftoff.app.ui.theme.PrimaryButton
import com.liftoff.app.ui.theme.UnderlinedTextButton

/**
 * Landing a run, with the distance and duration the owner chose to record. Same Paper-card shell as
 * LandConfirmDialog / ScrubDialogContent; the fields live in [RunLandDialogContent] so they can be
 * tested outside a Dialog, and the card reports what was typed rather than landing anything itself.
 */
@Composable
internal fun RunLandDialog(
    sortieNumber: Int,
    distanceUnit: DistanceUnit,
    errors: RunEntry.Invalid?,
    onLand: (distance: String, minutes: String) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        RunLandDialogContent(
            sortieNumber = sortieNumber,
            distanceUnit = distanceUnit,
            errors = errors,
            onLand = onLand,
            onDismiss = onDismiss,
        )
    }
}

/** Each field is remembered blank, so both start empty every time the card enters composition. */
@Composable
internal fun RunLandDialogContent(
    sortieNumber: Int,
    distanceUnit: DistanceUnit,
    errors: RunEntry.Invalid?,
    onLand: (distance: String, minutes: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var distance by remember { mutableStateOf("") }
    var minutes by remember { mutableStateOf("") }

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
        LabeledTextField(
            label = "Distance (${distanceUnitWord(distanceUnit)})",
            value = distance,
            onChange = { distance = it },
            keyboardType = KeyboardType.Decimal,
            error = errors?.distanceError,
        )
        LabeledTextField(
            label = "Duration (min)",
            value = minutes,
            onChange = { minutes = it },
            keyboardType = KeyboardType.Decimal,
            error = errors?.minutesError,
        )
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Both buttons fillMaxWidth() after the caller's modifier, so each needs a weight.
            // Land is always enabled: both fields are optional, so blank lands with both null.
            PrimaryButton(
                text = "Land",
                height = 48.dp,
                onClick = { onLand(distance, minutes) },
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

/** The lowercase unit word the rest of the screen prints (`5 mi`), not the enum's `MI`. */
private fun distanceUnitWord(unit: DistanceUnit): String = when (unit) {
    DistanceUnit.MI -> "mi"
    DistanceUnit.KM -> "km"
}
