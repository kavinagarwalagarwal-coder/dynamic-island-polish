package com.dynamicdroid.app

import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.net.Uri
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream

class IslandNotificationListener : NotificationListenerService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentNotification: StatusBarNotification? = null
    private var currentMediaController: MediaController? = null

    private val mediaControllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            publishCurrentMedia()
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            publishCurrentMedia()
        }
    }

    private val mediaActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val controller = currentMediaController ?: return
            val controls = controller.transportControls
            when (intent?.getStringExtra(EXTRA_MEDIA_COMMAND)) {
                MEDIA_TOGGLE -> {
                    if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) {
                        controls.pause()
                    } else {
                        controls.play()
                    }
                }
                MEDIA_PREVIOUS -> controls.skipToPrevious()
                MEDIA_NEXT -> controls.skipToNext()
                MEDIA_SEEK -> controls.seekTo(
                    intent.getLongExtra(EXTRA_MEDIA_POSITION, 0L).coerceAtLeast(0L)
                )
            }
            mainHandler.postDelayed({ publishCurrentMedia() }, 180L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        ContextCompat.registerReceiver(
            this,
            mediaActionReceiver,
            android.content.IntentFilter(ACTION_MEDIA_CONTROL),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        activeNotifications
            ?.lastOrNull { sbn ->
                sbn.notification.category == Notification.CATEGORY_TRANSPORT ||
                    sessionToken(sbn.notification) != null ||
                    findMediaController(sbn) != null
            }
            ?.let(::onNotificationPosted)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val notification = sbn.notification
        val extras = notification.extras
        val controller = findMediaController(sbn)
        val mediaSessionToken = sessionToken(notification)
        val isMediaNotification =
            notification.category == Notification.CATEGORY_TRANSPORT ||
                controller != null ||
                mediaSessionToken != null

        val mode = when (notification.category) {
            Notification.CATEGORY_CALL -> IslandMode.CALL
            Notification.CATEGORY_NAVIGATION -> IslandMode.NAVIGATION
            Notification.CATEGORY_MESSAGE -> IslandMode.MESSAGE
            Notification.CATEGORY_PROGRESS -> IslandMode.TIMER
            else -> if (isMediaNotification) IslandMode.MUSIC else IslandMode.MESSAGE
        }

        if (mode == IslandMode.MUSIC) {
            currentNotification = sbn
            setMediaController(controller)
            publishCurrentMedia()
            return
        }

        currentNotification = null
        setMediaController(null)
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?.takeIf { it.isNotBlank() }
            ?: mode.name.lowercase().replaceFirstChar { it.uppercase() }
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()

        sendBroadcast(Intent(IslandOverlayService.ACTION_ACTIVITY).apply {
            setPackage(packageName)
            putExtra(IslandOverlayService.EXTRA_MODE, mode.name)
            putExtra(IslandOverlayService.EXTRA_TITLE, title)
            putExtra(IslandOverlayService.EXTRA_SUBTITLE, text)
            putExtra(IslandOverlayService.EXTRA_KEY, sbn.key)
            putExtra(IslandOverlayService.EXTRA_CONTENT_INTENT, notification.contentIntent)
            putParcelableArrayListExtra(
                IslandOverlayService.EXTRA_ACTION_INTENTS,
                ArrayList<PendingIntent>(notification.actions.orEmpty().mapNotNull { it.actionIntent })
            )
            putStringArrayListExtra(
                IslandOverlayService.EXTRA_ACTION_LABELS,
                ArrayList(notification.actions.orEmpty().map { it.title.toString() })
            )
        })
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        sendBroadcast(Intent(IslandOverlayService.ACTION_REMOVED).apply {
            setPackage(packageName)
            putExtra(IslandOverlayService.EXTRA_KEY, sbn.key)
        })
        if (sbn.key == currentNotification?.key) {
            currentNotification = null
            setMediaController(null)
        }
    }

    override fun onDestroy() {
        currentMediaController?.unregisterCallback(mediaControllerCallback)
        currentMediaController = null
        currentNotification = null
        unregisterReceiver(mediaActionReceiver)
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun findMediaController(sbn: StatusBarNotification): MediaController? {
        val token = sessionToken(sbn.notification)
        val activeSession = runCatching {
            val manager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val sessions = manager.getActiveSessions(
                ComponentName(this, IslandNotificationListener::class.java)
            ).filter { it.packageName == sbn.packageName }
            sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                ?: sessions.firstOrNull()
        }.getOrNull()

        return activeSession ?: token?.let {
            runCatching { MediaController(this, it) }.getOrNull()
        }
    }

    @Suppress("DEPRECATION")
    private fun sessionToken(notification: Notification): MediaSession.Token? =
        if (Build.VERSION.SDK_INT >= 33) {
            notification.extras.getParcelable(
                Notification.EXTRA_MEDIA_SESSION,
                MediaSession.Token::class.java
            )
        } else {
            notification.extras.getParcelable(Notification.EXTRA_MEDIA_SESSION)
        }

    private fun setMediaController(controller: MediaController?) {
        if (currentMediaController?.sessionToken == controller?.sessionToken) return
        currentMediaController?.unregisterCallback(mediaControllerCallback)
        currentMediaController = controller
        currentMediaController?.registerCallback(mediaControllerCallback, mainHandler)
    }

    private fun publishCurrentMedia() {
        val sbn = currentNotification ?: return
        val notification = sbn.notification
        val extras = notification.extras
        val metadata = currentMediaController?.metadata
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?.takeIf { it.isNotBlank() }
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
            ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
                ?.takeIf { it.isNotBlank() }
            ?: "Unknown track"
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?.takeIf { it.isNotBlank() }
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?.takeIf { it.isNotBlank() }
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val playbackState = currentMediaController?.playbackState
        val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)
            ?.takeIf { it > 0L } ?: 0L
        val position = playbackState?.position?.coerceAtLeast(0L) ?: 0L
        val artwork = mediaArtwork(metadata, notification)

        sendBroadcast(Intent(IslandOverlayService.ACTION_ACTIVITY).apply {
            setPackage(packageName)
            putExtra(IslandOverlayService.EXTRA_MODE, IslandMode.MUSIC.name)
            putExtra(IslandOverlayService.EXTRA_TITLE, title)
            putExtra(IslandOverlayService.EXTRA_SUBTITLE, artist)
            putExtra(IslandOverlayService.EXTRA_KEY, sbn.key)
            putExtra(IslandOverlayService.EXTRA_CONTENT_INTENT, notification.contentIntent)
            putParcelableArrayListExtra(
                IslandOverlayService.EXTRA_ACTION_INTENTS,
                ArrayList<PendingIntent>(notification.actions.orEmpty().mapNotNull { it.actionIntent })
            )
            putStringArrayListExtra(
                IslandOverlayService.EXTRA_ACTION_LABELS,
                ArrayList(notification.actions.orEmpty().map { it.title.toString() })
            )
            putExtra(EXTRA_MEDIA_IS_PLAYING, playbackState?.state == PlaybackState.STATE_PLAYING)
            putExtra(EXTRA_MEDIA_POSITION, position)
            putExtra(EXTRA_MEDIA_DURATION, duration)
            putExtra(EXTRA_HAS_MEDIA_SESSION, currentMediaController != null)
            if (artwork != null) putExtra(EXTRA_MEDIA_ARTWORK, artwork)
        })
    }

    private fun mediaArtwork(metadata: MediaMetadata?, notification: Notification): ByteArray? {
        val bitmap = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            ?: notification.extras.getBitmapCompat(Notification.EXTRA_LARGE_ICON)
            ?: notification.extras.getBitmapCompat(Notification.EXTRA_LARGE_ICON_BIG)
            ?: artworkFromUri(metadata)
            ?: return null

        val scaled = Bitmap.createScaledBitmap(bitmap, 192, 192, true)
        return ByteArrayOutputStream().use { output ->
            if (scaled.compress(Bitmap.CompressFormat.JPEG, 82, output)) output.toByteArray() else null
        }
    }

    private fun artworkFromUri(metadata: MediaMetadata?): Bitmap? {
        val uriText = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ART_URI)
            ?: return null
        val uri = runCatching { Uri.parse(uriText) }.getOrNull() ?: return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            var sampleSize = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sampleSize > 384) sampleSize *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
        }.getOrNull()
    }

    @Suppress("DEPRECATION")
    private fun android.os.Bundle.getBitmapCompat(key: String): Bitmap? =
        if (Build.VERSION.SDK_INT >= 33) getParcelable(key, Bitmap::class.java) else getParcelable(key)

    companion object {
        const val ACTION_MEDIA_CONTROL = "com.dynamicdroid.app.MEDIA_CONTROL"
        const val EXTRA_MEDIA_COMMAND = "media_command"
        const val EXTRA_MEDIA_IS_PLAYING = "media_is_playing"
        const val EXTRA_MEDIA_POSITION = "media_position"
        const val EXTRA_MEDIA_DURATION = "media_duration"
        const val EXTRA_MEDIA_ARTWORK = "media_artwork"
        const val EXTRA_HAS_MEDIA_SESSION = "has_media_session"
        const val MEDIA_TOGGLE = "toggle"
        const val MEDIA_PREVIOUS = "previous"
        const val MEDIA_NEXT = "next"
        const val MEDIA_SEEK = "seek"
    }
}
