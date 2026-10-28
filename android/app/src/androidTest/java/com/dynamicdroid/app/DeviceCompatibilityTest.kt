package com.dynamicdroid.app

import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Runs on API 26, 30 and 36; no OEM-specific APIs or screen-content access. */
@RunWith(AndroidJUnit4::class)
class DeviceCompatibilityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).let {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { input -> input.readBytes() }
        }
    }
    private fun button(view: View, text: String): Button? {
        if (view is Button && view.text.toString() == text) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) button(view.getChildAt(i), text)?.let { return it }
        return null
    }
    @Test fun pagesAndStandardOverlayRemainUsableOnLegacyAndCurrentAndroid() {
        val context = instrumentation.targetContext
        shell("appops set com.dynamicdroid.app SYSTEM_ALERT_WINDOW allow")
        if (android.os.Build.VERSION.SDK_INT >= 33) shell("pm grant com.dynamicdroid.app android.permission.POST_NOTIFICATIONS")
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                for (page in listOf("Playground", "Setup", "Appearance", "Phone fit")) {
                    scenario.onActivity { activity ->
                        val nav = button(activity.window.decorView, page) ?: error("Missing $page")
                        assertTrue(nav.isShown); assertTrue(nav.performClick()); assertTrue(nav.isSelected)
                    }
                    instrumentation.waitForIdleSync()
                }
                scenario.onActivity { activity ->
                    IslandPreferences.save(activity, IslandFit(placement = IslandPlacement.BELOW_STATUS_BAR))
                    androidx.core.content.ContextCompat.startForegroundService(activity,
                        Intent(activity, IslandOverlayService::class.java).setAction(IslandOverlayService.ACTION_FIT))
                    androidx.core.content.ContextCompat.startForegroundService(activity,
                        Intent(activity, IslandOverlayService::class.java).setAction(IslandOverlayService.ACTION_TEST))
                }
                val deadline = SystemClock.uptimeMillis() + 8000L
                var seen = false
                while (!seen && SystemClock.uptimeMillis() < deadline) {
                    instrumentation.runOnMainSync {
                        val service = IslandBus.listener as? IslandOverlayService
                        val view = service?.let { IslandOverlayService::class.java.getDeclaredField("island").apply { isAccessible = true }.get(it) as? IslandView }
                        if (view?.activity?.key == "placement-test") {
                            val top = IslandView::class.java.getDeclaredField("baseTop").apply { isAccessible = true }.getFloat(view)
                            val location = IntArray(2); view.getLocationOnScreen(location)
                            val id = view.resources.getIdentifier("status_bar_height", "dimen", "android")
                            val bar = if (id != 0) view.resources.getDimensionPixelSize(id) else 0
                            if (top + location[1] >= bar) seen = true
                        }
                    }
                    if (!seen) SystemClock.sleep(100)
                }
                assertTrue("Test island is clipped behind the status bar", seen)
            }
        } finally {
            context.stopService(Intent(context, IslandOverlayService::class.java))
            IslandPreferences.save(context, IslandFit())
        }
    }
}
