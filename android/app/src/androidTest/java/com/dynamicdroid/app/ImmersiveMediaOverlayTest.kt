package com.dynamicdroid.app

import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** A full-screen video with the status bar still visible (like a Short or Reel) hides the island. */
@RunWith(AndroidJUnit4::class)
class ImmersiveMediaOverlayTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation = instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private val context = instrumentation.targetContext

    private fun shell(command: String) = automation.executeShellCommand(command).let {
        ParcelFileDescriptor.AutoCloseInputStream(it).use { input -> String(input.readBytes()) }
    }

    private fun waitFor(timeoutMs: Long, check: () -> Boolean): Boolean {
        val until = SystemClock.uptimeMillis() + timeoutMs
        do {
            var done = false
            instrumentation.runOnMainSync { done = check() }
            if (done) return true
            Thread.sleep(80)
        } while (SystemClock.uptimeMillis() < until)
        return false
    }

    private fun service() = IslandBus.listener as? IslandOverlayService
    private fun immersive(): Boolean = service()?.let {
        it.javaClass.getDeclaredField("immersiveMedia").apply { isAccessible = true }.get(it) as Boolean
    } == true

    @Test fun fullScreenVideoHidesTheIslandAndHomeBringsItBack() {
        shell("appops set ${APP_ID} SYSTEM_ALERT_WINDOW allow")
        IslandFeatures.setBoolean(context, "hide_for_media", true)
        ActivityScenario.launch(MainActivity::class.java).use {
            try {
                shell("cmd notification allow_listener ${APP_ID}/com.dynamicdroid.app.IslandNotificationListener")
                shell("settings put secure enabled_accessibility_services ${APP_ID}/com.dynamicdroid.app.IslandAccessibilityService")
                shell("settings put secure accessibility_enabled 1")
                assertTrue("Overlay service not attached", waitFor(20_000) {
                    service() != null && IslandAccessibilityService.instance != null && IslandNotificationListener.instance != null
                })
                shell("am broadcast -f 32 -n com.dynamicdroid.fixture/.FixtureReceiver --es kind custom")
                assertTrue("No live activity to hide", waitFor(10_000) {
                    service()?.javaClass?.getDeclaredField("island")?.apply { isAccessible = true }
                        ?.get(service()) is IslandView && IslandBus.listener != null
                })
                Thread.sleep(1_000)
                // The emulator sometimes shows a stray system dialog ("android"); clear it so the
                // video is the window in front, and give it a second try if one pops up again.
                shell("am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS")
                shell("am start -n com.dynamicdroid.fixture/.FixtureActivity --es kind video")
                if (!waitFor(8_000) { immersive() }) {
                    shell("am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS")
                    shell("am start -n com.dynamicdroid.fixture/.FixtureActivity --es kind video")
                }
                if (!waitFor(8_000) { immersive() })
                    fail("A full-screen video should hide the island: " + IslandAccessibilityService.instance?.mediaDebug)
                shell("input keyevent KEYCODE_HOME")
                assertTrue("Leaving the video should bring the island back", waitFor(8_000) { !immersive() })
            } finally {
                shell("am broadcast -f 32 -n com.dynamicdroid.fixture/.FixtureReceiver --es kind clear")
                // Don't leave the video screen in the fixture's back stack for later tests.
                shell("am force-stop com.dynamicdroid.fixture")
                shell("cmd notification disallow_listener ${APP_ID}/com.dynamicdroid.app.IslandNotificationListener")
                shell("settings delete secure enabled_accessibility_services")
                shell("settings put secure accessibility_enabled 0")
                context.stopService(Intent(context, IslandOverlayService::class.java))
                shell("appops set ${APP_ID} SYSTEM_ALERT_WINDOW deny")
            }
        }
    }
}
