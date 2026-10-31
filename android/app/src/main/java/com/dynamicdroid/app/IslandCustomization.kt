package com.dynamicdroid.app

import android.content.Context
import java.time.LocalTime

/** User choices for notification visibility, priority, actions, app accents and quiet rules. */
object IslandCustomization {
    val accents = listOf(
        "Default" to null,
        "Red" to 0xFFFF453A.toInt(),
        "Orange" to 0xFFFF9F0A.toInt(),
        "Yellow" to 0xFFFFD60A.toInt(),
        "Green" to 0xFF30D158.toInt(),
        "Blue" to 0xFF0A84FF.toInt(),
        "Purple" to 0xFFBF5AF2.toInt(),
        "Pink" to 0xFFFF375F.toInt(),
    )

    private fun p(context: Context) = context.getSharedPreferences("island_customization", Context.MODE_PRIVATE)
    private fun set(context: Context, key: String) = p(context).getStringSet(key, emptySet())?.toSet().orEmpty()
    private fun storeSet(context: Context, key: String, value: Set<String>) = p(context).edit().putStringSet(key, value).apply()
    private fun packageKey(pkg: String) = pkg.replace("%", "%25").replace("/", "%2F")

    fun allowed(context: Context, pkg: String): Boolean {
        val prefs = p(context)
        if (!prefs.getBoolean("allowed_filter_on", false)) return true
        return pkg in set(context, "allowed_apps")
    }
    fun allowedApps(context: Context) = set(context, "allowed_apps")
    fun setAllowedApps(context: Context, apps: Set<String>, filterEnabled: Boolean = true) {
        p(context).edit().putStringSet("allowed_apps", apps.toSet())
            .putBoolean("allowed_filter_on", filterEnabled).apply()
    }
    fun allowedFilterEnabled(context: Context) = p(context).getBoolean("allowed_filter_on", false)
    fun setAppAllowed(context: Context, pkg: String, allowed: Boolean) {
        val values = set(context, "allowed_apps").toMutableSet()
        if (allowed) values += pkg else values -= pkg
        storeSet(context, "allowed_apps", values)
    }

    fun hiddenWhenOpen(context: Context) = set(context, "hide_open_apps")
    fun setHiddenWhenOpen(context: Context, apps: Set<String>) = storeSet(context, "hide_open_apps", apps)
    fun isHiddenForForeground(context: Context, pkg: String?) = pkg != null && pkg in hiddenWhenOpen(context)

    fun timerAsBubble(context: Context) = p(context).getBoolean("timer_bubble", true)
    fun setTimerAsBubble(context: Context, enabled: Boolean) = p(context).edit().putBoolean("timer_bubble", enabled).apply()
    fun priority(context: Context, mode: IslandMode): Int = p(context).getInt("priority_${mode.name}", defaultPriority(mode))
    fun setPriority(context: Context, mode: IslandMode, priority: Int) = p(context).edit().putInt("priority_${mode.name}", priority.coerceIn(0, 100)).apply()
    private fun defaultPriority(mode: IslandMode) = when (mode) {
        IslandMode.CALL -> 100; IslandMode.TIMER -> 80; IslandMode.MUSIC -> 70
        IslandMode.NAVIGATION -> 60; IslandMode.MESSAGE -> 30; IslandMode.CHARGING -> 20
    }

    fun customAccent(context: Context, pkg: String): Int? =
        if (accentChoice(context, pkg) == 0) null else p(context).getInt("accent_${packageKey(pkg)}", accents[accentChoice(context, pkg)].second ?: 0xFFFFFFFF.toInt())
    fun motionSpeed(context: Context, pkg: String): Float =
        p(context).getFloat("speed_${packageKey(pkg)}", 1f).coerceIn(.65f, 1.5f)
    fun setMotionSpeed(context: Context, pkg: String, speed: Float) =
        p(context).edit().putFloat("speed_${packageKey(pkg)}", speed.coerceIn(.65f, 1.5f)).apply()
    fun preferredExpanded(context: Context, pkg: String): Boolean? =
        p(context).getString("layout_${packageKey(pkg)}", null)?.toBooleanStrictOrNull()
    fun setPreferredExpanded(context: Context, pkg: String, expanded: Boolean?) {
        val edit = p(context).edit(); val key = "layout_${packageKey(pkg)}"
        if (expanded == null) edit.remove(key) else edit.putString(key, expanded.toString())
        edit.apply()
    }
    fun accentChoice(context: Context, pkg: String): Int =
        p(context).getInt("accent_choice_${packageKey(pkg)}", 0)
    fun setAccentChoice(context: Context, pkg: String, choice: Int) {
        val index = choice.coerceIn(0, accents.lastIndex)
        val editor = p(context).edit().putInt("accent_choice_${packageKey(pkg)}", index)
        accents[index].second?.let { editor.putInt("accent_${packageKey(pkg)}", it) }
            ?: editor.remove("accent_${packageKey(pkg)}")
        editor.apply()
    }

    fun shownActions(context: Context, activity: LiveActivity): List<IslandAction> {
        val selected = p(context).getStringSet("actions_${packageKey(activity.packageName)}", null)
            ?: return activity.actions
        return activity.actions.filter { it.label in selected }
    }
    fun mediaSelection(context: Context, pkg: String): Set<String>? =
        p(context).getStringSet("media_actions_${packageKey(pkg)}", null)?.toSet()
    fun setMediaSelection(context: Context, pkg: String, labels: Set<String>?) {
        val editor = p(context).edit(); val key = "media_actions_${packageKey(pkg)}"
        if (labels == null) editor.remove(key) else editor.putStringSet(key, labels)
        editor.apply()
    }
    fun showMediaAction(context: Context, pkg: String, action: String): Boolean =
        mediaSelection(context, pkg)?.contains(action) ?: true

    fun actionSelection(context: Context, pkg: String): Set<String>? =
        p(context).getStringSet("actions_${packageKey(pkg)}", null)?.toSet()
    fun setActionSelection(context: Context, pkg: String, labels: Set<String>?) {
        val editor = p(context).edit(); val key = "actions_${packageKey(pkg)}"
        if (labels == null) editor.remove(key) else editor.putStringSet(key, labels)
        editor.apply()
    }

    fun setQuietHours(context: Context, enabled: Boolean, startMinute: Int, endMinute: Int) {
        p(context).edit().putBoolean("quiet_on", enabled)
            .putInt("quiet_start", startMinute.coerceIn(0, 1439))
            .putInt("quiet_end", endMinute.coerceIn(0, 1439)).apply()
    }
    fun quietEnabled(context: Context) = p(context).getBoolean("quiet_on", false)
    fun quietStart(context: Context) = p(context).getInt("quiet_start", 22 * 60)
    fun quietEnd(context: Context) = p(context).getInt("quiet_end", 7 * 60)
    fun quietNow(context: Context, now: LocalTime = LocalTime.now()): Boolean {
        if (!quietEnabled(context)) return false
        val minute = now.hour * 60 + now.minute
        val start = quietStart(context); val end = quietEnd(context)
        return if (start == end) false else if (start < end) minute in start until end else minute >= start || minute < end
    }

    fun savedApps(context: Context) = p(context).getStringSet("styled_apps", emptySet())?.toSet().orEmpty()
    fun saveStyledApps(context: Context, apps: Set<String>) = storeSet(context, "styled_apps", apps)
}

/** Device-specific saved placement without changing the current live calibration until selected. */
object IslandProfileStore {
    private fun prefs(context: Context) = context.getSharedPreferences("island_profiles", Context.MODE_PRIVATE)
    fun names(context: Context): Set<String> = prefs(context).getStringSet("names", emptySet())?.toSet().orEmpty()
    fun save(context: Context, name: String, fit: IslandFit) {
        val safe = fit.safe(); val key = "profile_${name.hashCode()}"
        prefs(context).edit().putStringSet("names", names(context) + name)
            .putString(key, listOf(safe.scale, safe.pillWidth, safe.offsetX, safe.offsetY,
                if (safe.alignCamera) 1f else 0f, safe.placement.ordinal.toFloat()).joinToString(",")).apply()
    }
    fun load(context: Context, name: String): IslandFit? = runCatching {
        val values = prefs(context).getString("profile_${name.hashCode()}", null)?.split(",") ?: return null
        IslandFit(values[0].toFloat(), values[1].toFloat(), values[2].toFloat(), values[3].toFloat(),
            values[4].toFloat() > .5f, IslandPlacement.values()[values[5].toFloat().toInt()]).safe()
    }.getOrNull()
    fun remove(context: Context, name: String) {
        prefs(context).edit().putStringSet("names", names(context) - name).remove("profile_${name.hashCode()}").apply()
    }
}

fun appTint(context: Context, activity: LiveActivity): LiveActivity =
    activity.copy(actions = IslandCustomization.shownActions(context, activity).take(3))
