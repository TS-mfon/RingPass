package com.ringpass.app.ui.viewmodel

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ringpass.app.RingPassApplication
import com.ringpass.app.data.contacts.DeviceContactsAndCallLogHelper
import com.ringpass.app.data.contacts.PickedContactResult
import com.ringpass.app.data.contacts.RecentUnknownCaller
import com.ringpass.app.data.local.TrustedRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PermissionStatusState(
    val dndPolicyGranted: Boolean = false,
    val callScreeningRoleHeld: Boolean = false,
    val notificationListenerGranted: Boolean = false,
    val overlayGranted: Boolean = false,
    val contactsGranted: Boolean = false,
    val callLogGranted: Boolean = false,
    val phoneStateGranted: Boolean = false,
    val postNotificationsGranted: Boolean = true,
    val batteryOptimizationIgnored: Boolean = false,
    val currentInterruptionFilter: Int = NotificationManager.INTERRUPTION_FILTER_ALL
) {
    val runtimePermissionsGranted: Boolean
        get() = contactsGranted && callLogGranted && phoneStateGranted && postNotificationsGranted

    val isDndActive: Boolean
        get() = currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
}

class RingPassViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as RingPassApplication
    private val repository = app.repository

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val filteredContacts: StateFlow<List<TrustedRule>> = combine(
        repository.whitelistedContacts,
        _searchQuery
    ) { contacts, query ->
        val q = query.trim()
        if (q.isEmpty()) contacts
        else contacts.filter {
            it.contactName.orEmpty().contains(q, ignoreCase = true) ||
                it.rawNumber.contains(q, ignoreCase = true)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val notedNumbers: StateFlow<List<TrustedRule>> = repository.notedNumbers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _recentUnknownCallers = MutableStateFlow<List<RecentUnknownCaller>>(emptyList())
    val recentUnknownCallers: StateFlow<List<RecentUnknownCaller>> =
        _recentUnknownCallers.asStateFlow()

    private val _permissions = MutableStateFlow(PermissionStatusState())
    val permissions: StateFlow<PermissionStatusState> = _permissions.asStateFlow()

    private val _pendingMultiNumberContact = MutableStateFlow<PickedContactResult?>(null)
    val pendingMultiNumberContact: StateFlow<PickedContactResult?> =
        _pendingMultiNumberContact.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    init {
        refreshPermissions()
        viewModelScope.launch {
            repository.allMatchKeys.collect {
                refreshRecentUnknownCallers(it.toSet())
            }
        }
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
    }

    fun refreshPermissions() {
        val ctx = app
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val roleManager = ctx.getSystemService(RoleManager::class.java)
        val powerManager = ctx.getSystemService(PowerManager::class.java)

        val dndGranted = nm?.isNotificationPolicyAccessGranted == true
        val roleHeld = roleManager?.isRoleHeld(RoleManager.ROLE_CALL_SCREENING) == true
        val listenerEnabled = NotificationManagerCompat
            .getEnabledListenerPackages(ctx)
            .contains(ctx.packageName)
        val overlayGranted = Settings.canDrawOverlays(ctx)
        val contactsGranted = hasPerm(Manifest.permission.READ_CONTACTS)
        val callLogGranted = hasPerm(Manifest.permission.READ_CALL_LOG)
        val phoneStateGranted = hasPerm(Manifest.permission.READ_PHONE_STATE)
        val notificationsGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasPerm(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            true
        }
        val batteryIgnored = powerManager?.isIgnoringBatteryOptimizations(ctx.packageName) == true

        _permissions.value = PermissionStatusState(
            dndPolicyGranted = dndGranted,
            callScreeningRoleHeld = roleHeld,
            notificationListenerGranted = listenerEnabled,
            overlayGranted = overlayGranted,
            contactsGranted = contactsGranted,
            callLogGranted = callLogGranted,
            phoneStateGranted = phoneStateGranted,
            postNotificationsGranted = notificationsGranted,
            batteryOptimizationIgnored = batteryIgnored,
            currentInterruptionFilter = nm?.currentInterruptionFilter
                ?: NotificationManager.INTERRUPTION_FILTER_ALL
        )
    }

    fun refreshRecentUnknownCallers(excludedKeysOverride: Set<String>? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            val excluded = excludedKeysOverride ?: buildSet {
                filteredContacts.value.forEach { add(it.matchKey) }
                notedNumbers.value.forEach { add(it.matchKey) }
            }
            _recentUnknownCallers.value = DeviceContactsAndCallLogHelper
                .queryRecentUnknownCallers(app, excluded)
        }
    }

    fun handlePickedContact(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val picked = DeviceContactsAndCallLogHelper.extractContactFromPickerUri(app, uri)
            if (picked == null || picked.phoneNumbers.isEmpty()) {
                _statusMessage.value = "Selected contact has no valid phone number."
                return@launch
            }
            if (picked.phoneNumbers.size == 1) {
                val added = repository.upsertWhitelistedContact(
                    rawNumber = picked.phoneNumbers.first(),
                    contactName = picked.displayName
                )
                _statusMessage.value = if (added) {
                    "Whitelisted ${picked.displayName}"
                } else {
                    "Could not normalize contact number."
                }
            } else {
                _pendingMultiNumberContact.value = picked
            }
        }
    }

    fun confirmMultiNumberContact(contactName: String, numbers: List<String>) {
        _pendingMultiNumberContact.value = null
        viewModelScope.launch(Dispatchers.IO) {
            var count = 0
            numbers.forEach { number ->
                if (repository.upsertWhitelistedContact(number, contactName)) {
                    count++
                }
            }
            _statusMessage.value = "Added $count number(s) for $contactName"
        }
    }

    fun dismissMultiNumberContact() {
        _pendingMultiNumberContact.value = null
    }

    fun saveNotedNumber(rawNumber: String, note: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = repository.upsertNotedNumber(rawNumber = rawNumber, note = note)
            _statusMessage.value = if (ok) {
                "Saved note for $rawNumber"
            } else {
                "Enter a valid phone number."
            }
            refreshRecentUnknownCallers()
        }
    }

    fun updateExistingNote(id: Long, note: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.updateNote(id, note)
            _statusMessage.value = "Note updated"
        }
    }

    fun toggleAllowCalls(rule: TrustedRule, allow: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.setAllowCalls(rule.id, allow)
        }
    }

    fun toggleAllowMessages(rule: TrustedRule, allow: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.setAllowMessages(rule.id, allow)
        }
    }

    fun deleteRule(rule: TrustedRule) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteRule(rule)
            refreshRecentUnknownCallers()
            _statusMessage.value = "Removed ${rule.contactName ?: rule.rawNumber}"
        }
    }

    private fun hasPerm(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(app, permission) ==
            PackageManager.PERMISSION_GRANTED
    }
}
