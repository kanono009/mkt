package com.example.accutap.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class TapService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
        ready = true
        Log.i(TAG, "AccuTap tap service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) {
            instance = null
            ready = false
        }
        super.onDestroy()
    }

    /**
     * Thread-safe: may be called from the timing thread at the deadline.
     */
    fun tap(
        x: Float,
        y: Float,
        durationMs: Long,
        onComplete: ((Boolean) -> Unit)?
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            onComplete?.invoke(false)
            return false
        }

        val stroke = GestureDescription.StrokeDescription(
            Path().apply { moveTo(x, y) },
            0L,
            durationMs.coerceAtLeast(1L)
        )

        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        return dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    onComplete?.invoke(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    onComplete?.invoke(false)
                }
            },
            null
        )
    }

    companion object {
        private const val TAG = "TapService"

        @Volatile
        var ready: Boolean = false
            private set

        @Volatile
        private var instance: TapService? = null

        fun tapAt(
            x: Float,
            y: Float,
            durationMs: Long,
            onComplete: ((Boolean) -> Unit)?
        ): Boolean {
            val service = instance ?: run {
                onComplete?.invoke(false)
                return false
            }
            return service.tap(x, y, durationMs, onComplete)
        }
    }
}