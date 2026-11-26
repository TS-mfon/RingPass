package com.ringpass.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ringpass.app.data.contacts.PickedContactResult
import com.ringpass.app.data.contacts.DeviceContactEntry
import com.ringpass.app.util.PhoneNormalizer

@Composable
fun AddOrEditNoteDialog(
    title: String,
    initialNumber: String,
    initialNote: String,
    allowEditingNumber: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (number: String, note: String) -> Unit
) {
    var number by remember(initialNumber) { mutableStateOf(initialNumber) }
    var note by remember(initialNote) { mutableStateOf(initialNote) }

    val canSave = PhoneNormalizer.toMatchKey(number).length >= 7 && note.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = number,
                    onValueChange = { if (allowEditingNumber) number = it },
                    enabled = allowEditingNumber,
                    label = { Text("Phone Number") },
                    placeholder = { Text("e.g. 08031234567 or +234...") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Caller Note") },
                    placeholder = { Text("e.g. Dispatch rider, Plumber, Bank agent") },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences
                    ),
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "Calls from this number will ring through DND and display this note on screen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(number.trim(), note.trim()) },
                enabled = canSave
            ) {
                Text("Save & Allow")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun MultiNumberContactDialog(
    picked: PickedContactResult,
    onDismiss: () -> Unit,
    onConfirm: (selectedNumbers: List<String>) -> Unit
) {
    val selected = remember(picked) {
        mutableStateListOf<String>().apply { addAll(picked.phoneNumbers) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Numbers for ${picked.displayName}") },
        text = {
            Column {
                Text(
                    text = "This contact has multiple phone numbers. Choose which ones should bypass DND:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))
                picked.phoneNumbers.forEach { num ->
                    val isChecked = selected.contains(num)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (isChecked) selected.remove(num) else selected.add(num)
                            }
                            .padding(vertical = 4.dp)
                    ) {
                        Checkbox(
                            checked = isChecked,
                            onCheckedChange = { checked ->
                                if (checked && !isChecked) selected.add(num)
                                else if (!checked) selected.remove(num)
                            }
                        )
                        Text(text = num, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(selected.toList()) },
                enabled = selected.isNotEmpty()
            ) {
                Text("Whitelist (${selected.size})")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}


@Composable
fun BulkImportContactsDialog(
    deviceContacts: List<DeviceContactEntry>,
    existingMatchKeys: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (List<DeviceContactEntry>) -> Unit
) {
    var filter by remember { mutableStateOf("") }
    val selectedKeys = remember { mutableStateListOf<String>() }

    val filtered = remember(deviceContacts, filter) {
        val q = filter.trim()
        if (q.isEmpty()) deviceContacts
        else deviceContacts.filter {
            it.displayName.contains(q, ignoreCase = true) || it.rawNumber.contains(q, ignoreCase = true)
        }
    }
    val selectableVisible = remember(filtered, existingMatchKeys) {
        filtered.filter { it.matchKey !in existingMatchKeys }
    }
    val allVisibleSelected = selectableVisible.isNotEmpty() &&
        selectableVisible.all { selectedKeys.contains(it.matchKey) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Bulk Import Contacts") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    trailingIcon = {
                        if (filter.isNotEmpty()) {
                            IconButton(onClick = { filter = "" }) {
                                Icon(Icons.Rounded.Close, contentDescription = "Clear")
                            }
                        }
                    },
                    placeholder = { Text("Filter contacts") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "${selectedKeys.size} selected",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    TextButton(
                        enabled = selectableVisible.isNotEmpty(),
                        onClick = {
                            if (allVisibleSelected) {
                                selectableVisible.forEach { selectedKeys.remove(it.matchKey) }
                            } else {
                                selectableVisible.forEach {
                                    if (!selectedKeys.contains(it.matchKey)) selectedKeys.add(it.matchKey)
                                }
                            }
                        }
                    ) {
                        Text(if (allVisibleSelected) "Clear Visible" else "Select All (${selectableVisible.size})")
                    }
                }
                if (filtered.isEmpty()) {
                    Text(
                        text = "No contacts found.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 340.dp)) {
                        items(filtered, key = { it.matchKey }) { entry ->
                            val alreadySaved = entry.matchKey in existingMatchKeys
                            val checked = alreadySaved || selectedKeys.contains(entry.matchKey)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !alreadySaved) {
                                        if (selectedKeys.contains(entry.matchKey)) selectedKeys.remove(entry.matchKey)
                                        else selectedKeys.add(entry.matchKey)
                                    }
                                    .padding(vertical = 4.dp)
                            ) {
                                Checkbox(
                                    checked = checked,
                                    enabled = !alreadySaved,
                                    onCheckedChange = { c ->
                                        if (!alreadySaved) {
                                            if (c && !selectedKeys.contains(entry.matchKey)) selectedKeys.add(entry.matchKey)
                                            else if (!c) selectedKeys.remove(entry.matchKey)
                                        }
                                    }
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(entry.displayName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                    Text(
                                        text = if (alreadySaved) "${entry.rawNumber} • Already whitelisted" else entry.rawNumber,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            val chosen = deviceContacts.filter { selectedKeys.contains(it.matchKey) }
            Button(onClick = { onConfirm(chosen) }, enabled = chosen.isNotEmpty()) {
                Text("Whitelist (${chosen.size})")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
