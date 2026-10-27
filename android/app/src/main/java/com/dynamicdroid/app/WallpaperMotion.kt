package com.dynamicdroid.app

/** User-selected motion, in source-image coordinates; no object recognition or uploads. */
data class WallpaperMotion(
    val enabled: Boolean = false,
    val automatic: Boolean = true,
    val autoEffect: String = "drift",
    val autoLabel: String = "Choose an image to recognise its subject",
    val autoConfidence: Float = 0f,
    val glass: Boolean = true,
    val spin: Boolean = true,
    val x: Float = 0.5f,
    val y: Float = 0.5f,
    val radius: Float = 0.3f,
    val strength: Float = 0.45f,
    val periodSeconds: Float = 16f,
) {
    val effect: String get() = if (automatic) autoEffect else if (spin) "spin" else "drift"
    fun safe(): WallpaperMotion {
        fun bounded(value: Float, fallback: Float, low: Float, high: Float) =
            if (value.isFinite()) value.coerceIn(low, high) else fallback
        return copy(autoEffect = autoEffect.takeIf { it in setOf("spin", "sway", "breathe", "glide", "drift") } ?: "drift",
            autoConfidence = bounded(autoConfidence, 0f, 0f, 1f), x = bounded(x, 0.5f, 0f, 1f), y = bounded(y, 0.5f, 0f, 1f),
            radius = bounded(radius, 0.3f, 0.05f, 0.5f), strength = bounded(strength, 0.45f, 0.15f, 0.65f),
            periodSeconds = bounded(periodSeconds, 16f, 8f, 30f))
    }

    fun angle(elapsedMs: Long): Float = ((elapsedMs.coerceAtLeast(0) % (safe().periodSeconds * 1000).toLong()) /
        (safe().periodSeconds * 1000) * 360f)
}
