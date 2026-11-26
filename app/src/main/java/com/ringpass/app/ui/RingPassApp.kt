package com.ringpass.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.role.RoleManager
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ringpass.app.ui.components.MultiNumberContactDialog
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

    val contacts by viewModel.filteredContacts.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val notedNumbers by viewModel.notedNumbers.collectAsStateWithLifecycle()
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

    val contactsPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.refreshPermissions()
        if (granted) {
            pickContactLauncher.launch(null)
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
                WhitelistedContactsScreen(
                    contacts = contacts,
                    searchQuery = searchQuery,
                    onSearchChange = viewModel::updateSearchQuery,
                    onPickContactClick = {
                        val hasContacts = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.READ_CONTACTS
                        ) == PackageManager.PERMISSION_GRANTED
                        if (hasContacts) {
                            pickContactLauncher.launch(null)
                        } else {
                            contactsPermLauncher.launch(Manifest.permission.READ_CONTACTS)
                        }
                    },
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
                    onRequestCallLogPermission = requestAllRuntimePermissions,
                    onRefreshCallLog = { viewModel.refreshRecentUnknownCallers() },
                    onSaveNote = viewModel::saveNotedNumber,
                    onUpdateNote = viewModel::updateExistingNote,
                    onToggleCalls = viewModel::toggleAllowCalls,
                    onToggleMessages = viewModel::toggleAllowMessages,
                    onDeleteRule = viewModel::deleteRule,
                    modifier = Modifier.padding(innerPadding)
                )
            }

            RingPassTab.SETUP -> {
                SetupScreen(
                    status = permissions,
                    onOpenDndSettings = {
                        context.startActivity(
                            Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                        )
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
                        context.startActivity(
                            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        )
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
}
