package com.ringpass.app.ui.viewmodel

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.content.ComponentName
import android.media.AudioManager
import android.service.notification.NotificationListenerService
import android.util.Log
import com.ringpass.app.data.contacts.DeviceContactEntry
import com.ringpass.app.service.CustomRingtonePlayer
import com.ringpass.app.service.ListenerDiagnostics
import com.ringpass.app.service.SelectiveNotificationListener
import com.ringpass.app.util.NotificationHelper
import com.ringpass.app.util.RingtonePrefs
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ringpass.app.RingPassApplication
import com.ringpass.app.data.contacts.DeviceContactsAndCallLogHelper
import com.ringpass.app.data.contacts.PickedContactResult
import com.ringpass.app.data.contacts.RecentUnknownCaller
import com.ringpass.app.data.local.TrustedRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
    val currentInterruptionFilter: Int = NotificationManager.INTERRUPTION_FILTER_ALL,
    val alarmPlaybackAllowed: Boolean = false,
    val alarmVolumePercent: Int = 0
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

    val whitelistedMatchKeys: StateFlow<Set<String>> = repository.whitelistedContacts
        .map { contacts -> contacts.map(TrustedRule::matchKey).toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private val _bulkImportContacts = MutableStateFlow<List<DeviceContactEntry>?>(null)
    val bulkImportContacts: StateFlow<List<DeviceContactEntry>?> = _bulkImportContacts.asStateFlow()
    private var bulkImportTask: Job? = null

    private val _globalRingtoneName = MutableStateFlow(RingtonePrefs.getGlobalName(app))
    val globalRingtoneName: StateFlow<String?> = _globalRingtoneName.asStateFlow()
    private val ringtoneSaveLock = Mutex()

    private val listenerComponent = ComponentName(app, SelectiveNotificationListener::class.java)
    private var listenerProbeJob: Job? = null
    val listenerConnected: StateFlow<Boolean> = ListenerDiagnostics.isConnected
    val listenerLogs: StateFlow<List<String>> = ListenerDiagnostics.logs

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

    fun showStatusMessage(message: String) {
        _statusMessage.value = message
    }

    fun refreshPermissions() {
        val ctx = app
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val roleManager = ctx.getSystemService(RoleManager::class.java)
        val powerManager = ctx.getSystemService(PowerManager::class.java)
        val audioManager = ctx.getSystemService(AudioManager::class.java)

        val dndGranted = nm?.isNotificationPolicyAccessGranted == true
        if (dndGranted) app.dndController.cleanUpLegacyOverride()
        val roleHeld = roleManager?.isRoleHeld(RoleManager.ROLE_CALL_SCREENING) == true
        val listenerEnabled = nm?.isNotificationListenerAccessGranted(listenerComponent) == true
        val overlayGranted = Settings.canDrawOverlays(ctx)
        val contactsGranted = hasPerm(Manifest.permission.READ_CONTACTS)
        val callLogGranted = hasPerm(Manifest.permission.READ_CALL_LOG)
        val phoneStateGranted = hasPerm(Manifest.permission.READ_PHONE_STATE)
        val notificationsGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasPerm(Manifest.permission.POST_NOTIFICATIONS)
        } else true
        val batteryIgnored = powerManager?.isIgnoringBatteryOptimizations(ctx.packageName) == true

        val alarmVol = audioManager?.getStreamVolume(AudioManager.STREAM_ALARM) ?: 0
        val alarmMax = (audioManager?.getStreamMaxVolume(AudioManager.STREAM_ALARM) ?: 1).coerceAtLeast(1)
        val alarmPercent = ((alarmVol * 100f) / alarmMax).toInt()

        if (listenerEnabled && !ListenerDiagnostics.isConnected.value) {
            requestListenerRebind()
        }

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
            currentInterruptionFilter = nm?.currentInterruptionFilter ?: NotificationManager.INTERRUPTION_FILTER_ALL,
            alarmPlaybackAllowed = app.dndController.allowsAlarmPlayback(),
            alarmVolumePercent = alarmPercent
        )
    }

    private fun requestListenerRebind() {
        runCatching { NotificationListenerService.requestRebind(listenerComponent) }
            .onFailure { ListenerDiagnostics.log("Android declined the listener reconnect request.") }
    }

    fun verifyNotificationListener() {
        refreshPermissions()
        if (!_permissions.value.notificationListenerGranted) {
            _statusMessage.value = "Open Notification access and enable Allow notification access for RingPass."
            return
        }
        if (listenerProbeJob?.isActive == true) return
        listenerProbeJob = viewModelScope.launch {
            if (!ListenerDiagnostics.isConnected.value) {
                requestListenerRebind()
                val bound = withTimeoutOrNull(3_000L) {
                    ListenerDiagnostics.isConnected.first { it }
                } == true
                if (!bound) {
                    _statusMessage.value = "Android has not connected the listener. Turn RingPass off and back on in Notification access."
                    return@launch
                }
            }

            val token = SystemClock.elapsedRealtimeNanos()
            if (!NotificationHelper.postListenerProbe(app, token)) {
                _statusMessage.value = "Allow RingPass notifications and its Listener verification notification category."
                return@launch
            }
            try {
                val received = withTimeoutOrNull(4_000L) {
                    ListenerDiagnostics.probeReceived.first { it == token }
                } != null
                _statusMessage.value = if (received) "Android delivered an ongoing notification to RingPass."
                else "Android connected the listener but did not deliver the test. Check ongoing notification access."
            } finally {
                app.getSystemService(NotificationManager::class.java)?.cancel(NotificationHelper.LISTENER_PROBE_ID)
            }
        }
    }

    fun testBypassSound() {
        refreshPermissions()
        val status = _permissions.value
        if (!status.alarmPlaybackAllowed) {
            _statusMessage.value = "Allow alarms in your active Do Not Disturb mode, then try again."
            return
        }
        if (status.alarmVolumePercent == 0) {
            _statusMessage.value = "Turn up Alarm volume, then try again."
            return
        }
        viewModelScope.launch {
            val started = CustomRingtonePlayer.ring(
                app, "preview", contactUri = null, alarm = true, durationMs = 2_500L
            ) { app.dndController.allowsAlarmPlayback() }
            _statusMessage.value = if (started) "Playing the RingPass ringtone on Alarm volume."
                else "RingPass could not play the ringtone."
        }
    }

    fun openBulkImportContacts() {
        if (bulkImportTask?.isActive == true) return
        bulkImportTask = viewModelScope.launch(Dispatchers.IO) {
            try {
                val all = DeviceContactsAndCallLogHelper.loadAllDeviceContacts(app)
                if (all.isEmpty()) _statusMessage.value = "No device contacts with phone numbers found."
                else _bulkImportContacts.value = all
            } catch (error: SecurityException) {
                _statusMessage.value = "Grant Contacts access to import phone numbers."
            } catch (error: RuntimeException) {
                Log.w("RingPass", "Unable to read device contacts", error)
                _statusMessage.value = "Could not load contacts. Try importing again."
            }
        }
    }

    fun dismissBulkImport() { _bulkImportContacts.value = null }

    fun confirmBulkImport(entries: List<DeviceContactEntry>) {
        if (_bulkImportContacts.value == null) return
        _bulkImportContacts.value = null
        bulkImportTask = viewModelScope.launch(Dispatchers.IO) {
            try {
                val added = repository.bulkWhitelistContacts(entries)
                _statusMessage.value = when (added) {
                    0 -> "These numbers are already whitelisted."
                    1 -> "Whitelisted 1 phone number."
                    else -> "Whitelisted $added phone numbers."
                }
            } catch (error: android.database.SQLException) {
                Log.w("RingPass", "Unable to import device contacts", error)
                _statusMessage.value = "Could not whitelist the selected numbers. Try importing again."
            }
        }
    }

    fun setGlobalRingtone(uri: Uri?) {
        saveRingtone(uri) { selectedUri, name ->
            RingtonePrefs.setGlobalRingtone(app, selectedUri, name)
            _globalRingtoneName.value = name
            if (name == null) "Restored the system ringtone" else "Default ringtone set to $name"
        }
    }

    fun setRuleRingtone(ruleId: Long, uri: Uri?) {
        saveRingtone(uri) { selectedUri, name ->
            val updated = repository.updateCustomRingtone(ruleId, selectedUri, name)
            when {
                updated == 0 -> "This number is no longer in RingPass."
                name == null -> "This number now uses the default ringtone."
                else -> "Ringtone set to $name"
            }
        }
    }

    private fun saveRingtone(uri: Uri?, save: suspend (String?, String?) -> String) {
        viewModelScope.launch {
            ringtoneSaveLock.withLock {
                val name = uri?.let { withContext(Dispatchers.IO) { RingtonePrefs.validateAndKeepAudio(app, it) } }
                if (uri != null && name == null) {
                    _statusMessage.value = "Could not keep access to this audio. Choose another file from your device."
                    return@withLock
                }
                try {
                    _statusMessage.value = save(uri?.toString(), name)
                } catch (error: android.database.SQLException) {
                    Log.w("RingPass", "Unable to save ringtone", error)
                    _statusMessage.value = "Could not save the ringtone. Try again."
                }
            }
        }
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
            val count = repository.whitelistContactNumbers(contactName, numbers)
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
