package com.liftoff.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun TriStripe(
    modifier: Modifier = Modifier,
    red: Color = Red,
    mustard: Color = Mustard,
    teal: Color = Teal,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Box(modifier = Modifier.height(6.dp).background(red))
        Box(modifier = Modifier.height(6.dp).background(mustard))
        Box(modifier = Modifier.height(6.dp).background(teal))
    }
}

@Composable
fun DuoStripe(
    modifier: Modifier = Modifier,
    mustard: Color = Mustard,
    red: Color = Red,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Box(modifier = Modifier.height(5.dp).background(mustard))
        Box(modifier = Modifier.height(5.dp).background(red))
    }
}

@Composable
fun TriStripePreview() {
    if (LocalInspectionMode.current) {
        TriStripe(modifier = Modifier.background(Cream))
    }
}

@Composable
fun DuoStripePreview() {
    if (LocalInspectionMode.current) {
        DuoStripe(modifier = Modifier.background(Cream))
    }
}
