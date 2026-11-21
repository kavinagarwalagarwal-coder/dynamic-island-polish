package com.dynamicdroid.app

/** Which way the next manoeuvre goes; drawn as the Maps-style turn arrow. */
enum class Turn { STRAIGHT, LEFT, RIGHT, SLIGHT_LEFT, SLIGHT_RIGHT, SHARP_LEFT, SHARP_RIGHT, U_TURN, ROUNDABOUT, ARRIVE }

/**
 * A navigation notification split the way iOS Maps lays out its Live Activity: the distance
 * to the next turn (big), the instruction (small) and the turn arrow. Pure, so it is unit-tested.
 */
data class NavigationStep(val distance: String?, val instruction: String, val turn: Turn?) {
    companion object {
        private val DISTANCE = Regex("""(?i)(?<![\w.,])\d+(?:[.,]\d+)?\s?(?:km|mi|m|ft|yd|mile|miles|metres|meters)(?![\w])""")

        fun parse(title: String, subtitle: String): NavigationStep {
            val t = title.trim(); val s = subtitle.trim()
            val inTitle = distanceIn(t); val inSub = distanceIn(s)
            val (distance, rest) = when {
                inTitle != null && inTitle.value.length >= t.length - 1 -> inTitle.value to s
                inSub != null && inSub.value.length >= s.length - 1 -> inSub.value to t
                inTitle != null -> inTitle.value to listOf(strip(t, inTitle), s).filter { it.isNotBlank() }.joinToString(" · ")
                inSub != null -> inSub.value to listOf(t, strip(s, inSub)).filter { it.isNotBlank() }.joinToString(" · ")
                else -> null to listOf(t, s).filter { it.isNotBlank() }.joinToString(" · ")
            }
            return NavigationStep(distance, rest.ifBlank { t }.replaceFirstChar { it.uppercaseChar() }, turnOf(t, s))
        }

        // A number with a unit inside a street name ("onto 8 Mile Road") is not a distance: accept only
        // a whole line, "in 200 m", or a distance set off at either end by punctuation.
        private fun distanceIn(line: String) = DISTANCE.findAll(line).firstOrNull { m ->
            val before = line.substring(0, m.range.first).trimEnd()
            val after = line.substring(m.range.last + 1).trimStart()
            (before.isEmpty() && (after.isEmpty() || after[0] in SEPARATORS)) ||
                (after.isEmpty() && before.lastOrNull() in SEPARATORS) ||
                before.endsWith(" in", ignoreCase = true) || before.equals("in", ignoreCase = true)
        }

        private val SEPARATORS = setOf(',', '·', '-', '–', '—', ':', ';', '•')

        private fun strip(text: String, m: MatchResult) =
            text.removeRange(m.range).trim().removePrefix("in ").removePrefix("In ").trim(' ', ',', '·', '-')

        // Road names follow these words ("onto Left Bank Road"), so only the words before them say which way to go.
        private val ROAD = Regex("""(?i)\b(?:onto|on|into|toward|towards|via|to)\b(?!\s+(?:your|the)\s+(?:left|right))""")
        private val MANOEUVRE = Regex("""(?i)\b(?:turn|keep|bear|exit|merge|continue|head|make|take|slight|sharp|arriv|u-turn|roundabout|fork|stay|destination)""")

        /** The turn named in the phrase that holds the manoeuvre ("use any lane to turn right"), not in a street name. */
        fun turnOf(vararg lines: String): Turn? {
            val phrases = lines.flatMap { ROAD.split(it) }
            val manoeuvres = phrases.filter { MANOEUVRE.containsMatchIn(it) }
            return manoeuvres.ifEmpty { phrases }.firstNotNullOfOrNull { classify(it) }
        }

        private fun classify(text: String): Turn? {
            val x = text.lowercase()
            fun has(vararg w: String) = w.any { x.contains(it) }
            return when {
                has("u-turn", "u turn", "make a u") -> Turn.U_TURN
                has("roundabout", "rotary", "traffic circle") -> Turn.ROUNDABOUT
                has("arrive", "destination", "you have reached", "on your left", "on your right") && !has("turn") -> Turn.ARRIVE
                has("sharp left") -> Turn.SHARP_LEFT
                has("sharp right") -> Turn.SHARP_RIGHT
                has("slight left", "keep left", "bear left", "exit left", "fork left") -> Turn.SLIGHT_LEFT
                has("slight right", "keep right", "bear right", "exit right", "fork right") -> Turn.SLIGHT_RIGHT
                has("left") -> Turn.LEFT
                has("right") -> Turn.RIGHT
                has("straight", "continue", "head ", "merge", "stay") -> Turn.STRAIGHT
                else -> null
            }
        }
    }
}
