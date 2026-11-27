package com.ringpass.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Person
import android.media.AudioManager
import android.os.Bundle
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.os.BundleCompat
import com.ringpass.app.RingPassApplication
import com.ringpass.app.data.local.TrustedRule
import com.ringpass.app.util.PhoneNormalizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.coroutineContext

class SelectiveNotificationListener : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val pendingCalls = mutableMapOf<String, Job>()
    private val activeCalls = mutableSetOf<String>()
    private val identitiesInCall = mutableMapOf<String, List<String>>()
    private val messageJobs = mutableMapOf<String, Job>()
    private val lastMessageByNotification = mutableMapOf<String, String>()
    private val app get() = application as RingPassApplication

    override fun onListenerConnected() {
        super.onListenerConnected()
        ListenerDiagnostics.setConnected(true)
        // A rebind can occur while a call notification is already on the screen.
        activeNotifications?.filter { it.packageName in WHATSAPP_PACKAGES }?.forEach { sbn ->
            runCatching { handleNotification(sbn, allowMessages = false) }
                .onFailure { Log.w("RingPassListener", "Could not read active call metadata", it) }
        }
    }

    override fun onListenerDisconnected() {
        clearAlerts()
        ListenerDiagnostics.setConnected(false)
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        try {
            handleNotification(sbn, allowMessages = true)
        } catch (error: RuntimeException) {
            Log.w("RingPassListener", "Could not read notification metadata", error)
        }
    }

    private fun handleNotification(sbn: StatusBarNotification, allowMessages: Boolean) {
        if (sbn.packageName == packageName) return
        val notification = sbn.notification
        val extras = notification.extras ?: return
        val isWhatsApp = sbn.packageName in WHATSAPP_PACKAGES
        if (isWhatsApp) {
            val event = whatsAppEvent(
                callType = extras.getInt(Notification.EXTRA_CALL_TYPE),
                callCategory = notification.category == Notification.CATEGORY_CALL || notification.fullScreenIntent != null,
                callChannel = notification.channelId.orEmpty().lowercase(Locale.ROOT).contains("call"),
                hasAnswer = BundleCompat.getParcelable(extras, Notification.EXTRA_ANSWER_INTENT, PendingIntent::class.java) != null,
                hasHangUp = BundleCompat.getParcelable(extras, Notification.EXTRA_HANG_UP_INTENT, PendingIntent::class.java) != null,
                actionCount = notification.actions?.size ?: 0,
                text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
            )
            when (event) {
                WhatsAppEvent.RINGING -> {
                    if (app.dndController.isActive) {
                        beginCall(sbn, senderCandidates(extras, call = true))
                    } else {
                        stopCall(sbn.key)
                    }
                    return
                }
                WhatsAppEvent.OTHER_CALL -> {
                    stopCall(sbn.key)
                    return
                }
                WhatsAppEvent.MESSAGE -> stopCall(sbn.key)
            }
        }
        if (!allowMessages || !app.dndController.isActive || sbn.isOngoing ||
            notification.flags and Notification.FLAG_GROUP_SUMMARY != 0
        ) return
        if (!isWhatsApp && sbn.packageName !in TELEGRAM_PACKAGES &&
            sbn.packageName != Telephony.Sms.getDefaultSmsPackage(this)
        ) return
        if (notification.category != null && notification.category != Notification.CATEGORY_MESSAGE &&
            !extras.containsKey(Notification.EXTRA_MESSAGES)
        ) return
        beginMessage(sbn, extras)
    }

    private fun beginCall(sbn: StatusBarNotification, candidates: List<String>) {
        if (candidates.isEmpty()) return
        val key = sbn.key
        if (identitiesInCall[key] == candidates) return
        stopCall(key)
        identitiesInCall[key] = candidates
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val lookup = coroutineContext[Job]
            try {
                val rule = withTimeoutOrNull(1_500L) { app.repository.findForRuleCandidates(candidates) }
                if (rule == null || !rule.allowCalls) {
                    ListenerDiagnostics.log("WhatsApp call skipped. No enabled matching rule.")
                    return@launch
                }
                if (!app.dndController.isActive) return@launch
                if (!canPlayAlarm()) {
                    ListenerDiagnostics.log("WhatsApp alert unavailable. Check DND alarms and Alarm volume.")
                    return@launch
                }
                val started = CustomRingtonePlayer.ring(
                    app, key, rule.customRingtoneUri, alarm = true, durationMs = CALL_LIMIT_MS
                ) { pendingCalls[key] === lookup && app.dndController.isActive && canPlayAlarm() }
                if (started) {
                    activeCalls.add(key)
                    ListenerDiagnostics.log("WhatsApp trusted ringtone started.")
                    recordAlert(rule)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w("RingPassListener", "Could not start the WhatsApp call alert", error)
            } finally {
                coroutineContext[Job]?.let { pendingCalls.remove(key, it) }
            }
        }
        pendingCalls[key] = job
        job.start()
    }

    private fun beginMessage(sbn: StatusBarNotification, extras: Bundle) {
        if (!canPlayAlarm()) return
        val latest = latestMessage(extras)
        val signature = latest?.let { "${it.getLong("time")}:${it.getCharSequence("text")}" }
            ?: "${sbn.notification.`when`}:${extras.getCharSequence(Notification.EXTRA_TEXT)}"
        val key = sbn.key
        if (lastMessageByNotification[key] == signature) return
        lastMessageByNotification[key] = signature
        messageJobs.remove(key)?.cancel()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val rule = app.repository.findForRuleCandidates(senderCandidates(extras, call = false))
                if (rule == null || !rule.allowMessages) return@launch
                val started = CustomRingtonePlayer.chime(app, "message:$key") {
                    app.dndController.isActive && canPlayAlarm()
                }
                if (started) {
                    ListenerDiagnostics.log("Trusted message chime started.")
                    recordAlert(rule)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w("RingPassListener", "Could not play the trusted message chime", error)
            } finally {
                coroutineContext[Job]?.let { messageJobs.remove(key, it) }
            }
        }
        messageJobs[key] = job
        job.start()
    }

    private fun canPlayAlarm(): Boolean = app.dndController.allowsAlarmPlayback() &&
        (getSystemService(AudioManager::class.java)?.getStreamVolume(AudioManager.STREAM_ALARM) ?: 0) > 0

    private fun senderCandidates(extras: Bundle, call: Boolean): List<String> {
        val latest = if (call) null else latestMessage(extras)
        val person = if (call) {
            BundleCompat.getParcelable(extras, Notification.EXTRA_CALL_PERSON, Person::class.java)
        } else {
            latest?.let { BundleCompat.getParcelable(it, "sender_person", Person::class.java) }
        }
        val legacySender = latest?.getCharSequence("sender")?.toString()
        if (latest != null && person == null && legacySender.isNullOrBlank()) return emptyList()
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val callerNumber = person?.uri?.let(::phoneIdentity)
            ?: person?.key?.takeIf { it.contains('@') || it.startsWith("tel:") }?.let(::phoneIdentity)
            ?: title?.takeIf(PhoneNormalizer::looksLikePhoneNumber)
        if (callerNumber != null) return listOf(callerNumber)
        val name = person?.name?.toString()?.takeIf { it.isNotBlank() }
        if (name != null || !legacySender.isNullOrBlank()) return listOfNotNull(name ?: legacySender)
        if (!call && extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION)) return emptyList()
        return listOfNotNull(title ?: extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString())
    }

    private fun phoneIdentity(identity: String): String? {
        val raw = when {
            identity.startsWith("tel:", ignoreCase = true) -> identity.substring(4).substringBefore(';')
            identity.endsWith("@s.whatsapp.net", ignoreCase = true) -> identity.substringBefore('@').substringBefore(':')
            else -> identity
        }
        return android.net.Uri.decode(raw).takeIf(PhoneNormalizer::looksLikePhoneNumber)
    }

    private fun latestMessage(extras: Bundle): Bundle? =
        BundleCompat.getParcelableArray(extras, Notification.EXTRA_MESSAGES, Bundle::class.java)
            ?.lastOrNull() as? Bundle

    private suspend fun recordAlert(rule: TrustedRule) = app.repository.markTriggered(rule.id)

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val key = sbn?.key ?: return
        stopCall(key)
        lastMessageByNotification.remove(key)
        messageJobs.remove(key)?.cancel()
    }

    private fun stopCall(key: String) {
        identitiesInCall.remove(key)
        pendingCalls.remove(key)?.cancel()
        if (activeCalls.remove(key)) CustomRingtonePlayer.stop(key)
    }

    private fun clearAlerts() {
        pendingCalls.keys.toList().forEach(::stopCall)
        activeCalls.toList().forEach(::stopCall)
        messageJobs.values.toList().forEach { it.cancel() }
        lastMessageByNotification.keys.forEach { CustomRingtonePlayer.stop("message:$it") }
        identitiesInCall.clear()
        messageJobs.clear()
        lastMessageByNotification.clear()
    }

    override fun onDestroy() {
        clearAlerts()
        scope.cancel()
        ListenerDiagnostics.setConnected(false)
        super.onDestroy()
    }

    companion object {
        private const val CALL_LIMIT_MS = 60_000L
        private val WHATSAPP_PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")
        private val TELEGRAM_PACKAGES = setOf("org.telegram.messenger", "org.telegram.messenger.web")
    }
}
