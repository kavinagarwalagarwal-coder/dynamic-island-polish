package com.dynamicdroid.app

import org.junit.Assert.*
import org.junit.Test

class IslandTouchPolicyTest {
    @Test fun restingPillKeepsTakingTapsAfterOtherIslandsHaveComeAndGone() {
        // Idle with the pill and home card on: always tappable, however it got back to idle.
        assertTrue(IslandTouchPolicy.takesTouches(false, idlePill = true, homeCard = true, concealed = false, flying = false, passing = false))
    }

    @Test fun idleWithoutAPillToTapLetsTouchesThrough() {
        assertFalse(IslandTouchPolicy.takesTouches(false, idlePill = false, homeCard = true, concealed = false, flying = false, passing = false))
        assertFalse(IslandTouchPolicy.takesTouches(false, idlePill = true, homeCard = false, concealed = false, flying = false, passing = false))
    }

    @Test fun hiddenFlyingOrPassingIslandsNeverTakeTouches() {
        assertTrue(IslandTouchPolicy.takesTouches(true, idlePill = false, homeCard = false, concealed = false, flying = false, passing = false))
        assertFalse(IslandTouchPolicy.takesTouches(true, idlePill = true, homeCard = true, concealed = true, flying = false, passing = false))
        assertFalse(IslandTouchPolicy.takesTouches(true, idlePill = true, homeCard = true, concealed = false, flying = true, passing = false))
        assertFalse(IslandTouchPolicy.takesTouches(false, idlePill = true, homeCard = true, concealed = false, flying = false, passing = true))
    }
}
