package com.dynamicdroid.app

import org.junit.Assert.*
import org.junit.Test

class AppMorphTest {
    @Test fun cardShrinksRisesAndLandsExactlyOnTheIslandWithoutLeavingTheScreen() {
        val g = AppMorphGeometry()
        fun step(p: Float) = g.update(p, 360f, 800f, 180f, 8f, 232f, 36f, 1f, 40f)
        step(0f)
        assertEquals(360f * AppMorphGeometry.START_SCALE, g.width, .01f)
        assertEquals(800f * AppMorphGeometry.START_SCALE, g.height, .01f)
        assertEquals(1f, g.face, 0f); assertEquals(0f, g.reveal, 0f)
        var lastW = g.width; var lastH = g.height; var lastCy = g.top + g.height / 2f
        for (i in 1..200) {
            step(i / 200f)
            // One continuous move: never grows back, never sinks, always on screen.
            assertTrue(g.width <= lastW + .001f); assertTrue(g.height <= lastH + .001f)
            val cy = g.top + g.height / 2f
            assertTrue(cy <= lastCy + .001f)
            assertTrue(g.left >= 0f); assertTrue(g.left + g.width <= 360.001f)
            assertTrue(g.top >= 0f); assertTrue(g.top + g.height <= 800f)
            assertTrue(g.radius <= kotlin.math.min(g.width, g.height) / 2f + .001f)
            lastW = g.width; lastH = g.height; lastCy = cy
        }
        step(1f)
        assertEquals(232f, g.width, .001f); assertEquals(36f, g.height, .001f)
        assertEquals(8f, g.top, .001f); assertEquals(18f, g.radius, .001f)
        assertEquals(1f, g.reveal, 1e-4f); assertEquals(0f, g.face, 1e-4f)
    }

    @Test fun cardTurnsIntoAPillOnTheWayInsteadOfATinyRectangle() {
        val g = AppMorphGeometry()
        g.update(.7f, 360f, 800f, 180f, 8f, 232f, 36f, 1f, 40f)
        // Height gives way faster than width: well before landing it is already wider than tall.
        assertTrue(g.width > g.height * 1.5f)
        // And the art has mostly dissolved into the black body by then.
        assertTrue(g.face < .35f)
    }

    @Test fun flightIsSmoothWithNoJumpsBetweenNeighbouringFrames() {
        val g = AppMorphGeometry()
        fun state(p: Float): FloatArray {
            g.update(p, 360f, 800f, 180f, 8f, 232f, 36f, 1f, 40f)
            return floatArrayOf(g.left, g.top, g.width, g.height, g.radius)
        }
        var prev = state(0f)
        for (i in 1..1000) {
            val next = state(i / 1000f)
            for (k in prev.indices) assertTrue("jump in $k at ${i / 1000f}", kotlin.math.abs(next[k] - prev[k]) < 3f)
            prev = next
        }
    }

    @Test fun leavingAHiddenOwnerForHomeCanAnimateButOtherSwitchesCannot() {
        val shelf = ActivityShelf<String>({ it }, { 1 })
        shelf.put("music.app")
        assertNull(shelf.pair { it != "music.app" }.first)
        assertTrue(AppReturnPolicy.shouldMorph(shelf.pair().first, "music.app", "home", true, true, true))
        assertFalse(AppReturnPolicy.shouldMorph("music.app", "music.app", "settings", false, true, true))
        assertFalse(AppReturnPolicy.shouldMorph("music.app", "home", "home", true, true, true))
        assertFalse(AppReturnPolicy.shouldMorph("music.app", "music.app", "home", true, false, true))
        assertFalse(AppReturnPolicy.shouldMorph("music.app", "music.app", "home", true, true, false))
    }
}
