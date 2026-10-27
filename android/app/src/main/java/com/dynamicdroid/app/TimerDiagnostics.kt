package com.dynamicdroid.app

import android.content.Context

/** Only notification format metadata; never persists notification titles, bodies, or images. */
object TimerDiagnostics {
    fun record(context: Context, packageName: String, source: String, recognized: Boolean, custom: Boolean) {
        val value = "$packageName · $source · ${if (recognized) "timer detected" else "format not recognised"} · custom layout: $custom"
        val prefs = context.getSharedPreferences("clock_signal", Context.MODE_PRIVATE)
        if (prefs.getString("last", null) != value) prefs.edit().putString("last", value).apply()
    }
    fun read(context: Context): String = context.getSharedPreferences("clock_signal", Context.MODE_PRIVATE)
        .getString("last", null) ?: "No Clock notification received yet. Start a timer after enabling notification access."
}
