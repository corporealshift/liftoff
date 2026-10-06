package com.liftoff.app.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun OffsetShadowBox(
    color: Color = Ink,
    offset: Dp = 4.dp,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.TopStart,
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            drawRect(color = color)
        }
        Box(
            modifier = Modifier.offset(offset).align(Alignment.Center),
            content = content,
        )
    }
}

private fun Modifier.offset(offset: Dp): Modifier = this
