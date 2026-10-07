package com.liftoff.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp

@Composable
fun InkRuledListRow(
    index: String,
    title: String,
    detail: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 9.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = index,
                style = LiftoffType.index().copy(color = Red),
                modifier = Modifier.width(30.dp).alignByBaseline(),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = title,
                style = LiftoffType.exerciseName(),
                modifier = Modifier.weight(1f).alignByBaseline(),
            )
            if (detail != null) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = detail,
                    style = LiftoffType.load(),
                    modifier = Modifier.alignByBaseline(),
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Ink),
        ) {}
    }
}

@Composable
fun InkRuledListRowPreview() {
    if (LocalInspectionMode.current) {
        Column(modifier = Modifier.background(Cream)) {
            InkRuledListRow(index = "1", title = "Bench Press", detail = "4×8")
            InkRuledListRow(index = "2", title = "Squat", detail = "3×10")
            InkRuledListRow(index = "3", title = "Deadlift")
        }
    }
}
