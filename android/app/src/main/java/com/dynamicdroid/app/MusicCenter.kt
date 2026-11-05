package com.dynamicdroid.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager

/**
 * The song playing right now, for the dot player, the dot widgets and the dot wallpaper.
 *
 * Fed by [IslandBus] from the same media session the island shows, so everything agrees.
 * Controls go back through the island's media controls.
 */
object MusicCenter {
    fun interface Observer { fun onTrack(track: Track?) }

    enum class Command { TOGGLE, NEXT, PREVIOUS }

    const val DEFAULT_ACCENT = 0xFFFF3B30.toInt()

    private val main = Handler(Looper.getMainLooper())
    private val observers = LinkedHashSet<Observer>()
    private var app: Context? = null
    private var musicKey: String? = null

    var current: Track? = null
        private set

    fun attach(context: Context) { app = context.applicationContext }

    fun observe(o: Observer) { observers += o; o.onTrack(current) }
    fun forget(o: Observer) { observers -= o }

    fun command(c: Command) {
        IslandBus.mediaControl?.invoke(when (c) {
            Command.TOGGLE -> IslandBus.MEDIA_TOGGLE
            Command.NEXT -> IslandBus.MEDIA_NEXT
            Command.PREVIOUS -> IslandBus.MEDIA_PREVIOUS
        }, null)
    }

    fun seekTo(ms: Long) { IslandBus.mediaControl?.invoke(IslandBus.MEDIA_SEEK, ms) }

    /** From [IslandBus], on the main thread, for every music update the island gets. */
    internal fun fromIsland(music: LiveActivity) {
        musicKey = music.key
        deliver(Track(
            title = music.title,
            artist = music.subtitle,
            packageName = music.packageName,
            art = music.artwork,
            accent = music.accent,
            isPlaying = music.isPlaying,
            positionMs = music.positionMs,
            positionUpdatedAt = music.positionUpdatedAt,
            speed = music.playbackSpeed,
            durationMs = music.durationMs,
            openApp = music.contentIntent,
        ))
    }

    internal fun removedFromIsland(key: String) {
        if (key != musicKey) return
        musicKey = null
        deliver(null)
    }

    private fun deliver(track: Track?) {
        val before = current
        current = track
        // Only re-render widgets when something they show changed.
        if (before?.id != track?.id || before?.isPlaying != track?.isPlaying || (before == null) != (track == null) ||
            before?.art !== track?.art) app?.let { MusicWidgets.refresh(it) }
        else app?.let { MusicWidgets.refreshProgress(it) }
        observers.toList().forEach { it.onTrack(track) }
        scheduleTick()
    }

    // The widgets' dot progress bar steps forward on its own while a song plays.
    private val tick = Runnable {
        app?.let { MusicWidgets.refreshProgress(it) }
        scheduleTick()
    }

    fun scheduleTick() {
        main.removeCallbacks(tick)
        val ctx = app ?: return
        if (current?.isPlaying != true || !MusicWidgets.hasAny(ctx)) return
        if (ctx.getSystemService(PowerManager::class.java)?.isInteractive == false) return
        main.postDelayed(tick, PROGRESS_TICK_MS)
    }

    private const val PROGRESS_TICK_MS = 6_000L
}
