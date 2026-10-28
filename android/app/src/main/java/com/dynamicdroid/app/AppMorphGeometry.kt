package com.dynamicdroid.app

import kotlin.math.max
import kotlin.math.min

/** Reference sequence: portrait card pinches, travels upward, then opens into the pill. */
class AppMorphGeometry {
    var left = 0f; private set
    var top = 0f; private set
    var width = 0f; private set
    var height = 0f; private set
    var radius = 0f; private set
    var reveal = 0f; private set

    fun update(progress: Float, screenWidth: Float, screenHeight: Float, targetX: Float,
               targetTop: Float, targetWidth: Float, targetHeight: Float, density: Float) {
        val p = progress.coerceIn(0f, 1f)
        val needleWidth = min(14f * density, targetHeight * .5f)
        val needleHeight = targetHeight * .65f
        var cx = screenWidth / 2f
        val flight = smooth((p / .85f).coerceIn(0f, 1f))
        val startY = screenHeight * .47f
        val endY = targetTop + targetHeight / 2f
        val u = 1f - flight
        val cy = u*u*u*startY + 3f*u*u*flight*(screenHeight * .64f) +
            3f*u*flight*flight*(endY + screenHeight * .10f) + flight*flight*flight*endY
        when {
            p < .45f -> {
                val t = smooth(p / .45f)
                width = mix(screenWidth * .88f, screenWidth * .14f, t)
                height = mix(screenHeight * .84f, screenHeight * .28f, t)
                radius = mix(36f * density, 12f * density, t)
            }
            p < .85f -> {
                val t = (p - .45f) / .40f
                width = mix(screenWidth * .14f, needleWidth, smooth(t))
                height = mix(screenHeight * .28f, needleHeight, smooth(t))
                cx = mix(cx, targetX, smooth(t))
                radius = mix(12f * density, needleWidth / 2f, smooth(t))
            }
            else -> {
                val t = (p - .85f) / .15f
                val open = smooth(t)
                val settle = kotlin.math.sin(t * Math.PI).toFloat() * t * (1f - t) * .10f
                width = mix(needleWidth, targetWidth, open) + targetWidth * settle
                height = mix(needleHeight, targetHeight, open)
                cx = targetX
                radius = mix(needleWidth / 2f, targetHeight / 2f, open)
            }
        }
        val low = width / 2f
        cx = cx.coerceIn(low, max(low, screenWidth - width / 2f))
        left = cx - width / 2f; top = max(0f, cy - height / 2f)
        radius = radius.coerceIn(0f, min(width, height) / 2f)
        reveal = smooth(((p - .90f) / .10f).coerceIn(0f, 1f))
    }
    private fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t
    private fun smooth(t: Float) = t.coerceIn(0f, 1f).let { it * it * it * (it * (it * 6f - 15f) + 10f) }
}

object AppReturnPolicy {
    fun shouldMorph(sourcePackage: String?, previous: String?, destination: String,
                    isHome: Boolean, wasHidden: Boolean, unlocked: Boolean): Boolean =
        sourcePackage != null && sourcePackage == previous && previous != destination && isHome && wasHidden && unlocked
}
