package com.liftoff.app.ui.landed

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.liftoff.app.AppContainer
import com.liftoff.app.ui.theme.Cream
import com.liftoff.app.ui.theme.Ink
import com.liftoff.app.ui.theme.InkRuledListRow
import com.liftoff.app.ui.theme.LiftoffType
import com.liftoff.app.ui.theme.Muted
import com.liftoff.app.ui.theme.TitleBlock

@Composable
fun LandedScreen(
    container: AppContainer,
) {
    val scope = rememberCoroutineScope()
    val viewModel = remember(container) {
        LandedViewModel(container.database, container.settingsStore, scope)
    }

    val history by viewModel.history.collectAsStateWithLifecycle()

    // The row tap is wired to nothing until this screen's detail view exists.
    LandedHistoryContent(state = history, onSelect = {})
}

@Composable
private fun LandedHistoryContent(state: LandedHistoryState, onSelect: (Long) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Cream)
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        // The title block is composed even while the first read is running, so the empty state
        // cannot flash over real data.
        TitleBlock(eyebrow = "History", title = "Landed")
        Spacer(Modifier.height(16.dp))

        when (state) {
            LandedHistoryState.Loading -> Unit

            LandedHistoryState.Empty -> Text(
                text = "No history yet.",
                style = LiftoffType.note().copy(color = Muted),
            )

            is LandedHistoryState.Weeks -> state.weeks.forEach { week ->
                WeekSection(week = week, onSelect = onSelect)
            }
        }
    }
}

@Composable
private fun WeekSection(week: HistoryWeek, onSelect: (Long) -> Unit) {
    Text(text = week.head, style = LiftoffType.sectionHead(), color = Ink)
    Spacer(Modifier.height(4.dp))

    week.rows.forEach { row ->
        val reason = row.scrubReason
        // The reason is a note, not a second row: it sits between the row and its ink rule.
        val actions: (@Composable RowScope.() -> Unit)? = if (reason == null) null else {
            {
                Text(
                    text = reason,
                    style = LiftoffType.note().copy(color = Muted),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        InkRuledListRow(
            index = row.indexLabel,
            title = row.title,
            detail = "${row.dateLabel} · ${row.typeLabel} · ${row.stateLabel}",
            modifier = Modifier.clickable { onSelect(row.sortieId) },
            actions = actions,
        )
    }
    Spacer(Modifier.height(20.dp))
}
