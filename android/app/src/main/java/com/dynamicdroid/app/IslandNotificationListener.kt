package com.dynamicdroid.app

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.graphics.drawable.toBitmap

/**
 * Turns notifications into island activities. Filters out the noise (silent, ongoing,
 * group summaries, repeat posts) that used to make the island flash in and out.
 */
class IslandNotificationListener : NotificationListenerService() {
    private val main = Handler(Looper.getMainLooper())
    private var mediaNotification: StatusBarNotification? = null
    private var controller: MediaController? = null
    private val timers = HashMap<String, LiveActivity>()
    private val systemPackages = setOf("com.android.systemui", "android")
    /** Notifications currently shown as recordings, so the "saved" update can end them. */
    private val recordings = HashSet<String>()
    /** Notifications shown as live scores, so the result ("India won") keeps updating the card. */
    private val scores = HashSet<String>()
    private val lastMessageText = HashMap<String, String>()
    private val lastProgress = HashMap<String, Float>()

    private var artKey: String? = null
    private var artBitmap: Bitmap? = null
    private var artAccent: Int? = null

    private val publishMedia = Runnable { publishPending = false; publishCurrentMedia() }
    private var publishPending = false
    // What the island last showed, so position-only chatter isn't re-sent.
    private var lastPublished: LiveActivity? = null
    // Artwork from a URI is decoded off the main thread; this is the track it's for.
    private var artLoading: String? = null
    private val artWorker by lazy {
        android.os.HandlerThread("island-art").apply { start() }.let { Handler(it.looper) }
    }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = schedulePublish()
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            // Play, pause and skips show at once; everything else is batched.
            val playing = state?.state == PlaybackState.STATE_PLAYING || state?.state == PlaybackState.STATE_BUFFERING
            if (lastPublished?.isPlaying != playing) publishNow() else schedulePublish()
        }
        override fun onSessionDestroyed() {
            lastPublished = null
            mediaNotification?.key?.let(IslandBus::remove)
            setController(null)
            mediaNotification = null
        }
    }

    override fun onListenerConnected() {
        MusicCenter.attach(this)
        super.onListenerConnected()
        instance = this
        IslandBus.mediaControl = ::handleMedia
        IslandBus.notifyListenerChanged()
        runCatching { activeNotifications }.getOrNull()
            ?.sortedBy { it.postTime }
            ?.filter { it.isOngoing || it.notification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER) ||
                it.notification.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN) || it.packageName.contains("clock", true) ||
                it.notification.category == Notification.CATEGORY_ALARM || it.notification.contentView != null || it.notification.bigContentView != null }
            ?.forEach(::onNotificationPosted)
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
        // Without notification access the island cannot stand in for pop-ups: bring them back.
        HeadsUpControl.restore(this)
        IslandBus.notifyListenerChanged()
        IslandBus.mediaControl = null
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName || !IslandCustomization.allowed(this, sbn.packageName)) return
        // System UI's own notifications (casting, battery...) already have their own status-bar
        // chips; only its screen recorder gets an island (as a recording, never as a stopwatch).
        if (sbn.packageName in systemPackages && !(sbn.isOngoing && LiveSignals.isRecording(sbn.packageName,
                if (Build.VERSION.SDK_INT >= 26) sbn.notification.channelId else null,
                sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(), "", true, true))) {
            if (sbn.key in recordings) { recordings.remove(sbn.key); IslandBus.remove(sbn.key) }
            return
        }
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = n.extras
        val token = sessionToken(n)
        val isMedia = token != null || n.category == Notification.CATEGORY_TRANSPORT

        if (isMedia) {
            mediaNotification = sbn
            setController(findMediaController(sbn, token))
            schedulePublish()
            return
        }


        val appLabel = AppStyle.label(this, sbn.packageName)
        val clockApp = sbn.packageName.contains("clock", true) || appLabel.contains("clock", true) || appLabel.contains("timer", true)
        val customClock = NotificationClockReader.read(this, n, clockApp, sbn.key in timers)
        val title = (extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
            ?: extras.getCharSequence(Notification.EXTRA_TITLE))?.toString()?.takeIf { it.isNotBlank() }
            ?: AppStyle.label(this, sbn.packageName)
        val text = (extras.getCharSequence(Notification.EXTRA_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT))?.toString().orEmpty()
        val chronometer = extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)
        val nativeCountDown = extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN)
        val recognizedTimer = TimerRecognition.matches(sbn.key in timers || customClock != null, chronometer, nativeCountDown,
            sbn.isOngoing, n.category == Notification.CATEGORY_ALARM || n.category == "stopwatch",
            clockApp,
            ClockTimeline.parseRemaining(text) != null || ClockTimeline.parseRemaining(title) != null,
            n.actions.orEmpty().any { a -> listOf("pause", "resume", "cancel", "stop").any { a.title.toString().contains(it, true) } })
        // A known timer whose update no longer carries any time (finished, or the notification
        // turned into something else) ends here instead of counting on forever from memory.
        if (sbn.key in timers && !chronometer && customClock == null &&
            ClockTimeline.parseRemaining(text) == null && ClockTimeline.parseRemaining(title) == null &&
            n.actions.orEmpty().none { a -> listOf("pause", "resume", "cancel", "stop", "reset").any { a.title.toString().contains(it, true) } }) {
            timers.remove(sbn.key)
            IslandBus.remove(sbn.key)
            return
        }
        if (clockApp || customClock != null || sbn.key in timers) {
            TimerDiagnostics.record(this, sbn.packageName, customClock?.source ?: "standard fields",
                recognizedTimer, n.contentView != null || n.bigContentView != null)
        }
        val features = IslandFeatures.read(this)
        // A payment from a wallet or UPI app: a short "Paid" notice instead of a message card.
        if (features.payments && !sbn.isOngoing && IslandSignals.isPayment(sbn.packageName, title, text)) {
            IslandBus.post(LiveActivity(
                key = "event:pay:${sbn.key}", packageName = sbn.packageName, mode = IslandMode.EVENT,
                title = title.ifBlank { getString(R.string.event_paid) }, subtitle = text,
                appLabel = AppStyle.label(this, sbn.packageName), appIcon = AppStyle.icon(this, sbn.packageName),
                artwork = null, accent = AppStyle.GREEN, glyph = Glyph.PAY, contentIntent = n.contentIntent,
            ))
            return
        }
        val actionLabels = n.actions.orEmpty().map { it.title?.toString().orEmpty() }
        val progress = IslandSignals.progressFraction(extras.getInt(Notification.EXTRA_PROGRESS),
            extras.getInt(Notification.EXTRA_PROGRESS_MAX), extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
        val channel = if (Build.VERSION.SDK_INT >= 26) n.channelId else null
        val shortCritical = extras.getCharSequence(LiveSignals.EXTRA_SHORT_CRITICAL_TEXT)
        // Recordings first: a recorder's Pause/Stop stopwatch would otherwise pass for a timer.
        val recording = features.recording && LiveSignals.isRecording(sbn.packageName, channel, title, text, sbn.isOngoing, chronometer)
        val hotspot = features.liveUpdates && LiveSignals.isHotspot(sbn.packageName, channel, sbn.isOngoing)
        // Live scores: the notification's text, or for a scoreboard drawn as a custom layout, its words.
        val alertOnce = n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0
        var scoreLines = listOfNotNull(title, text, extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(), shortCritical?.toString()).filter { it.isNotBlank() }
        val quiet = !sbn.isOngoing && !isAlerting(sbn)
        val scoreCandidate = features.liveUpdates && (sbn.isOngoing || alertOnce || quiet) &&
            (sbn.packageName in LiveSignals.scoreApps || sbn.key in scores)
        var readCustom = false
        if (scoreCandidate && !LiveSignals.looksLikeScore(scoreLines) && (n.contentView != null || n.bigContentView != null)) {
            val custom = runCatching { NotificationText.read(this, n) }.getOrDefault(emptyList())
            if (custom.isNotEmpty()) { scoreLines = custom; readCustom = true }
        }
        val liveScore = features.liveUpdates && n.category != Notification.CATEGORY_CALL &&
            (LiveSignals.isLiveScore(sbn.packageName, sbn.isOngoing, alertOnce, scoreLines, quiet) ||
                sbn.key in scores && (sbn.isOngoing || alertOnce || quiet))
        if (sbn.packageName in LiveSignals.scoreApps || liveScore) LiveDiagnostics.record(this, sbn.packageName, sbn.isOngoing,
            alertOnce, quiet, readCustom, LiveSignals.looksLikeScore(scoreLines, sbn.packageName in LiveSignals.scoreApps),
            liveScore && IslandFeatures.read(this).shows(IslandMode.LIVE))
        val live = features.liveUpdates && n.category != Notification.CATEGORY_CALL && sbn.packageName != LiveSignals.GOOGLE_APP && LiveSignals.isLiveUpdate(
            extras.getString(Notification.EXTRA_TEMPLATE), n.flags, extras.getBoolean(LiveSignals.EXTRA_REQUEST_PROMOTED),
            sbn.packageName, sbn.isOngoing, !shortCritical.isNullOrBlank())
        // A recording whose notification stops recording ("Recording saved") ends here.
        if (sbn.key in recordings && !recording) {
            recordings.remove(sbn.key)
            IslandBus.remove(sbn.key)
            return
        }
        val mode = when {
            n.category == Notification.CATEGORY_CALL -> IslandMode.CALL
            features.alarms && IslandSignals.isRingingAlarm(n.category, clockApp, actionLabels) -> IslandMode.ALARM
            n.category == Notification.CATEGORY_NAVIGATION && sbn.isOngoing -> IslandMode.NAVIGATION
            recording -> IslandMode.RECORDING
            hotspot -> IslandMode.LIVE
            liveScore -> IslandMode.LIVE
            recognizedTimer -> IslandMode.TIMER
            live && !(features.downloads && n.category == Notification.CATEGORY_PROGRESS) -> IslandMode.LIVE
            features.downloads && progress != null && progress < 1f &&
                (sbn.isOngoing || n.category == Notification.CATEGORY_PROGRESS) -> IslandMode.DOWNLOAD
            sbn.key in lastProgress -> { finishProgress(sbn.key); if (sbn.isOngoing) return else IslandMode.MESSAGE }
            sbn.isOngoing -> return
            else -> IslandMode.MESSAGE
        }
        if (mode == IslandMode.DOWNLOAD) {
            val fraction = progress ?: return
            if (!IslandSignals.progressWorthPosting(lastProgress[sbn.key], fraction)) return
            lastProgress[sbn.key] = fraction
        }

        if (mode == IslandMode.MESSAGE) {
            if (!isAlerting(sbn)) return
            val signature = "$title|$text"
            if (lastMessageText[sbn.key] == signature) return
            lastMessageText[sbn.key] = signature
            if (lastMessageText.size > 64) lastMessageText.keys.firstOrNull()?.let(lastMessageText::remove)
        }

        val actions = n.actions.orEmpty()
            .filter { it.actionIntent != null && (mode == IslandMode.CALL || it.remoteInputs.isNullOrEmpty()) }
            .map { IslandAction(it.title?.toString().orEmpty(), it.actionIntent) }
            .filter { it.label.isNotBlank() }
        // The app's own reply action (free-form text), sent back through its RemoteInput.
        val reply = if (mode == IslandMode.MESSAGE && features.inlineReply) n.actions.orEmpty().firstNotNullOfOrNull { a ->
            val input = a.remoteInputs?.firstOrNull { it.allowFreeFormInput } ?: return@firstNotNullOfOrNull null
            val intent = a.actionIntent ?: return@firstNotNullOfOrNull null
            ReplyAction(a.title?.toString()?.takeIf { it.isNotBlank() } ?: "Reply", intent, input)
        } else null
        val callState = if (mode == IslandMode.CALL) IslandSignals.callState(
            extras.getInt(EXTRA_CALL_TYPE, 0), actionLabels, chronometer) else null

        val previousTimer = timers[sbn.key]
        val parsedRemaining = if (mode == IslandMode.TIMER && !chronometer) {
            ClockTimeline.parseRemaining(text) ?: ClockTimeline.parseRemaining(title)
        } else null
        val paused = customClock?.paused == true || mode == IslandMode.TIMER && !chronometer &&
            (actions.any { it.label.equals("Resume", ignoreCase = true) || it.label.equals("Start", ignoreCase = true) } ||
                text.contains("paused", ignoreCase = true))
        val remaining = parsedRemaining ?: customClock?.remainingMs ?: previousTimer?.clockMs()
        val base = when {
            // A call's timer starts only once it connects; "Calling…" shows until then.
            mode == IslandMode.CALL -> if (callState == CallState.ACTIVE) n.`when`.takeIf { it > 0L } else null
            chronometer -> n.`when`.takeIf { it > 0L }
            remaining != null && !paused -> System.currentTimeMillis() + remaining
            else -> null
        }

        val activity = LiveActivity(
            key = sbn.key,
            packageName = sbn.packageName,
            mode = mode,
            // A scoreboard drawn as a custom layout: its words become the card's text.
            title = if (liveScore && title == appLabel && scoreLines.firstOrNull() != title)
                scoreLines.first().take(80) else title,
            subtitle = if (liveScore && text.isBlank()) scoreLines.drop(1).joinToString("  ").take(120) else text,
            appLabel = AppStyle.label(this, sbn.packageName),
            appIcon = AppStyle.icon(this, sbn.packageName),
            artwork = largeIcon(n),
            accent = AppStyle.accent(this, sbn.packageName),
            contentIntent = n.contentIntent,
            actions = actions,
            chronometerBase = base,
            chronometerCountDown = nativeCountDown || customClock?.countDown == true || parsedRemaining != null || previousTimer?.chronometerCountDown == true,
            clockBaseElapsed = if (paused) null else customClock?.baseElapsed ?: base?.let {
                SystemClock.elapsedRealtime() + (it - System.currentTimeMillis())
            },
            pausedTimerMs = if (paused) remaining else null,
            progress = if (mode == IslandMode.DOWNLOAD || mode == IslandMode.LIVE) progress else null,
            callState = callState,
            reply = reply,
            glyph = when {
                hotspot -> Glyph.HOTSPOT
                mode == IslandMode.RECORDING -> if (LiveSignals.isScreenRecording(sbn.packageName, channel, title)) Glyph.SCREEN else Glyph.MIC
                else -> null
            },
            detail = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty(),
            shortText = if (mode == IslandMode.LIVE && !hotspot) (if (liveScore) LiveSignals.cricketScore(scoreLines) else null)
                ?: LiveSignals.shortText(shortCritical, title, text) else null,
        )
        if (mode == IslandMode.TIMER) timers[sbn.key] = activity
        if (mode == IslandMode.RECORDING) recordings += sbn.key
        if (liveScore && mode == IslandMode.LIVE) scores += sbn.key
        // Android 16+ draws its own status-bar chip for this one; remember the app so Settings
        // can take the user straight to the switch that hides that duplicate.
        if (mode == IslandMode.LIVE && n.flags and LiveSignals.FLAG_PROMOTED_ONGOING != 0)
            LiveDiagnostics.recordPromoted(this, sbn.packageName)
        IslandBus.post(activity)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        timers.remove(sbn.key)
        recordings.remove(sbn.key)
        scores.remove(sbn.key)
        lastProgress.remove(sbn.key)
        lastMessageText.remove(sbn.key)
        IslandBus.remove(sbn.key)
        if (sbn.key == mediaNotification?.key) {
            lastPublished = null
            mediaNotification = null
            setController(null)
        }
    }

    fun refreshAllowedApps() {
        IslandBus.allOngoing().filterNot { IslandCustomization.allowed(this, it.packageName) }.forEach { IslandBus.remove(it.key) }
        runCatching { activeNotifications?.filter { IslandCustomization.allowed(this, it.packageName) }
            ?.sortedBy { it.postTime }?.forEach(::onNotificationPosted) }
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        setController(null)
        IslandBus.mediaControl = null
        main.removeCallbacksAndMessages(null)
        runCatching { artWorker.looper.quitSafely() }
        super.onDestroy()
    }

    /** A progress notification finished or stopped reporting progress: drop its island. */
    private fun finishProgress(key: String) {
        lastProgress.remove(key)
        IslandBus.remove(key)
    }

    // ---------------------------------------------------------------- filtering

    private fun isAlerting(sbn: StatusBarNotification): Boolean {
        val ranking = Ranking()
        if (!currentRanking.getRanking(sbn.key, ranking)) return true
        return ranking.importance >= NotificationManager.IMPORTANCE_DEFAULT && !ranking.isSuspended
    }

    // ---------------------------------------------------------------- media

    private fun schedulePublish() {
        // Players fire bursts of callbacks; coalesce them so the island updates once. The timer
        // is not restarted by later callbacks: streaming players (Bloomee, other just_audio and
        // ExoPlayer apps) report buffering progress many times a second, and restarting the wait
        // on each one used to hold the island back until the stream went quiet.
        if (publishPending) return
        publishPending = true
        main.postDelayed(publishMedia, 50L)
    }

    private fun publishNow() {
        main.removeCallbacks(publishMedia)
        publishPending = false
        publishCurrentMedia()
    }

    private fun handleMedia(command: String, position: Long?): Boolean {
        val c = controller ?: return false
        val controls = c.transportControls
        when (command) {
            IslandBus.MEDIA_TOGGLE ->
                if (c.playbackState?.state == PlaybackState.STATE_PLAYING) controls.pause() else controls.play()
            IslandBus.MEDIA_PREVIOUS -> controls.skipToPrevious()
            IslandBus.MEDIA_NEXT -> controls.skipToNext()
            IslandBus.MEDIA_SEEK -> controls.seekTo((position ?: 0L).coerceAtLeast(0L))
            else -> return false
        }
        return true
    }

    private fun findMediaController(sbn: StatusBarNotification, token: MediaSession.Token?): MediaController? {
        token?.let { t -> runCatching { MediaController(this, t) }.getOrNull()?.let { return it } }
        return runCatching {
            val manager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val sessions = manager.getActiveSessions(ComponentName(this, IslandNotificationListener::class.java))
                .filter { it.packageName == sbn.packageName }
            sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: sessions.firstOrNull()
        }.getOrNull()
    }

    @Suppress("DEPRECATION")
    private fun sessionToken(n: Notification): MediaSession.Token? =
        if (Build.VERSION.SDK_INT >= 33) n.extras.getParcelable(Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java)
        else n.extras.getParcelable(Notification.EXTRA_MEDIA_SESSION)

    private fun setController(next: MediaController?) {
        if (controller?.sessionToken == next?.sessionToken && next != null) return
        controller?.unregisterCallback(controllerCallback)
        controller = next
        next?.registerCallback(controllerCallback, main)
    }

    private fun publishCurrentMedia() {
        val sbn = mediaNotification ?: return
        if (!IslandCustomization.allowed(this, sbn.packageName)) { lastPublished = null; IslandBus.remove(sbn.key); return }
        val n = sbn.notification
        val extras = n.extras
        val metadata = controller?.metadata
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)?.takeIf { it.isNotBlank() }
            ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.takeIf { it.isNotBlank() }
            ?: return
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)?.takeIf { it.isNotBlank() }
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val state = controller?.playbackState
        val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0L } ?: 0L

        // Artwork + its colour only change with the track, so cache them. A bitmap the player
        // hands over is used straight away; one behind a URI is decoded on a worker thread so
        // the island never waits on another app's storage.
        val key = "$title|$artist"
        if (key != artKey || artBitmap == null) {
            if (key != artKey) { artBitmap = null; artAccent = null }
            artKey = key
            val direct = mediaArtworkInMemory(metadata)
            if (direct != null) {
                artBitmap = direct
                artAccent = AppStyle.fromBitmap(direct)
            } else if (artLoading != key) {
                artLoading = key
                val uriMeta = metadata
                artWorker.post {
                    val art = artworkFromUri(uriMeta)?.let(::shrink) ?: largeIcon(n)?.let(::shrink)
                    val accent = art?.let(AppStyle::fromBitmap)
                    main.post {
                        if (artLoading == key) artLoading = null
                        if (artKey == key && art != null && artBitmap == null) {
                            artBitmap = art
                            artAccent = accent
                            publishNow()
                        }
                    }
                }
            }
        }

        val next = LiveActivity(
            key = sbn.key,
            packageName = sbn.packageName,
            mode = IslandMode.MUSIC,
            title = title,
            subtitle = artist,
            appLabel = AppStyle.label(this, sbn.packageName),
            appIcon = AppStyle.icon(this, sbn.packageName),
            artwork = artBitmap,
            accent = artAccent ?: AppStyle.accent(this, sbn.packageName),
            contentIntent = n.contentIntent ?: controller?.sessionActivity,
            actions = n.actions.orEmpty().filter { it.actionIntent != null }
                .map { IslandAction(it.title?.toString().orEmpty(), it.actionIntent) },
            isPlaying = state?.state == PlaybackState.STATE_PLAYING || state?.state == PlaybackState.STATE_BUFFERING,
            positionMs = state?.position?.coerceAtLeast(0L) ?: 0L,
            positionUpdatedAt = state?.lastPositionUpdateTime?.takeIf { it > 0L } ?: SystemClock.elapsedRealtime(),
            playbackSpeed = state?.playbackSpeed?.takeIf { it > 0f } ?: 1f,
            durationMs = duration,
            hasMediaSession = controller != null,
        )
        if (sameForIsland(lastPublished, next)) return
        lastPublished = next
        IslandBus.post(next)
    }

    /**
     * True when [b] would show the same thing as [a]: same song, state, cover and controls, with
     * the position where the running clock already puts it (players re-send it constantly).
     */
    private fun sameForIsland(a: LiveActivity?, b: LiveActivity): Boolean {
        if (a == null || IslandBus.allOngoing().none { it.key == b.key } || a.key != b.key || a.title != b.title || a.subtitle != b.subtitle || a.isPlaying != b.isPlaying ||
            a.artwork !== b.artwork || a.durationMs != b.durationMs || a.playbackSpeed != b.playbackSpeed ||
            a.accent != b.accent || a.actions.size != b.actions.size || a.contentIntent != b.contentIntent) return false
        val expected = a.positionMs + if (a.isPlaying) ((b.positionUpdatedAt - a.positionUpdatedAt) * a.playbackSpeed).toLong() else 0L
        return kotlin.math.abs(expected - b.positionMs) < 700L
    }

    private fun largeIcon(n: Notification): Bitmap? = runCatching {
        n.getLargeIcon()?.loadDrawable(this)?.toBitmap(160, 160)
    }.getOrNull()

    private fun mediaArtworkInMemory(metadata: MediaMetadata?): Bitmap? {
        val raw = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            ?: return null
        return shrink(raw)
    }

    private fun shrink(raw: Bitmap): Bitmap? = runCatching {
        val soft = if (raw.config == Bitmap.Config.HARDWARE) raw.copy(Bitmap.Config.ARGB_8888, false) else raw
        if (soft.width > 320 || soft.height > 320) Bitmap.createScaledBitmap(soft, 320, 320, true) else soft
    }.getOrNull()

    private fun artworkFromUri(metadata: MediaMetadata?): Bitmap? {
        val uriText = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ART_URI)
            ?: return null
        val uri = runCatching { Uri.parse(uriText) }.getOrNull() ?: return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 400) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        }.getOrNull()
    }

    companion object {
        @Volatile var instance: IslandNotificationListener? = null
            private set
        /** Notification.EXTRA_CALL_TYPE (API 31); read by name so older SDK levels still work. */
        private const val EXTRA_CALL_TYPE = "android.callType"
    }
}
