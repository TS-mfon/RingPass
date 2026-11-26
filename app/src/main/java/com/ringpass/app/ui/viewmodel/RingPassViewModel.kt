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
import android.content.ComponentName
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.service.notification.NotificationListenerService
import com.ringpass.app.data.contacts.DeviceContactEntry
import com.ringpass.app.service.ListenerDiagnostics
import com.ringpass.app.service.SelectiveNotificationListener
import com.ringpass.app.util.RingtonePrefs
import kotlinx.coroutines.delay
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
    val currentInterruptionFilter: Int = NotificationManager.INTERRUPTION_FILTER_ALL,
    val alarmVolumePercent: Int = 100
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

    val allMatchKeys: StateFlow<Set<String>> = combine(
        repository.allMatchKeys, _searchQuery
    ) { keys, _ -> keys.toSet() }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private val _bulkImportContacts = MutableStateFlow<List<DeviceContactEntry>?>(null)
    val bulkImportContacts: StateFlow<List<DeviceContactEntry>?> = _bulkImportContacts.asStateFlow()

    private val _globalRingtoneName = MutableStateFlow(RingtonePrefs.getGlobalName(app))
    val globalRingtoneName: StateFlow<String?> = _globalRingtoneName.asStateFlow()

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

    fun refreshPermissions() {
        val ctx = app
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val roleManager = ctx.getSystemService(RoleManager::class.java)
        val powerManager = ctx.getSystemService(PowerManager::class.java)
        val audioManager = ctx.getSystemService(AudioManager::class.java)

        val dndGranted = nm?.isNotificationPolicyAccessGranted == true
        val roleHeld = roleManager?.isRoleHeld(RoleManager.ROLE_CALL_SCREENING) == true
        val listenerEnabled = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
        val overlayGranted = Settings.canDrawOverlays(ctx)
        val contactsGranted = hasPerm(Manifest.permission.READ_CONTACTS)
        val callLogGranted = hasPerm(Manifest.permission.READ_CALL_LOG)
        val phoneStateGranted = hasPerm(Manifest.permission.READ_PHONE_STATE)
        val notificationsGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasPerm(Manifest.permission.POST_NOTIFICATIONS)
        } else true
        val batteryIgnored = powerManager?.isIgnoringBatteryOptimizations(ctx.packageName) == true

        val alarmVol = audioManager?.getStreamVolume(AudioManager.STREAM_ALARM) ?: 1
        val alarmMax = (audioManager?.getStreamMaxVolume(AudioManager.STREAM_ALARM) ?: 1).coerceAtLeast(1)
        val alarmPercent = ((alarmVol * 100f) / alarmMax).toInt()

        if (listenerEnabled && !ListenerDiagnostics.isConnected.value) {
            rebindNotificationListener(silent = true)
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
            alarmVolumePercent = alarmPercent
        )
    }

    fun rebindNotificationListener(silent: Boolean = false) {
        try {
            NotificationListenerService.requestRebind(
                ComponentName(app, SelectiveNotificationListener::class.java)
            )
            if (!silent) _statusMessage.value = "Requested Android to bind Notification Listener."
        } catch (_: Exception) {
            if (!silent) _statusMessage.value = "Could not bind listener; toggle it off and on in Settings."
        }
    }

    fun testBypassSound() {
        refreshPermissions()
        val alarmPercent = _permissions.value.alarmVolumePercent
        if (alarmPercent == 0) {
            _statusMessage.value = "Alarm volume is 0%! Turn up Alarm volume so DND bypass sounds are audible."
            return
        }
        val globalUri = RingtonePrefs.getGlobalUri(app)?.let { runCatching { Uri.parse(it) }.getOrNull() }
        val soundUri = globalUri
            ?: RingtoneManager.getActualDefaultRingtoneUri(app, RingtoneManager.TYPE_NOTIFICATION)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        if (soundUri == null) {
            playTestTone()
            _statusMessage.value = "Played fallback tone (Alarm vol: $alarmPercent%)."
            return
        }
        val player = MediaPlayer()
        try {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            player.setDataSource(app, soundUri)
            player.setOnPreparedListener { mp ->
                mp.start()
                viewModelScope.launch {
                    delay(2_500L)
                    runCatching { if (mp.isPlaying) mp.stop() }
                    runCatching { mp.release() }
                }
            }
            player.setOnCompletionListener { mp -> runCatching { mp.release() } }
            player.setOnErrorListener { mp, _, _ ->
                runCatching { mp.release() }
                playTestTone()
                true
            }
            player.prepareAsync()
            _statusMessage.value = "Playing bypass audio (Alarm vol: $alarmPercent%)."
        } catch (_: Exception) {
            runCatching { player.release() }
            playTestTone()
            _statusMessage.value = "Played fallback tone (Alarm vol: $alarmPercent%)."
        }
    }

    private fun playTestTone() {
        runCatching {
            val tg = ToneGenerator(AudioManager.STREAM_ALARM, 95)
            tg.startTone(ToneGenerator.TONE_PROP_BEEP2, 450)
            viewModelScope.launch {
                delay(600L)
                runCatching { tg.release() }
            }
        }
    }

    fun openBulkImportContacts() {
        viewModelScope.launch(Dispatchers.IO) {
            val all = DeviceContactsAndCallLogHelper.loadAllDeviceContacts(app)
            if (all.isEmpty()) _statusMessage.value = "No device contacts with phone numbers found."
            else _bulkImportContacts.value = all
        }
    }

    fun dismissBulkImport() { _bulkImportContacts.value = null }

    fun confirmBulkImport(entries: List<DeviceContactEntry>) {
        _bulkImportContacts.value = null
        viewModelScope.launch(Dispatchers.IO) {
            val added = repository.bulkWhitelistContacts(entries)
            _statusMessage.value = "Whitelisted $added contact(s)"
        }
    }

    fun setGlobalRingtone(uri: Uri?) {
        if (uri == null) {
            RingtonePrefs.setGlobalRingtone(app, null, null)
            _globalRingtoneName.value = null
            _statusMessage.value = "Reset global ringtone to system default"
            return
        }
        RingtonePrefs.persistUriPermission(app, uri)
        val name = RingtonePrefs.resolveDisplayName(app, uri)
        RingtonePrefs.setGlobalRingtone(app, uri.toString(), name)
        _globalRingtoneName.value = name
        _statusMessage.value = "Global ringtone set: $name"
    }

    fun setRuleRingtone(ruleId: Long, uri: Uri?) {
        viewModelScope.launch(Dispatchers.IO) {
            if (uri == null) {
                repository.updateCustomRingtone(ruleId, null, null)
                _statusMessage.value = "Reset contact ringtone"
            } else {
                RingtonePrefs.persistUriPermission(app, uri)
                val name = RingtonePrefs.resolveDisplayName(app, uri)
                repository.updateCustomRingtone(ruleId, uri.toString(), name)
                _statusMessage.value = "Contact ringtone set: $name"
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
