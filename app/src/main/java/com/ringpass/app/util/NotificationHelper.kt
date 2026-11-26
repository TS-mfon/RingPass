package com.ringpass.app.util

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.ringpass.app.QuickNoteActivity

object NotificationHelper {
    const val CHANNEL_OVERLAY_SERVICE = "ringpass_overlay_service"
    const val CHANNEL_POST_CALL_NOTE = "ringpass_post_call_note"

    const val EXTRA_NUMBER = "extra_number"

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        val overlayChannel = NotificationChannel(
            CHANNEL_OVERLAY_SERVICE,
            "Active Call Note Banner",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shown while displaying a floating caller note during an incoming call."
            setShowBadge(false)
        }

        val postCallChannel = NotificationChannel(
            CHANNEL_POST_CALL_NOTE,
            "Quick Note Prompts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Actionable prompt to attach a note to a recent unknown caller."
        }

        manager.createNotificationChannel(overlayChannel)
        manager.createNotificationChannel(postCallChannel)
    }

    fun buildOverlayServiceNotification(context: Context, number: String): Notification {
        ensureChannels(context)
        return NotificationCompat.Builder(context, CHANNEL_OVERLAY_SERVICE)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle("RingPass Call Note Active")
            .setContentText("Showing note for $number")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    fun showPostCallQuickNotePrompt(context: Context, number: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        ensureChannels(context)

        val quickNoteIntent = Intent(context, QuickNoteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_NUMBER, number)
        }

        val notificationId = 4000 + (PhoneNormalizer.toMatchKey(number).hashCode() and 0x0FFF)
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            quickNoteIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_POST_CALL_NOTE)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle("Add note for $number")
            .setContentText("Tap to attach a note & allow future calls through DND.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .addAction(
                android.R.drawable.ic_menu_edit,
                "Add Note",
                pendingIntent
            )
            .build()

        NotificationManagerCompat.from(context).notify(notificationId, notification)
    }
}
