package com.dynamicdroid.app

/** Still-image backdrop only; existing settings migrate from the 1.3.0 preference file. */
data class WallpaperStyle(
    val enabled: Boolean = false,
    val glass: Boolean = true,
    val x: Float = .5f,
    val y: Float = .5f,
    val strength: Float = .45f,
) {
    fun safe(): WallpaperStyle {
        fun bounded(value: Float, fallback: Float, low: Float, high: Float) =
            if (value.isFinite()) value.coerceIn(low, high) else fallback
        return copy(x = bounded(x, .5f, 0f, 1f), y = bounded(y, .5f, 0f, 1f),
            strength = bounded(strength, .45f, .15f, .65f))
    }
}
