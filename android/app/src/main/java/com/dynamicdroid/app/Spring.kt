package com.dynamicdroid.app

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/** Small critically-tunable spring, stepped by IslandView every frame (iOS-style motion). */
class Spring(
    initial: Float,
    var stiffness: Float,
    var dampingRatio: Float,
    private val threshold: Float,
) {
    companion object {
        /** A spring described the way Apple does: perceptual duration (seconds) and bounce. */
        fun apple(initial: Float, duration: Float, bounce: Float, threshold: Float) =
            Spring(initial, 0f, 1f, threshold).also { it.tune(duration, bounce) }
    }

    var value = initial
        private set
    var target = initial
    private var velocity = 0f
    /** Current speed, units per second (for squash, stretch and liquid edges). */
    val speed: Float get() = velocity

    val isAtRest: Boolean
        get() = abs(target - value) < threshold && abs(velocity) < threshold * 10f

    /**
     * Apple's spring model (WWDC23 "Animate with springs"): a perceptual duration and a bounce
     * (0 = no overshoot, 0.3 = lively) instead of stiffness and damping. Velocity is kept, so a
     * retuned spring carries on from wherever it is mid-flight.
     */
    fun tune(duration: Float, bounce: Float) {
        val omega = (2.0 * Math.PI / duration.coerceAtLeast(0.05f)).toFloat()
        stiffness = omega * omega
        dampingRatio = (1f - bounce).coerceIn(0.05f, 1f)
    }

    fun snap(v: Float) {
        value = v
        target = v
        velocity = 0f
    }

    fun step(dtSeconds: Float) {
        if (isAtRest) {
            value = target
            velocity = 0f
            return
        }
        var remaining = dtSeconds.coerceIn(0f, 0.064f)
        val damping = 2f * dampingRatio * sqrt(stiffness)
        while (remaining > 0f) {
            val h = min(remaining, 1f / 240f)
            val accel = -stiffness * (value - target) - damping * velocity
            velocity += accel * h
            value += velocity * h
            remaining -= h
        }
        if (isAtRest) {
            value = target
            velocity = 0f
        }
    }
}
