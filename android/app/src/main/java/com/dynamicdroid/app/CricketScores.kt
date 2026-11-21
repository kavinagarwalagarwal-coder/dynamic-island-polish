package com.dynamicdroid.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Live cricket from ESPN's public score panel, turned into what the island shows. Pure, so the
 * choice of match and the chip text are tested without a phone. Only scores are downloaded;
 * nothing about the phone or its user is ever sent.
 */
object CricketScores {
    const val FEED = "https://site.web.api.espn.com/apis/site/v2/sports/cricket/scorepanel"
    const val KEY = "cricket-auto"

    data class Side(val abbr: String, val score: String, val batting: Boolean)
    data class Match(val id: String, val state: String, val league: String, val home: Side, val away: Side, val summary: String) {
        val live get() = state == "in"
        val sides get() = listOf(home, away)
        fun has(team: String) = sides.any { it.abbr.equals(team, true) }
    }

    /** Test-playing nations: with no favourite playing, only their matches show by themselves. */
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
                val state = ev["status"]?.obj?.get("type")?.obj?.str("state") ?: continue
                val comp = ev["competitions"].arr.firstOrNull()?.obj ?: continue
                val sides = comp["competitors"].arr.mapNotNull { c ->
                    val o = c.obj ?: return@mapNotNull null
                    val abbr = o["team"]?.obj?.str("abbreviation") ?: return@mapNotNull null
                    val batting = o["linescores"].arr.any {
                        val l = it.obj
                        l?.get("isBatting")?.jsonPrimitive?.booleanOrNull == true && l["isCurrent"]?.jsonPrimitive?.intOrNull == 1
                    }
                    (o.str("homeAway") ?: "") to Side(abbr, o.str("score").orEmpty(), batting)
                }
                if (sides.size < 2) continue
                val home = sides.firstOrNull { it.first == "home" }?.second ?: sides[0].second
                val away = sides.firstOrNull { it.first == "away" }?.second ?: sides[1].second
                val summary = comp["status"]?.obj?.str("summary").orEmpty()
                out += Match(ev.str("id").orEmpty(), state, league, home, away, summary)
            }
        }
        out
    }.getOrDefault(emptyList())

    /** The live match to show: a favourite team's first, else one between two international sides. */
    fun pick(matches: List<Match>, favourites: List<String>): Match? {
        val live = matches.filter { it.live }
        for (team in favourites) live.firstOrNull { it.has(team) }?.let { return it }
        return live.firstOrNull { m -> m.sides.all { it.abbr.uppercase() in international } }
    }

    /** "292 & 138/3 (21.2 ov, target 138)" → "138/3": the innings being played now. */
    fun current(score: String): String = score.substringAfterLast('&').substringBefore('(').trim()

    /** "AUS 33/1 (3.4/20 ov, target 188)" → "3.4 ov". */
    fun overs(score: String): String? {
        val inside = score.substringAfterLast('&').substringAfter('(', "").substringBefore(')')
        val ov = inside.substringBefore(" ov", "").substringBefore('/').trim()
        return ov.takeIf { it.isNotEmpty() }?.let { "$it ov" }
    }

    private fun batting(m: Match) = m.sides.firstOrNull { it.batting } ?: m.sides.firstOrNull { '(' in it.score } ?: m.away

    /** The small island's chip: the batting side and its score, "IND 486/4". */
    fun chip(m: Match): String = batting(m).let { "${it.abbr} ${current(it.score)}".trim() }

    /** "IND 486/4 (125 ov)  ·  NZ 368": batting side first. */
    fun title(m: Match): String {
        val bat = batting(m)
        val other = m.sides.first { it !== bat }
        val b = listOfNotNull("${bat.abbr} ${current(bat.score)}", overs(bat.score)?.let { "($it)" }).joinToString(" ")
        val o = if (other.score.isBlank()) other.abbr else "${other.abbr} ${current(other.score)}"
        return "$b  ·  $o"
    }

    private val JsonElement.obj get() = this as? JsonObject
    private val JsonElement?.arr get() = (this as? JsonArray) ?: JsonArray(emptyList())
    private fun JsonObject.str(k: String) = (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
}
