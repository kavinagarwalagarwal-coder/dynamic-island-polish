package com.dynamicdroid.app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real foreign notifications -> OS listener -> bus -> foreground service -> accessibility overlay. */
@RunWith(AndroidJUnit4::class)
class LiveOverlayTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation = instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private fun shell(command: String) = automation.executeShellCommand(command).let {
        ParcelFileDescriptor.AutoCloseInputStream(it).use { input -> String(input.readBytes()) }
    }
    @Suppress("UNCHECKED_CAST")
    private fun <T> field(value: Any, name: String): T = value.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(value) as T
    private fun await(message: String, check: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 20_000
        do {
            var done = false
            instrumentation.runOnMainSync { done = check() }
            if (done) return
            Thread.sleep(80)
        } while (SystemClock.uptimeMillis() < until)
        fail(message)
    }
    private fun capture(name: String) {
        val b = automation.takeScreenshot() ?: error("Missing screenshot")
        val r = instrumentation.targetContext.contentResolver
        val v = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/IslandSmoke")
        }
        val uri = r.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v)!!
        r.openOutputStream(uri)!!.use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        b.recycle()
    }
    @Test fun customClockTimerShowsBesideMusicInActualAndroid16Overlay() {
        shell("pm grant com.dynamicdroid.app android.permission.POST_NOTIFICATIONS")
        shell("appops set com.dynamicdroid.app SYSTEM_ALERT_WINDOW allow")
        ActivityScenario.launch(MainActivity::class.java).use {
            try {
                shell("cmd notification allow_listener com.dynamicdroid.app/com.dynamicdroid.app.IslandNotificationListener")
                shell("settings put secure enabled_accessibility_services com.dynamicdroid.app/com.dynamicdroid.app.IslandAccessibilityService")
                shell("settings put secure accessibility_enabled 1")
                await("Overlay service not attached") { IslandBus.listener is IslandOverlayService && IslandAccessibilityService.instance != null }
                shell("am start -n com.dynamicdroid.fixture/.FixtureActivity --es kind custom")
                fun visible(paused: Boolean): Boolean {
                    val service = IslandBus.listener as? IslandOverlayService ?: return false
                    val view = field<IslandView?>(service, "island") ?: return false
                    val timer = field<LiveActivity?>(view, "secondary") ?: return false
                    val bubble = field<RectF>(view, "secondaryRect")
                    return view.activity?.mode == IslandMode.MUSIC && view.presentation == Presentation.COMPACT &&
                        timer.mode == IslandMode.TIMER && (timer.pausedTimerMs != null) == paused && timer.clockMs() != null &&
                        bubble.width() > 20 && !RectF.intersects(field(view, "rect"), bubble) && !view.isConcealed
                }
                await("Custom Clock notification did not reach the detached overlay bubble") { visible(false) }
                capture("live-overlay-custom-clock")
                shell("am start -n com.dynamicdroid.fixture/.FixtureActivity --es kind paused")
                await("Paused custom timer lost its detached bubble") { visible(true) }
                capture("live-overlay-paused-clock")
                shell("am start -n com.dynamicdroid.fixture/.FixtureActivity --es kind custom")
                await("Resumed custom timer lost its detached bubble") { visible(false) }
                capture("live-overlay-resumed-clock")
                shell("am start -n com.dynamicdroid.fixture/.FixtureActivity --es kind chip")
                await("Android 16 timer chip text was not recognised") {
                    visible(false) && TimerDiagnostics.read(instrumentation.targetContext).contains("notification timer text")
                }
                capture("live-overlay-timer-chip")
            } finally {
                shell("am start -n com.dynamicdroid.fixture/.FixtureActivity --es kind clear")
                shell("cmd notification disallow_listener com.dynamicdroid.app/com.dynamicdroid.app.IslandNotificationListener")
                shell("settings delete secure enabled_accessibility_services")
                shell("settings put secure accessibility_enabled 0")
                instrumentation.targetContext.stopService(Intent(instrumentation.targetContext, IslandOverlayService::class.java))
                shell("appops set com.dynamicdroid.app SYSTEM_ALERT_WINDOW deny")
            }
        }
    }
}
