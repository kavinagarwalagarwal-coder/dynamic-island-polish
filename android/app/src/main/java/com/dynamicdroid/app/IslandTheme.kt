package com.dynamicdroid.app

/**
 * Wallpaper-derived colours for the island. Pure data (no Android imports) so the
 * regression checks can exercise it on the JVM.
 */
data class IslandTheme(
    val enabled: Boolean = false,
    val accent: Int = 0xFF63DCF4.toInt(),
    val secondary: Int = 0xFF228AA3.toInt(),
    /** Deep-water glass body, overridden by wallpaper sampling when theme matching is enabled. */
    val base: Int = 0xFF05111A.toInt(),
) {
    /** Colours must be opaque; a translucent accent would wash out on the dark pill. */
    fun safe() = copy(
        accent = accent or 0xFF000000.toInt(),
        secondary = secondary or 0xFF000000.toInt(),
        base = base or 0xFF000000.toInt(),
    )
}
