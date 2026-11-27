package com.ringpass.app.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telecom.PhoneAccountHandle
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat

internal object CellularCallAlerts {
    const val RING_KEY = "sim_call"
    private val handler = Handler(Looper.getMainLooper())
    private var unregister: (() -> Unit)? = null
    private var endTimeout: Runnable? = null
    private var sawRinging = false
    private var answered = false
    private var statePermission = false

    fun canRing(session: Runnable): Boolean = endTimeout === session && statePermission && !answered
    fun isCurrent(session: Runnable): Boolean = endTimeout === session

    fun track(context: Context, account: PhoneAccountHandle?): Runnable {
        val app = context.applicationContext
        finish(app)
        val timeout = Runnable { finish(app) }
        endTimeout = timeout
        handler.postDelayed(timeout, 60_000L)
        statePermission = ContextCompat.checkSelfPermission(app, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED
        if (!statePermission) {
            ListenerDiagnostics.log("Grant Phone permission in Setup for cellular ringtones.")
            return timeout
        }
        val defaultTm = app.getSystemService(TelephonyManager::class.java) ?: return timeout
        try {
            val tm = account?.let(defaultTm::createForPhoneAccountHandle) ?: defaultTm
            unregister = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                watchModern(app, tm, timeout)
            } else {
                watchLegacy(app, tm, timeout)
            }
        } catch (error: RuntimeException) {
            Log.w("RingPassScreening", "Could not register phone state listener", error)
        }
        return timeout
    }

    fun onPhoneState(context: Context, state: Int) {
        if (endTimeout == null) return
        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                sawRinging = true
                answered = false
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                answered = true
                stopRingtone()
            }
            TelephonyManager.CALL_STATE_IDLE -> if (sawRinging) finish(context)
        }
    }

    fun finish(context: Context) {
        if (endTimeout == null) return
        clearWatcher()
        stopRingtone()
        NoteOverlayService.dismiss(context)
    }

    private fun stopRingtone() {
        if (CustomRingtonePlayer.stop(RING_KEY)) {
            ListenerDiagnostics.log("Cellular trusted ringtone stopped.")
        }
    }

    private fun clearWatcher() {
        endTimeout?.let(handler::removeCallbacks)
        endTimeout = null
        sawRinging = false
        answered = false
        statePermission = false
        val stop = unregister
        unregister = null
        runCatching { stop?.invoke() }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun watchModern(context: Context, tm: TelephonyManager, session: Runnable): () -> Unit {
        val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) {
                if (isCurrent(session)) onPhoneState(context, state)
            }
        }
        tm.registerTelephonyCallback(context.mainExecutor, callback)
        return { tm.unregisterTelephonyCallback(callback) }
    }

    @Suppress("DEPRECATION")
    private fun watchLegacy(context: Context, tm: TelephonyManager, session: Runnable): () -> Unit {
        val listener = object : PhoneStateListener() {
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                if (isCurrent(session)) onPhoneState(context, state)
            }
        }
        tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
        return { tm.listen(listener, PhoneStateListener.LISTEN_NONE) }
    }
}
