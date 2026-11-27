package com.ringpass.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Contacts
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ringpass.app.service.SelectiveNotificationListener
import com.ringpass.app.ui.components.MultiNumberContactDialog
import com.ringpass.app.ui.components.BulkImportContactsDialog
import com.ringpass.app.ui.screens.NotedNumbersScreen
import com.ringpass.app.ui.screens.SetupScreen
import com.ringpass.app.ui.screens.WhitelistedContactsScreen
import com.ringpass.app.ui.viewmodel.RingPassViewModel

private enum class RingPassTab(val label: String) {
    CONTACTS("Contacts"),
    NOTED("Noted Numbers"),
    SETUP("Setup")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RingPassApp(
    viewModel: RingPassViewModel = viewModel()
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var selectedTabIndex by rememberSaveable { mutableIntStateOf(0) }
    var pendingContactsActionCode by rememberSaveable { mutableIntStateOf(0) }
    var ringtoneTargetRuleId by rememberSaveable { mutableLongStateOf(-1L) }

    val contacts by viewModel.filteredContacts.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val notedNumbers by viewModel.notedNumbers.collectAsStateWithLifecycle()
    val whitelistedMatchKeys by viewModel.whitelistedMatchKeys.collectAsStateWithLifecycle()
    val bulkImportContacts by viewModel.bulkImportContacts.collectAsStateWithLifecycle()
    val globalRingtoneName by viewModel.globalRingtoneName.collectAsStateWithLifecycle()
    val listenerConnected by viewModel.listenerConnected.collectAsStateWithLifecycle()
    val listenerLogs by viewModel.listenerLogs.collectAsStateWithLifecycle()
    val recentUnknownCallers by viewModel.recentUnknownCallers.collectAsStateWithLifecycle()
    val permissions by viewModel.permissions.collectAsStateWithLifecycle()
    val pendingMultiNumber by viewModel.pendingMultiNumberContact.collectAsStateWithLifecycle()
    val statusMessage by viewModel.statusMessage.collectAsStateWithLifecycle()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshPermissions()
        viewModel.refreshRecentUnknownCallers()
    }

    LaunchedEffect(statusMessage) {
        val msg = statusMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.clearStatusMessage()
    }

    val pickContactLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickContact()
    ) { uri ->
        if (uri != null) {
            viewModel.handlePickedContact(uri)
        }
    }

    val audioPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        val targetId = ringtoneTargetRuleId
        ringtoneTargetRuleId = -1L
        if (uri != null) {
            if (targetId <= 0L) viewModel.setGlobalRingtone(uri)
            else viewModel.setRuleRingtone(targetId, uri)
        }
    }

    val pickRingtone: (Long) -> Unit = { ruleId ->
        ringtoneTargetRuleId = ruleId
        try {
            audioPickerLauncher.launch(arrayOf("audio/*"))
        } catch (_: ActivityNotFoundException) {
            ringtoneTargetRuleId = -1L
            viewModel.showStatusMessage("Android could not open a file picker for audio.")
        }
    }

    val contactsPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.refreshPermissions()
        val action = pendingContactsActionCode
        pendingContactsActionCode = 0
        if (granted) {
            if (action == 1) pickContactLauncher.launch(null)
            else if (action == 2) viewModel.openBulkImportContacts()
        } else if (action != 0) {
            viewModel.showStatusMessage("Grant Contacts access in Setup to import contacts.")
        }
    }

    val runtimePermissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.refreshPermissions()
        viewModel.refreshRecentUnknownCallers()
    }

    val roleRequestLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.refreshPermissions()
    }

    val requestAllRuntimePermissions = {
        val perms = buildList {
            add(Manifest.permission.READ_CONTACTS)
            add(Manifest.permission.READ_CALL_LOG)
            add(Manifest.permission.READ_PHONE_STATE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
        runtimePermissionsLauncher.launch(perms)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("RingPass") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                RingPassTab.entries.forEachIndexed { index, tab ->
                    val icon = when (tab) {
                        RingPassTab.CONTACTS -> Icons.Rounded.Contacts
                        RingPassTab.NOTED -> Icons.Rounded.EditNote
                        RingPassTab.SETUP -> Icons.Rounded.Tune
                    }
                    NavigationBarItem(
                        selected = selectedTabIndex == index,
                        onClick = { selectedTabIndex = index },
                        icon = { Icon(icon, contentDescription = tab.label) },
                        label = { Text(tab.label) }
                    )
                }
            }
        }
    ) { innerPadding ->
        when (RingPassTab.entries[selectedTabIndex]) {
            RingPassTab.CONTACTS -> {
                val runContactsAction: (Int) -> Unit = { action ->
                    val hasContacts = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.READ_CONTACTS
                    ) == PackageManager.PERMISSION_GRANTED
                    if (hasContacts) {
                        if (action == 1) pickContactLauncher.launch(null)
                        else if (action == 2) viewModel.openBulkImportContacts()
                    } else {
                        pendingContactsActionCode = action
                        contactsPermLauncher.launch(Manifest.permission.READ_CONTACTS)
                    }
                }
                WhitelistedContactsScreen(
                    contacts = contacts,
                    searchQuery = searchQuery,
                    globalRingtoneName = globalRingtoneName,
                    onSearchChange = viewModel::updateSearchQuery,
                    onPickContactClick = { runContactsAction(1) },
                    onBulkImportClick = { runContactsAction(2) },
                    onPickGlobalRingtone = { pickRingtone(-1L) },
                    onClearGlobalRingtone = { viewModel.setGlobalRingtone(null) },
                    onPickRuleRingtone = { rule -> pickRingtone(rule.id) },
                    onClearRuleRingtone = { rule -> viewModel.setRuleRingtone(rule.id, null) },
                    onToggleCalls = viewModel::toggleAllowCalls,
                    onToggleMessages = viewModel::toggleAllowMessages,
                    onDeleteRule = viewModel::deleteRule,
                    modifier = Modifier.padding(innerPadding)
                )
            }

            RingPassTab.NOTED -> {
                NotedNumbersScreen(
                    recentUnknownCallers = recentUnknownCallers,
                    notedNumbers = notedNumbers,
                    callLogGranted = permissions.callLogGranted,
                    globalRingtoneName = globalRingtoneName,
                    onRequestCallLogPermission = requestAllRuntimePermissions,
                    onRefreshCallLog = { viewModel.refreshRecentUnknownCallers() },
                    onSaveNote = viewModel::saveNotedNumber,
                    onUpdateNote = viewModel::updateExistingNote,
                    onPickRuleRingtone = { rule -> pickRingtone(rule.id) },
                    onClearRuleRingtone = { rule -> viewModel.setRuleRingtone(rule.id, null) },
                    onToggleCalls = viewModel::toggleAllowCalls,
                    onToggleMessages = viewModel::toggleAllowMessages,
                    onDeleteRule = viewModel::deleteRule,
                    modifier = Modifier.padding(innerPadding)
                )
            }

            RingPassTab.SETUP -> {
                SetupScreen(
                    status = permissions,
                    listenerConnected = listenerConnected,
                    listenerLogs = listenerLogs,
                    globalRingtoneName = globalRingtoneName,
                    onOpenDndSettings = {
                        context.startActivity(
                            Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                        )
                    },
                    onOpenDndModeSettings = {
                        try {
                            context.startActivity(Intent("android.settings.ZEN_MODE_SETTINGS"))
                        } catch (_: ActivityNotFoundException) {
                            context.startActivity(Intent(Settings.ACTION_SOUND_SETTINGS))
                        }
                    },
                    onRequestCallScreeningRole = {
                        val roleManager = context.getSystemService(RoleManager::class.java)
                        if (roleManager != null &&
                            roleManager.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)
                        ) {
                            roleRequestLauncher.launch(
                                roleManager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING)
                            )
                        }
                    },
                    onOpenNotificationListenerSettings = {
                        val listener = ComponentName(context, SelectiveNotificationListener::class.java)
                        try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                                    .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, listener.flattenToString()))
                            } else {
                                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                            }
                        } catch (_: ActivityNotFoundException) {
                            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        }
                    },
                    onOpenOverlaySettings = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                        )
                    },
                    onRequestRuntimePermissions = requestAllRuntimePermissions,
                    onRequestBatteryOptimizationExemption = {
                        @SuppressLint("BatteryLife")
                        val intent = Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:${context.packageName}")
                        )
                        context.startActivity(intent)
                    },
                    onVerifyListener = viewModel::verifyNotificationListener,
                    onTestBypassSound = viewModel::testBypassSound,
                    onPickGlobalRingtone = { pickRingtone(-1L) },
                    onClearGlobalRingtone = { viewModel.setGlobalRingtone(null) },
                    onOpenAppDetailsSettings = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:${context.packageName}")
                            )
                        )
                    },
                    modifier = Modifier.padding(innerPadding)
                )
            }
        }
    }

    pendingMultiNumber?.let { picked ->
        MultiNumberContactDialog(
            picked = picked,
            onDismiss = viewModel::dismissMultiNumberContact,
            onConfirm = { chosen ->
                viewModel.confirmMultiNumberContact(picked.displayName, chosen)
            }
        )
    }

    bulkImportContacts?.let { deviceContacts ->
        BulkImportContactsDialog(
            deviceContacts = deviceContacts,
            whitelistedMatchKeys = whitelistedMatchKeys,
            onDismiss = viewModel::dismissBulkImport,
            onConfirm = viewModel::confirmBulkImport
        )
    }
}
