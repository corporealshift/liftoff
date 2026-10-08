package com.liftoff.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.liftoff.app.ui.theme.Muted
import com.liftoff.app.ui.theme.TitleBlock

/** A shared placeholder screen: eyebrow + display title, then one line of body text. */
@Composable
fun PlaceholderScreen(
    eyebrow: String,
    title: String,
    line: String,
) {
    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(top = 18.dp, start = 20.dp, end = 20.dp),
    ) {
        TitleBlock(eyebrow = eyebrow, title = title)
        Spacer(Modifier.height(12.dp))
        Text(text = line, style = MaterialTheme.typography.bodyLarge, color = Muted)
    }
}
