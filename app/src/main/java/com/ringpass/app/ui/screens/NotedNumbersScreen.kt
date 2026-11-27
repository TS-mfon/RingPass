package com.ringpass.app.ui.screens

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Message
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.InputChip
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ringpass.app.data.contacts.RecentUnknownCaller
import com.ringpass.app.data.local.TrustedRule
import com.ringpass.app.ui.components.AddOrEditNoteDialog

@Composable
fun NotedNumbersScreen(
    recentUnknownCallers: List<RecentUnknownCaller>,
    notedNumbers: List<TrustedRule>,
    callLogGranted: Boolean,
    globalRingtoneName: String?,
    onRequestCallLogPermission: () -> Unit,
    onRefreshCallLog: () -> Unit,
    onSaveNote: (number: String, note: String) -> Unit,
    onUpdateNote: (id: Long, note: String) -> Unit,
    onPickRuleRingtone: (TrustedRule) -> Unit,
    onClearRuleRingtone: (TrustedRule) -> Unit,
    onToggleCalls: (TrustedRule, Boolean) -> Unit,
    onToggleMessages: (TrustedRule, Boolean) -> Unit,
    onDeleteRule: (TrustedRule) -> Unit,
    modifier: Modifier = Modifier
) {
    var showManualAddDialog by remember { mutableStateOf(false) }
    var quickNoteTargetNumber by remember { mutableStateOf<String?>(null) }
    var editingRule by remember { mutableStateOf<TrustedRule?>(null) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(top = 10.dp, bottom = 28.dp)
    ) {
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Noted Numbers",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        text = "Unsaved numbers that ring through DND with an on-screen note",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = { showManualAddDialog = true }) {
                    Icon(Icons.Rounded.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Add Number & Note")
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Recent Unknown Callers",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                if (callLogGranted) {
                    IconButton(onClick = onRefreshCallLog) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Refresh call log")
                    }
                }
            }
        }

        if (!callLogGranted) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(14.dp)
                    ) {
                        Text(
                            text = "Grant Call Log permission to attach notes to recent unknown callers.",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        FilledTonalButton(onClick = onRequestCallLogPermission) {
                            Text("Grant")
                        }
                    }
                }
            }
        } else if (recentUnknownCallers.isEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "No un-noted unknown callers in recent call history.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            }
        } else {
            items(recentUnknownCallers, key = { "recent_${it.matchKey}_${it.timestamp}" }) { caller ->
                RecentCallerRow(
                    caller = caller,
                    onAddNoteClick = { quickNoteTargetNumber = caller.number }
                )
            }
        }

        item {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Saved Noted Numbers (${notedNumbers.size})",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }

        if (notedNumbers.isEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "No noted numbers yet. Tap \"Add Number & Note\" or pick a recent caller above.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            }
        } else {
            items(notedNumbers, key = { it.id }) { rule ->
                NotedRuleCard(
                    rule = rule,
                    onEditNote = { editingRule = rule },
                    hasGlobalRingtone = globalRingtoneName != null,
                    onPickRingtone = { onPickRuleRingtone(rule) },
                    onClearRingtone = { onClearRuleRingtone(rule) },
                    onToggleCalls = { onToggleCalls(rule, it) },
                    onToggleMessages = { onToggleMessages(rule, it) },
                    onDelete = { onDeleteRule(rule) }
                )
            }
        }
    }

    if (showManualAddDialog) {
        AddOrEditNoteDialog(
            title = "Add Number & Note",
            initialNumber = "",
            initialNote = "",
            allowEditingNumber = true,
            onDismiss = { showManualAddDialog = false },
            onConfirm = { number, note ->
                onSaveNote(number, note)
                showManualAddDialog = false
            }
        )
    }

    quickNoteTargetNumber?.let { target ->
        AddOrEditNoteDialog(
            title = "Add Note for Caller",
            initialNumber = target,
            initialNote = "",
            allowEditingNumber = false,
            onDismiss = { quickNoteTargetNumber = null },
            onConfirm = { number, note ->
                onSaveNote(number, note)
                quickNoteTargetNumber = null
            }
        )
    }

    editingRule?.let { rule ->
        AddOrEditNoteDialog(
            title = "Edit Caller Note",
            initialNumber = rule.rawNumber,
            initialNote = rule.note.orEmpty(),
            allowEditingNumber = false,
            onDismiss = { editingRule = null },
            onConfirm = { _, updatedNote ->
                onUpdateNote(rule.id, updatedNote)
                editingRule = null
            }
        )
    }
}

@Composable
private fun RecentCallerRow(
    caller: RecentUnknownCaller,
    onAddNoteClick: () -> Unit
) {
    val relativeTime = DateUtils.getRelativeTimeSpanString(
        caller.timestamp,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS
    ).toString()

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = caller.number,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = relativeTime,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            FilledTonalButton(onClick = onAddNoteClick) {
                Text("Add Note")
            }
        }
    }
}

@Composable
private fun NotedRuleCard(
    rule: TrustedRule,
    hasGlobalRingtone: Boolean,
    onEditNote: () -> Unit,
    onPickRingtone: () -> Unit,
    onClearRingtone: () -> Unit,
    onToggleCalls: (Boolean) -> Unit,
    onToggleMessages: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    val timestampToShow = rule.lastTriggeredAt ?: rule.createdAt
    val formattedTime = DateUtils.getRelativeTimeSpanString(
        timestampToShow,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS
    ).toString()
    val timePrefix = if (rule.lastTriggeredAt != null) "Last triggered" else "Added"

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = rule.rawNumber,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "$timePrefix $formattedTime",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onEditNote) {
                    Icon(
                        imageVector = Icons.Rounded.Edit,
                        contentDescription = "Edit note",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteOutline,
                        contentDescription = "Delete rule",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }

            if (!rule.note.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = rule.note,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                InputChip(
                    modifier = Modifier.weight(1f, fill = false),
                    selected = rule.customRingtoneUri != null,
                    onClick = onPickRingtone,
                    leadingIcon = { Icon(Icons.Rounded.MusicNote, contentDescription = null) },
                    label = {
                        Text(
                            text = rule.customRingtoneName
                                ?: if (hasGlobalRingtone) "Using default ringtone" else "Set ringtone",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                )
                if (rule.customRingtoneUri != null) {
                    TextButton(onClick = onClearRingtone) { Text("Use default") }
                }
            }
            Spacer(modifier = Modifier.height(10.dp))

            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.Call,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Calls", style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(checked = rule.allowCalls, onCheckedChange = onToggleCalls)
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.Message,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Messages", style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(checked = rule.allowMessages, onCheckedChange = onToggleMessages)
                }
            }
        }
    }
}
