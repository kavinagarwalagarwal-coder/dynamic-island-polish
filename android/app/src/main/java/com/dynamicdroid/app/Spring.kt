package com.dynamicdroid.app

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
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

    /** Flick the spring without moving its target (used for liquid settle wobble). */
    fun fling(v: Float) { velocity += v }

    fun step(dtSeconds: Float) {
        if (isAtRest) {
            value = target
            velocity = 0f
            return
        }
        if (!dtSeconds.isFinite() || dtSeconds <= 0f) return
        // Solve the spring exactly: the same elapsed time produces the same trajectory at
        // 30, 60, 90 and 120 Hz, without Euler's refresh-dependent damping or substep churn.
        // Bound a stalled frame so resuming the app cannot teleport the island to its target.
        val dt = dtSeconds.coerceAtMost(0.064f).toDouble()
        val omega = sqrt(stiffness.coerceAtLeast(0f).toDouble())
        val zeta = dampingRatio.coerceAtLeast(0f).toDouble()
        val x = (value - target).toDouble()
        val v = velocity.toDouble()
        if (omega == 0.0) {
            value += (v * dt).toFloat()
        } else if (abs(zeta - 1.0) < 0.0001) {
            val b = v + omega * x
            val decay = exp(-omega * dt)
            value = target + ((x + b * dt) * decay).toFloat()
            velocity = ((v - omega * b * dt) * decay).toFloat()
        } else if (zeta < 1.0) {
            val decayRate = zeta * omega
            val frequency = omega * sqrt(1.0 - zeta * zeta)
            val c = cos(frequency * dt)
            val s = sin(frequency * dt)
            val decay = exp(-decayRate * dt)
            value = target + (decay * (x * c + (v + decayRate * x) / frequency * s)).toFloat()
            velocity = (decay * (v * c - (decayRate * v + omega * omega * x) / frequency * s)).toFloat()
        } else {
            val root = sqrt(zeta * zeta - 1.0)
            val r1 = -omega / (zeta + root)
            val r2 = -omega * (zeta + root)
            val a = (v - r2 * x) / (r1 - r2)
            val b = x - a
            val e1 = exp(r1 * dt)
            val e2 = exp(r2 * dt)
            value = target + (a * e1 + b * e2).toFloat()
            velocity = (a * r1 * e1 + b * r2 * e2).toFloat()
        }
        if (isAtRest) {
            value = target
            velocity = 0f
        }
    }
}
