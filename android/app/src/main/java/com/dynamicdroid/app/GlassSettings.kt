package com.dynamicdroid.app

import android.content.Context

/** Optical treatment is independent of accent colours. Capture is explicitly opt-in. */
data class GlassSettings(val enabled: Boolean = false, val wallpaperTint: Boolean = false) {
    companion object {
        fun read(context: Context): GlassSettings {
            val p = context.getSharedPreferences("island_glass", Context.MODE_PRIVATE)
            return GlassSettings(p.getBoolean("enabled", false), p.getBoolean("tint", false))
        }
    }
    fun save(context: Context) {
        context.getSharedPreferences("island_glass", Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", enabled).putBoolean("tint", wallpaperTint).apply()
    }
}
