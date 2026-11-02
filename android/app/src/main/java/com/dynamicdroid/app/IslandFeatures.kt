package com.dynamicdroid.app

import android.app.NotificationManager
import android.content.Context

enum class OutlineMode { OFF, EVENTS, ALWAYS }
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
) {
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
        ).safe()
    }

    fun setBoolean(context: Context, key: String, value: Boolean) = p(context).edit().putBoolean(key, value).apply()
    fun setCompactHeight(context: Context, dp: Float) = p(context).edit()
        .putFloat("compact_height", dp.coerceIn(IslandFeatureSet.MIN_COMPACT_DP, IslandFeatureSet.MAX_COMPACT_DP)).apply()
    fun setOutline(context: Context, mode: OutlineMode) = p(context).edit().putString("outline", mode.name).apply()

    /** Do Not Disturb is on when anything other than "all" interruptions is allowed. */
    fun dndActive(context: Context): Boolean = runCatching {
        context.getSystemService(NotificationManager::class.java)?.currentInterruptionFilter?.let {
            it != NotificationManager.INTERRUPTION_FILTER_ALL && it != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        } ?: false
    }.getOrDefault(false)
}

/** Platform-free rules for turning notification facts into island activities; unit tested. */
object IslandSignals {
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
