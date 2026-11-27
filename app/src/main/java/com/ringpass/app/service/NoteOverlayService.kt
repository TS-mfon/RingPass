package com.ringpass.app.service

import android.annotation.SuppressLint
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.ringpass.app.R
import com.ringpass.app.util.NotificationHelper
import kotlin.math.abs

class NoteOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private val autoDismissHandler = Handler(Looper.getMainLooper())
    private val autoDismissRunnable = Runnable { stopOverlay() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_DISMISS) {
            stopOverlay()
            return START_NOT_STICKY
        }

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        val number = intent.getStringExtra(EXTRA_NUMBER).orEmpty()
        val note = intent.getStringExtra(EXTRA_NOTE).orEmpty()
        if (number.isBlank() || note.isBlank()) {
            stopOverlay()
            return START_NOT_STICKY
        }

        val startedAsForeground = intent.getBooleanExtra(EXTRA_STARTED_FOREGROUND, false)
        if (startedAsForeground) {
            promoteToForeground(number)
        }

        showOrUpdateOverlay(number, note)

        autoDismissHandler.removeCallbacks(autoDismissRunnable)
        autoDismissHandler.postDelayed(autoDismissRunnable, MAX_OVERLAY_DURATION_MS)

        return START_NOT_STICKY
    }

    private fun promoteToForeground(number: String) {
        val notification = NotificationHelper.buildOverlayServiceNotification(this, number)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    OVERLAY_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                ServiceCompat.startForeground(
                    this,
                    OVERLAY_NOTIFICATION_ID,
                    notification,
                    0
                )
            }
        } catch (_: Exception) {
        }
    }

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    private fun showOrUpdateOverlay(number: String, note: String) {
        val wm = windowManager ?: getSystemService(WindowManager::class.java).also {
            windowManager = it
        } ?: return

        val existingView = overlayView
        if (existingView != null) {
            existingView.findViewById<TextView>(R.id.tv_overlay_number).text = number
            existingView.findViewById<TextView>(R.id.tv_overlay_note).text = note
            return
        }

        val view = LayoutInflater.from(this).inflate(R.layout.view_note_overlay, null)
        val card = view.findViewById<LinearLayout>(R.id.overlay_card)
        card.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = resources.displayMetrics.density * 18f
            setColor(0xF5111622.toInt())
            setStroke((resources.displayMetrics.density * 1.5f).toInt(), 0xFF4FD1C5.toInt())
        }

        view.findViewById<TextView>(R.id.tv_overlay_number).text = number
        view.findViewById<TextView>(R.id.tv_overlay_note).text = note
        view.findViewById<ImageButton>(R.id.btn_overlay_close).setOnClickListener {
            stopOverlay()
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = (resources.displayMetrics.density * 52f).toInt()
        }

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        card.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > 4 || abs(dy) > 4) {
                        params.x = initialX + dx
                        params.y = initialY + dy
                        overlayView?.let { wm.updateViewLayout(it, params) }
                    }
                    true
                }
                else -> false
            }
        }

        try {
            wm.addView(view, params)
            overlayView = view
            layoutParams = params
        } catch (_: Exception) {
            stopOverlay()
        }
    }

    private fun stopOverlay() {
        autoDismissHandler.removeCallbacks(autoDismissRunnable)
        overlayView?.let { view ->
            try {
                windowManager?.removeViewImmediate(view)
            } catch (_: Exception) {
            }
        }
        overlayView = null
        layoutParams = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        autoDismissHandler.removeCallbacks(autoDismissRunnable)
        overlayView?.let { view ->
            try {
                windowManager?.removeViewImmediate(view)
            } catch (_: Exception) {
            }
        }
        overlayView = null
        super.onDestroy()
    }

    companion object {
        private const val ACTION_SHOW = "com.ringpass.app.action.SHOW_OVERLAY"
        private const val ACTION_DISMISS = "com.ringpass.app.action.DISMISS_OVERLAY"
        private const val EXTRA_NUMBER = "extra_number"
        private const val EXTRA_NOTE = "extra_note"
        private const val EXTRA_STARTED_FOREGROUND = "extra_started_foreground"
        private const val OVERLAY_NOTIFICATION_ID = 3001
        private const val MAX_OVERLAY_DURATION_MS = 90_000L

        fun show(context: Context, number: String, note: String) {
            if (!Settings.canDrawOverlays(context)) return

            val fgIntent = Intent(context, NoteOverlayService::class.java).apply {
                action = ACTION_SHOW
                putExtra(EXTRA_NUMBER, number)
                putExtra(EXTRA_NOTE, note)
                putExtra(EXTRA_STARTED_FOREGROUND, true)
            }

            try {
                ContextCompat.startForegroundService(context, fgIntent)
            } catch (e: Exception) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    e is ForegroundServiceStartNotAllowedException
                ) {
                    val bgIntent = Intent(fgIntent).putExtra(EXTRA_STARTED_FOREGROUND, false)
                    try {
                        context.startService(bgIntent)
                    } catch (_: Exception) {
                        ListenerDiagnostics.log("Android blocked the background caller note overlay.")
                    }
                } else {
                    ListenerDiagnostics.log("Android could not start the caller note overlay.")
                }
            }
        }

        fun dismiss(context: Context) {
            val intent = Intent(context, NoteOverlayService::class.java).apply {
                action = ACTION_DISMISS
            }
            try {
                context.stopService(intent)
            } catch (_: Exception) {
            }
        }
    }
}
