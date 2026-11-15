package com.dynamicdroid.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Apple's duration-and-bounce springs (WWDC23 "Animate with springs"). */
class AppleSpringTest {
    @Test fun durationAndBounceMapToStiffnessAndDamping() {
        val s = Spring.apple(0f, 0.5f, 0.15f, 0.001f)
        assertEquals(157.91f, s.stiffness, 0.05f)
        assertEquals(0.85f, s.dampingRatio, 1e-4f)
    }

    @Test fun noBounceNeverOvershoots() {
        val s = Spring.apple(0f, 0.5f, 0f, 0.001f)
        s.target = 1f
        var peak = 0f
        repeat(120) { s.step(1f / 60f); peak = maxOf(peak, s.value) }
        assertTrue("peak $peak", peak <= 1.0001f)
        assertEquals(1f, s.value, 0.001f)
    }

    @Test fun bounceOvershootsOnceAndSettles() {
        val s = Spring.apple(0f, 0.5f, 0.2f, 0.001f)
        s.target = 1f
        var peak = 0f
        repeat(120) { s.step(1f / 60f); peak = maxOf(peak, s.value) }
        assertTrue("peak $peak", peak > 1.01f && peak < 1.12f)
        assertEquals(1f, s.value, 0.001f)
    }

    @Test fun retuningMidFlightKeepsMomentum() {
        val s = Spring.apple(0f, 0.5f, 0f, 0.001f)
        s.target = 1f
        repeat(6) { s.step(1f / 60f) }
        val before = s.speed
        s.tune(0.3f, 0.1f)
        assertEquals(before, s.speed, 0f)
    }
}
