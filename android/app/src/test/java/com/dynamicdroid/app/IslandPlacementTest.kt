package com.dynamicdroid.app

import org.junit.Assert.*
import org.junit.Test

class IslandPlacementTest {
    @Test fun standardOverlayAlwaysClearsStatusBarEvenWithCameraPreference() {
        for (mode in IslandPlacement.values()) {
            assertTrue(IslandPlacementPolicy.belowStatusBar(mode, false, false, false))
        }
        assertEquals(54f, IslandPlacementPolicy.top(8f, 48f, true, -24f, 6f), 0f)
        assertEquals(74f, IslandPlacementPolicy.top(8f, 48f, true, 20f, 6f), 0f)
    }
    @Test fun automaticHandlesCenteredAndCornerCamerasWideNotchesAndRotation() {
        assertFalse(IslandPlacementPolicy.belowStatusBar(IslandPlacement.AUTO, true, false, false))
        assertTrue(IslandPlacementPolicy.belowStatusBar(IslandPlacement.AUTO, true, true, false))
        assertTrue(IslandPlacementPolicy.belowStatusBar(IslandPlacement.AUTO, true, false, true))
        assertTrue(IslandPlacementPolicy.belowStatusBar(IslandPlacement.BELOW_STATUS_BAR, true, false, false))
        assertEquals(12f, IslandPlacementPolicy.top(12f, 48f, false, 0f, 6f), 0f)
        assertEquals(0f, IslandPlacementPolicy.top(4f, 48f, false, -24f, 6f), 0f)
    }
}
