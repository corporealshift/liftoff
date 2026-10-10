package com.liftoff.app.ui.inflight

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.liftoff.app.AppContainer
import com.liftoff.app.ui.theme.Cream
import com.liftoff.app.ui.theme.LiftoffTheme
import com.liftoff.app.ui.theme.LiftoffType
import com.liftoff.app.ui.theme.Muted
import com.liftoff.app.ui.theme.TitleBlock
import com.liftoff.app.ui.theme.UnderlinedTextButton

@Composable
fun InFlightScreen(
    sortieId: Long,
    container: AppContainer,
    onBack: () -> Unit,
) {
    var planTitle by remember(sortieId) { mutableStateOf<String?>(null) }

    LaunchedEffect(sortieId) {
        val plan = container.database.flightPlanDao().getPlan(sortieId)
        planTitle = plan?.plan?.title
    }

    val sortieLabel = "SORTIE $sortieId"
    val eyebrow = "IN FLIGHT · $sortieLabel"
    val title = planTitle ?: "Sortie $sortieId"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Cream)
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(40.dp))
        TitleBlock(eyebrow = eyebrow, title = title)
        Spacer(Modifier.height(20.dp))
        Text(
            text = "The In-Flight checklist comes later.",
            style = LiftoffType.note().copy(color = Muted),
        )
        Spacer(Modifier.weight(1f))
        UnderlinedTextButton(text = "Back", onClick = onBack)
        Spacer(Modifier.height(20.dp))
    }
}
