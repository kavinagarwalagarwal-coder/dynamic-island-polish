package com.dynamicdroid.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusBarControlTest {
    @Test fun keepsTheUsersOwnHiddenIconsAndAddsOurs() {
        val merged = StatusBarControl.merged("rotate, airplane").split(',')
        assertEquals(listOf("rotate", "airplane"), merged.take(2))
        assertTrue("bluetooth" in merged && "alarm_clock" in merged)
        assertEquals(merged.size, merged.toSet().size)
    }

    @Test fun neverHidesTimeSignalWifiOrBattery() {
        val merged = StatusBarControl.merged(null).split(',')
        listOf("clock", "battery", "wifi", "mobile").forEach { assertTrue(it !in merged) }
    }
}
