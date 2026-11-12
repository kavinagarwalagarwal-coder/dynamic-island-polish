package com.dynamicdroid.app

import android.app.NotificationManager
import android.content.Context

enum class OutlineMode { OFF, EVENTS, ALWAYS }

/**
 * How the island's shape moves: Apple's (default: one soft overshoot, no squash), liquid
 * (flows and squashes like a drop), bouncy like jelly, or smooth with no overshoot.
 */
enum class MotionStyle { APPLE, LIQUID, BOUNCY, SMOOTH }

/**
 * The island's overall look. APPLE follows Apple's Dynamic Island (SF-like semibold type, app
 * colours, plain black body); NOTHING is the original monochrome, monospace, dot-matrix look.
 */
enum class IslandLook { APPLE, NOTHING }
enum class HapticGesture { TAP, HOLD, SWIPE, UP }

/**
 * Optional island features, read by the overlay service, the island view and the listeners.
 * Everything defaults on except the lock screen and outlines, which change how the phone behaves.
 */
data class IslandFeatureSet(
    val volume: Boolean = true,
    val headphones: Boolean = true,
    val bluetoothLost: Boolean = true,
    val downloads: Boolean = true,
    val alarms: Boolean = true,
    val inlineReply: Boolean = true,
    /** Ring / vibrate / silent and Do Not Disturb changes show on the island. */
    val ringer: Boolean = true,
    /** Ring / vibrate / silent / Do Not Disturb buttons under the volume slider. */
    val ringerControls: Boolean = true,
    /** Opened islands (messages, volume, devices) span the screen instead of sitting at the camera. */
    val fullWidthOpen: Boolean = true,
    /** While a song plays, the pill's background is its album cover, blurred and dimmed. */
    val artworkBackdrop: Boolean = false,
    /** With nothing going on, keep a small black pill at the camera instead of disappearing. */
    val idlePill: Boolean = true,
    /** Step aside for Shorts, Reels, full-screen players and photo viewers, not just true fullscreen. */
    val hideForMedia: Boolean = true,
    /** Music in the island as a spinning, rippling record of dots (Dot music) instead of plain art. */
    val dotMusic: Boolean = false,
    /** Off by default: it takes over from the phone's own pop-ups. */
    val hidePhonePopups: Boolean = false,
    val messagesMediumFirst: Boolean = true,
    val shortCompact: Boolean = false,
    val compactHeightDp: Float = IslandMotion.COMPACT_HEIGHT_DP,
    val phoneCorners: Boolean = true,
    val colouredBubbles: Boolean = true,
    val outline: OutlineMode = OutlineMode.OFF,
    /** A soft halo around the pill's edge so it stands out. */
    val edgeGlow: Boolean = false,
    val burnInCare: Boolean = false,
    val lockScreen: Boolean = false,
    val hapticTap: Boolean = true,
    val hapticHold: Boolean = true,
    val hapticSwipe: Boolean = true,
    val hapticUp: Boolean = true,
    val quietHapticsInDnd: Boolean = true,
    // Which kinds of live activity use the island at all. Off = the phone handles it as usual.
    val music: Boolean = true,
    val calls: Boolean = true,
    val messages: Boolean = true,
    val timers: Boolean = true,
    val navigation: Boolean = true,
    val charging: Boolean = true,
    /** The edge glow only while the island is open, not around the small pill. */
    val glowOpenOnly: Boolean = false,
    /** Calls show over fullscreen apps in portrait; in landscape (games, videos) they never do. */
    val callsOverFullscreen: Boolean = true,
    /** In landscape there's no camera to hide, so the island turns see-through. */
    val landscapeSeeThrough: Boolean = true,
    /** Content fades to black around the camera hole, so nothing sits behind it. */
    val cameraFade: Boolean = true,
    val motion: MotionStyle = MotionStyle.APPLE,
    val look: IslandLook = IslandLook.APPLE,
    // Short notices for system events, each on its own switch.
    val eventUnplugged: Boolean = true,
    val eventLowBattery: Boolean = true,
    val eventWifi: Boolean = true,
    val eventAirplane: Boolean = true,
    val eventUsb: Boolean = true,
    val eventUnlock: Boolean = false,
    val eventBrightness: Boolean = false,
    val eventAlarmSet: Boolean = true,
    val payments: Boolean = true,
    /** The flashlight island while the torch is on. */
    val torch: Boolean = true,
    /** The sleep island in the 12 hours before an alarm. */
    val sleep: Boolean = true,
    /** Tapping the resting pill opens the time, date, battery and quick buttons. */
    val homeCard: Boolean = true,
    /** Swipe the small music island sideways to skip tracks. */
    val swipeTracks: Boolean = true,
    /** Going home from the app that's playing: the app flies up into the island. */
    val appMorph: Boolean = true,
) {
    /** False when the user turned this kind of activity off. */
    fun shows(mode: IslandMode): Boolean = when (mode) {
        IslandMode.MUSIC -> music
        IslandMode.CALL -> calls
        IslandMode.MESSAGE -> messages
        IslandMode.TIMER -> timers
        IslandMode.NAVIGATION -> navigation
        IslandMode.CHARGING -> charging
        IslandMode.DOWNLOAD -> downloads
        IslandMode.ALARM -> alarms
        IslandMode.VOLUME -> volume
        IslandMode.RINGER -> ringer
        IslandMode.DEVICE -> true
        IslandMode.EVENT -> true
        IslandMode.TORCH -> torch
        IslandMode.SLEEP -> sleep
        IslandMode.HOME -> homeCard
    }

    fun safe() = copy(compactHeightDp = compactHeightDp.coerceIn(MIN_COMPACT_DP, MAX_COMPACT_DP))

    fun haptic(gesture: HapticGesture) = when (gesture) {
        HapticGesture.TAP -> hapticTap
        HapticGesture.HOLD -> hapticHold
        HapticGesture.SWIPE -> hapticSwipe
        HapticGesture.UP -> hapticUp
    }

    companion object {
        const val MIN_COMPACT_DP = 30f
        const val MAX_COMPACT_DP = 44f
    }
}

object IslandFeatures {
    private fun p(context: Context) = context.getSharedPreferences("island_features", Context.MODE_PRIVATE)

    fun read(context: Context): IslandFeatureSet {
        val p = p(context)
        val d = IslandFeatureSet()
        return IslandFeatureSet(
            volume = p.getBoolean("volume", d.volume),
            headphones = p.getBoolean("headphones", d.headphones),
            bluetoothLost = p.getBoolean("bluetooth_lost", d.bluetoothLost),
            downloads = p.getBoolean("downloads", d.downloads),
            alarms = p.getBoolean("alarms", d.alarms),
            inlineReply = p.getBoolean("inline_reply", d.inlineReply),
            ringer = p.getBoolean("ringer", d.ringer),
            ringerControls = p.getBoolean("ringer_controls", d.ringerControls),
            fullWidthOpen = p.getBoolean("full_width_open", d.fullWidthOpen),
            artworkBackdrop = p.getBoolean("artwork_backdrop", d.artworkBackdrop),
            idlePill = p.getBoolean("idle_pill", d.idlePill),
            hideForMedia = p.getBoolean("hide_for_media", d.hideForMedia),
            dotMusic = p.getBoolean("dot_music", d.dotMusic),
            hidePhonePopups = p.getBoolean("hide_phone_popups", d.hidePhonePopups),
            messagesMediumFirst = p.getBoolean("messages_medium", d.messagesMediumFirst),
            shortCompact = p.getBoolean("short_compact", d.shortCompact),
            compactHeightDp = p.getFloat("compact_height", d.compactHeightDp),
            phoneCorners = p.getBoolean("phone_corners", d.phoneCorners),
            colouredBubbles = p.getBoolean("coloured_bubbles", d.colouredBubbles),
            outline = runCatching { OutlineMode.valueOf(p.getString("outline", d.outline.name) ?: d.outline.name) }
                .getOrDefault(d.outline),
            edgeGlow = p.getBoolean("edge_glow", d.edgeGlow),
            burnInCare = p.getBoolean("burn_in", d.burnInCare),
            lockScreen = p.getBoolean("lock_screen", d.lockScreen),
            hapticTap = p.getBoolean("haptic_tap", d.hapticTap),
            hapticHold = p.getBoolean("haptic_hold", d.hapticHold),
            hapticSwipe = p.getBoolean("haptic_swipe", d.hapticSwipe),
            hapticUp = p.getBoolean("haptic_up", d.hapticUp),
            quietHapticsInDnd = p.getBoolean("haptic_dnd_quiet", d.quietHapticsInDnd),
            music = p.getBoolean("show_music", d.music),
            calls = p.getBoolean("show_calls", d.calls),
            messages = p.getBoolean("show_messages", d.messages),
            timers = p.getBoolean("show_timers", d.timers),
            navigation = p.getBoolean("show_navigation", d.navigation),
            charging = p.getBoolean("show_charging", d.charging),
            glowOpenOnly = p.getBoolean("glow_open_only", d.glowOpenOnly),
            callsOverFullscreen = p.getBoolean("calls_over_fullscreen", d.callsOverFullscreen),
            landscapeSeeThrough = p.getBoolean("landscape_see_through", d.landscapeSeeThrough),
            cameraFade = p.getBoolean("camera_fade", d.cameraFade),
            motion = runCatching { MotionStyle.valueOf(p.getString("motion", d.motion.name) ?: d.motion.name) }
                .getOrDefault(d.motion),
            look = runCatching { IslandLook.valueOf(p.getString("look", d.look.name) ?: d.look.name) }
                .getOrDefault(d.look),
            eventUnplugged = p.getBoolean("event_unplugged", d.eventUnplugged),
            eventLowBattery = p.getBoolean("event_low_battery", d.eventLowBattery),
            eventWifi = p.getBoolean("event_wifi", d.eventWifi),
            eventAirplane = p.getBoolean("event_airplane", d.eventAirplane),
            eventUsb = p.getBoolean("event_usb", d.eventUsb),
            eventUnlock = p.getBoolean("event_unlock", d.eventUnlock),
            eventBrightness = p.getBoolean("event_brightness", d.eventBrightness),
            eventAlarmSet = p.getBoolean("event_alarm_set", d.eventAlarmSet),
            payments = p.getBoolean("event_payments", d.payments),
            torch = p.getBoolean("show_torch", d.torch),
            sleep = p.getBoolean("show_sleep", d.sleep),
            homeCard = p.getBoolean("home_card", d.homeCard),
            swipeTracks = p.getBoolean("swipe_tracks", d.swipeTracks),
            appMorph = p.getBoolean("app_morph", d.appMorph),
        ).safe()
    }

    fun setBoolean(context: Context, key: String, value: Boolean) = p(context).edit().putBoolean(key, value).apply()
    fun setCompactHeight(context: Context, dp: Float) = p(context).edit()
        .putFloat("compact_height", dp.coerceIn(IslandFeatureSet.MIN_COMPACT_DP, IslandFeatureSet.MAX_COMPACT_DP)).apply()
    fun setOutline(context: Context, mode: OutlineMode) = p(context).edit().putString("outline", mode.name).apply()
    fun setMotion(context: Context, style: MotionStyle) = p(context).edit().putString("motion", style.name).apply()

    /**
     * Switching the look also sets the switches that belong to it; each can still be changed
     * afterwards. Apple: plain black body with the album art in its corner, a resting pill and
     * Apple's spring. Nothing: dot-matrix music, blurred art behind it, liquid motion.
     */
    fun setLook(context: Context, look: IslandLook) {
        val apple = look == IslandLook.APPLE
        p(context).edit()
            .putString("look", look.name)
            .putBoolean("dot_music", !apple)
            .putBoolean("artwork_backdrop", !apple)
            .putBoolean("idle_pill", apple)
            .putBoolean("edge_glow", false)
            .putString("motion", (if (apple) MotionStyle.APPLE else MotionStyle.LIQUID).name)
            .apply()
    }

    /** The island is paused (Quick Settings tile) until this wall-clock time; 0 = not paused. */
    fun pausedUntil(context: Context) = p(context).getLong("paused_until", 0L)
    fun setPausedUntil(context: Context, at: Long) = p(context).edit().putLong("paused_until", at).apply()
    fun paused(context: Context) = System.currentTimeMillis() < pausedUntil(context)

    /** Do Not Disturb is on when anything other than "all" interruptions is allowed. */
    fun dndActive(context: Context): Boolean = runCatching {
        context.getSystemService(NotificationManager::class.java)?.currentInterruptionFilter?.let {
            it != NotificationManager.INTERRUPTION_FILTER_ALL && it != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        } ?: false
    }.getOrDefault(false)
}

/** Platform-free rules for turning notification facts into island activities; unit tested. */
object IslandSignals {
    /** Wallets and payment apps whose "paid" notifications become a short Paid notice. */
    private val payApps = setOf(
        "com.google.android.apps.walletnfcrel", "com.samsung.android.spay", "com.samsung.android.samsungpay.gear",
        "com.google.android.apps.nbu.paisa.user", "com.phonepe.app", "net.one97.paytm", "in.org.npci.upiapp",
        "in.amazon.mShop.android.shopping", "com.mobikwik_new", "com.paypal.android.p2pmobile", "com.revolut.revolut",
        "com.squareup.cash", "com.venmo",
    )
    private val paidWords = Regex(
        "(paid|payment (?:successful|complete|done|sent)|sent ₹|sent \\$|you sent|money sent|transaction successful|" +
            "debited|purchase approved|оплачено|оплата прошла)", RegexOption.IGNORE_CASE)

    fun isPayment(pkg: String, title: String, text: String): Boolean =
        pkg in payApps && paidWords.containsMatchIn("$title $text")

    private val snoozeWords = listOf("snooze")
    private val dismissWords = listOf("dismiss", "stop", "turn off", "cancel")
    private val answerWords = listOf("answer", "accept")

    fun isRingingAlarm(category: String?, clockApp: Boolean, actionLabels: List<String>): Boolean =
        (category == "alarm" || clockApp) && actionLabels.any { label -> snoozeWords.any { label.contains(it, true) } }

    fun alarmDismissIndex(actionLabels: List<String>): Int =
        actionLabels.indexOfFirst { label -> dismissWords.any { label.contains(it, true) } }

    fun alarmSnoozeIndex(actionLabels: List<String>): Int =
        actionLabels.indexOfFirst { label -> snoozeWords.any { label.contains(it, true) } }

    /**
     * Android 12+ CallStyle reports the type directly (1 incoming, 2 ongoing, 3 screening). Older
     * dialers: an answer button means ringing; a running chronometer means connected; otherwise the
     * call is still being placed.
     */
    fun callState(callType: Int, actionLabels: List<String>, showsChronometer: Boolean): CallState = when {
        callType == 1 || callType == 3 -> CallState.INCOMING
        actionLabels.any { label -> answerWords.any { label.contains(it, true) } } -> CallState.INCOMING
        showsChronometer -> CallState.ACTIVE
        callType == 2 -> CallState.CALLING
        else -> CallState.CALLING
    }

    /** Determinate progress only; indeterminate spinners and finished bars are not islands. */
    fun progressFraction(progress: Int, max: Int, indeterminate: Boolean): Float? {
        if (indeterminate || max <= 0) return null
        return (progress.toFloat() / max).coerceIn(0f, 1f)
    }

    /** Coalesce progress posts: a new value only when the shown whole percent changes or it completes. */
    fun progressWorthPosting(previous: Float?, next: Float): Boolean =
        previous == null || next >= 1f || percent(next) != percent(previous)

    /** Whole-percent label for volume and progress islands. */
    fun percent(fraction: Float): Int = (fraction.coerceIn(0f, 1f) * 100f + 0.5f).toInt()

    /** Volume key steps go through the stream's own range, so a 15-step phone lands on exact steps. */
    fun stepVolume(current: Int, max: Int, up: Boolean): Int =
        (current + if (up) 1 else -1).coerceIn(0, max.coerceAtLeast(0))
}
