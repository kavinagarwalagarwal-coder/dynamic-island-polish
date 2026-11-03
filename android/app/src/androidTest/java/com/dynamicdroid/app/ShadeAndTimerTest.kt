package com.dynamicdroid.app

import android.content.Intent
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The island never blocks the status bar: a swipe down beside it opens the shade. And a
 * count-up timer whose notification stops counting (like a finished screen recording) ends.
 */
@RunWith(AndroidJUnit4::class)
class ShadeAndTimerTest {
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
    @Suppress("UNCHECKED_CAST")
    private fun <T> field(value: Any, name: String): T = value.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(value) as T
    private fun liveModes(): List<String> = service()?.let { s ->
        val shelf = field<Any>(s, "activities")
        (shelf.javaClass.getMethod("all").invoke(shelf) as List<*>).map { (it as LiveActivity).let { a -> "${a.mode}:${a.title}" } }
    }.orEmpty()

    private fun shadeOpen(): Boolean {
        val height = context.resources.displayMetrics.heightPixels
        return automation.windows.any { w ->
            val r = Rect().also(w::getBoundsInScreen)
            w.root?.packageName == "com.android.systemui" && r.height() > height / 2
        }
    }

    @Test fun swipeBesideTheIslandOpensTheShadeAndFinishedTimersEnd() {
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        shell("appops set com.dynamicdroid.app SYSTEM_ALERT_WINDOW allow")
        ActivityScenario.launch(MainActivity::class.java).use {
            try {
                shell("cmd notification allow_listener com.dynamicdroid.app/com.dynamicdroid.app.IslandNotificationListener")
                shell("settings put secure enabled_accessibility_services com.dynamicdroid.app/com.dynamicdroid.app.IslandAccessibilityService")
                shell("settings put secure accessibility_enabled 1")
                assertTrue("Overlay service not attached", waitFor(20_000) {
                    service() != null && IslandAccessibilityService.instance != null && IslandNotificationListener.instance != null
                })
                shell("input keyevent KEYCODE_HOME")
                Thread.sleep(800)

                // A recording-style count-up timer shows...
                shell("am broadcast -f 32 -n com.dynamicdroid.fixture/.FixtureReceiver --es kind recording")
                assertTrue("Count-up timer did not show: " + liveModes(), waitFor(6_000) { liveModes().any { it.startsWith("TIMER") } })

                // ...the island's own window takes no touches; only a small window over the pill does.
                val screenW = context.resources.displayMetrics.widthPixels
                assertTrue("Touch area should hug the island", waitFor(4_000) {
                    val s = service() ?: return@waitFor false
                    val draw = field<IslandView?>(s, "island")?.layoutParams as? WindowManager.LayoutParams
                    val touch = field<WindowManager.LayoutParams?>(s, "touchParams")
                    draw != null && draw.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0 &&
                        touch != null && touch.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE == 0 &&
                        touch.width < screenW * 0.8f
                })

                // A real swipe down from the status bar, well clear of the island, opens the shade.
                val x = (screenW * 0.92f).toInt()
                shell("input swipe $x 4 $x ${context.resources.displayMetrics.heightPixels / 2} 250")
                val opened = waitFor(4_000) { shadeOpen() }
                shell("cmd statusbar collapse")
                assertTrue("Swiping down beside the island did not open the shade", opened)
                Thread.sleep(600)

                // When the notification stops counting (recording saved), the timer ends.
                shell("am broadcast -f 32 -n com.dynamicdroid.fixture/.FixtureReceiver --es kind recording-done")
                assertTrue("Finished count-up timer kept running: " + liveModes(), waitFor(6_000) {
                    liveModes().none { it.startsWith("TIMER") }
                })
            } finally {
                shell("cmd statusbar collapse")
                shell("am broadcast -f 32 -n com.dynamicdroid.fixture/.FixtureReceiver --es kind clear")
                shell("cmd notification disallow_listener com.dynamicdroid.app/com.dynamicdroid.app.IslandNotificationListener")
                shell("settings delete secure enabled_accessibility_services")
                shell("settings put secure accessibility_enabled 0")
                context.stopService(Intent(context, IslandOverlayService::class.java))
                shell("appops set com.dynamicdroid.app SYSTEM_ALERT_WINDOW deny")
            }
        }
    }
}
