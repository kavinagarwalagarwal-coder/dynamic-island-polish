package com.dynamicdroid.app

import kotlin.math.sqrt

/**
 * Spring numbers shared with Sources/IslandUI/IslandMotion.swift.
 *
 * SwiftUI uses interpolatingSpring(stiffness, damping). The Canvas stepper uses
 * dampingRatio = damping / (2 * sqrt(stiffness)).
 */
object IslandMotion {
    const val DROP_K = 170f
    const val DROP_DAMPING_RATIO = 0.64f
    const val SHAPE_K = 205f
    const val WIDTH_DAMPING_RATIO = 0.83f
    const val HEIGHT_DAMPING_RATIO = 0.88f
    const val CENTER_DAMPING_RATIO = 0.90f
    const val RADIUS_DAMPING_RATIO = 0.93f
    // Fluid tuning: a touch less damping so the shape flows past its mark and settles back like
    // a liquid, while staying well clear of a bounce.
    const val FLUID_WIDTH_DAMPING = 0.74f
    const val FLUID_HEIGHT_DAMPING = 0.79f
    const val FLUID_CENTER_DAMPING = 0.84f
    const val FLUID_RADIUS_DAMPING = 0.86f
    // Calm, liquid morph timing: width leads the opening bloom; height leads the closing fold.
    const val APPLE_DURATION = 0.56f
    const val APPLE_OPEN_BOUNCE = 0.14f
    const val APPLE_CLOSE_BOUNCE = 0.06f
    const val AXIS_LAG = 1.18f
    const val CONTENT_IN_K = 140f
    const val CONTENT_OUT_K = 380f
    const val IDLE_SIDE_MARGIN_DP = 2f
    const val IDLE_HEIGHT_DP = 14f
    const val SPLIT_WIDTH_DP = 180f
    const val COMPACT_HEIGHT_DP = 36f
    const val COMPACT_WIDTH_DP = 232f

    const val WATER_WOBBLE_K = 220f
    const val WATER_WOBBLE_DAMPING = 0.38f

    /** Speed-driven surface tension, not a second animation kicked after the island lands. */
    fun waterWobble(widthSpeed: Float, heightSpeed: Float, width: Float, height: Float, strength: Float): Float =
        ((heightSpeed / height.coerceAtLeast(1f) - widthSpeed / width.coerceAtLeast(1f)) * 0.008f * strength)
            .coerceIn(-0.045f, 0.045f)

    fun dampingRatio(stiffness: Float, damping: Float): Float =
        damping / (2f * sqrt(stiffness))

    fun idleWidth(screenWidth: Float, density: Float): Float {
        val margin = IDLE_SIDE_MARGIN_DP * density * 2f
        return (screenWidth - margin).coerceAtLeast(0f)
    }
}
