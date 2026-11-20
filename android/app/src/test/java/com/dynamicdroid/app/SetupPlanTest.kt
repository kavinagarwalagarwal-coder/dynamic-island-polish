package com.dynamicdroid.app

import org.junit.Assert.*
import org.junit.Test

class SetupPlanTest {
    private val none = SetupState(listener = false, island = false, postNotifications = false, battery = false, usage = false)

    @Test fun requiredStepsComeFirstThenOptionalOnesInOrder() {
        assertEquals(SetupStep.LISTENER, SetupPlan.next(none, 34))
        assertEquals(SetupStep.ISLAND, SetupPlan.next(none.copy(listener = true), 34))
        // Optional steps only once both required ones are done, even if an optional one is done.
        assertEquals(SetupStep.ISLAND, SetupPlan.next(none.copy(listener = true, battery = true), 34))
        assertEquals(SetupStep.POST_NOTIFICATIONS, SetupPlan.next(none.copy(listener = true, island = true), 34))
        assertNull(SetupPlan.next(SetupState(true, true, true, true, true), 34))
    }

    @Test fun readyNeedsOnlyTheRequiredSteps() {
        assertFalse(SetupPlan.ready(none.copy(listener = true), 34))
        assertTrue(SetupPlan.ready(none.copy(listener = true, island = true), 34))
    }

    @Test fun theNotificationPermissionStepOnlyExistsOnAndroid13AndLater() {
        assertFalse(SetupStep.POST_NOTIFICATIONS in SetupPlan.steps(32))
        assertTrue(SetupStep.POST_NOTIFICATIONS in SetupPlan.steps(33))
        assertEquals(SetupStep.BATTERY, SetupPlan.next(none.copy(listener = true, island = true), 30))
        assertEquals(4, SetupPlan.steps(30).size)
    }

    @Test fun computerCommandGrantsEverythingAndKeepsOtherAccessibilityApps() {
        val c = SetupPlan.adbCommand("io.github.x.dynamicdroid")
        assertTrue(c.startsWith("adb shell '") && c.endsWith("'"))
        assertTrue("io.github.x.dynamicdroid/com.dynamicdroid.app.IslandNotificationListener" in c)
        assertTrue("io.github.x.dynamicdroid/com.dynamicdroid.app.IslandAccessibilityService" in c)
        // Appends to the services already on rather than replacing them.
        assertTrue("\"\$s:io.github.x.dynamicdroid/com.dynamicdroid.app.IslandAccessibilityService\"" in c)
        // No characters Windows cmd would split on outside double quotes.
        assertFalse('|' in c); assertFalse('&' in c); assertFalse('>' in c); assertFalse('<' in c)
        // Only one pair of single quotes: the whole command is one argument to adb shell.
        assertEquals(2, c.count { it == '\'' })
    }

    @Test fun updateLinkPointsAtTheRightApkForEachBuild() {
        assertTrue(AppLinks.latestApk("io.github.x.dynamicdroid").endsWith("/releases/latest/download/DynamicDroid.apk"))
        assertTrue(AppLinks.latestApk("io.github.x.dynamicdroid.android17").endsWith("/releases/latest/download/DynamicDroid-Android17.apk"))
    }
}
