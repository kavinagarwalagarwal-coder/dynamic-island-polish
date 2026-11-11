package com.dynamicdroid.app

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RenderNode
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * How much main-thread time the island spends drawing while it opens, measured by recording each
 * frame into a RenderNode (the work the UI thread does before the GPU). Run with the instrumentation
 * argument report=1 to get the numbers back as the failure message.
 */
@RunWith(AndroidJUnit4::class)
class IslandPerfTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val report = InstrumentationRegistry.getArguments().getString("report") == "1"

    private fun findIsland(view: View): IslandView? {
        if (view is IslandView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findIsland(view.getChildAt(i))?.let { return it }
        return null
    }

    private fun art(): Bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).also { b ->
        for (y in 0 until 128) for (x in 0 until 128) b.setPixel(x, y, Color.rgb(40 + x, 30 + y, 140))
    }

    private data class Run(val name: String, val avg: Double, val max: Double, val frames: Int, val gaps: Int)

    private fun measure(scenario: ActivityScenario<MainActivity>, name: String, dots: Boolean): Run {
        val music = LiveActivity("perf", "music.test", IslandMode.MUSIC, "Perf song", "Artist", "Player", null, art(),
            Color.WHITE, isPlaying = true, durationMs = 200_000L)
        scenario.onActivity { activity ->
            val view = findIsland(activity.window.decorView)!!
            view.applyFeatures(IslandFeatures.read(activity).copy(dotMusic = dots))
            view.applyFit(IslandFit(alignCamera = false)); view.setSecondary(null)
            view.show(music, Presentation.COMPACT)
        }
        SystemClock.sleep(1200)
        val times = ArrayList<Double>()
        var gaps = 0
        val done = CountDownLatch(1)
        scenario.onActivity { activity ->
            val view = findIsland(activity.window.decorView)!!
            val node = RenderNode("perf").apply { setPosition(0, 0, view.width, view.height) }
            view.show(music, Presentation.EXPANDED)
            var index = 0
            var last = 0L
            val callback = object : android.view.Choreographer.FrameCallback {
                override fun doFrame(time: Long) {
                    if (last != 0L && time - last > 25_000_000L) gaps++
                    last = time
                    val start = System.nanoTime()
                    val canvas = node.beginRecording()
                    view.draw(canvas)
                    node.endRecording()
                    times += (System.nanoTime() - start) / 1e6
                    if (++index < 45) android.view.Choreographer.getInstance().postFrameCallback(this) else done.countDown()
                }
            }
            android.view.Choreographer.getInstance().postFrameCallback(callback)
        }
        assertTrue(done.await(15, TimeUnit.SECONDS))
        scenario.onActivity { activity -> findIsland(activity.window.decorView)!!.show(music, Presentation.COMPACT) }
        SystemClock.sleep(800)
        return Run(name, times.average(), times.maxOrNull() ?: 0.0, times.size, gaps)
    }

    @Test fun openingTheMusicIslandStaysWithinAFrame() {
        assumeTrue(android.os.Build.VERSION.SDK_INT >= 29)
        val runs = ArrayList<Run>()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.openPage("Playground") }
            val deadline = SystemClock.uptimeMillis() + 6_000L
            while (SystemClock.uptimeMillis() < deadline) {
                var ok = false
                scenario.onActivity { ok = findIsland(it.window.decorView)?.isAttachedToWindow == true }
                if (ok) break
                SystemClock.sleep(50)
            }
            instrumentation.waitForIdleSync()
            runs += measure(scenario, "dots", dots = true)
            runs += measure(scenario, "plain", dots = false)
            runs += measure(scenario, "dots-again", dots = true)
        }
        val summary = runs.joinToString(" ; ") {
            "%s avg=%.2fms max=%.2fms frames=%d late=%d".format(it.name, it.avg, it.max, it.frames, it.gaps)
        }
        if (report) throw AssertionError("PERF $summary")
        assertTrue("Drawing the opening island is too slow: $summary", runs.all { it.avg < 8.0 })
    }
}
