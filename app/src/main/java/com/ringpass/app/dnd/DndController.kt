package com.ringpass.app.dnd

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.ringpass.app.service.NoteOverlayService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class DndController(private val context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val notificationManager =
        appContext.getSystemService(NotificationManager::class.java)
    private val telephonyManager =
        appContext.getSystemService(TelephonyManager::class.java)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var timeoutJob: Job? = null
    private var activeTelephonyCallback: Any? = null
    @Suppress("DEPRECATION")
    private var activePhoneStateListener: PhoneStateListener? = null

    private var unmutedAtMillis: Long = 0L
    private var sawActiveCallState: Boolean = false

    @Synchronized
    fun temporarilyUnmuteForCall() {
        val nm = notificationManager ?: return
        if (!nm.isNotificationPolicyAccessGranted) return

        val currentlyUnmuted = prefs.getBoolean(KEY_IS_UNMUTED, false)
        val currentFilter = nm.currentInterruptionFilter

        if (!currentlyUnmuted && currentFilter != NotificationManager.INTERRUPTION_FILTER_ALL) {
            prefs.edit()
                .putInt(KEY_PREVIOUS_FILTER, currentFilter)
                .putBoolean(KEY_IS_UNMUTED, true)
                .commit()
        }

        if (currentFilter != NotificationManager.INTERRUPTION_FILTER_ALL) {
            try {
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
            } catch (_: SecurityException) {
                return
            }
        }

        unmutedAtMillis = System.currentTimeMillis()
        sawActiveCallState = false

        registerCallEndWatcher()
        scheduleTimeoutSafeguard()
    }

    @Synchronized
    fun restoreDndIfNeeded() {
        val isUnmuted = prefs.getBoolean(KEY_IS_UNMUTED, false)
        if (!isUnmuted) {
            unregisterCallEndWatcher()
            return
        }

        val previousFilter = prefs.getInt(
            KEY_PREVIOUS_FILTER,
            NotificationManager.INTERRUPTION_FILTER_PRIORITY
        )

        val nm = notificationManager
        if (nm != null && nm.isNotificationPolicyAccessGranted) {
            try {
                nm.setInterruptionFilter(previousFilter)
            } catch (_: SecurityException) {
            }
        }

        prefs.edit()
            .putBoolean(KEY_IS_UNMUTED, false)
            .remove(KEY_PREVIOUS_FILTER)
            .commit()

        timeoutJob?.cancel()
        timeoutJob = null
        WorkManager.getInstance(appContext).cancelUniqueWork(WORK_RESTORE_DND)
        unregisterCallEndWatcher()
        NoteOverlayService.dismiss(appContext)
    }

    fun isTemporarilyUnmuted(): Boolean = prefs.getBoolean(KEY_IS_UNMUTED, false)

    private fun handleCallStateChanged(state: Int) {
        when (state) {
            TelephonyManager.CALL_STATE_RINGING,
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                sawActiveCallState = true
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                val elapsed = System.currentTimeMillis() - unmutedAtMillis
                if (sawActiveCallState || elapsed > 5_000L) {
                    restoreDndIfNeeded()
                }
            }
        }
    }

    private fun registerCallEndWatcher() {
        val tm = telephonyManager ?: return
        val hasPhoneStatePermission = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPhoneStatePermission) return

        unregisterCallEndWatcher()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                registerModernCallback(tm)
            } else {
                registerLegacyListener(tm)
            }
        } catch (_: SecurityException) {
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun registerModernCallback(tm: TelephonyManager) {
        val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) {
                handleCallStateChanged(state)
            }
        }
        tm.registerTelephonyCallback(appContext.mainExecutor, callback)
        activeTelephonyCallback = callback
    }

    @Suppress("DEPRECATION")
    private fun registerLegacyListener(tm: TelephonyManager) {
        val listener = object : PhoneStateListener() {
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                handleCallStateChanged(state)
            }
        }
        tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
        activePhoneStateListener = listener
    }

    private fun unregisterCallEndWatcher() {
        val tm = telephonyManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (activeTelephonyCallback as? TelephonyCallback)?.let {
                try {
                    tm.unregisterTelephonyCallback(it)
                } catch (_: Exception) {
                }
            }
            activeTelephonyCallback = null
        } else {
            @Suppress("DEPRECATION")
            activePhoneStateListener?.let {
                try {
                    tm.listen(it, PhoneStateListener.LISTEN_NONE)
                } catch (_: Exception) {
                }
            }
            activePhoneStateListener = null
        }
    }

    private fun scheduleTimeoutSafeguard() {
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(TIMEOUT_SAFEGUARD_MS)
            val tm = telephonyManager
            val hasPermission = ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.READ_PHONE_STATE
            ) == PackageManager.PERMISSION_GRANTED

            val isOffhook = hasPermission && tm?.callState == TelephonyManager.CALL_STATE_OFFHOOK
            if (!isOffhook) {
                restoreDndIfNeeded()
            }
        }

        val workRequest = OneTimeWorkRequestBuilder<RestoreDndWorker>()
            .setInitialDelay(60, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(appContext).enqueueUniqueWork(
            WORK_RESTORE_DND,
            ExistingWorkPolicy.REPLACE,
            workRequest
        )
    }

    companion object {
        private const val PREFS_NAME = "ringpass_dnd_prefs"
        private const val KEY_PREVIOUS_FILTER = "previous_interruption_filter"
        private const val KEY_IS_UNMUTED = "is_temporarily_unmuted"
        private const val WORK_RESTORE_DND = "ringpass_restore_dnd_safeguard"
        private const val TIMEOUT_SAFEGUARD_MS = 60_000L
    }
}
