package com.ringpass.app.dnd

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import com.ringpass.app.service.DndMode
import com.ringpass.app.service.allowsAlarmAudio

class DndController(context: Context) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(NotificationManager::class.java)

    val isActive: Boolean get() = mode() != DndMode.OFF

    fun allowsAlarmPlayback(): Boolean {
        val mode = mode()
        val alarmsAllowed = mode == DndMode.PRIORITY && runCatching {
            val nm = manager ?: return@runCatching false
            val policy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                nm.consolidatedNotificationPolicy
            } else {
                nm.notificationPolicy
            }
            policy.priorityCategories and NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS != 0
        }.getOrDefault(false)
        return allowsAlarmAudio(mode, alarmsAllowed)
    }

    private fun mode(): DndMode = when (manager?.currentInterruptionFilter) {
        NotificationManager.INTERRUPTION_FILTER_ALL -> DndMode.OFF
        NotificationManager.INTERRUPTION_FILTER_PRIORITY -> DndMode.PRIORITY
        NotificationManager.INTERRUPTION_FILTER_ALARMS -> DndMode.ALARMS_ONLY
        NotificationManager.INTERRUPTION_FILTER_NONE -> DndMode.TOTAL_SILENCE
        else -> DndMode.UNKNOWN
    }

    // Older RingPass builds could leave a temporary override behind, including an app-owned
    // implicit rule on Android 15. This migration runs from setup and from pending old work.
    fun cleanUpLegacyOverride() {
        val nm = manager ?: return
        if (!nm.isNotificationPolicyAccessGranted) return
        val prefs = appContext.getSharedPreferences("ringpass_dnd_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("legacy_override_removed", false)) return
        try {
            val pending = prefs.getBoolean("is_temporarily_unmuted", false)
            val previous = prefs.getInt("previous_interruption_filter", 0)
            if (Build.VERSION.SDK_INT >= 35) {
                nm.automaticZenRules.keys.filter { it == "implicit_${appContext.packageName}" }
                    .forEach { nm.removeAutomaticZenRule(it) }
            } else if (pending && previous in NotificationManager.INTERRUPTION_FILTER_ALL..
                NotificationManager.INTERRUPTION_FILTER_ALARMS &&
                nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL
            ) {
                nm.setInterruptionFilter(previous)
            }
            prefs.edit().putBoolean("legacy_override_removed", true)
                .remove("is_temporarily_unmuted").remove("previous_interruption_filter").apply()
        } catch (error: RuntimeException) {
            Log.w("RingPassDnd", "Unable to clear the previous RingPass DND override", error)
        }
    }
}
