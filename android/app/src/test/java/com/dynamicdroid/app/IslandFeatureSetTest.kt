package com.dynamicdroid.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IslandFeatureSetTest {
    @Test fun everyKindShowsByDefault() {
        val features = IslandFeatureSet()
        IslandMode.values().forEach { assertTrue("$it should show by default", features.shows(it)) }
    }

    @Test fun eachSwitchTurnsOffOnlyItsOwnKind() {
        val cases = mapOf(
            IslandMode.MUSIC to IslandFeatureSet(music = false),
            IslandMode.CALL to IslandFeatureSet(calls = false),
            IslandMode.MESSAGE to IslandFeatureSet(messages = false),
            IslandMode.TIMER to IslandFeatureSet(timers = false),
            IslandMode.NAVIGATION to IslandFeatureSet(navigation = false),
            IslandMode.CHARGING to IslandFeatureSet(charging = false),
        )
        cases.forEach { (mode, features) ->
            assertFalse("$mode should be off", features.shows(mode))
            IslandMode.values().filter { it != mode }.forEach { other ->
                assertTrue("$other should stay on when only $mode is off", features.shows(other))
            }
        }
    }
}
