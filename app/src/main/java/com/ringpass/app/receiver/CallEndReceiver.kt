package com.ringpass.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import com.ringpass.app.RingPassApplication
import com.ringpass.app.data.contacts.DeviceContactsAndCallLogHelper
import com.ringpass.app.service.NoteOverlayService
import com.ringpass.app.service.CustomRingtonePlayer
import com.ringpass.app.util.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class CallEndReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val newState = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val previousState = prefs.getString(KEY_LAST_STATE, TelephonyManager.EXTRA_STATE_IDLE)

        @Suppress("DEPRECATION")
        val incomingExtra = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
        if (!incomingExtra.isNullOrBlank()) {
            prefs.edit().putString(KEY_LAST_INCOMING_NUMBER, incomingExtra).apply()
        }

        prefs.edit().putString(KEY_LAST_STATE, newState).apply()

        if (newState == TelephonyManager.EXTRA_STATE_OFFHOOK) {
            CustomRingtonePlayer.stop("sim_call")
        }

        val callJustEnded = newState == TelephonyManager.EXTRA_STATE_IDLE &&
            (previousState == TelephonyManager.EXTRA_STATE_RINGING ||
                previousState == TelephonyManager.EXTRA_STATE_OFFHOOK)

        if (!callJustEnded) return

        val app = context.applicationContext as? RingPassApplication ?: return
        CustomRingtonePlayer.stop("sim_call")
        app.dndController.restoreDndIfNeeded()
        NoteOverlayService.dismiss(app)

        val cachedNumber = prefs.getString(KEY_LAST_INCOMING_NUMBER, null)
        prefs.edit().remove(KEY_LAST_INCOMING_NUMBER).apply()

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                delay(650L)

                val number = DeviceContactsAndCallLogHelper.getLatestIncomingCallNumber(app)
                    ?: cachedNumber
                    ?: return@launch

                if (DeviceContactsAndCallLogHelper.isNumberInSystemContacts(app, number)) {
                    return@launch
                }

                val existingRule = app.repository.findByNumber(number)
                if (existingRule != null) {
                    return@launch
                }

                NotificationHelper.showPostCallQuickNotePrompt(app, number)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val PREFS_NAME = "ringpass_call_state_prefs"
        private const val KEY_LAST_STATE = "last_phone_state"
        private const val KEY_LAST_INCOMING_NUMBER = "last_incoming_number"
    }
}
