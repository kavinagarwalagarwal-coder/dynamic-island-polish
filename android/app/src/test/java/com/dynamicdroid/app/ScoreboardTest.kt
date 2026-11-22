package com.dynamicdroid.app

import org.junit.Assert.*
import org.junit.Test

class ScoreboardTest {
    @Test fun teamsKeepTheirColourAndCode() {
        assertEquals(Scoreboard.colorFor("IND"), Scoreboard.colorFor("IND-W"))
        assertEquals(Scoreboard.colorFor("XYZ"), Scoreboard.colorFor("XYZ"))
        assertEquals("HH", Scoreboard.Team("HH-W", "").code)
        assertEquals("KNGH", Scoreboard.Team("KNGHT", "").code)
        assertEquals(0xFF050CEB.toInt(), Scoreboard.Team("IND", "1/0", color = 0xFF050CEB.toInt()).tint)
        assertNull(Scoreboard.parseColor("")); assertNull(Scoreboard.parseColor("#12"))
        assertTrue(Scoreboard.brightness(Scoreboard.colorFor("AUS")) > .62f)
        assertTrue(Scoreboard.brightness(Scoreboard.colorFor("IND")) < .62f)
    }

    @Test fun liveBadgeOnlyWhilePlayIsOn() {
        val t = Scoreboard.Team("IND", "100/1")
        assertTrue(Scoreboard(t, t, "Live", "").inPlay)
        assertFalse(Scoreboard(t, t, "Stumps", "").inPlay)
        assertFalse(Scoreboard(t, t, "Result", "", live = false).inPlay)
    }
}
