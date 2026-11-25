package com.liftoff.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

@Composable
fun Eyebrow(
    text: String,
    color: Color = Muted,
) {
    Text(
        text = text.uppercase(),
        style = LiftoffType.eyebrow().copy(color = color),
    )
}

// The line cap and the layout callback default to Text's own (no maximum, Clip, no callback), so a caller
// that does not ask for one draws exactly what it drew before they existed.
@Composable
fun DisplayTitle(
    text: String,
    style: TextStyle = LiftoffType.screenTitle(),
    color: Color = Ink,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
) {
    Text(
        text = text.uppercase(),
        style = style.copy(color = color),
        maxLines = maxLines,
        overflow = overflow,
        onTextLayout = onTextLayout,
    )
}

@Composable
fun TitleBlock(
    eyebrow: String,
    title: String,
    eyebrowColor: Color = Muted,
    titleColor: Color = Ink,
    titleStyle: TextStyle = LiftoffType.screenTitle(),
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
) {
    Column {
        Eyebrow(text = eyebrow, color = eyebrowColor)
        Spacer(Modifier.height(4.dp))
        DisplayTitle(
            text = title,
            style = titleStyle,
            color = titleColor,
            maxLines = maxLines,
            overflow = overflow,
            onTextLayout = onTextLayout,
        )
    }
}

@Composable
fun Wordmark(
    color: Color = Ink,
) {
    Text(
        text = "LIFTOFF",
        style = LiftoffType.wordmark().copy(color = color),
    )
}

@Preview
@Composable
private fun HeadingsPreview() {
    if (LocalInspectionMode.current) {
        Column(modifier = Modifier.background(Cream).padding(20.dp)) {
            Eyebrow(text = "Section")
            Spacer(Modifier.height(16.dp))
            DisplayTitle(text = "Screen Title")
            Spacer(Modifier.height(16.dp))
            TitleBlock(eyebrow = "In-Flight", title = "Session")
            Spacer(Modifier.height(16.dp))
            Wordmark()
        }
    }
}
