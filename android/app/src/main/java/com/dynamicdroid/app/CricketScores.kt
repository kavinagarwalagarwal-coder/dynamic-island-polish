package com.dynamicdroid.app

import android.content.Context
import android.graphics.Color
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

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

    private val completedStatus = Regex("\\b(won|drawn|abandoned|cancelled|canceled|no result|completed)\\b", RegexOption.IGNORE_CASE)

    fun prioritized(matches: List<CricketScore>, favorites: Set<String>): List<CricketScore> =
        matches.withIndex()
            .sortedWith(
                compareBy<IndexedValue<CricketScore>> { (_, match) ->
                    if (completedStatus.containsMatchIn(match.status)) 1 else 0
                }.thenBy { (_, match) ->
                    if (match.preferredNames.any(favorites::contains)) 0 else 1
                }.thenBy { it.index },
            )
            .map { it.value }
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

/**
 * Live cricket from ESPN's public score panel, turned into what the island shows. Pure, so the
 * choice of match and the chip text are tested without a phone. Only scores are downloaded;
 * nothing about the phone or its user is ever sent.
 */
object CricketScores {
    const val FEED = "https://site.web.api.espn.com/apis/site/v2/sports/cricket/scorepanel"
    const val KEY = "cricket-auto"

    data class Side(val abbr: String, val score: String, val batting: Boolean, val color: Int? = null)
    data class Match(val id: String, val state: String, val league: String, val home: Side, val away: Side, val summary: String,
                     val status: String = "") {
        val live get() = state == "in"
        val finished get() = state == "post"
        val sides get() = listOf(home, away)
        fun has(team: String) = sides.any { it.abbr.equals(team, true) }
    }

    val international = setOf("IND", "AUS", "ENG", "NZ", "PAK", "SA", "SL", "BAN", "WI", "AFG", "ZIM", "IRE")

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(body: String): List<Match> = runCatching {
        val out = ArrayList<Match>()
        val root = json.parseToJsonElement(body).obj ?: return emptyList()
        for (group in root["scores"].arr) {
            val g = group.obj ?: continue
            val league = g["leagues"].arr.firstOrNull()?.obj?.str("name").orEmpty()
            for (e in g["events"].arr) {
                val ev = e.obj ?: continue
                val type = ev["status"]?.obj?.get("type")?.obj
                val state = type?.str("state") ?: continue
                val comp = ev["competitions"].arr.firstOrNull()?.obj ?: continue
                val sides = comp["competitors"].arr.mapNotNull { c ->
                    val o = c.obj ?: return@mapNotNull null
                    val abbr = o["team"]?.obj?.str("abbreviation") ?: return@mapNotNull null
                    val batting = o["linescores"].arr.any {
                        val l = it.obj
                        l?.get("isBatting")?.jsonPrimitive?.booleanOrNull == true && l["isCurrent"]?.jsonPrimitive?.intOrNull == 1
                    }
                    val color = Scoreboard.parseColor(o["team"]?.obj?.str("color"))
                    (o.str("homeAway") ?: "") to Side(abbr, o.str("score").orEmpty(), batting, color)
                }
                if (sides.size < 2) continue
                val home = sides.firstOrNull { it.first == "home" }?.second ?: sides[0].second
                val away = sides.firstOrNull { it.first == "away" }?.second ?: sides[1].second
                val summary = unescape(comp["status"]?.obj?.str("summary").orEmpty())
                out += Match(ev.str("id").orEmpty(), state, league, home, away, summary, type?.str("shortDetail").orEmpty())
            }
        }
        out
    }.getOrDefault(emptyList())

    fun pick(matches: List<Match>, favourites: List<String>): Match? {
        val live = matches.filter { it.live }
        for (team in favourites) live.firstOrNull { it.has(team) }?.let { return it }
        return live.firstOrNull { m -> m.sides.all { it.abbr.uppercase() in international } }
    }

    fun current(score: String): String = score.substringAfterLast('&').substringBefore('(').trim()

    fun overs(score: String): String? {
        val inside = score.substringAfterLast('&').substringAfter('(', "").substringBefore(')')
        val ov = inside.substringBefore(" ov", "").substringBefore('/').trim()
        return ov.takeIf { it.isNotEmpty() }?.let { "$it ov" }
    }

    fun target(score: String): Int? =
        Regex("target\\s+(\\d+)").find(score.substringAfterLast('&'))?.groupValues?.get(1)?.toIntOrNull()

    fun previous(score: String): String? =
        score.takeIf { '&' in it }?.substringBeforeLast('&')?.substringBefore('(')?.trim()?.takeIf { it.isNotEmpty() }

    fun board(m: Match): Scoreboard {
        val bat = batting(m).takeIf { m.sides.any(Side::batting) || '(' in it.score }
        fun team(s: Side) = Scoreboard.Team(
            abbr = s.abbr, score = current(s.score), overs = overs(s.score), batting = s === bat,
            color = s.color, target = target(s.score), previous = previous(s.score),
        )
        return Scoreboard(team(m.home), team(m.away), if (m.finished) "Result" else m.status, m.summary, m.league, live = m.live)
    }

    private fun batting(m: Match) = m.sides.firstOrNull { it.batting } ?: m.sides.firstOrNull { '(' in it.score } ?: m.away

    fun chip(m: Match): String = batting(m).let { "${it.abbr} ${current(it.score)}".trim() }

    fun title(m: Match): String {
        val bat = batting(m)
        val other = m.sides.first { it !== bat }
        val b = listOfNotNull("${bat.abbr} ${current(bat.score)}", overs(bat.score)?.let { "($it)" }).joinToString(" ")
        val o = if (other.score.isBlank()) other.abbr else "${other.abbr} ${current(other.score)}"
        return "$b  ·  $o"
    }

    private fun unescape(s: String) =
        s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")

    private val JsonElement.obj get() = this as? JsonObject
    private val JsonElement?.arr get() = (this as? JsonArray) ?: JsonArray(emptyList())
    private fun JsonObject.str(k: String) = (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
}
