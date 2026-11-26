package com.ringpass.app.ui.screens

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Message
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.GroupAdd
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ringpass.app.data.local.TrustedRule

@Composable
fun WhitelistedContactsScreen(
    contacts: List<TrustedRule>,
    searchQuery: String,
    globalRingtoneName: String?,
    onSearchChange: (String) -> Unit,
    onPickContactClick: () -> Unit,
    onBulkImportClick: () -> Unit,
    onPickGlobalRingtone: () -> Unit,
    onClearGlobalRingtone: () -> Unit,
    onPickRuleRingtone: (TrustedRule) -> Unit,
    onClearRuleRingtone: (TrustedRule) -> Unit,
    onToggleCalls: (TrustedRule, Boolean) -> Unit,
    onToggleMessages: (TrustedRule, Boolean) -> Unit,
    onDeleteRule: (TrustedRule) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                InputChip(
                    selected = rule.customRingtoneUri != null,
                    onClick = onPickRingtone,
                    leadingIcon = { Icon(Icons.Rounded.MusicNote, contentDescription = null) },
                    label = {
                        Text(
                            text = rule.customRingtoneName
                                ?: if (hasGlobalRingtone) "Ringtone: Global MP3" else "Set MP3 Ringtone",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                )
                if (rule.customRingtoneUri != null) {
                    TextButton(onClick = onClearRingtone) { Text("Reset") }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchChange,
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onSearchChange("") }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Clear search")
                        }
                    }
                },
                placeholder = { Text("Search whitelisted contacts or numbers") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                FilledTonalButton(onClick = onBulkImportClick) {
                    Icon(Icons.Rounded.GroupAdd, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Bulk Import")
                }
                FilledTonalButton(onClick = onPickGlobalRingtone, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.MusicNote, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(globalRingtoneName ?: "All Contacts MP3", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (globalRingtoneName != null) {
                    IconButton(onClick = onClearGlobalRingtone) {
                        Icon(Icons.Rounded.Close, contentDescription = "Reset global MP3")
                    }
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            if (contacts.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = if (searchQuery.isBlank()) {
                                "No whitelisted contacts yet"
                            } else {
                                "No matching contacts found"
                            },
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Tap + to pick VIP contacts that can ring & alert through Do Not Disturb.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 88.dp)
                ) {
                    items(contacts, key = { it.id }) { rule ->
                        ContactRuleCard(
                            rule = rule,
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
        }

        FloatingActionButton(
            onClick = onPickContactClick,
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        ) {
            Icon(Icons.Rounded.PersonAdd, contentDescription = "Add Contact")
        }
    }
}

@Composable
private fun ContactRuleCard(
    rule: TrustedRule,
    hasGlobalRingtone: Boolean,
    onPickRingtone: () -> Unit,
    onClearRingtone: () -> Unit,
    onToggleCalls: (Boolean) -> Unit,
    onToggleMessages: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
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
                        text = rule.contactName ?: "Saved Contact",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = rule.rawNumber,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteOutline,
                        contentDescription = "Remove contact",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

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
