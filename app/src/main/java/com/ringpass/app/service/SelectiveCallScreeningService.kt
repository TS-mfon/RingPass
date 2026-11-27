package com.ringpass.app.service

import android.media.AudioManager
import android.telecom.Call
import android.telecom.CallScreeningService
import android.util.Log
import com.ringpass.app.RingPassApplication
import com.ringpass.app.data.local.TrustedRule
import com.ringpass.app.util.RingtonePrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class SelectiveCallScreeningService : CallScreeningService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onScreenCall(callDetails: Call.Details) {
        if (callDetails.callDirection != Call.Details.DIRECTION_INCOMING) return
        val number = callDetails.handle?.takeIf { it.scheme == "tel" }?.schemeSpecificPart
            ?.substringBefore(';').orEmpty()
        scope.launch {
            val app = application as RingPassApplication
            var silent = false
            var rule: TrustedRule? = null
            var session: Runnable? = null
            try {
                rule = withTimeoutOrNull(1_400L) {
                    app.repository.findByNumber(number)?.takeIf { it.allowCalls }
                }
                rule?.let {
                    val tracked = CellularCallAlerts.track(app, callDetails.accountHandle)
                    session = tracked
                    silent = startAlert(app, it, tracked)
                }
            } catch (error: Exception) {
                Log.w("RingPassScreening", "Could not prepare trusted call alert", error)
            } finally {
                respondToCall(callDetails, CallResponse.Builder().setSilenceCall(silent).build())
            }
            rule?.let { matched ->
                if (session?.let(CellularCallAlerts::isCurrent) == true) {
                    matched.note?.takeIf { it.isNotBlank() }?.let { NoteOverlayService.show(app, number, it) }
                }
                runCatching { app.repository.markTriggered(matched.id) }
                    .onFailure { Log.w("RingPassScreening", "Could not record the latest call", it) }
            }
        }
    }

    private suspend fun startAlert(app: RingPassApplication, rule: TrustedRule, session: Runnable): Boolean {
        if (!CellularCallAlerts.canRing(session)) return false
        val audio = app.getSystemService(AudioManager::class.java) ?: return false
        val dnd = app.dndController.isActive
        if (dnd) {
            if (!app.dndController.allowsAlarmPlayback() || audio.getStreamVolume(AudioManager.STREAM_ALARM) == 0) {
                ListenerDiagnostics.log("Cellular alert unavailable. Check DND alarms and Alarm volume.")
                return false
            }
        } else {
            val hasCustom = rule.customRingtoneUri != null || RingtonePrefs.getGlobalUri(app) != null
            if (!hasCustom || audio.ringerMode != AudioManager.RINGER_MODE_NORMAL ||
                audio.getStreamVolume(AudioManager.STREAM_RING) == 0
            ) return false
        }
        val started = CustomRingtonePlayer.ring(
            app, CellularCallAlerts.RING_KEY, rule.customRingtoneUri, alarm = dnd, durationMs = 60_000L
        ) {
            CellularCallAlerts.canRing(session) && app.dndController.isActive == dnd &&
                (!dnd || app.dndController.allowsAlarmPlayback())
        }
        if (started) ListenerDiagnostics.log("Cellular trusted ringtone started.")
        return started
    }
}
