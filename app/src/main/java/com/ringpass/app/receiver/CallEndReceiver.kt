package com.ringpass.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import com.ringpass.app.RingPassApplication
import com.ringpass.app.data.contacts.DeviceContactsAndCallLogHelper
import com.ringpass.app.service.CellularCallAlerts
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

        val edit = prefs.edit().putString(KEY_LAST_STATE, newState)
        if (newState == TelephonyManager.EXTRA_STATE_RINGING) {
            @Suppress("DEPRECATION")
            val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
            if (previousState != TelephonyManager.EXTRA_STATE_RINGING) {
                edit.remove(KEY_LAST_INCOMING_NUMBER)
                edit.putLong(KEY_RINGING_SINCE, System.currentTimeMillis())
            }
            if (!number.isNullOrBlank()) edit.putString(KEY_LAST_INCOMING_NUMBER, number)
        }
        edit.apply()

        val app = context.applicationContext as? RingPassApplication ?: return
        when (newState) {
            TelephonyManager.EXTRA_STATE_RINGING ->
                CellularCallAlerts.onPhoneState(app, TelephonyManager.CALL_STATE_RINGING)
            TelephonyManager.EXTRA_STATE_OFFHOOK ->
                CellularCallAlerts.onPhoneState(app, TelephonyManager.CALL_STATE_OFFHOOK)
            TelephonyManager.EXTRA_STATE_IDLE ->
                CellularCallAlerts.onPhoneState(app, TelephonyManager.CALL_STATE_IDLE)
        }
        if (newState != TelephonyManager.EXTRA_STATE_IDLE ||
            previousState == TelephonyManager.EXTRA_STATE_IDLE
        ) return
        val ringingSince = prefs.getLong(KEY_RINGING_SINCE, 0)
        val cachedNumber = prefs.getString(KEY_LAST_INCOMING_NUMBER, null)
        prefs.edit().remove(KEY_LAST_INCOMING_NUMBER).remove(KEY_RINGING_SINCE).apply()
        if (ringingSince == 0L) return
        CellularCallAlerts.finish(app)

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                delay(650L)

                val number = cachedNumber ?: DeviceContactsAndCallLogHelper.getLatestIncomingCallNumber(
                    app, maxAgeMillis = (System.currentTimeMillis() - ringingSince + 3_000L).coerceAtLeast(0L)
                )
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
        private const val KEY_RINGING_SINCE = "ringing_since"
        private const val KEY_LAST_INCOMING_NUMBER = "last_incoming_number"
    }
}
