package com.dynamicdroid.app

import android.content.Context
import android.graphics.Color
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

private const val CRICKET_SCORE_ENDPOINT = "https://dynamic-island-polish.vercel.app/api/cricket/current"

internal data class CricketScore(
    val id: String,
    val name: String,
    val teams: List<String>,
    val status: String,
    val matchType: String,
    val scores: List<String>,
) {
    val preferredNames: Set<String> get() = teams.map(::normalizeCricketTeam).toSet()
    val title: String get() = teams.joinToString(" vs ").ifBlank { name }.take(48)
    val scoreLine: String get() = scores.joinToString("  ·  ").ifBlank { status }.take(72)
    val shortScore: String? get() = scores.firstOrNull()?.take(18)

    fun toActivity(context: Context) = LiveActivity(
        key = "cricket:$id",
        packageName = context.packageName,
        mode = IslandMode.LIVE,
        title = title,
        subtitle = status,
        appLabel = "LIVE CRICKET",
        appIcon = null,
        artwork = null,
        accent = Color.rgb(112, 220, 170),
        glyph = Glyph.CRICKET,
        detail = scoreLine,
        shortText = shortScore,
    )
}

internal object CricketTeamPreferences {
    private const val FILE = "cricket_scores"
    private const val FAVORITES = "preferred_teams"

    fun favorites(context: Context): Set<String> = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        .getStringSet(FAVORITES, emptySet())?.toSet().orEmpty()

    fun setFavorite(context: Context, team: String, favorite: Boolean): Set<String> {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val updated = favorites(context).toMutableSet()
        val key = normalizeCricketTeam(team)
        if (favorite) updated += key else updated -= key
        prefs.edit().putStringSet(FAVORITES, updated).apply()
        return updated
    }

    fun prioritized(matches: List<CricketScore>, favorites: Set<String>): List<CricketScore> {
        if (favorites.isEmpty()) return matches
        return matches.withIndex()
            .sortedWith(
                compareBy<IndexedValue<CricketScore>> { (_, match) ->
                    if (match.preferredNames.any(favorites::contains)) 0 else 1
                }.thenBy { it.index },
            )
            .map { it.value }
    }
}

internal object CricketScoreClient {
    fun fetch(): List<CricketScore> {
        val connection = URL(CRICKET_SCORE_ENDPOINT).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.setRequestProperty("Accept", "application/json")
            if (connection.responseCode !in 200..299) {
                val errorMessage = runCatching {
                    connection.errorStream?.bufferedReader()?.use { JSONObject(it.readText()).optString("error") }
                }.getOrNull().orEmpty()
                throw IOException(errorMessage.ifBlank { "Score service unavailable" })
            }
            val payload = connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
            val matches = payload.optJSONArray("matches") ?: return emptyList()
            return buildList {
                for (index in 0 until matches.length()) {
                    val item = matches.optJSONObject(index) ?: continue
                    val teamsJson = item.optJSONArray("teams")
                    val teams = buildList {
                        if (teamsJson != null) {
                            for (teamIndex in 0 until teamsJson.length()) {
                                teamsJson.optString(teamIndex).takeIf(String::isNotBlank)?.let(::add)
                            }
                        }
                    }
                    if (teams.size < 2) continue
                    val scoreJson = item.optJSONArray("score")
                    val scores = buildList {
                        if (scoreJson != null) {
                            for (scoreIndex in 0 until scoreJson.length()) {
                                val score = scoreJson.optJSONObject(scoreIndex) ?: continue
                                val runs = score.optInt("runs", -1)
                                val wickets = score.optInt("wickets", -1)
                                val overs = score.optDouble("overs", Double.NaN)
                                if (runs < 0 || wickets < 0) continue
                                val team = score.optString("team").ifBlank { teams.getOrNull(scoreIndex).orEmpty() }
                                val oversText = if (overs.isFinite()) " (${overs} ov)" else ""
                                add("$team $runs/$wickets$oversText")
                            }
                        }
                    }
                    if (scores.isEmpty()) continue
                    add(
                        CricketScore(
                            id = item.optString("id").ifBlank { item.optString("name") },
                            name = item.optString("name").ifBlank { "Live cricket" },
                            teams = teams,
                            status = item.optString("status").ifBlank { "Live" },
                            matchType = item.optString("matchType"),
                            scores = scores,
                        ),
                    )
                }
            }
        } finally {
            connection.disconnect()
        }
    }
}

internal fun normalizeCricketTeam(name: String): String = name.trim().lowercase(Locale.ROOT)
