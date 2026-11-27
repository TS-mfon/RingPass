package com.ringpass.app.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.annotation.MainThread
import com.ringpass.app.util.RingtonePrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

internal object CustomRingtonePlayer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val playing = mutableMapOf<String, Job>()

    @MainThread
    suspend fun ring(
        context: Context,
        key: String,
        contactUri: String?,
        alarm: Boolean,
        durationMs: Long,
        canStart: () -> Boolean = { true }
    ): Boolean = playAlert(key, durationMs, alarm, repeat = true, canStart = canStart) {
        prepare(context, alarm, ringing = true) {
            ringtoneCandidates(
                contactUri,
                RingtonePrefs.getGlobalUri(context),
                defaultSounds(context, RingtoneManager.TYPE_RINGTONE) +
                    defaultSounds(context, RingtoneManager.TYPE_ALARM)
            )
        }
    }

    @MainThread
    suspend fun chime(context: Context, key: String, canStart: () -> Boolean): Boolean =
        playAlert(key, 5_000L, alarm = true, repeat = false, canStart = canStart) {
            prepare(context, alarm = true, ringing = false) {
                defaultSounds(context, RingtoneManager.TYPE_NOTIFICATION) +
                    defaultSounds(context, RingtoneManager.TYPE_ALARM)
            }
        }

    private suspend fun playAlert(
        key: String,
        durationMs: Long,
        alarm: Boolean,
        repeat: Boolean,
        canStart: () -> Boolean,
        prepare: suspend () -> MediaPlayer?
    ): Boolean {
        var started = false
        withTimeoutOrNull(1_800L) {
            val player = prepare()
            if (player != null) {
                if (canStart()) started = play(key, player, durationMs) else player.release()
            }
        }
        return started || (alarm && canStart() && playFallback(key, repeat, durationMs))
    }

    private suspend fun prepare(
        context: Context,
        alarm: Boolean,
        ringing: Boolean,
        sounds: () -> List<String>
    ): MediaPlayer? = suspendCancellableCoroutine { continuation ->
        val job = scope.launch(Dispatchers.IO) {
            val attributes = AudioAttributes.Builder()
                .setUsage(if (alarm) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val candidates = runCatching(sounds).getOrElse {
                Log.w("RingPassAudio", "Could not read ringtone settings", it)
                emptyList()
            }
            for (uri in candidates) {
                if (!continuation.isActive) break
                val player = MediaPlayer()
                try {
                    player.setAudioAttributes(attributes)
                    player.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
                    player.setDataSource(context, Uri.parse(uri))
                    player.isLooping = ringing
                    player.prepare()
                    continuation.resume(player) { _, unused, _ -> unused.release() }
                    return@launch
                } catch (error: Exception) {
                    player.release()
                    Log.w("RingPassAudio", "Could not prepare a ringtone: ${error.javaClass.simpleName}")
                }
            }
            continuation.resume(null)
        }
        continuation.invokeOnCancellation { job.cancel() }
    }

    private fun defaultSounds(context: Context, type: Int): List<String> = listOfNotNull(
        runCatching { RingtoneManager.getActualDefaultRingtoneUri(context, type)?.toString() }.getOrNull(),
        RingtoneManager.getDefaultUri(type)?.toString()
    )

    @MainThread
    private fun play(key: String, player: MediaPlayer, durationMs: Long): Boolean {
        stop(key)
        val job = scope.launch {
            try {
                delay(durationMs)
            } finally {
                player.release()
            }
        }
        playing[key] = job
        job.invokeOnCompletion { playing.remove(key, job) }
        try {
            player.setOnErrorListener { _, _, _ ->
                job.cancel()
                true
            }
            player.setOnCompletionListener { job.cancel() }
            player.start()
        } catch (error: RuntimeException) {
            job.cancel()
            return false
        }
        return true
    }

    @MainThread
    private fun playFallback(key: String, repeat: Boolean, durationMs: Long): Boolean {
        stop(key)
        val tone = runCatching { ToneGenerator(AudioManager.STREAM_ALARM, 85) }.getOrNull()
            ?: return false
        if (!tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 450)) {
            tone.release()
            return false
        }
        val job = scope.launch {
            try {
                val started = SystemClock.elapsedRealtime()
                do {
                    delay(if (repeat) 1_200L else 550L)
                    if (!repeat || SystemClock.elapsedRealtime() - started >= durationMs) break
                    tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 450)
                } while (true)
            } finally {
                tone.release()
            }
        }
        playing[key] = job
        job.invokeOnCompletion { playing.remove(key, job) }
        return true
    }

    @MainThread
    fun stop(key: String): Boolean {
        val job = playing.remove(key) ?: return false
        job.cancel()
        return true
    }
}
