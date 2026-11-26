package com.ringpass.app.service

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object CustomRingtonePlayer {

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activePlayer: MediaPlayer? = null
    private var activeKey: String? = null
    private var timeoutJob: Job? = null

    fun isActiveFor(key: String): Boolean = synchronized(lock) {
        activeKey == key && activePlayer != null
    }

    fun startLooping(
        context: Context,
        key: String,
        customUriString: String?,
        maxDurationMs: Long = 50_000L,
        onTimeoutStop: (() -> Unit)? = null
    ) {
        val appContext = context.applicationContext
        val primaryUri = customUriString?.takeIf { it.isNotBlank() }
            ?.let { runCatching { Uri.parse(it) }.getOrNull() }
        val fallbackUri = RingtoneManager.getActualDefaultRingtoneUri(appContext, RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        val uriToPlay = primaryUri ?: fallbackUri ?: return

        var shouldFallback = false
        synchronized(lock) {
            if (activeKey == key && activePlayer != null) return
            stopLocked()

            val player = MediaPlayer()
            try {
                player.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                player.setDataSource(appContext, uriToPlay)
                player.isLooping = true
                player.setOnPreparedListener { mp ->
                    synchronized(lock) { if (activePlayer === mp) mp.start() else mp.release() }
                }
                player.setOnErrorListener { mp, _, _ ->
                    synchronized(lock) {
                        if (activePlayer === mp) { activePlayer = null; activeKey = null }
                    }
                    mp.release()
                    if (primaryUri != null && fallbackUri != null && primaryUri != fallbackUri) {
                        startLooping(appContext, key, null, maxDurationMs, onTimeoutStop)
                    }
                    true
                }
                activePlayer = player
                activeKey = key
                player.prepareAsync()

                timeoutJob = scope.launch {
                    delay(maxDurationMs)
                    if (isActiveFor(key)) {
                        stop(key)
                        onTimeoutStop?.invoke()
                    }
                }
            } catch (_: Exception) {
                activePlayer = null
                activeKey = null
                runCatching { player.release() }
                if (primaryUri != null && fallbackUri != null && primaryUri != fallbackUri) {
                    shouldFallback = true
                }
            }
        }
        if (shouldFallback) startLooping(appContext, key, null, maxDurationMs, onTimeoutStop)
    }

    fun stop(key: String? = null) {
        synchronized(lock) {
            if (key != null && activeKey != null && activeKey != key) return
            stopLocked()
        }
    }

    private fun stopLocked() {
        timeoutJob?.cancel()
        timeoutJob = null
        activeKey = null
        val player = activePlayer
        activePlayer = null
        if (player != null) {
            runCatching { if (player.isPlaying) player.stop() }
            runCatching { player.release() }
        }
    }
}
