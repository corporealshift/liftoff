package com.liftoff.app.ui.inflight

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.liftoff.app.ui.theme.Cream
import com.liftoff.app.ui.theme.DisplayTitle
import com.liftoff.app.ui.theme.DuoStripe
import com.liftoff.app.ui.theme.Eyebrow
import com.liftoff.app.ui.theme.InkRuledListRow
import com.liftoff.app.ui.theme.LiftoffType
import com.liftoff.app.ui.theme.Mustard
import com.liftoff.app.ui.theme.Muted
import com.liftoff.app.ui.theme.Teal

/**
 * A RUN sortie's Flight Plan. Same header as the no-checklist state, but the plan's own rows instead
 * of "No checklist for this sortie." — and no set counter, progress bar or set rows.
 */
@Composable
fun RunContent(
    run: InFlightState.Run,
    onOpenSortieNote: () -> Unit,
    message: String?,
    onLand: () -> Unit,
    onScrub: () -> Unit,
) {
    // Rows are numbered in sequence, and a row only exists when it has a value.
    val rows = buildList {
        run.kind?.let { add("Kind" to it) }
        run.targetDistance?.let { add("Target distance" to it) }
        run.targetPace?.let { add("Target pace" to it) }
        for (segment in run.segments) add(segment.description to segment.detail)
    }

    Column(modifier = Modifier.fillMaxSize().background(Cream)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Teal)
                .padding(top = 22.dp, start = 20.dp, end = 20.dp, bottom = 18.dp),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Eyebrow(text = run.eyebrow, color = Mustard)
                Spacer(Modifier.height(4.dp))
                DisplayTitle(text = run.title.uppercase(), color = Cream, style = LiftoffType.headerTitle())
            }
        }
        DuoStripe()
        SortieNoteStrip(note = run.sortieNote, onOpen = onOpenSortieNote)

        // A generated plan can carry 20 segments, so the body scrolls.
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
        ) {
            run.notesLine?.let {
                Text(
                    text = it,
                    style = LiftoffType.note().copy(color = Muted),
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            rows.forEachIndexed { index, (title, detail) ->
                InkRuledListRow(
                    index = "%02d".format(index + 1),
                    title = title,
                    detail = detail,
                )
            }
        }

        Footer(message = message, onLand = onLand, onScrub = onScrub)
    }
}
