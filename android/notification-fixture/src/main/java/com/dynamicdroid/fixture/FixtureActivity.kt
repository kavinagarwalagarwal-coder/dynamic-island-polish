package com.dynamicdroid.fixture

import android.app.*
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.widget.RemoteViews

/** Intentionally has no normal timer extras, duration text, category, or ongoing flag. */
class FixtureActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("fixture", "Fixture", NotificationManager.IMPORTANCE_LOW))
        val kind = intent.getStringExtra("kind") ?: "custom"
        if (kind == "clear") {
            nm.cancelAll(); session?.release(); session = null
        } else {
            if (session == null) session = MediaSession(this, "fixture").apply {
                setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, "Fixture music")
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, "Test artist").putLong(MediaMetadata.METADATA_KEY_DURATION, 600_000).build())
                setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 0, 1f)
                    .setActions(PlaybackState.ACTION_PLAY_PAUSE).build())
                isActive = true
            }
            nm.notify(101, Notification.Builder(this, "fixture").setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("Fixture music").setOngoing(true)
                .setStyle(Notification.MediaStyle().setMediaSession(session!!.sessionToken)).build())
            val paused = kind == "paused"
            val remote = RemoteViews(packageName, R.layout.timer_notification).apply {
                setChronometer(R.id.clock, SystemClock.elapsedRealtime() + 300_000, null, !paused)
                setChronometerCountDown(R.id.clock, true)
                setViewVisibility(R.id.clock, if (paused) View.GONE else View.VISIBLE)
                setTextViewText(R.id.remaining, if (paused) "5:00" else "")
                setTextViewText(R.id.control, if (paused) "Resume" else "Pause")
            }
            nm.notify(202, Notification.Builder(this, "fixture").setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("Tea").setShowWhen(false).setCustomContentView(remote).setCustomBigContentView(remote)
                .setStyle(Notification.DecoratedCustomViewStyle()).build())
        }
        startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }
    companion object { private var session: MediaSession? = null }
}
