package com.dynamicdroid.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * Hides the phone's own pop-up (heads-up) notifications while the island is doing their job.
 *
 * Android has one system-wide switch for pop-ups. Changing it needs WRITE_SECURE_SETTINGS, which
 * only a one-time ADB grant can give. Pop-ups are hidden only while the island window is up and
 * notification access is connected; if either goes away they come straight back, so nothing is
 * ever silently lost. Notifications still arrive in the shade as usual. Whatever value the user had
 * before is remembered and put back when the option is turned off.
 */
object HeadsUpControl {
    private const val KEY = "heads_up_notifications_enabled"
    const val GRANT_COMMAND = "adb shell pm grant com.dynamicdroid.app android.permission.WRITE_SECURE_SETTINGS"

    private fun prefs(context: Context) = context.getSharedPreferences("heads_up", Context.MODE_PRIVATE)

    fun canControl(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** True while Dynamic Droid has pop-ups switched off. */
    fun hiding(context: Context): Boolean = prefs(context).getBoolean("managed", false)

    /** Pure rule: hide only when wanted and the island can actually show notifications instead. */
    fun shouldHide(wanted: Boolean, islandUp: Boolean, listenerUp: Boolean) = wanted && islandUp && listenerUp

    fun sync(context: Context, islandUp: Boolean) {
        val wanted = IslandFeatures.read(context).hidePhonePopups
        // Quiet hours hide the island on purpose; the phone's pop-ups take over again meanwhile.
        val showing = islandUp && !IslandCustomization.quietNow(context)
        apply(context, shouldHide(wanted, showing, IslandNotificationListener.instance != null))
    }

    /** Puts the user's own pop-up setting back, if Dynamic Droid had changed it. */
    fun restore(context: Context) = apply(context, false)

    private fun apply(context: Context, hide: Boolean) {
        val p = prefs(context)
        val managed = p.getBoolean("managed", false)
        if (hide == managed || !canControl(context)) return
        val resolver = context.contentResolver
        runCatching {
            if (hide) {
                val previous = Settings.Global.getInt(resolver, KEY, 1)
                if (previous == 0) return // Pop-ups are already off; leave the user's choice alone.
                p.edit().putInt("previous", previous).putBoolean("managed", true).commit()
                Settings.Global.putInt(resolver, KEY, 0)
            } else {
                Settings.Global.putInt(resolver, KEY, p.getInt("previous", 1))
                p.edit().putBoolean("managed", false).commit()
            }
        }
    }
}
