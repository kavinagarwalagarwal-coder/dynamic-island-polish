package com.dynamicdroid.fixture

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.widget.RemoteViews

/**
 * Posts the fixture's notifications. The timer intentionally has no normal timer extras,
 * duration text, category, or ongoing flag.
 */
object FixturePoster {
    private var session: MediaSession? = null

    fun post(context: Context, kind: String) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("fixture", "Fixture", NotificationManager.IMPORTANCE_LOW))
        if (kind == "clear") {
            nm.cancelAll(); session?.release(); session = null
            return
        }
        if (kind == "message" || kind == "message2") {
            // High importance, so the phone shows its own pop-up for it.
            nm.createNotificationChannel(NotificationChannel("alerts", "Alerts", NotificationManager.IMPORTANCE_HIGH))
            val second = kind == "message2"
            nm.notify(if (second) 304 else 303, Notification.Builder(context, "alerts")
                .setSmallIcon(android.R.drawable.sym_action_chat)
                .setContentTitle(if (second) "Popup second" else "Popup first")
                .setContentText("Island takes this one")
                .setCategory(Notification.CATEGORY_MESSAGE).build())
            return
        }
        if (session == null) session = MediaSession(context, "fixture").apply {
            setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, "Fixture music")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "Test artist").putLong(MediaMetadata.METADATA_KEY_DURATION, 600_000).build())
            setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 0, 1f)
                .setActions(PlaybackState.ACTION_PLAY_PAUSE).build())
            isActive = true
        }
        nm.notify(101, Notification.Builder(context, "fixture").setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Fixture music").setOngoing(true)
            .setStyle(Notification.MediaStyle().setMediaSession(session!!.sessionToken)).build())
        if (kind == "player") {
            nm.cancel(202)
            return
        }
        val paused = kind == "paused"
        val remote = RemoteViews(context.packageName, R.layout.timer_notification).apply {
            setChronometer(R.id.clock, SystemClock.elapsedRealtime() + 300_000, null, !paused)
            setChronometerCountDown(R.id.clock, true)
            setViewVisibility(R.id.clock, if (paused) View.GONE else View.VISIBLE)
            setTextViewText(R.id.remaining, if (paused) "5:00" else "")
            setTextViewText(R.id.control, if (paused) "Resume" else "Pause")
        }
        val builder = Notification.Builder(context, "fixture").setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Tea").setShowWhen(false)
        if (kind == "chip") {
            builder.addExtras(Bundle().apply { putCharSequence("android.shortCriticalText", "5:00") })
            val action = PendingIntent.getActivity(context, 0, Intent(context, FixtureActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(Notification.Action.Builder(null, "Pause", action).build())
        } else builder.setCustomContentView(remote).setCustomBigContentView(remote).setStyle(Notification.DecoratedCustomViewStyle())
        nm.notify(202, builder.build())
    }
}

/**
 * Posts in the background: the fixture never comes to the front, so tests do not race the
 * island's "hide music while its own app is open" rule.
 */
class FixtureReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        FixturePoster.post(context, intent.getStringExtra("kind") ?: "custom")
    }
}

/** Foreground variant: "player" stays open as the music app; other kinds hand over to Settings. */
class FixtureActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val kind = intent.getStringExtra("kind") ?: "custom"
        FixturePoster.post(this, kind)
        if (kind == "player") {
            setContentView(android.widget.TextView(this).apply {
                text = "Fixture music player"; textSize = 28f
                setTextColor(android.graphics.Color.WHITE); setBackgroundColor(android.graphics.Color.DKGRAY)
                gravity = android.view.Gravity.CENTER
            })
            return
        }
        if (kind != "clear") startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }
}
