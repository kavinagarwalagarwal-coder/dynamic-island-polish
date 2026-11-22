package com.dynamicdroid.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpringContinuityTest {
    @Test fun trajectoriesMatchAcrossDisplayRefreshRates() {
        for (damping in listOf(0.38f, 0.79f, 1f, 1.2f)) {
            fun sample(hz: Int): Spring {
                val s = Spring(36f, 205f, damping, 0.00001f)
                s.target = 138f
                repeat(hz / 3) { s.step(1f / hz) }
                return s
            }
            val reference = sample(120)
            for (hz in listOf(30, 60, 90)) {
                val actual = sample(hz)
                assertEquals("position at $hz Hz / damping $damping", reference.value, actual.value, 0.002f)
                assertEquals("velocity at $hz Hz / damping $damping", reference.speed, actual.speed, 0.02f)
            }
        }
    }

    @Test fun rapidReversalPreservesPositionAndMomentum() {
        val s = Spring(36f, 205f, 0.79f, 0.0001f)
        s.target = 138f
        repeat(8) { s.step(1f / 120f) }
        val position = s.value
        val velocity = s.speed
        assertTrue(velocity > 0f)
        s.target = 36f
        assertEquals(position, s.value, 0f)
        assertEquals(velocity, s.speed, 0f)
        repeat(360) { s.step(1f / 120f) }
        assertTrue(s.isAtRest)
        assertEquals(36f, s.value, 0.0001f)
    }

    @Test fun stalledAndInvalidFramesCannotTeleportOrCorruptTheSpring() {
        val a = Spring(0f, 205f, 0.79f, 0.0001f)
        val b = Spring(0f, 205f, 0.79f, 0.0001f)
        a.target = 1f; b.target = 1f
        a.step(4f); b.step(0.064f)
        assertEquals(b.value, a.value, 0f)
        assertTrue(a.value in 0f..0.5f)
        val before = a.value
        a.step(Float.NaN); a.step(Float.POSITIVE_INFINITY); a.step(-1f)
        assertEquals(before, a.value, 0f)
        assertTrue(a.speed.isFinite())
    }

    @Test fun liquidDeformationIsScaleIndependentAndStopsBeingDrivenAtRest() {
        val small = IslandMotion.waterWobble(240f, 80f, 232f, 36f, 1f)
        assertTrue(small > 0f)
        assertEquals(small, IslandMotion.waterWobble(720f, 240f, 696f, 108f, 1f), 0.00001f)
        assertEquals(0f, IslandMotion.waterWobble(240f, 80f, 232f, 36f, 0f), 0f)
        assertEquals(0f, IslandMotion.waterWobble(0f, 0f, 232f, 36f, 1f), 0f)
        assertEquals(0.045f, IslandMotion.waterWobble(0f, 10000f, 232f, 36f, 1f), 0f)
    }

    @Test fun liquidTouchImpulseSettlesWithoutALongJellyTail() {
        val s = Spring(0f, IslandMotion.WATER_WOBBLE_K, IslandMotion.WATER_WOBBLE_DAMPING, 0.0006f)
        s.fling(-0.18f)
        var peak = 0f
        repeat(120) { s.step(1f / 120f); peak = maxOf(peak, kotlin.math.abs(s.value)) }
        assertTrue(peak > 0.001f && peak < 0.015f)
        assertTrue(s.isAtRest)
    }
}