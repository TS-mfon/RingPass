package com.ringpass.app.service

import android.app.Notification
import android.app.NotificationManager
import android.app.Person
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.ringpass.app.RingPassApplication
import com.ringpass.app.util.PhoneNormalizer
import com.ringpass.app.util.RingtonePrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class SelectiveNotificationListener : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lastPlayedByRuleId = ConcurrentHashMap<Long, Long>()

    @Volatile
    private var activeCallKey: String? = null

    override fun onListenerConnected() {
        super.onListenerConnected()
        ListenerDiagnostics.setConnected(true)
    }

    override fun onListenerDisconnected() {
        ListenerDiagnostics.setConnected(false)
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.packageName == packageName) return
        val notification = sbn.notification ?: return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val isDndActive = nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL

        if (isWhatsAppCallNotification(sbn, notification)) {
            if (isIncomingRingingCall(notification)) {
                if (!isDndActive) return
                handleIncomingWhatsAppCall(sbn, notification)
            } else {
                stopWhatsAppCallBypass(sbn.key)
            }
            return
        }

        if (!isDndActive) return
        if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        if (sbn.isOngoing || (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0) return

        val candidates = extractSenderCandidates(notification)
        if (candidates.isEmpty()) {
            if (sbn.packageName in WHATSAPP_PACKAGES) {
                ListenerDiagnostics.log("WA msg skipped: no sender text")
            }
            return
        }

        val app = applicationContext as? RingPassApplication ?: return
        scope.launch {
            val matched = app.repository.findForRuleCandidates(candidates)
            if (matched == null) {
                if (sbn.packageName in WHATSAPP_PACKAGES) {
                    ListenerDiagnostics.log("WA msg unmatched: ${candidates.joinToString()}")
                }
                return@launch
            }
            if (!matched.allowMessages) {
                ListenerDiagnostics.log("Msg muted by rule: ${matched.contactName ?: matched.rawNumber}")
                return@launch
            }

            val now = System.currentTimeMillis()
            val lastPlayed = lastPlayedByRuleId[matched.id] ?: 0L
            if (now - lastPlayed < SOUND_DEBOUNCE_MS) return@launch
            lastPlayedByRuleId[matched.id] = now

            app.repository.markTriggered(matched.id)
            ListenerDiagnostics.log("Msg bypass: ${matched.contactName ?: matched.rawNumber}")
            playOneShotNotificationSound(nm)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (sbn.packageName in WHATSAPP_PACKAGES) {
            stopWhatsAppCallBypass(sbn.key)
        }
    }

    private fun handleIncomingWhatsAppCall(sbn: StatusBarNotification, notification: Notification) {
        val candidates = extractSenderCandidates(notification)
        if (candidates.isEmpty()) {
            ListenerDiagnostics.log("WA call skipped: no caller name/number")
            return
        }

        val app = applicationContext as? RingPassApplication ?: return
        scope.launch {
            val matched = app.repository.findForRuleCandidates(candidates)
            if (matched == null) {
                ListenerDiagnostics.log("WA call unmatched: ${candidates.joinToString()}")
                return@launch
            }
            if (!matched.allowCalls) {
                ListenerDiagnostics.log("WA call muted by rule: ${matched.contactName ?: matched.rawNumber}")
                return@launch
            }

            app.repository.markTriggered(matched.id)
            val customUri = matched.customRingtoneUri ?: RingtonePrefs.getGlobalUri(applicationContext)
            val unmuted = app.dndController.temporarilyUnmuteForVoipCall()
            activeCallKey = sbn.key
            ListenerDiagnostics.log("WA call ring: ${matched.contactName ?: matched.rawNumber} (unmuted=$unmuted)")
            CustomRingtonePlayer.startLooping(
                context = applicationContext,
                key = sbn.key,
                customUriString = customUri,
                maxDurationMs = MAX_RING_DURATION_MS
            ) {
                stopWhatsAppCallBypass(sbn.key)
            }
        }
    }

    private fun stopWhatsAppCallBypass(notificationKey: String?) {
        val cur = activeCallKey
        if (notificationKey == null || cur == null || cur == notificationKey) {
            if (cur != null) {
                activeCallKey = null
                CustomRingtonePlayer.stop(cur)
                (applicationContext as? RingPassApplication)?.dndController?.restoreDndIfNeeded()
            }
        }
    }

    private fun isWhatsAppCallNotification(sbn: StatusBarNotification, notification: Notification): Boolean {
        if (sbn.packageName !in WHATSAPP_PACKAGES) return false
        if (notification.category == Notification.CATEGORY_CALL) return true
        val extras = notification.extras ?: return false
        if (extras.get("android.answerIntent") != null ||
            extras.get("android.hangUpIntent") != null ||
            extras.get("android.callPerson") != null
        ) return true
        val template = extras.getString(Notification.EXTRA_TEMPLATE).orEmpty()
        if (template.contains("CallStyle", ignoreCase = true)) return true
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        return text.contains("voice call", ignoreCase = true) ||
            text.contains("video call", ignoreCase = true) ||
            text.contains("incoming call", ignoreCase = true)
    }

    private fun isIncomingRingingCall(notification: Notification): Boolean {
        val extras = notification.extras ?: return false
        if (extras.get("android.answerIntent") != null) return true
        if (extras.get("android.hangUpIntent") != null) return false

        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (text.contains("ongoing", ignoreCase = true) ||
            text.contains("calling", ignoreCase = true) ||
            text.contains("ringing", ignoreCase = true) ||
            text.contains("ended", ignoreCase = true) ||
            text.contains("in call", ignoreCase = true)
        ) return false

        if (text.contains("incoming", ignoreCase = true) ||
            text.contains("voice call", ignoreCase = true) ||
            text.contains("video call", ignoreCase = true)
        ) return true

        return (notification.actions?.size ?: 0) >= 2
    }

    private fun extractSenderCandidates(notification: Notification): List<String> {
        val extras = notification.extras ?: return emptyList()
        val found = mutableListOf<String>()
        fun addCandidate(value: CharSequence?) {
            val cleaned = PhoneNormalizer.cleanText(value?.toString().orEmpty())
            if (cleaned.isNotBlank()) found.add(cleaned)
        }

        val callPerson = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            extras.getParcelable("android.callPerson", Person::class.java)
        } else {
            @Suppress("DEPRECATION")
            extras.getParcelable("android.callPerson")
        }
        addCandidate(callPerson?.name)

        val people = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            extras.getParcelableArrayList(Notification.EXTRA_PEOPLE_LIST, Person::class.java)
        } else {
            @Suppress("DEPRECATION")
            extras.getParcelableArrayList(Notification.EXTRA_PEOPLE_LIST)
        }
        people?.forEach { addCandidate(it?.name) }

        val msgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            extras.getParcelableArray(Notification.EXTRA_MESSAGES, Parcelable::class.java)
        } else {
            @Suppress("DEPRECATION")
            extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        }
        msgs?.forEach { item ->
            val b = item as? Bundle ?: return@forEach
            val sp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                b.getParcelable("sender_person", Person::class.java)
            } else {
                @Suppress("DEPRECATION")
                b.getParcelable("sender_person")
            }
            addCandidate(sp?.name)
            addCandidate(b.getCharSequence("sender"))
        }

        addCandidate(extras.getCharSequence(Notification.EXTRA_TITLE))
        addCandidate(extras.getCharSequence(Notification.EXTRA_TITLE_BIG))
        addCandidate(extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE))
        addCandidate(extras.getCharSequence(Notification.EXTRA_SUB_TEXT))
        return found.distinct()
    }

    private fun playOneShotNotificationSound(nm: NotificationManager) {
        val soundUri = RingtoneManager.getActualDefaultRingtoneUri(applicationContext, RingtoneManager.TYPE_NOTIFICATION)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)

        val prev = nm.currentInterruptionFilter
        val lifted = prev == NotificationManager.INTERRUPTION_FILTER_NONE && nm.isNotificationPolicyAccessGranted
        if (lifted) runCatching { nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS) }

        if (soundUri == null) {
            playFallbackTone()
            restoreTotalSilenceDelayed(nm, prev, lifted)
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
            player.setDataSource(applicationContext, soundUri)
            player.setOnPreparedListener { it.start() }
            player.setOnCompletionListener {
                it.release()
                restoreTotalSilenceDelayed(nm, prev, lifted, 200L)
            }
            player.setOnErrorListener { mp, _, _ ->
                mp.release()
                playFallbackTone()
                restoreTotalSilenceDelayed(nm, prev, lifted)
                true
            }
            player.prepareAsync()
        } catch (_: Exception) {
            runCatching { player.release() }
            playFallbackTone()
            restoreTotalSilenceDelayed(nm, prev, lifted)
        }
    }

    private fun restoreTotalSilenceDelayed(nm: NotificationManager, prev: Int, restore: Boolean, ms: Long = 1_200L) {
        if (!restore) return
        scope.launch {
            delay(ms)
            if (nm.isNotificationPolicyAccessGranted) runCatching { nm.setInterruptionFilter(prev) }
        }
    }

    private fun playFallbackTone() {
        runCatching {
            val tg = ToneGenerator(AudioManager.STREAM_ALARM, 95)
            tg.startTone(ToneGenerator.TONE_PROP_BEEP2, 450)
            scope.launch {
                delay(600L)
                runCatching { tg.release() }
            }
        }
    }

    override fun onDestroy() {
        stopWhatsAppCallBypass(null)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val SOUND_DEBOUNCE_MS = 1_500L
        private const val MAX_RING_DURATION_MS = 45_000L
        private val WHATSAPP_PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")
    }
}
