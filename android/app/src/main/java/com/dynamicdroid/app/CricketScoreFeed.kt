package com.dynamicdroid.app

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Checks for a live cricket match and keeps its score on the island, with no cricket app needed.
 * Polls every 30 seconds while a match is live and every 5 minutes otherwise; a finished match
 * stays on its result for ten minutes. Only the public
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
    private var shownId: String? = null
    private var resultUntil = 0L
    private var failures = 0
    private var running = false

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            if (!IslandFeatures.read(context).autoCricket) { clear(); main.postDelayed(this, IDLE_MS); return }
            worker.execute {
                val matches = runCatching {
                    client.newCall(Request.Builder().url(CricketScores.FEED).build()).execute().use { r ->
                        if (r.isSuccessful) CricketScores.parse(r.body.string()) else null
                    }
                }.getOrNull()
                val teams = IslandFeatures.cricketTeams(context)
                main.post {
                    if (!running) return@post
                    main.postDelayed(this, if (update(matches, teams)) LIVE_MS else IDLE_MS)
                }
            }
        }
    }

    /**
     * Applies one download; true while a match is on the island. A failed download keeps the last
     * score for a few tries rather than flickering away; a match that just ended shows its result
     * for a while, the way a Live Activity ends on its final state.
     */
    private fun update(matches: List<CricketScores.Match>?, teams: List<String>): Boolean {
        if (matches == null) {
            if (++failures >= MAX_FAILURES) clear()
            return shown != null
        }
        failures = 0
        val live = CricketScores.pick(matches, teams)
        if (live != null) { resultUntil = 0L; show(live); return true }
        val ended = matches.firstOrNull { it.id == shownId && it.finished }
        val now = SystemClock.elapsedRealtime()
        if (ended != null) {
            if (resultUntil == 0L) resultUntil = now + RESULT_MS
            if (now < resultUntil) { show(ended); return true }
        }
        clear()
        return false
    }

    fun start() { if (!running) { running = true; main.post(tick) } }

    fun stop() { running = false; main.removeCallbacks(tick); clear(); worker.shutdownNow() }

    private fun show(m: CricketScores.Match) {
        val label = m.league.ifBlank { context.getString(R.string.cricket_live_label) }
        val open = PendingIntent.getActivity(context, 41,
            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.espncricinfo.com/live-cricket-score")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        shown = CricketScores.KEY
        shownId = m.id
        post(LiveActivity(
            key = CricketScores.KEY, packageName = context.packageName, mode = IslandMode.LIVE,
            title = CricketScores.title(m), subtitle = m.summary, appLabel = label,
            appIcon = null, artwork = null, accent = AppStyle.GREEN, contentIntent = open,
            shortText = CricketScores.chip(m), scoreboard = CricketScores.board(m),
        ))
    }

    private fun clear() { shown?.let(remove); shown = null; shownId = null; resultUntil = 0L }

    private companion object {
        const val LIVE_MS = 30_000L
        const val IDLE_MS = 300_000L
        const val RESULT_MS = 10 * 60_000L
        const val MAX_FAILURES = 4
    }
}
