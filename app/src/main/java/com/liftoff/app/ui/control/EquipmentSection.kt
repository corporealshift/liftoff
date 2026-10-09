package com.liftoff.app.ui.control

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.liftoff.app.data.Equipment
import com.liftoff.app.ui.theme.ButtonShape
import com.liftoff.app.ui.theme.CardShape
import com.liftoff.app.ui.theme.Cream
import com.liftoff.app.ui.theme.Ink
import com.liftoff.app.ui.theme.Muted
import com.liftoff.app.ui.theme.Paper
import com.liftoff.app.ui.theme.Red
import com.liftoff.app.ui.theme.InkButton
import com.liftoff.app.ui.theme.InkRuledListRow
import com.liftoff.app.ui.theme.LiftoffType
import com.liftoff.app.ui.theme.UnderlinedTextButton

@Composable
internal fun EquipmentSection(
    state: EquipmentState,
    vm: EquipmentViewModel,
) {
    if (state.editingId != null) {
        // Inline edit form — takes the full width of the section card.
        val item = state.active.find { it.id == state.editingId }
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            // Key read-only
            Text(
                text = "Key",
                style = LiftoffType.note(),
                color = Ink,
                modifier = Modifier.padding(start = 16.dp, bottom = 4.dp),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Paper, ButtonShape)
                    .border(BorderStroke(1.dp, Ink), ButtonShape)
                    .padding(12.dp),
            ) {
                Text(
                    text = item?.key ?: "",
                    style = LiftoffType.index(),
                    color = Muted,
                )
            }
            Spacer(Modifier.height(8.dp))

            LabeledTextField(
                label = "Name",
                value = state.editName,
                onChange = vm::setEditName,
                error = state.editNameError,
            )
            LabeledMultiLineTextField(
                label = "Notes",
                value = state.editNotes,
                onChange = vm::setEditNotes,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                InkButton("SAVE", onClick = vm::submitEdit)
                UnderlinedTextButton("Cancel", onClick = vm::cancelEdit)
            }
        }
    } else {
        // Active items list.
        if (state.active.isEmpty()) {
            Text(
                text = "No equipment yet.",
                style = LiftoffType.note(),
                color = Ink,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        } else {
            state.active.forEachIndexed { index, item ->
                InkRuledListRow(
                    index = "${index + 1}",
                    title = listOf(item.key, item.name, item.notes)
                        .filter { it.isNotBlank() }
                        .joinToString(" — "),
                    actions = {
                        UnderlinedTextButton(text = "Edit", onClick = { vm.startEdit(item) })
                        UnderlinedTextButton(text = "Deactivate", onClick = { vm.deactivate(item.id) })
                    },
                )
            }
        }

        // Add form toggle.
        Spacer(Modifier.height(8.dp))
        if (!state.adding) {
            UnderlinedTextButton("Add equipment", onClick = vm::startAdd)
        } else {
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                LabeledTextField(
                    label = "Key",
                    value = state.addKey,
                    onChange = vm::setAddKey,
                    keyboardType = KeyboardType.Text,
                    error = state.addKeyError,
                )
                LabeledTextField(
                    label = "Name",
                    value = state.addName,
                    onChange = vm::setAddName,
                    error = state.addNameError,
                )
                LabeledMultiLineTextField(
                    label = "Notes (optional)",
                    value = state.addNotes,
                    onChange = vm::setAddNotes,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    InkButton("ADD", onClick = vm::submitAdd)
                    UnderlinedTextButton("Cancel", onClick = vm::cancelAdd)
                }
            }
        }

        // Show / Hide deactivated.
        Spacer(Modifier.height(8.dp))
        if (!state.showDeactivated) {
            UnderlinedTextButton("Show deactivated", onClick = { vm.setShowDeactivated(true) })
        } else {
            Column(modifier = Modifier.fillMaxWidth()) {
                UnderlinedTextButton("Hide deactivated", onClick = { vm.setShowDeactivated(false) })
                if (state.deactivated.isNotEmpty()) {
                    state.deactivated.forEachIndexed { index, item ->
                        InkRuledListRow(
                            index = "${index + 1}",
                            title = listOf(item.key, item.name, item.notes)
                                .filter { it.isNotBlank() }
                                .joinToString(" — "),
                            actions = {
                                UnderlinedTextButton(text = "Reactivate", onClick = { vm.reactivate(item.id) })
                            },
                        )
                    }
                } else {
                    Text(
                        text = "No deactivated equipment.",
                        style = LiftoffType.note(),
                        color = Ink,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }
        }
    }
}

@Preview
@Composable
private fun EquipmentSectionPreview() {
    if (LocalInspectionMode.current) {
        Column(modifier = Modifier.background(Cream).padding(16.dp)) {
            Text("Preview only — no interactive VM.", style = LiftoffType.note(), color = Muted)
        }
    }
}
