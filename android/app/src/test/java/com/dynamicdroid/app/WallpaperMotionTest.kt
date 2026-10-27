package com.dynamicdroid.app

import org.junit.Assert.*
import org.junit.Test

class WallpaperMotionTest {
    @Test fun badSettingsCannotProduceInvalidMatricesOrUnboundedCycles() {
        val safe = WallpaperMotion(x = Float.NaN, y = Float.POSITIVE_INFINITY,
            radius = -5f, strength = 4f, periodSeconds = Float.NaN).safe()
        assertEquals(.5f, safe.x, 0f)
        assertEquals(.5f, safe.y, 0f)
        assertEquals(.05f, safe.radius, 0f)
        assertEquals(.65f, safe.strength, 0f)
        assertEquals(16f, safe.periodSeconds, 0f)
    }
    @Test fun rotationLoopsWithoutASeamAndUsesConfiguredSpeed() {
        val motion = WallpaperMotion(periodSeconds = 16f)
        assertEquals(90f, motion.angle(4000), .001f)
        assertEquals(0f, motion.angle(16000), .001f)
        assertEquals(motion.angle(1234), motion.angle(16000 * 1000L + 1234), .001f)
        assertEquals(0f, motion.angle(-1), 0f)
    }
}
