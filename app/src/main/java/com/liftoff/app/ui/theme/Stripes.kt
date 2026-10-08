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
import androidx.compose.ui.tooling.preview.Preview
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
        Box(modifier = Modifier.height(6.dp).fillMaxWidth().background(red))
        Box(modifier = Modifier.height(6.dp).fillMaxWidth().background(mustard))
        Box(modifier = Modifier.height(6.dp).fillMaxWidth().background(teal))
    }
}

@Composable
fun DuoStripe(
    modifier: Modifier = Modifier,
    mustard: Color = Mustard,
    red: Color = Red,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Box(modifier = Modifier.height(5.dp).fillMaxWidth().background(mustard))
        Box(modifier = Modifier.height(5.dp).fillMaxWidth().background(red))
    }
}

@Preview
@Composable
private fun TriStripePreview() {
    if (LocalInspectionMode.current) {
        TriStripe(modifier = Modifier.background(Cream))
    }
}

@Preview
@Composable
private fun DuoStripePreview() {
    if (LocalInspectionMode.current) {
        DuoStripe(modifier = Modifier.background(Cream))
    }
}
