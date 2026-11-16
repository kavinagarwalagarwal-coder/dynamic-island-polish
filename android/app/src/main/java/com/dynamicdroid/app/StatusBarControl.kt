package com.dynamicdroid.app

import android.content.Context
import android.provider.Settings

/**
 * An iPhone-clean status bar around the island: Android's own hidden "icon_blacklist" setting
 * (the one System UI Tuner uses) hides the extra system icons (alarm, Bluetooth, ringer, Do Not
 * Disturb, location, NFC, hotspot, cast, rotation, VPN...) so only the time, signal, Wi-Fi and
 * battery sit beside the island, as on iPhone. The island already shows most of those states itself.
 *
 * It needs the same one-time ADB grant as hiding the phone's pop-ups (WRITE_SECURE_SETTINGS).
 * The user's own list is kept: ours is added to it, and their exact value is put back when the
 * option is turned off or the island stops. Notification icons and Android 16's live-update chips
 * are not system icons and can't be hidden this way.
 */
object StatusBarControl {
    private const val KEY = "icon_blacklist"

    /** System icon slots hidden for the clean look. Time, signal, Wi-Fi and battery stay. */
    val CLUTTER = listOf(
        "alarm_clock", "bluetooth", "volume", "zen", "location", "nfc", "hotspot", "cast", "rotate",
        "headset", "data_saver", "vpn", "speakerphone", "managed_profile", "sensors_off", "ethernet",
        "tty", "connected_display",
    )

    private fun prefs(context: Context) = context.getSharedPreferences("status_bar", Context.MODE_PRIVATE)

    fun canControl(context: Context) = HeadsUpControl.canControl(context)

    /** True while Dynamic Droid has the extra icons hidden. */
    fun cleaning(context: Context) = prefs(context).getBoolean("managed", false)

    /** Pure rule: the user's list plus ours, without duplicates, in a stable order. */
    fun merged(existing: String?): String {
        val mine = existing.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        return (mine + CLUTTER.filter { it !in mine }).joinToString(",")
    }

    fun sync(context: Context, islandUp: Boolean) {
        val wanted = IslandFeatures.read(context).cleanStatusBar && islandUp
        apply(context, wanted)
    }

    fun restore(context: Context) = apply(context, false)

    private fun apply(context: Context, clean: Boolean) {
        val p = prefs(context)
        if (clean == p.getBoolean("managed", false) || !canControl(context)) return
        val resolver = context.contentResolver
        runCatching {
            if (clean) {
                val previous = Settings.Secure.getString(resolver, KEY)
                p.edit().putString("previous", previous).putBoolean("had_previous", previous != null)
                    .putBoolean("managed", true).commit()
                Settings.Secure.putString(resolver, KEY, merged(previous))
            } else {
                val previous = if (p.getBoolean("had_previous", false)) p.getString("previous", null) else null
                Settings.Secure.putString(resolver, KEY, previous)
                p.edit().putBoolean("managed", false).commit()
            }
        }
    }
}
