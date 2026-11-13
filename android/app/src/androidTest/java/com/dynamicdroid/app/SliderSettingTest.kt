package com.dynamicdroid.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The settings sliders follow a drag all the way, even though every step updates the value they
 * are given (which is how every slider in the app is wired). They used to be rebuilt at each
 * step, which cancelled the drag after one step: they only jumped to where they were tapped.
 */
@RunWith(AndroidJUnit4::class)
class SliderSettingTest {
    @get:Rule val compose = createComposeRule()
    private val slider = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)

    @Test fun dragMovesTheSliderAllTheWayWhileTheValueUpdates() {
        var value by mutableIntStateOf(20)
        var changes = 0
        var done = -1
        compose.setContent {
            SliderSetting({ "Size $it" }, value, 0..100, { "$it" },
                onDone = { done = it }, onChange = { value = it; changes++ })
        }
        // Each drag starts on the thumb, as a finger would, and runs past the end of the track.
        fun thumbX(left: Float, width: Float) = left + width * (.04f + .92f * value / 100f)
        compose.onNode(slider).performTouchInput { swipeRight(startX = thumbX(left, width), endX = right, durationMillis = 600) }
        compose.waitForIdle()
        assertTrue("Drag stopped early: value=$value after $changes changes", value >= 95)
        assertTrue("Value did not follow the drag step by step: $changes changes", changes > 20)
        assertEquals(value, done)
        compose.onNode(slider).performTouchInput { swipeLeft(startX = thumbX(left, width), endX = left, durationMillis = 600) }
        compose.waitForIdle()
        assertTrue("Drag back stopped early: value=$value", value <= 5)
        // And again: a slider that has been dragged once still drags.
        compose.onNode(slider).performTouchInput { swipeRight(startX = thumbX(left, width), endX = left + width * .5f, durationMillis = 400) }
        compose.waitForIdle()
        assertTrue("Third drag did not follow: value=$value", value in 40..60)
    }

    @Test fun sliderFollowsAValueChangedFromOutside() {
        var value by mutableIntStateOf(20)
        compose.setContent { key("fit") { SliderSetting({ "Size $it" }, value, 0..100, { "$it" }, onChange = { value = it }) } }
        compose.runOnIdle { value = 70 }   // a profile loaded, or a reset
        compose.waitForIdle()
        val info = compose.onNode(slider).fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(70f, info.current, .5f)
    }
}
