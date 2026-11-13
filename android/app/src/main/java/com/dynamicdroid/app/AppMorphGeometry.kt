package com.dynamicdroid.app

import android.graphics.RectF
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * The app flying into the island when you go home from it.
 *
 * Android's own close animation shrinks the app into its icon on the home screen; the flight
 * picks up exactly there. The icon lifts off with a small swell, arcs up (sideways quickly,
 * upwards smoothly), reshapes into a pill on the way and lands as the island. Starting where
 * Android left the app is what makes it read as one motion instead of a second animation popping
 * in. Everything is a pure function of [progress] (0 → 1), identical on every phone and frame rate.
 */
class AppMorphGeometry {
    var left = 0f; private set
    var top = 0f; private set
    var width = 0f; private set
    var height = 0f; private set
    var radius = 0f; private set
    /** How much of the island's own content shows (it fades in as the card lands). */
    var reveal = 0f; private set
    /** How much of the app's face (its icon) still shows on the card. */
    var face = 1f; private set

    /**
     * @param origin the app's icon on screen, in the island's coordinates, or null when it isn't
     *   on the home screen (in a folder, say): the flight then starts icon-sized near the bottom,
     *   where the home gesture ends.
     */
    fun update(progress: Float, screenWidth: Float, screenHeight: Float, targetX: Float,
               targetTop: Float, targetWidth: Float, targetHeight: Float, density: Float,
               origin: RectF? = null) {
        val p = progress.coerceIn(0f, 1f)
        val size = ICON_DP * density
        val sx = origin?.centerX() ?: (screenWidth / 2f)
        val sy = origin?.centerY() ?: (screenHeight * .8f)
        val sw = origin?.width()?.takeIf { it > 1f } ?: size
        val sh = origin?.height()?.takeIf { it > 1f } ?: size
        val sr = min(sw, sh) * ICON_CORNER

        // Path: an arc. Sideways settles early, upwards eases in and out.
        val tx = 1f - (1f - p).pow(2.4f)
        val ty = smoother(p)
        val cx = mix(sx, targetX, tx)
        val cy = mix(sy, targetTop + targetHeight / 2f, ty)

        // Shape: lifts off with a gentle swell, then becomes the pill while it travels.
        val shapeT = smoother(((p - .12f) / .80f).coerceIn(0f, 1f))
        val swell = 1f + LIFT_SWELL * sin(PI.toFloat() * (p / .5f).coerceIn(0f, 1f))
        width = mix(sw, targetWidth, shapeT) * swell
        height = mix(sh, targetHeight, shapeT) * swell
        radius = mix(sr, targetHeight / 2f, shapeT).coerceIn(0f, min(width, height) / 2f)

        val half = width / 2f
        val x = cx.coerceIn(half, max(half, screenWidth - half))
        left = x - half
        top = (cy - height / 2f).coerceIn(0f, max(0f, screenHeight - height))
        face = 1f - smoother(((p - .38f) / .40f).coerceIn(0f, 1f))
        reveal = smoother(((p - .80f) / .20f).coerceIn(0f, 1f))
    }

    private fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t
    private fun smoother(t: Float) = t.coerceIn(0f, 1f).let { it * it * it * (it * (it * 6f - 15f) + 10f) }

    companion object {
        /** Icon size when the real icon can't be found. */
        const val ICON_DP = 60f
        /** Home-screen icons are rounded squares (or circles) about this round. */
        const val ICON_CORNER = .3f
        /** How much the icon swells as it lifts off. */
        const val LIFT_SWELL = .12f
    }
}

object AppReturnPolicy {
    fun shouldMorph(sourcePackage: String?, previous: String?, destination: String,
                    isHome: Boolean, wasHidden: Boolean, unlocked: Boolean): Boolean =
        sourcePackage != null && sourcePackage == previous && previous != destination && isHome && wasHidden && unlocked
}
