package com.ringpass.app.service

import android.app.Notification
import android.app.NotificationManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.ringpass.app.RingPassApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class SelectiveNotificationListener : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lastPlayedByRuleId = ConcurrentHashMap<Long, Long>()

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return

        if (sbn.packageName == packageName) return
        val notification = sbn.notification ?: return
        if (sbn.isOngoing || (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0) return
        if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return

        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL) return

        val extras = notification.extras ?: return
        val candidates = buildList {
            extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()?.let {
                if (it.isNotEmpty()) add(it)
            }
            extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.trim()?.let {
                if (it.isNotEmpty()) add(it)
            }
            extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim()?.let {
                if (it.isNotEmpty()) add(it)
            }
        }
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
            playAlarmUsageNotificationSound()
        }
    }

    private fun playAlarmUsageNotificationSound() {
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
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val SOUND_DEBOUNCE_MS = 1_500L
    }
}
