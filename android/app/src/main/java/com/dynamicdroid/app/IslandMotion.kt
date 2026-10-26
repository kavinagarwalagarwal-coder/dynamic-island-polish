package com.dynamicdroid.app

import kotlin.math.sqrt

/**
 * Spring numbers shared with Sources/IslandUI/IslandMotion.swift.
 *
 * SwiftUI uses interpolatingSpring(stiffness, damping). The Canvas stepper uses
 * dampingRatio = damping / (2 * sqrt(stiffness)).
 */
object IslandMotion {
    const val SHAPE_K = 180f
    const val WIDTH_DAMPING_RATIO = 0.58f
    const val HEIGHT_DAMPING_RATIO = 0.68f
    const val CENTER_DAMPING_RATIO = 0.72f
    const val RADIUS_DAMPING_RATIO = 0.90f
    const val CONTENT_IN_K = 140f
    const val CONTENT_OUT_K = 380f
    const val IDLE_SIDE_MARGIN_DP = 2f
    const val IDLE_HEIGHT_DP = 34f
    const val COMPACT_HEIGHT_DP = 36f
    const val COMPACT_WIDTH_DP = 232f

    fun dampingRatio(stiffness: Float, damping: Float): Float =
        damping / (2f * sqrt(stiffness))

    fun idleWidth(screenWidth: Float, density: Float): Float {
        val margin = IDLE_SIDE_MARGIN_DP * density * 2f
        return (screenWidth - margin).coerceAtLeast(0f)
    }
}
