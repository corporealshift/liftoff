package com.liftoff.app.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The dimensions the three buttons draw with. */
internal val PrimaryButtonHeightDp = 72.dp
internal val InkButtonHeightDp = 64.dp
internal val ButtonBorderWidthDp = 2.dp
internal val ButtonIconSizeDp = 26.dp
internal val ButtonIconLabelGapDp = 12.dp
internal val TextButtonMinHeightDp = 44.dp
internal val TextButtonUnderlineWidthDp = 1.dp
internal val TextButtonUnderlineGapDp = 4.dp

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: Painter? = null,
    iconAtEnd: Boolean = false,
    height: Dp = PrimaryButtonHeightDp,
    labelStyle: TextStyle = LiftoffType.launchLabel(),
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed = interactionSource.collectIsPressedAsState()

    OffsetShadowBox(
        color = Ink,
        offset = OffsetShadowDefaultOffsetDp,
        modifier = modifier
            .fillMaxWidth()
            .height(height),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(if (isPressed.value) RedPressed else Red, ButtonShape)
                .border(BorderStroke(ButtonBorderWidthDp, Ink), ButtonShape)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    role = Role.Button,
                    onClick = { onClick() },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (icon != null && !iconAtEnd) {
                    Icon(
                        painter = icon,
                        contentDescription = null,
                        modifier = Modifier.size(ButtonIconSizeDp),
                        tint = White,
                    )
                    Spacer(Modifier.width(ButtonIconLabelGapDp))
                }
                Text(
                    text = text.uppercase(),
                    style = labelStyle.copy(color = White),
                )
                if (icon != null && iconAtEnd) {
                    Spacer(Modifier.width(ButtonIconLabelGapDp))
                    Icon(
                        painter = icon,
                        contentDescription = null,
                        modifier = Modifier.size(ButtonIconSizeDp),
                        tint = White,
                    )
                }
            }
        }
    }
}

@Composable
fun InkButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: Painter? = null,
    iconAtEnd: Boolean = false,
    height: Dp = InkButtonHeightDp,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed = interactionSource.collectIsPressedAsState()

    OffsetShadowBox(
        color = if (isPressed.value) RedPressed else Red,
        offset = OffsetShadowDefaultOffsetDp,
        modifier = modifier
            .fillMaxWidth()
            .height(height),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Ink, ButtonShape)
                .border(BorderStroke(ButtonBorderWidthDp, Ink), ButtonShape)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    role = Role.Button,
                    onClick = { onClick() },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (icon != null && !iconAtEnd) {
                    Icon(
                        painter = icon,
                        contentDescription = null,
                        modifier = Modifier.size(ButtonIconSizeDp),
                        tint = Cream,
                    )
                    Spacer(Modifier.width(ButtonIconLabelGapDp))
                }
                Text(
                    text = text.uppercase(),
                    style = LiftoffType.landLabel().copy(color = Cream),
                )
                if (icon != null && iconAtEnd) {
                    Spacer(Modifier.width(ButtonIconLabelGapDp))
                    Icon(
                        painter = icon,
                        contentDescription = null,
                        modifier = Modifier.size(ButtonIconSizeDp),
                        tint = Cream,
                    )
                }
            }
        }
    }
}

@Composable
fun UnderlinedTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed = interactionSource.collectIsPressedAsState()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = TextButtonMinHeightDp)
            .padding(horizontal = 8.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = { onClick() },
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        var baselinePx by remember { mutableStateOf(0f) }

        Text(
            text = text.uppercase(),
            style = LiftoffType.textButton().copy(color = if (isPressed.value) Red else Ink),
            modifier = Modifier.drawBehind {
                val y = baselinePx + TextButtonUnderlineGapDp.toPx()
                if (baselinePx > 0) {
                    drawLine(
                        color = if (isPressed.value) Red else Ink,
                        strokeWidth = TextButtonUnderlineWidthDp.toPx(),
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

@Preview
@Composable
private fun PrimaryButtonPreview() {
    if (LocalInspectionMode.current) {
        Column(modifier = Modifier.background(Cream).padding(20.dp)) {
            PrimaryButton(text = "LAUNCH", onClick = {})
        }
    }
}

@Preview
@Composable
private fun InkButtonPreview() {
    if (LocalInspectionMode.current) {
        Column(modifier = Modifier.background(Cream).padding(20.dp)) {
            InkButton(text = "LAND", onClick = {})
        }
    }
}

@Preview
@Composable
private fun UnderlinedTextButtonPreview() {
    if (LocalInspectionMode.current) {
        Column(modifier = Modifier.background(Cream).padding(20.dp)) {
            UnderlinedTextButton(text = "Learn more", onClick = {})
        }
    }
}
