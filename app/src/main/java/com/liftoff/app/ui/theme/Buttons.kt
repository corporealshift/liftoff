package com.liftoff.app.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.VectorPainter? = null,
    iconAtEnd: Boolean = false,
    height: Dp = 72.dp,
    labelStyle: TextStyle = LiftoffType.launchLabel(),
) {
    var isPressed by remember { mutableStateOf(false) }

    OffsetShadowBox(
        color = Ink,
        offset = 4.dp,
        modifier = modifier
            .fillMaxWidth()
            .height(height),
    ) {
        Box(
            modifier = Modifier
                .background(if (isPressed) RedPressed else Red, ButtonShape)
                .border(BorderStroke(2.dp, Ink), ButtonShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
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
                        modifier = Modifier.size(26.dp),
                        tint = White,
                    )
                    Spacer(Modifier.width(12.dp))
                }
                Text(
                    text = text.uppercase(),
                    style = labelStyle.copy(color = White),
                )
                if (icon != null && iconAtEnd) {
                    Spacer(Modifier.width(12.dp))
                    Icon(
                        painter = icon,
                        contentDescription = null,
                        modifier = Modifier.size(26.dp),
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
    icon: androidx.compose.ui.graphics.vector.VectorPainter? = null,
    iconAtEnd: Boolean = false,
    height: Dp = 64.dp,
) {
    var isPressed by remember { mutableStateOf(false) }

    OffsetShadowBox(
        color = if (isPressed) RedPressed else Red,
        offset = 4.dp,
        modifier = modifier
            .fillMaxWidth()
            .height(height),
    ) {
        Box(
            modifier = Modifier
                .background(Ink, ButtonShape)
                .border(BorderStroke(2.dp, Ink), ButtonShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
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
                        modifier = Modifier.size(26.dp),
                        tint = Cream,
                    )
                    Spacer(Modifier.width(12.dp))
                }
                Text(
                    text = text.uppercase(),
                    style = LiftoffType.landLabel().copy(color = Cream),
                )
                if (icon != null && iconAtEnd) {
                    Spacer(Modifier.width(12.dp))
                    Icon(
                        painter = icon,
                        contentDescription = null,
                        modifier = Modifier.size(26.dp),
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
    var isPressed by remember { mutableStateOf(false) }
    var underlineY by remember { mutableStateOf(0f) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .padding(horizontal = 8.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = { onClick() },
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Column {
            Text(
                text = text.uppercase(),
                style = LiftoffType.textButton().copy(color = if (isPressed) Red else Ink),
                onTextLayout = { result ->
                    if (result.lineCount > 0) {
                        val lastLineBaseline = result.getLineBaseline(result.lineCount - 1)
                        underlineY = lastLineBaseline + 4f
                    }
                },
            )
            androidx.compose.foundation.Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp),
            ) {
                drawLine(
                    color = if (isPressed) Red else Ink,
                    strokeWidth = 1f,
                    start = androidx.compose.ui.geometry.Offset(0f, underlineY.coerceAtMost(size.height)),
                    end = androidx.compose.ui.geometry.Offset(size.width, underlineY.coerceAtMost(size.height)),
                )
            }
        }
    }
}

@Composable
fun PrimaryButtonPreview() {
    if (LocalInspectionMode.current) {
        Column(modifier = Modifier.background(Cream).padding(20.dp)) {
            PrimaryButton(text = "LAUNCH", onClick = {})
        }
    }
}

@Composable
fun InkButtonPreview() {
    if (LocalInspectionMode.current) {
        Column(modifier = Modifier.background(Cream).padding(20.dp)) {
            InkButton(text = "LAND", onClick = {})
        }
    }
}

@Composable
fun UnderlinedTextButtonPreview() {
    if (LocalInspectionMode.current) {
        Column(modifier = Modifier.background(Cream).padding(20.dp)) {
            UnderlinedTextButton(text = "Learn more", onClick = {})
        }
    }
}
