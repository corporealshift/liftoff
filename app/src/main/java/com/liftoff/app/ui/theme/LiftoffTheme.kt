package com.liftoff.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

@Composable
fun LiftoffTheme(
    content: @Composable () -> Unit,
) {
    // Light theme only — ignore system dark mode.
    MaterialTheme(
        colorScheme = LiftoffColorScheme(),
        typography = LiftoffTypography(),
        shapes = LiftoffShapes,
        content = content,
    )
}
