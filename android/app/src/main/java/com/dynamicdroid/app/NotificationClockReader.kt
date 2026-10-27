package com.dynamicdroid.app

import android.app.Notification
import android.content.Context
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Chronometer
import android.widget.FrameLayout
import android.widget.TextView

/** Reads notification payloads, including Clock apps' custom RemoteViews. Never reads the screen. */
object NotificationClockReader {
    data class Clock(val baseElapsed: Long?, val remainingMs: Long?, val countDown: Boolean,
                     val paused: Boolean, val source: String)

    @Suppress("DEPRECATION")
    fun read(context: Context, n: Notification, clockApp: Boolean, known: Boolean): Clock? {
        val now = SystemClock.elapsedRealtime()
        val nativeChronometer = n.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)
        val nativeDown = n.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN)
        if ((nativeChronometer || nativeDown) && n.`when` > 0L) {
            val base = now + (n.`when` - System.currentTimeMillis())
            return Clock(base, ClockTimeline.remaining(base, now, nativeDown), nativeDown, false, "notification chronometer")
        }
        if (!clockApp && !known && n.category != Notification.CATEGORY_ALARM) return null
        val layouts = listOfNotNull(n.bigContentView, n.contentView, n.headsUpContentView).distinct()
        for (layout in layouts) {
            val root = runCatching { layout.apply(context, FrameLayout(context)) }.getOrNull() ?: continue
            val clocks = ArrayList<Chronometer>()
            val text = ArrayList<String>()
            var visited = 0
            fun visit(view: View, visible: Boolean, depth: Int) {
                if (++visited > 256 || depth > 16) return
                if (view is Chronometer) clocks.add(view)
                val showing = visible && view.visibility == View.VISIBLE
                if (showing) {
                    if (view is TextView && view !is Chronometer) text.add(view.text.toString())
                    view.contentDescription?.toString()?.let(text::add)
                }
                if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i), showing, depth + 1)
            }
            visit(root, true, 0)
            try {
                val labels = text + n.actions.orEmpty().map { it.title.toString() }
                val paused = labels.any { it.contains("paused", true) || it.contains("resume", true) }
                val controls = labels.any { label -> listOf("pause", "resume", "cancel", "stop").any { label.contains(it, true) } }
                val duration = text.firstNotNullOfOrNull(ClockTimeline::parseRemaining)
                val clock = clocks.firstOrNull { it.visibility == View.VISIBLE && it.base > 0L &&
                    (it.isCountDown || clockApp && it.base > now) }
                if (paused && duration != null) return Clock(null, duration, true, true, "custom paused timer")
                if (clock != null) {
                    val down = clock.isCountDown || clock.base > now
                    return Clock(if (paused) null else clock.base,
                        ClockTimeline.remaining(clock.base, now, down), down, paused, "custom chronometer")
                }
                if ((known || clockApp && controls) && duration != null) {
                    return Clock(if (paused) null else now + duration, duration, true, paused, "custom timer text")
                }
            } finally {
                // Inflated notification widgets are temporary and never attached to a window.
                clocks.forEach(Chronometer::stop)
            }
        }
        return null
    }
}
