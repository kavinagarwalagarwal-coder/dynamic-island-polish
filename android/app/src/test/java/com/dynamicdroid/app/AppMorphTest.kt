package com.dynamicdroid.app

import org.junit.Assert.*
import org.junit.Test

class AppMorphTest {
    private val icon = IconSpot(280f, 630f, 60f, 60f)

    @Test fun flightLiftsOffFromTheAppIconAndLandsExactlyOnTheIsland() {
        val g = AppMorphGeometry()
        g.update(0f, 360f, 800f, 180f, 8f, 232f, 36f, 1f, icon)
        // Starts exactly where Android's close animation left the app: on its icon.
        assertEquals(250f, g.left, .01f); assertEquals(600f, g.top, .01f)
        assertEquals(60f, g.width, .01f); assertEquals(60f, g.height, .01f)
        assertEquals(1f, g.face, 0f); assertEquals(0f, g.reveal, 0f)
        g.update(1f, 360f, 800f, 180f, 8f, 232f, 36f, 1f, icon)
        assertEquals(232f, g.width, .001f); assertEquals(36f, g.height, .001f)
        assertEquals(64f, g.left, .001f); assertEquals(8f, g.top, .001f); assertEquals(18f, g.radius, .001f)
        assertEquals(1f, g.reveal, 1e-4f); assertEquals(0f, g.face, 1e-4f)
    }

    @Test fun withoutAnIconItStartsIconSizedWhereTheHomeGestureEnds() {
        val g = AppMorphGeometry()
        g.update(0f, 360f, 800f, 180f, 8f, 232f, 36f, 2f, null)
        assertEquals(120f, g.width, .01f)
        assertEquals(180f, g.left + g.width / 2f, .01f)
        assertEquals(640f, g.top + g.height / 2f, .01f)
    }

    @Test fun flightOnlyRisesStaysOnScreenAndHasNoJumps() {
        val g = AppMorphGeometry()
        fun state(p: Float): FloatArray {
            g.update(p, 360f, 800f, 180f, 8f, 232f, 36f, 1f, icon)
            return floatArrayOf(g.left, g.top, g.width, g.height, g.radius, g.top + g.height / 2f)
        }
        var prev = state(0f)
        for (i in 1..1000) {
            val next = state(i / 1000f)
            for (k in 0..4) assertTrue("jump in $k at ${i / 1000f}", kotlin.math.abs(next[k] - prev[k]) < 3f)
            assertTrue("sank at ${i / 1000f}", next[5] <= prev[5] + .001f)
            assertTrue(next[0] >= 0f && next[0] + next[2] <= 360.001f && next[1] >= 0f && next[1] + next[3] <= 800.001f)
            assertTrue(next[4] <= kotlin.math.min(next[2], next[3]) / 2f + .001f)
            prev = next
        }
    }

    @Test fun pathArcsAndTheIconSwellsAsItLiftsOff() {
        val g = AppMorphGeometry()
        g.update(.3f, 360f, 800f, 180f, 8f, 232f, 36f, 1f, icon)
        val dx = (280f - (g.left + g.width / 2f)) / (280f - 180f)
        val dy = (630f - (g.top + g.height / 2f)) / (630f - 26f)
        // Sideways settles sooner than upwards: a curve, not a straight line.
        assertTrue("dx=$dx dy=$dy", dx > dy + .1f)
        g.update(.1f, 360f, 800f, 180f, 8f, 232f, 36f, 1f, icon)
        assertTrue("no swell: ${g.width}", g.width > 60f && g.height > 60f * .98f)
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
