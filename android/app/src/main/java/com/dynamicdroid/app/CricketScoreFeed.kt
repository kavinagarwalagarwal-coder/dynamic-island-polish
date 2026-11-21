package com.dynamicdroid.app

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Checks for a live cricket match and keeps its score on the island, with no cricket app needed.
 * Polls every 30 seconds while a match is live and every 5 minutes otherwise. Only the public
 * score list is downloaded; no request carries anything about the phone or its user.
 */
class CricketScoreFeed(
    private val context: Context,
    private val post: (LiveActivity) -> Unit,
    private val remove: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val client = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()
    private var shown: String? = null
    private var running = false

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            if (!IslandFeatures.read(context).autoCricket) { clear(); main.postDelayed(this, IDLE_MS); return }
            worker.execute {
                val match = runCatching {
                    client.newCall(Request.Builder().url(CricketScores.FEED).build()).execute().use { r ->
                        if (r.isSuccessful) CricketScores.parse(r.body.string()) else null
                    }
                }.getOrNull()?.let { CricketScores.pick(it, IslandFeatures.cricketTeams(context)) }
                main.post {
                    if (!running) return@post
                    if (match != null) show(match) else clear()
                    main.postDelayed(this, if (match != null) LIVE_MS else IDLE_MS)
                }
            }
        }
    }

    fun start() { if (!running) { running = true; main.post(tick) } }

    fun stop() { running = false; main.removeCallbacks(tick); clear(); worker.shutdownNow() }

    private fun show(m: CricketScores.Match) {
        val label = m.league.ifBlank { context.getString(R.string.cricket_live_label) }
        val open = PendingIntent.getActivity(context, 41,
            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.espncricinfo.com/live-cricket-score")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        shown = CricketScores.KEY
        post(LiveActivity(
            key = CricketScores.KEY, packageName = context.packageName, mode = IslandMode.LIVE,
            title = CricketScores.title(m), subtitle = m.summary, appLabel = label,
            appIcon = null, artwork = null, accent = AppStyle.GREEN, contentIntent = open,
            shortText = CricketScores.chip(m),
        ))
    }

    private fun clear() { shown?.let(remove); shown = null }

    private companion object {
        const val LIVE_MS = 30_000L
        const val IDLE_MS = 300_000L
    }
}
