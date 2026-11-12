package com.dynamicdroid.app

import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner

/**
 * Marks instrumented runs so the app skips its launch flourish and first-run tour. Tests then
 * see the settled screen immediately and screenshots are never taken mid-animation.
 */
class IslandTestRunner : AndroidJUnitRunner() {
    override fun onCreate(arguments: Bundle?) {
        System.setProperty(Motion.TEST_PROPERTY, "true")
        super.onCreate(arguments)
    }
}

/** The installed app's ID; it differs per build (standard or android17), so tests never hardcode it. */
val APP_ID: String
    get() = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.packageName
