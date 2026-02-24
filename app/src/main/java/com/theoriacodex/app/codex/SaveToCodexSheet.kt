package com.theoriacodex.app.codex

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.theoriacodex.domain.model.Codex

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SaveToCodexSheet(
    codices: List<Codex>,
    onCreateCodex: (String) -> Unit,
    onSelectCodex: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var newCodexName by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Save to Codex")

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    modifier = Modifier.weight(1f),
                    value = newCodexName,
                    onValueChange = { newCodexName = it },
                    label = { Text("New codex name") },
                )
                Button(
                    onClick = {
                        val trimmed = newCodexName.trim()
                        if (trimmed.isNotBlank()) {
                            onCreateCodex(trimmed)
                            newCodexName = ""
                        }
                    }
                ) {
                    Text("New Codex")
                }
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(codices, key = { it.codexId }) { codex ->
                    TextButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onSelectCodex(codex.codexId) },
                    ) {
                        Text(codex.name)
                    }
                }
            }
        }
    }
}
