package com.dynamicdroid.app

import android.graphics.Bitmap
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Exercises real Canvas/Choreographer output, rather than only testing target geometry. */
@RunWith(AndroidJUnit4::class)
class IslandRenderingTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun findIsland(view: View): IslandView? {
        if (view is IslandView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findIsland(view.getChildAt(i))?.let { return it }
        return null
    }
    @Suppress("UNCHECKED_CAST")
    private fun <T> field(view: IslandView, name: String): T =
        IslandView::class.java.getDeclaredField(name).apply { isAccessible = true }.get(view) as T

    private fun capture(name: String) {
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: error("No emulator screenshot")
        // MediaStore output survives the test runner uninstalling its target APK.
        val resolver = instrumentation.targetContext.contentResolver
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/IslandSmoke")
            put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("No screenshot destination")
        resolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        values.clear()
        values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        bitmap.recycle()
    }

    @Test fun splitMergeExpandAndCameraIdleAreRendered() {
        val music = LiveActivity("music", "music.test", IslandMode.MUSIC, "Music", "Artist", "Music", null, null,
            0xFFFFFFFF.toInt(), isPlaying = true)
        val timer = LiveActivity("timer", "clock.test", IslandMode.TIMER, "Tea", "Timer", "Clock", null, null,
            0xFFFF9F0A.toInt(), chronometerCountDown = true,
            clockBaseElapsed = SystemClock.elapsedRealtime() + 300_000L)
        instrumentation.uiAutomation.executeShellCommand(
            "pm grant com.dynamicdroid.app android.permission.POST_NOTIFICATIONS"
        ).let { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { input -> input.readBytes() } }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            fun present(primary: LiveActivity?, secondary: LiveActivity?, p: Presentation, scale: Float = 1f) {
                val settled = CountDownLatch(1)
                scenario.onActivity { activity ->
                    val view = findIsland(activity.window.decorView) ?: error("Missing island preview")
                    view.callbacks = object : IslandView.Callbacks { override fun onSettled() { settled.countDown() } }
                    view.applyFit(IslandFit(scale = scale, alignCamera = false))
                    view.setSecondary(secondary)
                    view.show(primary, p)
                }
                assertTrue("Island did not settle", settled.await(10, TimeUnit.SECONDS))
                instrumentation.waitForIdleSync()
            }
            for (scale in listOf(0.7f, 1f, 1.3f)) {
                present(music, timer, Presentation.COMPACT, scale)
                scenario.onActivity { activity ->
                    val view = findIsland(activity.window.decorView)!!
                    val main = field<RectF>(view, "rect")
                    val bubble = field<RectF>(view, "secondaryRect")
                    assertEquals("music", view.activity?.key)
                    assertTrue("Timer bubble is invisible", bubble.width() > 20f)
                    assertFalse("Timer bubble is covered by the primary pill", RectF.intersects(main, bubble))
                    assertTrue("Timer bubble runs off the preview", bubble.right * scale <= view.width + 1f)
                }
                capture("split-${(scale * 100).toInt()}")
            }
            present(timer, music, Presentation.EXPANDED)
            capture("expanded-timer")
            present(music, timer, Presentation.COMPACT)
            capture("split-restored")
            present(music, null, Presentation.COMPACT)
            scenario.onActivity { activity ->
                val view = findIsland(activity.window.decorView)!!
                assertTrue(field<RectF>(view, "secondaryRect").isEmpty)
            }
            capture("merged-single")
            present(null, null, Presentation.IDLE)
            scenario.onActivity { activity ->
                val view = findIsland(activity.window.decorView)!!
                assertTrue("Idle still paints an overlay", field<Spring>(view, "presenceS").value < 0.01f)
                assertTrue("Idle remains a bar", field<RectF>(view, "rect").width() < 40 * view.resources.displayMetrics.density)
            }
            capture("hidden-camera-idle")
        }
    }
}
