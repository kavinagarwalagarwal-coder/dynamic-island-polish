package com.dynamicdroid.app

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The app flying into the island when you go home from it.
 *
 * One continuous move, no stages: a card the size of the closing app (as Android's own close
 * animation has it, a little smaller than the screen) shrinks, rounds into a pill and rises into
 * the island. Height gives way faster than width, so the card turns into a pill on the way up
 * instead of arriving as a tiny rectangle. Everything is a pure function of [progress] (0 → 1),
 * so the motion is identical on every phone and frame rate.
 */
class AppMorphGeometry {
    var left = 0f; private set
    var top = 0f; private set
    var width = 0f; private set
    var height = 0f; private set
    var radius = 0f; private set
    /** How much of the island's own content shows (it fades in as the card lands). */
    var reveal = 0f; private set
    /** How much of the app's face (art or icon) still shows on the card. */
    var face = 1f; private set

    fun update(progress: Float, screenWidth: Float, screenHeight: Float, targetX: Float,
               targetTop: Float, targetWidth: Float, targetHeight: Float, density: Float,
               startCorner: Float = 0f) {
        val p = progress.coerceIn(0f, 1f)
        // Travel: eases in and out. Shrink: fast at first, landing softly, like a card let go.
        val travel = smoother(p)
        val shrinkW = 1f - (1f - p).pow(2.2f)
        val shrinkH = 1f - (1f - min(1f, p * 1.15f)).pow(2.6f)

        val startW = screenWidth * START_SCALE
        val startH = screenHeight * START_SCALE
        width = mix(startW, targetWidth, shrinkW)
        height = mix(startH, targetHeight, shrinkH)

        val cx = mix(screenWidth / 2f, targetX, travel)
        // A slight arc: the card lifts a touch faster than it centres, so it never sinks first.
        val startY = screenHeight * .52f
        val endY = targetTop + targetHeight / 2f
        val cy = mix(startY, endY, travel + (1f - travel) * travel * .18f)

        val corner = if (startCorner > 0f) startCorner * START_SCALE else 28f * density
        radius = mix(corner, targetHeight / 2f, smoother(min(1f, p * 1.25f)))
        radius = radius.coerceIn(0f, min(width, height) / 2f)

        val half = width / 2f
        val x = cx.coerceIn(half, max(half, screenWidth - half))
        left = x - half
        top = max(0f, cy - height / 2f)
        face = 1f - smoother(((p - .38f) / .40f).coerceIn(0f, 1f))
        reveal = smoother(((p - .80f) / .20f).coerceIn(0f, 1f))
    }

    private fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t
    private fun smoother(t: Float) = t.coerceIn(0f, 1f).let { it * it * it * (it * (it * 6f - 15f) + 10f) }

    companion object {
        /** Where Android's own close animation has the app when the island takes over. */
        const val START_SCALE = .78f
    }
}

object AppReturnPolicy {
    fun shouldMorph(sourcePackage: String?, previous: String?, destination: String,
                    isHome: Boolean, wasHidden: Boolean, unlocked: Boolean): Boolean =
        sourcePackage != null && sourcePackage == previous && previous != destination && isHome && wasHidden && unlocked
}
