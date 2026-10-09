package com.liftoff.app.ui.control

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.liftoff.app.settings.DistanceUnit
import com.liftoff.app.settings.SettingsStore
import com.liftoff.app.settings.WeightUnit
import com.liftoff.app.ui.theme.ButtonShape
import com.liftoff.app.ui.theme.CardShape
import com.liftoff.app.ui.theme.Cream
import com.liftoff.app.ui.theme.Ink
import com.liftoff.app.ui.theme.Muted
import com.liftoff.app.ui.theme.Paper
import com.liftoff.app.ui.theme.Red
import com.liftoff.app.ui.theme.Rule
import com.liftoff.app.ui.theme.TitleBlock
import com.liftoff.app.ui.theme.White
import com.liftoff.app.ui.theme.LiftoffType
import kotlinx.coroutines.CoroutineScope

@Composable
fun MissionControlScreen(
    settingsStore: SettingsStore,
    scope: CoroutineScope,
    onBack: () -> Unit,
) {
    val vm = remember(settingsStore) { MissionControlViewModel(settingsStore, scope) }
    val state by vm.state.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(top = 16.dp, start = 20.dp, end = 20.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier
                    .width(44.dp)
                    .height(44.dp),
                shape = ButtonShape,
                border = BorderStroke(2.dp, Ink),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Ink,
                )
            }
            Spacer(Modifier.width(16.dp))
            TitleBlock(eyebrow = "Settings", title = "Mission Control")
        }
        Spacer(Modifier.height(20.dp))
        MissionControlContent(vm = vm, state = state)
    }
}

@Composable
private fun MissionControlContent(
    vm: MissionControlViewModel,
    state: MissionControlState,
) {
    SectionCard(title = "CONNECTION") {
        LabeledTextField(label = "Daemon host", value = state.host, onChange = vm::setHost)
        LabeledTextField(
            label = "Port",
            value = state.portText,
            onChange = vm::setPortText,
            keyboardType = KeyboardType.Number,
            error = state.portError,
        )
        LabeledTextFieldWithIcon(
            label = "Token",
            value = state.token,
            onChange = vm::setToken,
            visualTransformation = if (state.tokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = vm::toggleTokenVisibility) {
                    Icon(
                        imageVector = if (state.tokenVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (state.tokenVisible) "Hide token" else "Show token",
                        tint = Ink,
                    )
                }
            },
        )
    }

    SectionCard(title = "COACH") {
        LabeledTextField(
            label = "Coach workspace path",
            value = state.workspacePath,
            onChange = vm::setWorkspacePath,
        )
    }

    SectionCard(title = "MISSION") {
        PatternChips(vm = vm, pattern = state.pattern, error = state.patternError)
        LabeledTextField(
            label = "Sortie length (minutes)",
            value = state.sortieLengthText,
            onChange = vm::setSortieLengthText,
            keyboardType = KeyboardType.Number,
            error = state.sortieLengthError,
        )
        LabeledTextField(
            label = "History window (days)",
            value = state.historyWindowText,
            onChange = vm::setHistoryWindowText,
            keyboardType = KeyboardType.Number,
            error = state.historyWindowError,
        )
    }

    SectionCard(title = "UNITS") {
        UnitSegments(
            label = "Weight",
            selected = state.weightUnit,
            onSelect = vm::setWeightUnit,
            options = WeightUnit.entries.toTypedArray(),
        )
        UnitSegments(
            label = "Distance",
            selected = state.distanceUnit,
            onSelect = vm::setDistanceUnit,
            options = DistanceUnit.entries.toTypedArray(),
        )
    }

    SectionCard(title = "RUNS") {
        GenerateRunPlansCheckbox(onCheckedChange = vm::setGenerateRunPlans, checked = state.generateRunPlans)
    }

    SectionCard(title = "OBJECTIVES AND CONSTRAINTS") {
        LabeledMultiLineTextField(
            label = "Objectives",
            value = state.objectives,
            onChange = vm::setObjectives,
        )
        LabeledMultiLineTextField(
            label = "Constraints",
            value = state.constraints,
            onChange = vm::setConstraints,
        )
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Paper, CardShape)
            .border(BorderStroke(2.dp, Ink), CardShape)
            .padding(16.dp),
    ) {
        Text(
            text = title,
            style = LiftoffType.sectionHead(),
            color = Ink,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Column(modifier = Modifier.fillMaxWidth()) {
            content()
        }
    }
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun LabeledTextField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text,
    error: String? = null,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label, style = LiftoffType.note()) },
            shape = ButtonShape,
            modifier = Modifier.fillMaxWidth(),
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Ink,
                unfocusedBorderColor = Ink,
                disabledBorderColor = Ink,
                focusedContainerColor = Paper,
                unfocusedContainerColor = Paper,
                disabledContainerColor = Paper,
            ),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        )
        if (error != null) {
            Text(
                text = error,
                style = LiftoffType.note(),
                color = Red,
                modifier = Modifier.padding(start = 16.dp, top = 2.dp),
            )
        }
    }
}

@Composable
private fun LabeledTextFieldWithIcon(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    visualTransformation: androidx.compose.ui.text.input.VisualTransformation,
    trailingIcon: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label, style = LiftoffType.note()) },
            shape = ButtonShape,
            visualTransformation = visualTransformation,
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = trailingIcon,
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Ink,
                unfocusedBorderColor = Ink,
                disabledBorderColor = Ink,
                focusedContainerColor = Paper,
                unfocusedContainerColor = Paper,
                disabledContainerColor = Paper,
            ),
        )
    }
}

@Composable
private fun LabeledMultiLineTextField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            text = label,
            style = LiftoffType.note(),
            color = Ink,
            modifier = Modifier.padding(start = 16.dp, bottom = 4.dp),
        )
        BasicTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
                .background(Paper, ButtonShape)
                .border(BorderStroke(1.dp, Ink), ButtonShape)
                .padding(12.dp),
            textStyle = LiftoffType.exerciseName(),
        )
    }
}

@Composable
private fun PatternChips(
    vm: MissionControlViewModel,
    pattern: String,
    error: String?,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (i in pattern.indices) {
                val chip = pattern[i]
                val isSelected = chip == 'R'
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(
                            if (isSelected) Red else Paper,
                            RoundedCornerShape(50),
                        )
                        .border(BorderStroke(2.dp, Ink), RoundedCornerShape(50))
                        .clickable { vm.togglePatternChip(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = chip.toString(),
                        style = LiftoffType.index(),
                        color = if (isSelected) White else Ink,
                    )
                }
            }
        }
        // Add / Remove buttons on their own row
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedButton(
                onClick = vm::removePatternChip,
                enabled = vm.state.value.canRemoveChip,
                shape = ButtonShape,
                border = BorderStroke(2.dp, Ink),
                modifier = Modifier.size(44.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            ) {
                Text("-", style = LiftoffType.index(), color = Ink)
            }
            OutlinedButton(
                onClick = vm::addPatternChip,
                enabled = vm.state.value.canAddChip,
                shape = ButtonShape,
                border = BorderStroke(2.dp, Ink),
                modifier = Modifier.size(44.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            ) {
                Text("+", style = LiftoffType.index(), color = Ink)
            }
        }
        if (error != null) {
            Text(
                text = error,
                style = LiftoffType.note(),
                color = Red,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp),
            )
        }
    }
}

@Composable
private fun <T : Enum<T>> UnitSegments(
    label: String,
    selected: T,
    onSelect: (T) -> Unit,
    options: Array<T>,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            text = label,
            style = LiftoffType.note(),
            color = Ink,
            modifier = Modifier.padding(start = 16.dp, bottom = 4.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            options.forEach { option ->
                val isSelected = option == selected
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .background(
                            if (isSelected) Red else Paper,
                            ButtonShape,
                        )
                        .border(BorderStroke(2.dp, Ink), ButtonShape)
                        .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(option) }),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = option.name,
                        style = LiftoffType.textButton(),
                        color = if (isSelected) White else Ink,
                    )
                }
            }
        }
    }
}

@Composable
private fun GenerateRunPlansCheckbox(
    onCheckedChange: (Boolean) -> Unit,
    checked: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(Paper, ButtonShape)
            .border(BorderStroke(2.dp, Ink), ButtonShape)
            .clickable { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(
                    if (checked) Red else Paper,
                    ButtonShape,
                )
                .border(BorderStroke(2.dp, Ink), ButtonShape),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Text("✓", style = LiftoffType.index(), color = White)
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = "Generate run plans",
            style = LiftoffType.textButton(),
            color = Ink,
        )
    }
}

@Preview
@Composable
private fun MissionControlScreenPreview() {
    if (LocalInspectionMode.current) {
        Column(modifier = Modifier.background(Cream).padding(16.dp)) {
            val previewState = MissionControlState(
                loaded = true,
                host = "localhost",
                portText = "8737",
                token = "secret-token",
                workspacePath = "/home/coach/workspace",
                pattern = "RLRLR",
                sortieLengthText = "60",
                historyWindowText = "28",
                weightUnit = WeightUnit.LB,
                distanceUnit = DistanceUnit.MI,
                generateRunPlans = true,
                objectives = "Improve pace.",
                constraints = "No running on Sundays.",
                tokenVisible = false,
            )
            MissionControlContentPreview(state = previewState)
        }
    }
}

/** Render the section cards with sample state — no VM interaction needed. */
@Composable
private fun MissionControlContentPreview(state: MissionControlState) {
    SectionCard(title = "CONNECTION") {
        Text("Host: ${state.host}", style = LiftoffType.note())
        Text("Port: ${state.portText}", style = LiftoffType.note())
        Text("Token: ${if (state.tokenVisible) state.token else "••••••"}", style = LiftoffType.note())
    }

    SectionCard(title = "COACH") {
        Text("Path: ${state.workspacePath}", style = LiftoffType.note())
    }

    SectionCard(title = "MISSION") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (i in state.pattern.indices) {
                Text(
                    text = state.pattern[i].toString(),
                    modifier = Modifier
                        .size(44.dp)
                        .background(
                            if (state.pattern[i] == 'R') Red else Paper,
                            RoundedCornerShape(50),
                        )
                        .border(BorderStroke(2.dp, Ink), RoundedCornerShape(50)),
                    style = LiftoffType.index(),
                    color = if (state.pattern[i] == 'R') White else Ink,
                )
            }
        }
        Text("Sortie length: ${state.sortieLengthText}", style = LiftoffType.note())
        Text("History window: ${state.historyWindowText}", style = LiftoffType.note())
    }

    SectionCard(title = "UNITS") {
        Text("Weight: ${state.weightUnit.name}", style = LiftoffType.note())
        Text("Distance: ${state.distanceUnit.name}", style = LiftoffType.note())
    }

    SectionCard(title = "RUNS") {
        Text("Generate run plans: ${if (state.generateRunPlans) "On" else "Off"}", style = LiftoffType.note())
    }

    SectionCard(title = "OBJECTIVES AND CONSTRAINTS") {
        Text("Objectives: ${state.objectives}", style = LiftoffType.note())
        Text("Constraints: ${state.constraints}", style = LiftoffType.note())
    }
}
