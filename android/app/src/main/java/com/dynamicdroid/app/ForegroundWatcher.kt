package com.dynamicdroid.app

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process

/**
 * Tells the island which app is on screen, so a live activity hides while its app is open
 * and flies into the island when you swipe away from it (like iOS).
 * Needs "Usage access"; without it the island simply always shows the activity.
 */
class ForegroundWatcher(private val context: Context, private val onChange: (String) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    private var last: String? = null
    private var since = 0L
    private var running = false
    private var polling = false
    private var hintedSince = 0L

    private val tick = object : Runnable {
        override fun run() {
            poll()
            if (running && polling) handler.postDelayed(this, POLL_MS)
        }
    }

    fun start() {
        // Accessibility already supplies foreground events; avoid a second main-thread
        // UsageStats binder query every 450ms while that fast path is connected.
        val needsPolling = IslandAccessibilityService.instance == null && hasPermission(context)
        if (running && polling == needsPolling) return
        handler.removeCallbacks(tick)
        polling = needsPolling
        if (!polling && !IslandAccessibilityService.isEnabled(context)) { running = false; return }
        if (!running) since = System.currentTimeMillis() - 60_000L
        running = true
        if (polling) handler.post(tick)
    }

    fun stop() {
        running = false; polling = false
        handler.removeCallbacks(tick)
    }

    /** Fast path from the already-authorised accessibility package event. */
    fun hint(packageName: String) {
        if (!running || last == packageName) return
        since = System.currentTimeMillis()
        hintedSince = since
        last = packageName
        onChange(packageName)
    }

    @Suppress("DEPRECATION")
    private fun poll() {
        val now = System.currentTimeMillis()
        val events = runCatching { usm.queryEvents(maxOf(since, hintedSince), now) }.getOrNull() ?: return
        since = now - 200L
        val event = UsageEvents.Event()
        var foreground: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) foreground = event.packageName
        }
        if (foreground != null && foreground != last) {
            last = foreground
            onChange(foreground)
        }
    }

    companion object {
        private const val POLL_MS = 450L

        @Suppress("DEPRECATION")
        fun hasPermission(context: Context): Boolean {
            val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
            val mode = if (Build.VERSION.SDK_INT >= 29) {
                ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            } else {
                ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            }
            return mode == AppOpsManager.MODE_ALLOWED
        }
    }
}
