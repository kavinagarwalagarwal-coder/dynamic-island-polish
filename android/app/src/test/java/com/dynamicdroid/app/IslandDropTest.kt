package com.dynamicdroid.app

import org.junit.Assert.*
import org.junit.Test

class IslandDropTest {
    @Test fun dropletBeginsInsideBodyThenPinchesOffAndSettlesRound() {
        val g = IslandDropGeometry()
        g.update(0f, 254.88f, 296f, 26f, 36f, 360f, 4f)
        assertTrue(g.centerX + g.radiusX <= 270f)
        assertEquals(0f, g.content, 0f)
        g.update(.7f, 254.88f, 296f, 26f, 36f, 360f, 4f)
        assertTrue(g.centerY > 26f)
        assertTrue(g.radiusX > g.radiusY)
        assertTrue(g.neck > 0f)
        g.update(1f, 254.88f, 296f, 26f, 36f, 360f, 4f)
        assertEquals(296f, g.centerX, .001f)
        assertEquals(26f, g.centerY, .001f)
        assertEquals(g.radiusX, g.radiusY, .001f)
        assertEquals(0f, g.neck, 0f)
        assertEquals(1f, g.content, 0f)
    }
    @Test fun interruptedDropKeepsPositionAndSettlesAtTheNewTarget() {
        val spring = Spring(0f, IslandMotion.DROP_K, IslandMotion.DROP_DAMPING_RATIO, .003f)
        spring.target = 1f
        repeat(9) { spring.step(1f / 120f) }
        val position = spring.value
        spring.target = 0f
        assertEquals(position, spring.value, 0f)
        repeat(4) { spring.step(1f / 120f) }
        spring.target = 1f
        repeat(360) { spring.step(1f / 120f) }
        assertTrue(spring.isAtRest)
        assertEquals(1f, spring.value, .003f)
    }
    @Test fun overshootAndReversalStayInsideNarrowScreens() {
        val g = IslandDropGeometry()
        for (width in listOf(180f, 240f, 360f)) for (i in 0..108) {
            g.update(i / 100f, width - 70, width - 22, 26f, 36f, width, 4f)
            assertTrue(g.centerX - g.radiusX >= 3.99f)
            assertTrue(g.centerX + g.radiusX <= width - 3.99f)
        }
    }
}
