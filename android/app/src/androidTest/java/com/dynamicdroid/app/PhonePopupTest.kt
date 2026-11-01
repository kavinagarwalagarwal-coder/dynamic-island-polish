package com.dynamicdroid.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A real high-importance notification from another app: without the option the phone pops it up;
 * with it on, the island shows it and the phone's pop-up is swiped away, while the notification
 * itself stays in the shade.
 */
@RunWith(AndroidJUnit4::class)
class PhonePopupTest {
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

    /** Is System UI showing a pop-up with [title] near the top of the screen? */
    private fun phonePopupShowing(title: String): Boolean {
        val maxTop = context.resources.displayMetrics.heightPixels * 0.3f
        return automation.windows.any { window ->
            val root = window.root ?: return@any false
            root.packageName == "com.android.systemui" &&
                root.findAccessibilityNodeInfosByText(title).any { node ->
                    val r = Rect().also(node::getBoundsInScreen)
                    !r.isEmpty && r.top < maxTop
                }
        }
    }

    private fun stillInShade(title: String) =
        IslandNotificationListener.instance?.activeNotifications.orEmpty().any {
            it.notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString() == title
        }

    @Test fun islandSwipesAwayThePhonesOwnPopup() {
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        shell("pm grant com.dynamicdroid.app android.permission.POST_NOTIFICATIONS")
        shell("pm grant com.dynamicdroid.fixture android.permission.POST_NOTIFICATIONS")
        shell("appops set com.dynamicdroid.app SYSTEM_ALERT_WINDOW allow")
        IslandFeatures.setBoolean(context, "hide_phone_popups", false)
        ActivityScenario.launch(MainActivity::class.java).use {
            try {
                shell("cmd notification allow_listener com.dynamicdroid.app/com.dynamicdroid.app.IslandNotificationListener")
                shell("settings put secure enabled_accessibility_services com.dynamicdroid.app/com.dynamicdroid.app.IslandAccessibilityService")
                shell("settings put secure accessibility_enabled 1")
                assertTrue("Overlay service not attached", waitFor(20_000) {
                    IslandBus.listener is IslandOverlayService && IslandAccessibilityService.instance != null &&
                        IslandNotificationListener.instance != null
                })
                shell("input keyevent KEYCODE_HOME")
                Thread.sleep(800)

                // Control: with the option off, the phone shows its own pop-up.
                shell("am broadcast -f 32 -n com.dynamicdroid.fixture/.FixtureReceiver --es kind message")
                assertTrue("The phone never showed its own pop-up, so there is nothing to test",
                    waitFor(5_000) { phonePopupShowing("Popup first") })
                waitFor(10_000) { !phonePopupShowing("Popup first") }
                shell("am broadcast -f 32 -n com.dynamicdroid.fixture/.FixtureReceiver --es kind clear")
                Thread.sleep(800)

                // With the option on, the island takes it and the phone's pop-up goes away quickly,
                // well before the system would have hidden it by itself.
                IslandFeatures.setBoolean(context, "hide_phone_popups", true)
                val posted = SystemClock.uptimeMillis()
                shell("am broadcast -f 32 -n com.dynamicdroid.fixture/.FixtureReceiver --es kind message2")
                assertTrue("Island did not show the message", waitFor(5_000) {
                    val service = IslandBus.listener as? IslandOverlayService ?: return@waitFor false
                    val transient = service.javaClass.getDeclaredField("transient").apply { isAccessible = true }
                        .get(service) as LiveActivity?
                    transient?.title == "Popup second"
                })
                Thread.sleep(600)
                assertTrue("The phone's pop-up was not swiped away", waitFor(2_500) { !phonePopupShowing("Popup second") })
                assertTrue("Pop-up lingered too long", SystemClock.uptimeMillis() - posted < 3_800)
                assertTrue("Notification should still be in the shade", stillInShade("Popup second"))
            } finally {
                IslandFeatures.setBoolean(context, "hide_phone_popups", false)
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
