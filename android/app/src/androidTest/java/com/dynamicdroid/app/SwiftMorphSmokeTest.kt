package com.dynamicdroid.app

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Basic SwiftUI composition startup guard for the instrumented UI suite. */
@RunWith(AndroidJUnit4::class)
class SwiftMorphSmokeTest {
    @Test fun swiftCompositionSurvivesTheFullMorph() {
        ActivityScenario.launch(SwiftIslandMorphPreviewActivity::class.java).use { scenario ->
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            SystemClock.sleep(1200)
            scenario.onActivity {
                assertFalse("SwiftUI preview closed during its morph", it.isFinishing)
                assertTrue("SwiftUI surface never laid out", it.window.decorView.width > 0)
            }
        }
    }
}
