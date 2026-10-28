package com.dynamicdroid.app

import org.junit.Assert.*
import org.junit.Test

class AppMorphTest {
    @Test fun cardPinchesTravelsAndWidensWithoutLeavingTheScreen() {
        val g = AppMorphGeometry()
        fun step(p: Float) = g.update(p, 360f, 800f, 180f, 8f, 232f, 36f, 1f)
        step(0f); val original = g.width
        step(.45f)
        assertTrue(g.width < original / 4)
        assertTrue(g.height > g.width * 3)
        val lowTop = g.top
        step(.8f); assertTrue(g.top < lowTop)
        step(.85f); assertTrue(g.width < 20f)
        step(1f)
        assertEquals(232f, g.width, .001f); assertEquals(36f, g.height, .001f)
        assertEquals(8f, g.top, .001f); assertEquals(1f, g.reveal, 0f)
        for (i in 0..100) {
            step(i / 100f)
            assertTrue(g.left >= 0f); assertTrue(g.left + g.width <= 360.001f)
            assertTrue(g.top >= 0f); assertTrue(g.top + g.height <= 800f)
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
