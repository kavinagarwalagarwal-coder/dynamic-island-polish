package com.dynamicdroid.app

/**
 * Both sides of a live match, laid out like an iPhone sports Live Activity: each team's badge
 * and score on its own side of the camera, the match state and the chase between them. Pure, so
 * parsing and colours are unit-tested; the island only draws it.
 */
data class Scoreboard(
    /** Left of the camera. Teams keep their side for the whole match, whoever bats. */
    val home: Team,
    /** Right of the camera. */
    val away: Team,
    /** The feed's state: "Live", "Stumps", "Tea", "Innings break", "Final Result". */
    val status: String,
    /** One line about the match: "India need 45 runs from 30 balls". */
    val summary: String,
    val league: String = "",
    /** False once the match has a result. */
    val live: Boolean = true,
) {
    data class Team(
        val abbr: String,
        /** The innings being played now: "245/3". Blank before the team bats. */
        val score: String,
        /** "42.1 ov" for the current innings, when known. */
        val overs: String? = null,
        val batting: Boolean = false,
        /** The team's own colour (ARGB), when the source gives one. */
        val color: Int? = null,
        /** Runs the team needs in total, when it is chasing. */
        val target: Int? = null,
        /** An earlier innings in a multi-day match: "265" of "265 & 75/1". */
        val previous: String? = null,
    ) {
        /** Up to four letters for the round badge: "IND", "HH" for "HH-W". */
        val code: String get() = abbr.substringBefore('-').take(4).uppercase()
        val runs: Int? get() = score.takeWhile { it.isDigit() }.toIntOrNull()
        val tint: Int get() = color ?: colorFor(abbr)
    }

    val teams: List<Team> get() = listOf(home, away)
    val batting: Team? get() = teams.firstOrNull { it.batting }
    /** Shows a red "LIVE" badge: play is on (not stumps, a break or a result). */
    val inPlay: Boolean get() = live && (status.isBlank() || status.equals("live", true))

    /** How close the chasing side is to its target, 0..1; null when nobody is chasing. */
    val chase: Float? get() {
        val t = teams.firstOrNull { it.target != null && it.target > 0 } ?: return null
        val runs = t.runs ?: return null
        return (runs.toFloat() / t.target!!).coerceIn(0f, 1f)
    }

    companion object {
        private val known = mapOf(
            // International sides
            "IND" to 0xFF1F5AD6, "AUS" to 0xFFF5C518, "ENG" to 0xFF2D5DB8, "NZ" to 0xFF9AA5AE,
            "PAK" to 0xFF0B8A3E, "SA" to 0xFF0E7C3A, "SL" to 0xFF1D4FBF, "BAN" to 0xFF0A8F4F,
            "WI" to 0xFF8A1538, "AFG" to 0xFF1C63C7, "ZIM" to 0xFFD2232A, "IRE" to 0xFF1BA34A,
            "NED" to 0xFFF36C21, "SCO" to 0xFF3A55A4, "NEP" to 0xFFD7263D, "UAE" to 0xFF00843D,
            "USA" to 0xFFB22234, "OMAN" to 0xFFCE1126,
            // Indian Premier League
            "CSK" to 0xFFF9CD05, "MI" to 0xFF1F6FD1, "RCB" to 0xFFD8202A, "KKR" to 0xFF6A3FA0,
            "SRH" to 0xFFF26522, "DC" to 0xFF2561AE, "RR" to 0xFFEA1A85, "PBKS" to 0xFFDD1F2D,
            "GT" to 0xFF5B7BB2, "LSG" to 0xFF3FD5F3,
        ).mapValues { it.value.toInt() }

        // iOS system colours, for teams without a known colour: the same team always gets the same one.
        private val palette = longArrayOf(0xFF0A84FF, 0xFF30D158, 0xFFFF9F0A, 0xFFFF375F, 0xFFBF5AF2, 0xFF64D2FF,
            0xFFFFD60A, 0xFFFF453A).map { it.toInt() }

        fun colorFor(abbr: String): Int {
            val key = abbr.substringBefore('-').uppercase()
            return known[key] ?: palette[Math.floorMod(key.hashCode(), palette.size)]
        }

        /** "#050ceb" (or "050ceb") → opaque ARGB; null for blanks and junk. */
        fun parseColor(hex: String?): Int? {
            val h = hex?.trim()?.removePrefix("#") ?: return null
            if (h.length != 6) return null
            return h.toLongOrNull(16)?.let { (0xFF000000 or it).toInt() }
        }

        /** Perceived brightness 0..1, for black or white letters on a badge. */
        fun brightness(color: Int): Float {
            val r = (color shr 16) and 0xFF; val g = (color shr 8) and 0xFF; val b = color and 0xFF
            return (0.299f * r + 0.587f * g + 0.114f * b) / 255f
        }
    }
}
