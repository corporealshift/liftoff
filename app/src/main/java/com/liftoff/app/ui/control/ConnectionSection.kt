package com.liftoff.app.ui.control

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.liftoff.app.ui.theme.Ink
import com.liftoff.app.ui.theme.LiftoffType
import com.liftoff.app.ui.theme.Muted

/**
 * The CONNECTION card's own action, drawn entirely from [ConnectionState]: the "Test connection"
 * button, the busy/result row under it, and the muted line naming the setting the daemon could not
 * be reached through. MissionControlScreen wraps it in `SectionCard("CONNECTION")`, below the fields.
 */
@Composable
internal fun ConnectionSection(
    state: ConnectionState,
    onTestConnection: () -> Unit,
) {
    // The card's one action goes quiet while it runs; the row below says that it is running.
    BackupTextButton(
        text = "Test connection",
        onClick = onTestConnection,
        enabled = state !is ConnectionState.Testing,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )

    val message = state.message
    if (message != null) {
        ConnectionStatusRow(message = message, color = connectionStatusColor(state))
    }

    // The client's own reason rides under the row: which setting the daemon could not be reached through.
    state.note?.let { reason ->
        Text(
            text = reason,
            style = LiftoffType.note(),
            color = Muted,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )
    }
}

/** The row's colour is the state's, not the message's: busy reads muted, an answer reads ink. */
private fun connectionStatusColor(state: ConnectionState): Color = when (state) {
    ConnectionState.Idle -> Ink
    ConnectionState.Testing -> Muted
    is ConnectionState.Result -> Ink
}

/** The busy/result row: the state's own message, ruled like the BACKUP card's row. */
@Composable
private fun ConnectionStatusRow(message: String, color: Color) {
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
