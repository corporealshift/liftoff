package com.liftoff.app.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Draws a solid offset shadow behind its content.
 *
 * Translates the shape's outline by [offset] in x and y, then fills it with [color].
 * No blur. Takes no extra layout space (like CSS box-shadow).
 */
fun Modifier.offsetShadow(
    color: Color = Ink,
    shape: Shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
    offset: Dp = 4.dp,
): Modifier = this then Modifier.drawBehind {
    val outline = shape.createOutline(size, layoutDirection, this)
    val dx = offset.toPx()
    val dy = offset.toPx()

    when (outline) {
        is Outline.Rectangle -> drawRect(color = color, topLeft = Offset(dx, dy))
        is Outline.Rounded -> {
            val rr = outline.roundRect
            // Build a path for the shifted round rect to handle all corner radii
            val path = Path().apply { addRoundRect(rr.shifted(Offset(dx, dy))) }
            drawPath(path = path, color = color)
        }
        is Outline.Generic -> {
            val path = Path().apply { addPath(outline.path, Offset(dx, dy)) }
            drawPath(path = path, color = color)
        }
    }
}

private fun androidx.compose.ui.geometry.RoundRect.shifted(offset: Offset): androidx.compose.ui.geometry.RoundRect {
    return androidx.compose.ui.geometry.RoundRect(
        left = this.left + offset.x,
        top = this.top + offset.y,
        right = this.right + offset.x,
        bottom = this.bottom + offset.y,
        topLeftCornerRadius = this.topLeftCornerRadius,
        topRightCornerRadius = this.topRightCornerRadius,
        bottomRightCornerRadius = this.bottomRightCornerRadius,
        bottomLeftCornerRadius = this.bottomLeftCornerRadius,
    )
}

@Composable
fun OffsetShadowBox(
    color: Color = Ink,
    offset: Dp = 4.dp,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier.offsetShadow(color = color, offset = offset),
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
