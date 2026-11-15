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
        "com.google.android.apps.sports",
        // Flights and trains
        "com.flightradar24free", "com.flightaware.android.liveFlightTracker", "com.tripit", "com.makemytrip",
        "com.ixigo.train.ixitrain",
    )

    /** An app's ongoing notification that is a live trip, order, score or flight. */
    fun isLiveUpdate(template: String?, flags: Int, requestPromoted: Boolean, packageName: String,
                     ongoing: Boolean, hasShortText: Boolean): Boolean =
        template == PROGRESS_STYLE || flags and FLAG_PROMOTED_ONGOING != 0 || requestPromoted ||
            (ongoing && (packageName in liveApps || hasShortText))

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
