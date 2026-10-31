package com.liftoff.app.ui.control

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.liftoff.app.ui.theme.Ink
import com.liftoff.app.ui.theme.InkRuledListRow
import com.liftoff.app.ui.theme.LiftoffType
import com.liftoff.app.ui.theme.Muted
import com.liftoff.app.ui.theme.Red

/**
 * The BACKUP card's content, drawn entirely from [BackupState]: the Export and Import rows, the
 * busy/result row, and the line naming what the file does not hold. MissionControlScreen wraps it
 * in `SectionCard("BACKUP")`, the way it wraps [EquipmentSection].
 */
@Composable
internal fun BackupSection(
    state: BackupState,
    onExport: () -> Unit,
    onImport: () -> Unit,
) {
    // Both actions go quiet while either one is running; the row below says which.
    val actionsEnabled = state !is BackupState.Busy

    InkRuledListRow(
        index = "1",
        title = "Export the whole database to a JSON file",
        actions = {
            BackupTextButton(
                text = "Export",
                onClick = onExport,
                enabled = actionsEnabled,
                modifier = Modifier.weight(1f),
            )
        },
    )
    InkRuledListRow(
        index = "2",
        title = "Import a backup file into an empty database",
        actions = {
            BackupTextButton(
                text = "Import",
                onClick = onImport,
                enabled = actionsEnabled,
                modifier = Modifier.weight(1f),
            )
        },
    )

    val message = state.message
    if (message != null) {
        BackupStatusRow(message = message, color = statusColor(state))
    }

    // The half of what the owner sees that a restore cannot bring back.
    Text(
        text = "Settings and the daemon token are not in this file.",
        style = LiftoffType.note(),
        color = Muted,
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
    )
}

private fun statusColor(state: BackupState): Color = when (state) {
    BackupState.Idle -> Ink
    is BackupState.Busy -> Muted
    is BackupState.Succeeded -> Ink
    is BackupState.Failed -> Red
}

/** The busy/result row: the state's own message, ruled like the action rows above it. */
@Composable
private fun BackupStatusRow(message: String, color: Color) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = message,
            style = LiftoffType.exerciseName(),
            color = color,
            modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Ink),
        ) {}
    }
}

/**
 * `ui.theme`'s UnderlinedTextButton with the one thing it lacks: an enabled flag. Same Box, same
 * label style, same underline drawn from the text baseline — Ink normally, Muted when disabled.
 */
@Composable
internal fun BackupTextButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed = interactionSource.collectIsPressedAsState()
    val color = when {
        !enabled -> Muted
        isPressed.value -> Red
        else -> Ink
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .padding(horizontal = 8.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        var baselinePx by remember { mutableStateOf(0f) }

        Text(
            text = text.uppercase(),
            style = LiftoffType.textButton().copy(color = color),
            modifier = Modifier.drawBehind {
                if (baselinePx > 0) {
                    val y = baselinePx + 4.dp.toPx()
                    drawLine(
                        color = color,
                        strokeWidth = 1.dp.toPx(),
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                    )
                }
            },
            onTextLayout = { result ->
                if (result.lineCount > 0) {
                    baselinePx = result.getLineBaseline(result.lineCount - 1)
                }
            },
        )
    }
}
