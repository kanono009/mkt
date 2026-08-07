package com.example.accutap.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.example.accutap.accessibility.TapService
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var tapButton: TextView
    private lateinit var panel: LinearLayout
    private lateinit var tapParams: WindowManager.LayoutParams
    private lateinit var panelParams: WindowManager.LayoutParams
    private lateinit var delayField: EditText
    private lateinit var statusView: TextView

    private val main = Handler(Looper.getMainLooper())
    private lateinit var timingThread: HandlerThread
    private lateinit var timing: Handler
    private val token = Any()

    @Volatile private var armed = false

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager

        timingThread = HandlerThread(
            "AccuTapTiming",
            android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY
        ).apply { start() }
        timing = Handler(timingThread.looper)

        // Warm the timing thread's binder path with a harmless IPC (NOT a tap).
        timing.post {
            runCatching {
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                    .areNotificationsEnabled()
            }
        }

        startForeground(NOTIF_ID, buildNotification())
        buildTapButton()
        buildPanel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        armed = false
        main.removeCallbacksAndMessages(token)
        timing.removeCallbacksAndMessages(token)
        timingThread.quitSafely()
        runCatching { wm.removeView(tapButton) }
        runCatching { wm.removeView(panel) }
        super.onDestroy()
    }

    private fun buildTapButton() {
        val size = dp(BUTTON_DP)
        tapButton = TextView(this).apply {
            text = "TAP"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundResource(android.R.drawable.presence_online)
        }
        tapParams = WindowManager.LayoutParams(
            size, size, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(110)
            y = dp(220)
        }
        tapButton.setOnTouchListener(ButtonDrag())
        wm.addView(tapButton, tapParams)
    }

    private fun buildPanel() {
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(0xEE111827.toInt())
        }
        panel.addView(
            TextView(this).apply {
                text = "AccuTap"
                textSize = 16f
                setTextColor(0xFFFFFFFF.toInt())
            },
            LinearLayout.LayoutParams(-1, -2)
        )

        delayField = EditText(this).apply {
            setText("22.000")
            hint = "Delay seconds 0.000-31.000"
            setSingleLine(true)
            textSize = 15f
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF94A3B8.toInt())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setOnTouchListener { view, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    view.requestFocus()
                    setPanelInputMode(true)
                    showIme(view)
                }
                false
            }
        }
        panel.addView(delayField, LinearLayout.LayoutParams(-1, dp(50)))

        panel.addView(
            Button(this).apply {
                text = "START COUNTDOWN"
                setOnClickListener { startCountdown() }
            },
            LinearLayout.LayoutParams(-1, dp(48))
        )

        panel.addView(
            Button(this).apply {
                text = "CANCEL"
                setOnClickListener { cancelPending("Cancelled.") }
            },
            LinearLayout.LayoutParams(-1, dp(44))
        )

        statusView = TextView(this).apply {
            text = "Drag TAP onto target, set delay, start."
            textSize = 12f
            setTextColor(0xFFE2E8F0.toInt())
            setPadding(0, dp(6), 0, 0)
        }
        panel.addView(statusView, LinearLayout.LayoutParams(-1, -2))

        panelParams = WindowManager.LayoutParams(
            dp(270),
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            PASSIVE_FLAGS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(64)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        }
        panel.setOnTouchListener(PanelDrag())
        wm.addView(panel, panelParams)
    }

    // ------------------------------------------------------------------
    // Countdown: exactly two taps.
    // Fire path = hide + dispatch inside ONE main-thread message
    // (the most stable path measured on-device).
    // ------------------------------------------------------------------

    private fun startCountdown() {
        hideIme()
        setPanelInputMode(false)

        if (!TapService.ready) {
            status("Enable the accessibility tap service first.")
            return
        }

        val ms = parseDelayMillis(delayField.text.toString())
        if (ms == null) {
            status("Delay must be between 0.000 and 31.000 seconds.")
            return
        }

        cancelPending(null)
        armed = true

        // Single monotonic base, captured once.
        val baseUptime = SystemClock.uptimeMillis()
        val baseNano = System.nanoTime()
        val effectiveMs = ms - COMPENSATION_MS
        val targetNano = baseNano + effectiveMs * 1_000_000L
        val targetUptime = baseUptime + effectiveMs

        // Tap 1: hide + dispatch in one main-thread message.
        performTap("Immediate")

        // Tap 2: wake exactly, spin, then same fire path on main.
        timing.postAtTime({
            var now = System.nanoTime()
            while (armed && now < targetNano - SPIN_WINDOW_NS) {
                SystemClock.sleep(1)
                now = System.nanoTime()
            }
            while (armed && System.nanoTime() < targetNano) { }
            if (!armed) return@postAtTime
            main.post {
                if (!armed) return@post
                performTap("Delayed")
                armed = false
                status("Done. Delayed tap fired at live position.")
            }
        }, token, maxOf(baseUptime, targetUptime - WAKE_LEAD_MS))

        status(
            String.format(Locale.US, "Armed: second tap at +%.3f s.", ms / 1000.0)
        )
    }

    private fun performTap(label: String) {
        val centerX = tapParams.x + tapParams.width / 2f
        val centerY = tapParams.y + tapParams.height / 2f

        tapButton.visibility = View.INVISIBLE

        val dispatched = TapService.tapAt(centerX, centerY, TAP_DURATION_MS) { ok ->
            if (!ok) main.post { status("$label tap cancelled by the system.") }
        }
        if (!dispatched) {
            main.post { status("$label dispatch failed. Is the tap service enabled?") }
        }

        main.postAtTime(
            { tapButton.visibility = View.VISIBLE },
            token,
            SystemClock.uptimeMillis() + RESTORE_AFTER_MS
        )
    }

    private fun cancelPending(message: String?) {
        armed = false
        main.removeCallbacksAndMessages(token)
        timing.removeCallbacksAndMessages(token)
        tapButton.visibility = View.VISIBLE
        if (message != null) status(message)
    }

    private fun togglePanel() {
        panel.visibility =
            if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    private fun setPanelInputMode(input: Boolean) {
        panelParams.flags = if (input) INPUT_FLAGS else PASSIVE_FLAGS
        runCatching { wm.updateViewLayout(panel, panelParams) }
    }

    private fun hideIme() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(delayField.windowToken, 0)
        delayField.clearFocus()
    }

    private fun showIme(view: View) {
        view.postDelayed({
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        }, 80)
    }

    private fun status(message: String) {
        statusView.text = message
    }

    private fun parseDelayMillis(raw: String): Long? {
        val trimmed = raw.trim()
        if (!trimmed.matches(Regex("\\d{1,2}(\\.\\d{1,3})?"))) return null
        val parts = trimmed.split('.')
        val seconds = parts[0].toLongOrNull() ?: return null
        val millisPart =
            if (parts.size == 2) parts[1].padEnd(3, '0').toLongOrNull() ?: 0L else 0L
        val total = seconds * 1000 + millisPart
        return if (total in 0..MAX_DELAY_MS) total else null
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "AccuTap", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("AccuTap is running")
            .setContentText("Drag TAP, set delay, start countdown.")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()

    private inner class ButtonDrag : View.OnTouchListener {
        private var rawX = 0f
        private var rawY = 0f
        private var startX = 0
        private var startY = 0
        private var moved = false
        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    rawX = event.rawX
                    rawY = event.rawY
                    startX = tapParams.x
                    startY = tapParams.y
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - rawX
                    val dy = event.rawY - rawY
                    if (abs(dx) > dp(3) || abs(dy) > dp(3)) moved = true
                    tapParams.x = startX + dx.roundToInt()
                    tapParams.y = startY + dy.roundToInt()
                    wm.updateViewLayout(tapButton, tapParams)
                }
                MotionEvent.ACTION_UP -> if (!moved) togglePanel()
            }
            return true
        }
    }

    private inner class PanelDrag : View.OnTouchListener {
        private var rawX = 0f
        private var rawY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false
        override fun onTouch(view: View, event: MotionEvent): Boolean {
            if (delayField.hasFocus()) return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    rawX = event.rawX
                    rawY = event.rawY
                    startX = panelParams.x
                    startY = panelParams.y
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - rawX
                    val dy = event.rawY - rawY
                    if (!dragging && abs(dx) < dp(8) && abs(dy) < dp(8)) return true
                    dragging = true
                    panelParams.x = startX + dx.roundToInt()
                    panelParams.y = startY + dy.roundToInt()
                    wm.updateViewLayout(panel, panelParams)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
            }
            return true
        }
    }

    companion object {
        private const val CHANNEL_ID = "accutap_overlay"
        private const val NOTIF_ID = 7
        private const val BUTTON_DP = 56
        private const val TAP_DURATION_MS = 100L
        private const val MAX_DELAY_MS = 31_000L
        private const val RESTORE_AFTER_MS = 200L
        private const val WAKE_LEAD_MS = 6L
        private const val SPIN_WINDOW_NS = 3_000_000L

        // Constant trim for the device's fixed pipeline offset.
        // Calibrate ONCE with the tester: run 4 pairs, take the median.
        //   median 22003 -> keep 3
        //   median 22006 -> set 6
        //   median 21998 -> set 1
        private const val COMPENSATION_MS = 3L

        private const val PASSIVE_FLAGS =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        private const val INPUT_FLAGS =
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
    }
}