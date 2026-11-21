package com.dynamicdroid.app

/**
 * Rules for the ongoing notifications that become islands of their own, the way iPhone apps run
 * Live Activities: rides and deliveries, live scores, flights, recordings, the hotspot. Pure
 * functions of a notification's fields, so they can be tested off-device.
 */
object LiveSignals {
    /** Android 16's "Live Updates" template: progress with steps, like a ride or a delivery. */
    const val PROGRESS_STYLE = "android.app.Notification\$ProgressStyle"
    /** Android 16: an app asking for its ongoing notification to be shown as a Live Update. */
    const val EXTRA_REQUEST_PROMOTED = "android.requestPromotedOngoing"
    /** Android 16: the short status chip text ("5 min", "2–1") an app gives a Live Update. */
    const val EXTRA_SHORT_CRITICAL_TEXT = "android.shortCriticalText"
    /** Android 16 flag on a notification the system promoted to a Live Update. */
    const val FLAG_PROMOTED_ONGOING = 0x00040000

    /**
     * Apps whose ongoing notifications are live trips, orders, scores or flights even before they
     * adopt Android 16's Live Updates.
     */
    val liveApps = setOf(
        // Rides
        "com.ubercab", "com.olacabs.customer", "com.rapido.passenger", "com.lyft.android", "ee.mtakso.client",
        "com.blablacar.android",
        // Food and groceries
        "com.ubercab.eats", "in.swiggy.android", "com.application.zomato", "com.zeptoconsumerapp",
        "com.grofers.customerapp", "com.bigbasket.mobileapp", "com.dunzo.user", "com.dd.doordash",
        "com.grubhub.android", "com.deliveroo.orderapp", "com.global.foodpanda.android", "com.talabat",
        "com.instacart.client",
        // Live scores
        "com.cricbuzz.android", "com.july.cricinfo", "com.espn.score_center", "com.fotmob.android",
        "com.onefootball.android", "com.theathletic", "com.sofascore.results",
        "com.google.android.apps.sports", "com.pl.cwc_2015", "in.cricketexchange.app.cricketexchange",
        // Flights and trains
        "com.flightradar24free", "com.flightaware.android.liveFlightTracker", "com.tripit", "com.makemytrip",
        "com.ixigo.train.ixitrain",
    )

    /** An app's ongoing notification that is a live trip, order, score or flight. */
    fun isLiveUpdate(template: String?, flags: Int, requestPromoted: Boolean, packageName: String,
                     ongoing: Boolean, hasShortText: Boolean): Boolean =
        template == PROGRESS_STYLE || flags and FLAG_PROMOTED_ONGOING != 0 || requestPromoted ||
            (ongoing && (packageName in liveApps || hasShortText))

    /** The Google app: its live sports scores, and only those, become live updates. */
    const val GOOGLE_APP = "com.google.android.googlequicksearchbox"

    /** Apps that post live scores: Cricbuzz, ESPNcricinfo, ICC, CREX, ESPN, Google, JioHotstar... */
    val scoreApps = setOf(
        "com.cricbuzz.android", "com.july.cricinfo", "com.pl.cwc_2015", "in.cricketexchange.app.cricketexchange",
        "com.espn.score_center", "com.fotmob.android", "com.onefootball.android", "com.sofascore.results",
        "com.theathletic", "com.google.android.apps.sports", GOOGLE_APP, "in.startv.hotstar",
    )

    // "IND 245/3 (42.1 ov)", "AUS: 198/7 (20)", "245/3 (42.1)": runs/wickets with overs, or a team code.
    private val cricketOvers = Regex("(?:\\b([A-Z]{2,5})\\s*:?\\s+)?\\b(\\d{1,3}[/-]\\d{1,2})\\s*\\(\\s*(\\d{1,2}(?:\\.[0-5])?)\\s*(?:ov|ovs|overs?)?\\s*\\)",
        RegexOption.IGNORE_CASE)
    private val cricketRunsOvers = Regex("(?:\\b([A-Z]{2,5})\\s*:?\\s+)?\\b(\\d{1,3})\\s*\\(\\s*\\d{1,2}(?:\\.[0-5])?\\s*(?:ov|ovs|overs?)\\s*\\)",
        RegexOption.IGNORE_CASE)
    private val cricketTeam = Regex("\\b([A-Z]{2,5})\\s*:?\\s+(\\d{1,3}[/-]\\d{1,2})\\b")
    // From a score app, a full team name counts too: "India 245/3", "Mumbai Indians 188-6".
    private val namedTeam = Regex("\\b([A-Z][A-Za-z]+(?: [A-Z][A-Za-z]+)?)\\s*:?\\s+(\\d{1,3}[/-]\\d{1,2})\\b")
    private val chase = Regex("\\b(need|needs|require|requires|target|trail|lead)s?\\b.*\\b\\d+\\b.*\\b(runs?|balls?|overs?)\\b",
        RegexOption.IGNORE_CASE)

    /** The pill's chip for a cricket score: "IND 245/3", or the runs alone. */
    fun cricketScore(lines: List<String>): String? = cricketScoreIn(lines)
        // Scoreboards (Google's pinned score among them) put the team, the runs and the overs
        // in separate boxes: read them run together as one line as well.
        ?: lines.takeIf { it.size > 1 }?.let { cricketScoreIn(listOf(it.joinToString(" "))) }

    private fun cricketScoreIn(lines: List<String>): String? {
        for (s in lines) (cricketOvers.find(s) ?: cricketRunsOvers.find(s))?.let { m ->
            val team = m.groupValues[1].takeIf { it.isNotEmpty() && it == it.uppercase() }
            return listOfNotNull(team, m.groupValues[2]).joinToString(" ")
        }
        for (s in lines) cricketTeam.find(s)?.let { return "${it.groupValues[1]} ${it.groupValues[2]}" }
        return null
    }

    // "IND 245/3 (42.1 ov)", "AUS: 274/8", "NZ 368": a team code, its runs, and overs when batting.
    private val teamInnings = Regex("\\b([A-Z]{2,5}(?:-[AW])?)\\s*:?\\s+(\\d{1,3}(?:[/-]\\d{1,2})?d?)(?![\\d.:])" +
        "(?:\\s*\\(\\s*(\\d{1,3}(?:\\.[0-5])?)\\s*(?:/\\s*\\d{1,3}\\s*)?(?:ov|ovs|overs?)?\\s*\\))?")
    private val yetToBat = Regex("\\b([A-Z]{2,5}(?:-[AW])?)\\s*:?\\s+(?i:yet to bat)\\b")
    private val notTeams = setOf("LIVE", "ODI", "TEST", "OVER", "OVERS", "RUNS", "NEED", "NEEDS", "ETA", "AM", "PM", "WON", "INN")

    /**
     * Both teams of a cricket notification, for the two-sided scoreboard: "IND 245/3 (42.1 ov)" and
     * "AUS 274/8" (or "AUS yet to bat"). Null unless two different teams are found and at least
     * one has a cricket score (wickets or overs), so a lone number never becomes a scoreboard.
     */
    fun cricketBoard(lines: List<String>, league: String = ""): Scoreboard? {
        val all = (lines + listOf(lines.joinToString(" "))).filter { it.isNotBlank() }
        val teams = LinkedHashMap<String, Scoreboard.Team>()
        var scored = false
        for (s in all) {
            for (m in teamInnings.findAll(s)) {
                val abbr = m.groupValues[1]
                if (abbr in notTeams || abbr in teams) continue
                val overs = m.groupValues[3].takeIf { it.isNotEmpty() }
                val score = m.groupValues[2].replace('-', '/')
                if (overs != null || '/' in score) scored = true
                teams[abbr] = Scoreboard.Team(abbr, score, overs?.let { "$it ov" }, batting = overs != null)
            }
            for (m in yetToBat.findAll(s)) {
                val abbr = m.groupValues[1].uppercase()
                if (abbr !in notTeams && abbr !in teams) teams[abbr] = Scoreboard.Team(abbr, "")
            }
        }
        if (!scored || teams.size < 2) return null
        val (home, away) = teams.values.take(2)
        // Without overs, the side whose score shows wickets is the one still batting.
        val fixed = if (home.batting || away.batting) listOf(home, away) else listOf(home, away).let { (h, a) ->
            when {
                '/' in h.score && '/' !in a.score -> listOf(h.copy(batting = true), a)
                '/' in a.score && '/' !in h.score -> listOf(h, a.copy(batting = true))
                else -> listOf(h, a)
            }
        }
        val summary = lines.firstOrNull { chase.containsMatchIn(it) || it.contains(" won ", true) || it.contains("yet to bat", true) }
            ?.trim().orEmpty()
        return Scoreboard(fixed[0], fixed[1], "", summary.take(80), league)
    }

    /** Text that reads as a live cricket score (or a chase: "need 30 runs in 18 balls"). */
    fun looksLikeScore(lines: List<String>, scoreApp: Boolean = true): Boolean =
        cricketScore(lines) != null || scoreApp && (lines.any { chase.containsMatchIn(it) || namedTeam.containsMatchIn(it) } ||
            lines.any { runsAlone.matches(it.trim()) })

    // A scoreboard box holding just "245/3" or "245-3", from a score app.
    private val runsAlone = Regex("\\d{1,3}[/-]\\d{1,2}(?:\\s*\\(\\d{1,2}(?:\\.[0-5])?\\s*(?:ov|ovs|overs?)?\\))?")

    /**
     * A live score notification: from a score app (or the Google app), or any app's ongoing one
     * whose text reads as a cricket score. Must be the kind that updates in place (ongoing, or
     * alerting only once), so wicket and boundary alerts stay ordinary notifications.
     */
    fun isLiveScore(packageName: String, ongoing: Boolean, alertOnce: Boolean, lines: List<String>, quiet: Boolean = false): Boolean {
        // A score app's silent notification that it keeps updating counts as well.
        if (!ongoing && !((alertOnce || quiet) && packageName in scoreApps)) return false
        return looksLikeScore(lines, packageName in scoreApps)
    }

    private val recorders = setOf(
        "com.google.android.apps.recorder", "com.sec.android.app.voicenote", "com.android.soundrecorder",
        "com.coloros.soundrecorder", "com.oneplus.soundrecorder", "com.miui.soundrecorder", "com.android.soundrecorder.app",
        "com.hecorat.screenrecorder.free", "com.kimcy929.screenrecorder", "com.duapps.recorder", "com.nll.asr",
        "com.sec.android.app.screenrecorder", "com.miui.screenrecorder", "com.oplus.screenrecorder",
    )
    private val recordingWords = Regex("\\b(recording|recorder|rec)\\b", RegexOption.IGNORE_CASE)

    /** Screen or voice recording in progress: a recorder app, or Android's own screen recorder. */
    fun isRecording(packageName: String, channel: String?, title: String, text: String,
                    ongoing: Boolean, chronometer: Boolean): Boolean {
        if (!ongoing) return false
        val c = channel.orEmpty()
        if (packageName == "com.android.systemui") return c.contains("screen_record", true) || c.contains("screenrecord", true) ||
            recordingWords.containsMatchIn(title) && title.contains("screen", true)
        if (packageName in recorders) return chronometer || recordingWords.containsMatchIn("$title $text")
        return chronometer && (c.contains("record", true) || recordingWords.containsMatchIn(title))
    }

    /** True for Android's own screen recorder, so the right glyph and words can be used. */
    fun isScreenRecording(packageName: String, channel: String?, title: String): Boolean =
        packageName == "com.android.systemui" || packageName.contains("screenrecorder", true) ||
            channel.orEmpty().contains("screen", true) || title.contains("screen", true)

    /** The phone sharing its connection (Wi-Fi hotspot or tethering). */
    fun isHotspot(packageName: String, channel: String?, ongoing: Boolean): Boolean {
        if (!ongoing) return false
        val c = channel.orEmpty()
        return packageName.contains("tethering", true) || c.contains("tether", true) || c.contains("hotspot", true)
    }

    private val eta = Regex("\\b(\\d{1,3})\\s?(min|mins|minutes|m)\\b", RegexOption.IGNORE_CASE)
    private val clock = Regex("\\b([01]?\\d|2[0-3])[:.][0-5]\\d(\\s?[ap]\\.?m\\.?)?\\b", RegexOption.IGNORE_CASE)
    private val score = Regex("\\b\\d{1,3}\\s?[-–:/]\\s?\\d{1,3}\\b")
    private val runs = Regex("\\b\\d{1,3}/\\d{1,2}\\b")

    /**
     * The few characters that say the most, for the small island: what the app gave as its status
     * chip, else an arrival time ("5 min"), a clock time, or a score, found in the title or text.
     */
    fun shortText(explicit: CharSequence?, title: String, text: String): String? {
        explicit?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it.take(10) }
        cricketScore(listOf(title, text))?.let { return it }
        for (s in listOf(title, text)) {
            eta.find(s)?.let { return "${it.groupValues[1]} min" }
        }
        for (s in listOf(title, text)) {
            runs.find(s)?.let { return it.value }
            score.find(s)?.let { m -> if (!clock.matches(m.value)) return m.value.replace(" ", "") }
        }
        for (s in listOf(title, text)) clock.find(s)?.let { return it.value }
        return null
    }

    /** The Android output switcher for an app's audio (Apple's AirPlay button): the intents to try. */
    const val MEDIA_OUTPUT_PANEL = "com.android.settings.panel.action.MEDIA_OUTPUT"
    const val MEDIA_OUTPUT_PACKAGE = "com.android.settings.panel.extra.PACKAGE_NAME"
}
