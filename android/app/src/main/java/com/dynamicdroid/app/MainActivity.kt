package com.dynamicdroid.app

import android.Manifest
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(9, 10, 12)
        setContentView(buildScreen())
        if (Build.VERSION.SDK_INT >= 33) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        val allowed = Settings.canDrawOverlays(this)
        findViewById<Button>(OVERLAY_BUTTON)?.text =
            if (allowed) "DYNAMIC ISLAND IS ACTIVE" else "ALLOW SCREEN OVERLAY"
        if (allowed) startIsland()
    }

    private fun buildScreen(): LinearLayout {
        val pad = dp(24)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, dp(48), pad, pad)
            setBackgroundColor(Color.rgb(9, 10, 12))
            addView(label("DYNAMIC · DROID", 13f, Color.WHITE).apply {
                letterSpacing = .15f
                gravity = Gravity.CENTER
            }, row(height = dp(48)))
            addView(IslandView(this@MainActivity).apply {
                mode = IslandMode.MUSIC
                isPlaying = true
                playbackPositionMs = 107_000L
                durationMs = 242_000L
                onTogglePlayback = { isPlaying = !isPlaying }
                onPreviousAction = { playbackPositionMs = 0L }
                onNextAction = { playbackPositionMs = 0L }
                onSeekAction = { playbackPositionMs = it }
                expanded = true
            }, row(height = dp(180)).apply { topMargin = dp(34) })
            addView(label("Your day,\nat a glance.", 46f, Color.WHITE).apply {
                gravity = Gravity.CENTER
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }, row(height = ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(24) })
            addView(label(
                "A native live-activity surface for Android. Grant overlay and notification access to connect it to your device.",
                14f, Color.rgb(145, 148, 157)
            ).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.35f) },
                row(height = ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18) })
            addView(action("ALLOW SCREEN OVERLAY", OVERLAY_BUTTON) {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")))
                } else {
                    startIsland()
                }
            }, row(height = dp(56)).apply { topMargin = dp(36) })
            addView(action("ENABLE NOTIFICATION ACCESS", View.generateViewId()) {
                startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
            }, row(height = dp(56)).apply { topMargin = dp(12) })
            addView(label(
                "The overlay can show media, calls, timers, navigation, charging, and messages when supported notifications arrive.",
                11f, Color.rgb(91, 94, 102)
            ).apply { gravity = Gravity.CENTER }, row(height = ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(24) })
        }
    }

    private fun action(text: String, viewId: Int, click: () -> Unit) = Button(this).apply {
        id = viewId
        this.text = text
        setTextColor(Color.BLACK)
        setBackgroundColor(Color.rgb(101, 215, 255))
        textSize = 11f
        letterSpacing = .12f
        setOnClickListener { click() }
    }

    private fun label(text: String, size: Float, color: Int) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
    }

    private fun row(width: Int = ViewGroup.LayoutParams.MATCH_PARENT, height: Int) =
        LinearLayout.LayoutParams(width, height)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun startIsland() {
        ContextCompat.startForegroundService(this, Intent(this, IslandOverlayService::class.java))
    }

    companion object { private const val OVERLAY_BUTTON = 4811 }
}
