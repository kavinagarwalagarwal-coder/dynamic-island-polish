package com.dynamicdroid.app

import org.junit.Assert.*
import org.junit.Test

class IslandSignalsTest {
    @Test fun ringingAlarmNeedsSnoozeButFiringTimerDoesNot() {
        assertTrue(IslandSignals.isRingingAlarm("alarm", false, listOf("Snooze", "Dismiss")))
        assertTrue(IslandSignals.isRingingAlarm(null, true, listOf("SNOOZE", "Stop")))
        // Timers firing in Clock use Stop / +1:00, never Snooze.
        assertFalse(IslandSignals.isRingingAlarm("alarm", true, listOf("Stop", "+1:00")))
        // "Upcoming alarm" offers only Dismiss.
        assertFalse(IslandSignals.isRingingAlarm("alarm", true, listOf("Dismiss")))
        assertFalse(IslandSignals.isRingingAlarm("msg", false, listOf("Snooze")))
        val labels = listOf("Snooze", "Turn off")
        assertEquals(0, IslandSignals.alarmSnoozeIndex(labels))
        assertEquals(1, IslandSignals.alarmDismissIndex(labels))
    }

    @Test fun callStateFollowsCallStyleThenButtonsThenChronometer() {
        assertEquals(CallState.INCOMING, IslandSignals.callState(1, emptyList(), false))
        assertEquals(CallState.INCOMING, IslandSignals.callState(3, emptyList(), true))
        assertEquals(CallState.CALLING, IslandSignals.callState(2, listOf("Hang up"), false))
        assertEquals(CallState.ACTIVE, IslandSignals.callState(2, listOf("Hang up"), true))
        assertEquals(CallState.INCOMING, IslandSignals.callState(0, listOf("Decline", "Answer"), false))
        assertEquals(CallState.ACTIVE, IslandSignals.callState(0, listOf("End call"), true))
        assertEquals(CallState.CALLING, IslandSignals.callState(0, listOf("End call"), false))
    }

    @Test fun progressIsDeterminateAndCoalesced() {
        assertNull(IslandSignals.progressFraction(10, 100, indeterminate = true))
        assertNull(IslandSignals.progressFraction(10, 0, indeterminate = false))
        assertEquals(0.25f, IslandSignals.progressFraction(25, 100, false)!!, 0.0001f)
        assertEquals(1f, IslandSignals.progressFraction(150, 100, false)!!, 0.0001f)
        assertTrue(IslandSignals.progressWorthPosting(null, 0f))
        assertFalse(IslandSignals.progressWorthPosting(0.500f, 0.505f))
        assertTrue(IslandSignals.progressWorthPosting(0.50f, 0.51f))
        assertTrue(IslandSignals.progressWorthPosting(0.995f, 1f))
    }

    @Test fun percentAndVolumeSteps() {
        assertEquals(0, IslandSignals.percent(-1f))
        assertEquals(47, IslandSignals.percent(7f / 15f))
        assertEquals(100, IslandSignals.percent(2f))
        assertEquals(15, IslandSignals.stepVolume(15, 15, up = true))
        assertEquals(0, IslandSignals.stepVolume(0, 15, up = false))
        assertEquals(8, IslandSignals.stepVolume(7, 15, up = true))
    }

    @Test fun featureSettingsStayInBounds() {
        val tooSmall = IslandFeatureSet(compactHeightDp = 10f).safe()
        assertEquals(IslandFeatureSet.MIN_COMPACT_DP, tooSmall.compactHeightDp, 0f)
        val tooBig = IslandFeatureSet(compactHeightDp = 90f).safe()
        assertEquals(IslandFeatureSet.MAX_COMPACT_DP, tooBig.compactHeightDp, 0f)
        val quietHold = IslandFeatureSet(hapticHold = false)
        assertFalse(quietHold.haptic(HapticGesture.HOLD))
        assertTrue(quietHold.haptic(HapticGesture.TAP))
    }
}
