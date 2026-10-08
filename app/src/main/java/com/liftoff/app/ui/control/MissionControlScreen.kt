package com.liftoff.app.ui.control

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.liftoff.app.ui.theme.ButtonShape
import com.liftoff.app.ui.theme.Ink
import com.liftoff.app.ui.theme.Muted
import com.liftoff.app.ui.theme.TitleBlock

@Composable
fun MissionControlScreen(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = 16.dp, start = 20.dp, end = 20.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier
                    .width(44.dp)
                    .height(44.dp)
                    .border(2.dp, Ink, ButtonShape),
                shape = ButtonShape,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Ink,
                )
            }
            Spacer(Modifier.width(16.dp))
            TitleBlock(eyebrow = "Settings", title = "Mission Control")
        }
        Spacer(Modifier.height(20.dp))
        MissionControlContent()
    }
}

/** Placeholder slot for settings and equipment management — next brief. */
@Composable
private fun MissionControlContent() {
    Text(text = "Settings will appear here.", style = MaterialTheme.typography.bodyLarge, color = Muted)
}

@Preview
@Composable
private fun MissionControlScreenPreview() {
    if (LocalInspectionMode.current) {
        Column(modifier = Modifier.background(Color.White)) {
            MissionControlScreen(onBack = {})
        }
    }
}
