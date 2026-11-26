from pathlib import Path
import sys

REL_PATH = Path("app/src/main/java/com/ringpass/app/service/SelectiveNotificationListener.kt")

NEW_CONTENT = r'''package com.ringpass.app.service

import android.app.Notification
import android.app.NotificationManager
import android.app.Person
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.ringpass.app.RingPassApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class SelectiveNotificationListener : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lastPlayedByRuleId = ConcurrentHashMap<Long, Long>()

    private val ringtoneLock = Any()
    private var activeCallPlayer: MediaPlayer? = null
    private var activeCallKey: String? = null
    private var ringtoneTimeoutJob: Job? = null

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (sbn.packageName == packageName) return

        val notification = sbn.notification ?: return
        if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return

        val nm = getSystemService(NotificationManager::class.java) ?: return
        val isDndActive = nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL

        // Handle WhatsApp / WhatsApp Business incoming & ongoing call notifications
        if (isWhatsAppCallNotification(sbn, notification)) {
            if (isIncomingRingingCall(notification)) {
                if (!isDndActive) return
                handleIncomingWhatsAppCall(sbn, notification)
            } else {
                // Call was answered or transitioned out of ringing state
                stopCallRingtone(sbn.key)
            }
            return
        }

        // Standard message notifications: ignore ongoing events and only alert during DND
        if (!isDndActive) return
        if (sbn.isOngoing || (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0) return

        val candidates = extractSenderCandidates(notification)
        if (candidates.isEmpty()) return

        val app = applicationContext as? RingPassApplication ?: return

        scope.launch {
            val matched = app.repository.findForMessageSender(candidates) ?: return@launch
            if (!matched.allowMessages) return@launch

            val now = System.currentTimeMillis()
            val lastPlayed = lastPlayedByRuleId[matched.id] ?: 0L
            if (now - lastPlayed < SOUND_DEBOUNCE_MS) return@launch
            lastPlayedByRuleId[matched.id] = now

            app.repository.markTriggered(matched.id)
            playOneShotNotificationSound()
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (sbn.packageName in WHATSAPP_PACKAGES) {
            stopCallRingtone(sbn.key)
        }
    }

    private fun handleIncomingWhatsAppCall(
        sbn: StatusBarNotification,
        notification: Notification
    ) {
        val candidates = extractSenderCandidates(notification)
        if (candidates.isEmpty()) return

        val app = applicationContext as? RingPassApplication ?: return

        scope.launch {
            val matched = app.repository.findForMessageSender(candidates) ?: return@launch
            if (!matched.allowCalls) return@launch

            app.repository.markTriggered(matched.id)
            startCallRingtone(sbn.key)
        }
    }

    private fun isWhatsAppCallNotification(
        sbn: StatusBarNotification,
        notification: Notification
    ): Boolean {
        if (sbn.packageName !in WHATSAPP_PACKAGES) return false
        return notification.category == Notification.CATEGORY_CALL
    }

    private fun isIncomingRingingCall(notification: Notification): Boolean {
        val extras = notification.extras ?: return false

        // Android 12+ CallStyle extras
        val hasAnswerIntent = extras.get("android.answerIntent") != null
        val hasHangUpIntent = extras.get("android.hangUpIntent") != null
        if (hasAnswerIntent) return true
        if (hasHangUpIntent) return false

        // Fallback for Android 10-11 or custom WhatsApp call notifications:
        // Incoming calls expose 2 actions (Decline + Answer); ongoing/outgoing calls expose 1 (Hang up)
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (text.contains("ongoing", ignoreCase = true) ||
            text.contains("calling", ignoreCase = true)
        ) {
            return false
        }
        val actionCount = notification.actions?.size ?: 0
        return actionCount >= 2
    }

    private fun extractSenderCandidates(notification: Notification): List<String> {
        val extras = notification.extras ?: return emptyList()
        return buildList {
            val callPerson = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                extras.getParcelable("android.callPerson", Person::class.java)
            } else {
                @Suppress("DEPRECATION")
                extras.getParcelable("android.callPerson")
            }
            callPerson?.name?.toString()?.trim()?.let {
                if (it.isNotEmpty()) add(it)
            }

            extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()?.let {
                if (it.isNotEmpty()) add(it)
            }
            extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.trim()?.let {
                if (it.isNotEmpty()) add(it)
            }
            extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim()?.let {
                if (it.isNotEmpty()) add(it)
            }
        }.distinct()
    }

    private fun startCallRingtone(notificationKey: String) {
        val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: return

        synchronized(ringtoneLock) {
            if (activeCallKey == notificationKey && activeCallPlayer != null) return
            stopCallRingtoneLocked()

            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            val player = MediaPlayer()
            try {
                player.setAudioAttributes(attributes)
                player.setDataSource(applicationContext, soundUri)
                player.isLooping = true
                player.setOnPreparedListener { mp ->
                    synchronized(ringtoneLock) {
                        if (activeCallPlayer === mp) {
                            mp.start()
                        } else {
                            mp.release()
                        }
                    }
                }
                player.setOnErrorListener { mp, _, _ ->
                    synchronized(ringtoneLock) {
                        if (activeCallPlayer === mp) {
                            activeCallPlayer = null
                            activeCallKey = null
                        }
                    }
                    mp.release()
                    true
                }
                activeCallPlayer = player
                activeCallKey = notificationKey
                player.prepareAsync()

                ringtoneTimeoutJob = scope.launch {
                    delay(MAX_RING_DURATION_MS)
                    stopCallRingtone(notificationKey)
                }
            } catch (_: Exception) {
                activeCallPlayer = null
                activeCallKey = null
                try {
                    player.release()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun stopCallRingtone(notificationKey: String?) {
        synchronized(ringtoneLock) {
            if (notificationKey != null && activeCallKey != null && activeCallKey != notificationKey) {
                return
            }
            stopCallRingtoneLocked()
        }
    }

    private fun stopCallRingtoneLocked() {
        ringtoneTimeoutJob?.cancel()
        ringtoneTimeoutJob = null
        activeCallKey = null
        val player = activeCallPlayer
        activeCallPlayer = null
        if (player != null) {
            try {
                if (player.isPlaying) {
                    player.stop()
                }
            } catch (_: Exception) {
            }
            try {
                player.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun playOneShotNotificationSound() {
        val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: return

        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val player = MediaPlayer()
        try {
            player.setAudioAttributes(attributes)
            player.setDataSource(applicationContext, soundUri)
            player.setOnPreparedListener { mp -> mp.start() }
            player.setOnCompletionListener { mp -> mp.release() }
            player.setOnErrorListener { mp, _, _ ->
                mp.release()
                true
            }
            player.prepareAsync()
        } catch (_: Exception) {
            try {
                player.release()
            } catch (_: Exception) {
            }
        }
    }

    override fun onDestroy() {
        synchronized(ringtoneLock) {
            stopCallRingtoneLocked()
        }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val SOUND_DEBOUNCE_MS = 1_500L
        private const val MAX_RING_DURATION_MS = 45_000L
        private val WHATSAPP_PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")
    }
}
'''


def find_repo_root() -> Path:
    if Path("app").is_dir():
        return Path(".")
    if Path("RingPass/app").is_dir():
        return Path("RingPass")
    print("Error: Could not find 'app/' directory. Run this inside or right above the 'RingPass' folder.")
    sys.exit(1)


def main() -> None:
    root = find_repo_root()
    target = root / REL_PATH
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(NEW_CONTENT, encoding="utf-8", newline="\n")
    print(f"Updated: {target.resolve()}")


if __name__ == "__main__":
    main()
