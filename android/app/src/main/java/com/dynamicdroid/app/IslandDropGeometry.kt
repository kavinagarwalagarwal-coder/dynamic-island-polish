package com.dynamicdroid.app

import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.pow

/** Reused geometry for an interruptible droplet, not an offscreen blur/filter. */
class IslandDropGeometry {
    var centerX = 0f; private set
    var centerY = 0f; private set
    var radiusX = 0f; private set
    var radiusY = 0f; private set
    var neck = 0f; private set
    var content = 0f; private set

    fun update(progress: Float, origin: Float, destination: Float, baseY: Float,
               diameter: Float, screenWidth: Float, margin: Float, press: Float = 1f) {
        val raw = progress.coerceIn(0f, 1.08f)
        val p = raw.coerceAtMost(1f)
        val wave = sin(raw * PI).toFloat()
        val size = diameter * .5f * (.78f + .22f * p) * press
        radiusX = size * (1f + .20f * wave)
        radiusY = size * (1f - .12f * wave)
        val travel = 1f - (1f - p).pow(1.5f) + (raw - p) * .6f
        val low = radiusX + margin
        centerX = (origin + (destination - origin) * travel).coerceIn(low, max(low, screenWidth - margin - radiusX))
        centerY = baseY + diameter / 6f * wave
        neck = if (p <= .02f) 0f else 1f - smooth(.55f, .96f, p)
        content = smooth(.40f, .94f, p)
    }

    private fun smooth(start: Float, end: Float, value: Float): Float {
        val t = ((value - start) / (end - start)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
