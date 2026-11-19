package com.dynamicdroid.app

import android.content.Context

/**
 * What the island made of the last score-app notification, so a missed live score can be
 * explained. Only format details are kept, never the notification's words.
 */
object LiveDiagnostics {
    fun record(context: Context, packageName: String, ongoing: Boolean, alertOnce: Boolean, quiet: Boolean,
               custom: Boolean, scoreFound: Boolean, shown: Boolean) {
        val value = listOf(packageName, if (ongoing) "ongoing" else "not ongoing",
            if (alertOnce || quiet) "silent updates" else "alerting", "custom layout: $custom",
            if (scoreFound) "score found" else "no score in its text",
            if (shown) "shown on the island" else "not shown").joinToString(" · ")
        val prefs = context.getSharedPreferences("live_signal", Context.MODE_PRIVATE)
        if (prefs.getString("last", null) != value) prefs.edit().putString("last", value).apply()
    }

    fun read(context: Context): String = context.getSharedPreferences("live_signal", Context.MODE_PRIVATE)
        .getString("last", null) ?: context.getString(R.string.live_none)
}
