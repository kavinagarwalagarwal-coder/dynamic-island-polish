package com.dynamicdroid.app

import kotlin.math.max

/** Device-independent phone fit values, also used by the regression checks. */
data class IslandFit(
    val scale: Float = 1f,
    val pillWidth: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val alignCamera: Boolean = true,
    val placement: IslandPlacement = IslandPlacement.AUTO,
) {
    fun safe() = copy(
        scale = scale.coerceIn(0.7f, 1.3f),
        pillWidth = pillWidth.coerceIn(0.7f, 1.5f),
        offsetX = offsetX.coerceIn(-180f, 180f),
        offsetY = offsetY.coerceIn(-24f, 160f),
    )

    companion object {
        fun center(screenWidth: Float, islandWidth: Float, desired: Float, margin: Float): Float {
            val half = islandWidth / 2f + margin
            return desired.coerceIn(half.coerceAtMost(screenWidth / 2f), max(screenWidth / 2f, screenWidth - half))
        }
    }
}

/** Explicit fallback for OEM status bars, wide notches and ordinary overlay windows. */
enum class IslandPlacement { AUTO, CAMERA, BELOW_STATUS_BAR }

object IslandPlacementPolicy {
    fun belowStatusBar(mode: IslandPlacement, accessibilityHost: Boolean, wideCutout: Boolean,
                       landscape: Boolean): Boolean = when (mode) {
        IslandPlacement.BELOW_STATUS_BAR -> true
        IslandPlacement.CAMERA -> !accessibilityHost
        IslandPlacement.AUTO -> !accessibilityHost || wideCutout || landscape
    }

    fun top(cameraTop: Float, statusBottom: Float, below: Boolean, offset: Float,
            gap: Float): Float = if (below) kotlin.math.max(statusBottom + gap, statusBottom + gap + offset)
        else kotlin.math.max(0f, cameraTop + offset)
}
