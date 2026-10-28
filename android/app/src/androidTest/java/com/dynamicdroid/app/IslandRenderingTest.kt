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
        saveBitmap(name, bitmap)
    }

    private fun saveBitmap(name: String, bitmap: Bitmap) {
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

    @Test fun appCardPinchesTravelsAndOpensIntoIsland() {
        val music = LiveActivity("handoff", "music.test", IslandMode.MUSIC, "Reference motion", "Local notification card", "Player", null, null,
            0xFFFFFFFF.toInt(), isPlaying = false)
        val frames = ArrayList<Bitmap>()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val view = findIsland(activity.window.decorView)!!
                    view.applyFit(IslandFit(alignCamera = false)); view.setSecondary(null); view.show(music, Presentation.COMPACT)
                }
                SystemClock.sleep(1000)
                val done = CountDownLatch(1)
                var narrow = false; var rose = false
                scenario.onActivity { activity ->
                    val view = findIsland(activity.window.decorView)!!
                    view.show(music, Presentation.COMPACT, absorb = true)
                    assertNotNull(field<Bitmap?>(view, "morphCard"))
                    var index = 0; var previousTop = 0f
                    val callback = object : android.view.Choreographer.FrameCallback {
                        override fun doFrame(time: Long) {
                            val g = field<AppMorphGeometry>(view, "appMorph")
                            narrow = narrow || g.width < view.width * .20f && g.height > g.width
                            rose = rose || g.top < previousTop && g.width < view.width * .20f
                            previousTop = g.top
                            if (index < 60 && index % 3 == 0) {
                                val b = Bitmap.createBitmap(view.width / 2, view.height / 2, Bitmap.Config.ARGB_8888)
                                val c = android.graphics.Canvas(b); c.drawColor(android.graphics.Color.DKGRAY); c.scale(.5f, .5f)
                                view.draw(c); frames.add(b)
                            }
                            if (++index < 100) android.view.Choreographer.getInstance().postFrameCallback(this) else done.countDown()
                        }
                    }
                    android.view.Choreographer.getInstance().postFrameCallback(callback)
                }
                assertTrue(done.await(15, TimeUnit.SECONDS)); assertTrue("Card never pinched", narrow); assertTrue("Card never travelled upward", rose)
                scenario.onActivity { activity ->
                    val view = findIsland(activity.window.decorView)!!
                    assertFalse(view.isAbsorbing)
                    assertNull("Temporary card was retained", field<Bitmap?>(view, "morphCard"))
                    assertEquals(Presentation.COMPACT, view.presentation)
                    assertEquals(232f * view.resources.displayMetrics.density, field<RectF>(view, "rect").width(), 1f)
                    view.show(music, Presentation.COMPACT, absorb = true)
                    view.cancelAppMorph()
                    assertFalse(view.isAbsorbing); assertNull(field<Bitmap?>(view, "morphCard"))
                }
            }
            frames.forEachIndexed { index, frame -> saveBitmap("app-morph-frame-${index.toString().padStart(2, '0')}", frame) }
        } finally { frames.filterNot { it.isRecycled }.forEach(Bitmap::recycle) }
    }

    @Test fun dropletConnectsDetachesAndReversesWithoutSnapping() {
        val music = LiveActivity("drop-music", "music.test", IslandMode.MUSIC, "Music", "Artist", "Music", null, null,
            0xFFFFFFFF.toInt(), isPlaying = false)
        val timer = LiveActivity("drop-timer", "clock.test", IslandMode.TIMER, "Tea", "Timer", "Clock", null, null,
            0xFFFF9F0A.toInt(), chronometerCountDown = true, clockBaseElapsed = SystemClock.elapsedRealtime() + 300_000L)
        val frames = ArrayList<Bitmap>()
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val view = findIsland(activity.window.decorView)!!
                    view.applyFit(IslandFit(alignCamera = false)); view.setSecondary(null); view.show(music, Presentation.COMPACT)
                }
                SystemClock.sleep(1200)
                val finished = CountDownLatch(1)
                var connected = false
                var dropped = false
                scenario.onActivity { activity ->
                    val view = findIsland(activity.window.decorView)!!
                    view.setSecondary(timer); view.show(music, Presentation.COMPACT)
                    var index = 0
                    val callback = object : android.view.Choreographer.FrameCallback {
                        override fun doFrame(time: Long) {
                            connected = connected || !field<android.graphics.Path>(view, "dropNeck").isEmpty
                            val g = field<IslandDropGeometry>(view, "dropGeometry")
                            dropped = dropped || g.centerY > view.resources.displayMetrics.density * 28f
                            if (index < 40 && index % 2 == 0) {
                                val bitmap = Bitmap.createBitmap(view.width, (90 * view.resources.displayMetrics.density).toInt(), Bitmap.Config.ARGB_8888)
                                val canvas = android.graphics.Canvas(bitmap)
                                canvas.drawColor(android.graphics.Color.rgb(40, 40, 40))
                                view.draw(canvas); frames.add(bitmap)
                            }
                            if (++index < 80) android.view.Choreographer.getInstance().postFrameCallback(this)
                            else finished.countDown()
                        }
                    }
                    android.view.Choreographer.getInstance().postFrameCallback(callback)
                }
                assertTrue("Drop animation did not finish", finished.await(10, TimeUnit.SECONDS))
                assertTrue("No liquid connection was rendered", connected)
                assertTrue("Bubble never followed its downward arc", dropped)
                scenario.onActivity { activity ->
                    val view = findIsland(activity.window.decorView)!!
                    assertTrue(field<android.graphics.Path>(view, "dropNeck").isEmpty)
                    assertFalse(RectF.intersects(field(view, "rect"), field(view, "secondaryRect")))
                    view.setSecondary(null); view.show(music, Presentation.COMPACT)
                }
                SystemClock.sleep(80)
                scenario.onActivity { activity ->
                    val view = findIsland(activity.window.decorView)!!
                    val before = field<Spring>(view, "dropS").value
                    view.setSecondary(timer); view.show(music, Presentation.COMPACT)
                    assertEquals("Reversing the merge snapped the droplet", before, field<Spring>(view, "dropS").value, 0f)
                }
                SystemClock.sleep(1200)
                val merged = CountDownLatch(1)
                scenario.onActivity { activity ->
                    val view = findIsland(activity.window.decorView)!!
                    view.callbacks = object : IslandView.Callbacks { override fun onSettled() { merged.countDown() } }
                    view.setSecondary(null); view.show(music, Presentation.COMPACT)
                }
                assertTrue("Merge did not settle", merged.await(10, TimeUnit.SECONDS))
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    val view = findIsland(activity.window.decorView)!!
                    assertTrue(field<RectF>(view, "secondaryRect").isEmpty)
                    assertTrue(field<android.graphics.Path>(view, "dropNeck").isEmpty)
                    assertFalse("Drop leaves a continuous animation loop", field<Boolean>(view, "running"))
                }
            }
            frames.forEachIndexed { index, frame -> saveBitmap("drop-frame-${index.toString().padStart(2, '0')}", frame) }
        } finally { frames.filterNot { it.isRecycled }.forEach(Bitmap::recycle) }
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
