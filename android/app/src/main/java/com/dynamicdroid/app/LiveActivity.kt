package com.dynamicdroid.app

import android.app.PendingIntent
import android.app.RemoteInput
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

enum class IslandMode { MUSIC, CALL, TIMER, NAVIGATION, CHARGING, MESSAGE, VOLUME, DEVICE, DOWNLOAD, ALARM, RINGER }

/** What the ringer switch or Do Not Disturb just changed to. */
enum class RingerState { RING, VIBRATE, SILENT, DND_ON, DND_OFF }

/** Incoming rings, Calling… while an outgoing call connects, Active once the call timer runs. */
enum class CallState { INCOMING, CALLING, ACTIVE }

data class IslandAction(val label: String, val intent: PendingIntent)

/** A notification's own reply action; the island sends typed text back through it. */
data class ReplyAction(val label: String, val intent: PendingIntent, val remoteInput: RemoteInput)

/** One thing the island can show. Built by the notification listener, drawn by IslandView. */
data class LiveActivity(
    val key: String,
    val packageName: String,
    val mode: IslandMode,
    val title: String,
    val subtitle: String,
    val appLabel: String,
    val appIcon: Bitmap?,
    /** Album art for music, sender/caller photo for messages and calls. */
    val artwork: Bitmap?,
    val accent: Int,
    val contentIntent: PendingIntent? = null,
    val actions: List<IslandAction> = emptyList(),
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val positionUpdatedAt: Long = SystemClock.elapsedRealtime(),
    val playbackSpeed: Float = 1f,
    val durationMs: Long = 0L,
    val hasMediaSession: Boolean = false,
    /** Wall-clock base (System.currentTimeMillis) for calls, timers and stopwatches. */
    val chronometerBase: Long? = null,
    val chronometerCountDown: Boolean = false,
    val pausedTimerMs: Long? = null,
    /** Monotonic deadline: changing the phone's wall clock cannot jump the digits. */
    val clockBaseElapsed: Long? = chronometerBase?.let {
        SystemClock.elapsedRealtime() + (it - System.currentTimeMillis())
    },
    /** 0..1 for downloads, volume and device battery; null when unknown or indeterminate. */
    val progress: Float? = null,
    val callState: CallState? = null,
    val reply: ReplyAction? = null,
    /** Device islands: true for a disconnect ("Bluetooth lost"). */
    val lost: Boolean = false,
    /** Ringer islands: which state the phone just switched to. */
    val ringer: RingerState? = null,
    /** Volume island: Do Not Disturb is on (shown on its button). */
    val dndOn: Boolean = false,
) {
    val isTransient: Boolean get() = mode == IslandMode.MESSAGE || mode == IslandMode.CHARGING ||
        mode == IslandMode.VOLUME || mode == IslandMode.DEVICE || mode == IslandMode.RINGER

    fun livePositionMs(now: Long = SystemClock.elapsedRealtime()): Long {
        val p = if (isPlaying) positionMs + ((now - positionUpdatedAt) * playbackSpeed).toLong() else positionMs
        return if (durationMs > 0L) p.coerceIn(0L, durationMs) else p.coerceAtLeast(0L)
    }

    fun clockMs(now: Long = SystemClock.elapsedRealtime()): Long? =
        pausedTimerMs ?: clockBaseElapsed?.let { ClockTimeline.remaining(it, now, chronometerCountDown) }

    fun chronometerText(): String? = clockMs()?.let { ClockTimeline.display(it, chronometerCountDown) }
}

fun formatDuration(ms: Long): String {
    val total = (ms / 1000L).coerceAtLeast(0L)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "$h:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
    else "$m:${s.toString().padStart(2, '0')}"
}

/**
 * In-process hand-off between the notification listener and the overlay service.
 * Both run in the same process, so objects (and bitmaps) are passed directly instead of
 * being serialised through broadcasts on every update.
 */
object IslandBus {
    const val MEDIA_TOGGLE = "toggle"
    const val MEDIA_PREVIOUS = "previous"
    const val MEDIA_NEXT = "next"
    const val MEDIA_SEEK = "seek"

    interface Listener {
        fun onActivity(activity: LiveActivity)
        fun onRemoved(key: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private val ongoing = LinkedHashMap<String, LiveActivity>()

    var listener: Listener? = null
        set(value) {
            field = value
            value?.let { l -> ongoing.values.toList().forEach(l::onActivity) }
        }

    /** Set by the overlay service; told when notification access connects or drops. */
    var listenerChanged: (() -> Unit)? = null

    fun notifyListenerChanged() = main.post { listenerChanged?.invoke() }

    /** Set by the notification listener; returns true when a media session handled it. */
    var mediaControl: ((command: String, positionMs: Long?) -> Boolean)? = null

    fun post(activity: LiveActivity) = main.post {
        if (!activity.isTransient) {
            ongoing[activity.key] = activity
        }
        listener?.onActivity(activity)
        if (activity.mode == IslandMode.MUSIC) MusicCenter.fromIsland(activity)
    }

    fun remove(key: String) = main.post {
        ongoing.remove(key)
        listener?.onRemoved(key)
        MusicCenter.removedFromIsland(key)
    }

    fun latestOngoing(): LiveActivity? = ongoing.values.lastOrNull()
    fun allOngoing(): List<LiveActivity> = ongoing.values.toList()
}
