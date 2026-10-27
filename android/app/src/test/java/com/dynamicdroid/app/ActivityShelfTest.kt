package com.dynamicdroid.app

import org.junit.Assert.*
import org.junit.Test

class ActivityShelfTest {
    private data class Item(val key: String, val rank: Int, val app: String = key, val revision: Int = 0)
    @Test fun refreshDoesNotStealFocusAndRemovalPromotesOtherActivity() {
        val shelf = ActivityShelf<Item>({ it.key }, { it.rank })
        shelf.put(Item("music", 1)); shelf.put(Item("timer", 2))
        repeat(50) { shelf.put(Item("music", 1, revision = it)) }
        assertEquals("timer", shelf.pair().first?.key)
        assertEquals(49, shelf.pair().second?.revision)
        shelf.select("music")
        shelf.put(Item("timer", 2, revision = 3))
        assertEquals("music", shelf.pair().first?.key)
        shelf.remove("music")
        assertEquals("timer", shelf.pair().first?.key)
        assertNull(shelf.pair().second)
        shelf.remove("timer")
        assertNull(shelf.pair().first)
    }
    @Test fun foregroundFilterAndUrgentArrival() {
        val shelf = ActivityShelf<Item>({ it.key }, { it.rank })
        shelf.put(Item("timer", 2)); shelf.put(Item("music", 1))
        assertEquals("music", shelf.pair { it.app != "timer" }.first?.key)
        shelf.put(Item("call", 3))
        assertEquals("call", shelf.pair().first?.key)
        assertEquals("timer", shelf.pair { it.app != "call" }.first?.key)
        shelf.select("missing")
        assertEquals("call", shelf.pair().first?.key)
    }
    @Test fun compactAndBubbleFitAtBothEdgesAndAllPhoneScales() {
        for (scale in listOf(0.7f, 1f, 1.3f)) for (physicalWidth in listOf(240f, 320f, 360f, 480f)) {
            val screen = physicalWidth / scale
            for (camera in listOf(-180f, screen / 2, screen + 180)) {
                val c = IslandCluster.layout(screen, 348f, camera, true, 36f, 8f, 4f)
                assertTrue(c.center - c.width / 2 >= 3.99f)
                assertTrue(c.bubbleCenter + 18 <= screen - 3.99f)
                assertTrue(c.bubbleCenter - 18 - (c.center + c.width / 2) >= 7.99f)
            }
        }
    }
    @Test fun completionFiresOnceAndRearmsOnlyForNewRun() {
        val completion = TimerCompletion()
        assertFalse(completion.observe("timer", 1, false, true))
        assertTrue(completion.observe("timer", 0, false, true))
        repeat(30) { assertFalse(completion.observe("timer", 0, false, true)) }
        assertFalse(completion.observe("timer", 10_000, true, true))
        assertFalse(completion.observe("timer", 0, true, true))
        assertTrue(completion.observe("timer", 0, false, true))
        assertFalse(completion.observe("stopwatch", 0, false, false))
        assertFalse(completion.observe("unknown", null, false, true))
        completion.remove("timer")
        assertTrue(completion.observe("timer", 0, false, true))
    }
}
