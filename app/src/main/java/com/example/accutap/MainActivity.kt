package com.example.accutap

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.example.accutap.accessibility.TapService
import com.example.accutap.overlay.OverlayService

class MainActivity : Activity() {

    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(28), dp(24), dp(24))
            setBackgroundColor(0xFF0F172A.toInt())
        }

        root.addView(
            TextView(this).apply {
                text = "AccuTap"
                textSize = 26f
                setTextColor(0xFFF8FAFC.toInt())
                gravity = Gravity.CENTER
            },
            LinearLayout.LayoutParams(-1, -2)
        )

        root.addView(
            TextView(this).apply {
                text = "One immediate tap at the TAP button center, then one delayed tap at the button's live position. Single monotonic deadline, urgent timing thread, deadline-side gesture dispatch."
                textSize = 14f
                setTextColor(0xFF94A3B8.toInt())
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, dp(18))
            },
            LinearLayout.LayoutParams(-1, -2)
        )

        statusView = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFFE2E8F0.toInt())
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setBackgroundColor(0xFF1E293B.toInt())
        }
        root.addView(
            statusView,
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(18) }
        )

        root.addView(actionButton("1. Grant Overlay Permission") {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            } else {
                toast("Overlay permission already granted.")
            }
        })

        root.addView(actionButton("2. Enable Accessibility Tap Service") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            toast("Enable 'AccuTap Tap Service'.")
        })

        root.addView(actionButton("3. Start Overlay") {
            if (!Settings.canDrawOverlays(this)) {
                toast("Grant overlay permission first.")
                return@actionButton
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
            }
            val intent = Intent(this, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            toast("Overlay started.")
        })

        root.addView(actionButton("Stop Overlay") {
            stopService(Intent(this, OverlayService::class.java))
            toast("Overlay stopped.")
        })

        setContentView(root)
    }

    private fun actionButton(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 15f
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(-1, dp(52)).apply {
                bottomMargin = dp(10)
            }
        }
    }

    private fun refreshStatus() {
        val overlay = if (Settings.canDrawOverlays(this)) "granted" else "not granted"
        val a11y = if (isTapServiceEnabled()) "enabled" else "not enabled"
        statusView.text =
            "Overlay permission: $overlay\n" +
                    "Accessibility tap service: $a11y\n" +
                    "Both must be active before taps can be injected."
    }

    private fun isTapServiceEnabled(): Boolean {
        val expected = "$packageName/${TapService::class.java.name}"
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        for (name in splitter) {
            if (name.equals(expected, ignoreCase = true)) return true
        }
        return false
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}