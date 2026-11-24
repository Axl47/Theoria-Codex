package com.theoriacodex.app.series

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.theoriacodex.app.ui.components.SettingsRow
import com.theoriacodex.app.ui.components.SettingsRowDivider
import com.theoriacodex.app.ui.components.SwitchRow
import com.theoriacodex.domain.series.ExtensionSetting

@Composable
internal fun ExtensionSettingRows(settings: List<ExtensionSetting>, onChange: (ExtensionSetting) -> Unit) {
    var editingKey by rememberSaveable { mutableStateOf<String?>(null) }
    settings.forEachIndexed { index, setting ->
        if (index > 0) SettingsRowDivider()
        val title = setting.title.ifBlank { setting.key }
        when (setting) {
            is ExtensionSetting.Toggle ->
                SwitchRow(title, setting.on, description = setting.summary, enabled = setting.enabled) { onChange(setting.copy(on = it)) }
            is ExtensionSetting.Text -> SettingsRow(title, setting.summary, enabled = setting.enabled, onClick = { editingKey = setting.key })
            is ExtensionSetting.Choice -> SettingsRow(
                title, setting.summary ?: setting.options.firstOrNull { it.value == setting.value }?.label,
                enabled = setting.enabled, onClick = { editingKey = setting.key },
            )
            is ExtensionSetting.MultiChoice -> SettingsRow(title, setting.summary, enabled = setting.enabled, onClick = { editingKey = setting.key })
        }
    }
    settings.firstOrNull { it.key == editingKey }?.let { setting ->
        SettingDialog(setting, onDismiss = { editingKey = null }) { changed ->
            editingKey = null
            onChange(changed)
        }
    }
}

@Composable
private fun SettingDialog(setting: ExtensionSetting, onDismiss: () -> Unit, onChange: (ExtensionSetting) -> Unit) {
    when (setting) {
        is ExtensionSetting.Text -> TextSettingDialog(setting, onDismiss, onChange)
        is ExtensionSetting.Choice -> ChoiceDialog(setting.title, onDismiss) {
            setting.options.forEach { option ->
                ChoiceRow(option.label, Role.RadioButton, selected = option.value == setting.value, onClick = { onChange(setting.copy(value = option.value)) }) {
                    RadioButton(selected = option.value == setting.value, onClick = null)
                }
            }
        }
        is ExtensionSetting.MultiChoice -> {
            var values by remember(setting) { mutableStateOf(setting.values) }
            ChoiceDialog(setting.title, onDismiss, onSave = { onChange(setting.copy(values = values)) }) {
                setting.options.forEach { option ->
                    val checked = option.value in values
                    ChoiceRow(option.label, Role.Checkbox, checked, onClick = { values = if (checked) values - option.value else values + option.value }) {
                        Checkbox(checked = checked, onCheckedChange = null)
                    }
                }
            }
        }
        is ExtensionSetting.Toggle -> Unit
    }
}

@Composable
private fun TextSettingDialog(setting: ExtensionSetting.Text, onDismiss: () -> Unit, onChange: (ExtensionSetting) -> Unit) {
    var value by remember(setting) { mutableStateOf(setting.value) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(setting.dialogTitle ?: setting.title) },
        text = {
            Column {
                setting.dialogMessage?.let { Text(it, Modifier.padding(bottom = 12.dp)) }
                OutlinedTextField(value, onValueChange = { value = it }, Modifier.fillMaxWidth(), singleLine = true)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        confirmButton = { TextButton(onClick = { onChange(setting.copy(value = value)) }) { Text("Save") } },
    )
}

@Composable
private fun ChoiceDialog(title: String, onDismiss: () -> Unit, onSave: (() -> Unit)? = null, options: @Composable () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { options() } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        confirmButton = { onSave?.let { TextButton(onClick = it) { Text("Save") } } },
    )
}

@Composable
private fun ChoiceRow(label: String, role: Role, selected: Boolean, onClick: () -> Unit, mark: @Composable () -> Unit) {
    val modifier = if (role == Role.Checkbox) {
        Modifier.toggleable(value = selected, role = role, onValueChange = { onClick() })
    } else {
        Modifier.selectable(selected = selected, role = role, onClick = onClick)
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).then(modifier), verticalAlignment = Alignment.CenterVertically) {
        mark()
        Text(label, Modifier.padding(start = 12.dp))
    }
}
