package com.dynamicdroid.app

import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DotMusicTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun cover(color: Int) = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
        eraseColor(color)
        for (i in 0 until 64) setPixel(i, i, Color.WHITE)
    }

    private fun track(title: String, color: Int, playing: Boolean = true) = Track(
        title, "Test Artist", "test.music", cover(color), color, playing,
        positionMs = 30_000, positionUpdatedAt = SystemClock.elapsedRealtime(), speed = 1f,
        durationMs = 200_000, openApp = null)

    /** Every widget, playing, paused and idle, inflates the way a launcher would inflate it. */
    @Test fun everyWidgetInflatesLikeOnAHomeScreen() {
        val layouts = listOf(R.layout.dot_widget_small, R.layout.dot_widget_medium, R.layout.dot_widget_wide, R.layout.dot_widget_stage)
        val states = listOf(track("Midnight City", Color.rgb(230, 60, 120)), track("Paused", Color.BLUE, playing = false), null)
        instrumentation.runOnMainSync {
            for (layout in layouts) for (state in states) {
                val views = MusicWidgets.build(context, layout, state)
                val host = FrameLayout(context)
                val inflated = views.apply(context, host)
                host.addView(inflated)
                assertNotNull(inflated.findViewById(R.id.play))
            }
        }
    }

    /** The 4×4 widget's record is drawn by the player's own code, and actually has dots on it. */
    @Test fun stageSnapshotHasTheRecordAndRing() {
        instrumentation.runOnMainSync {
            val stage = DotStage(context, unit = 330 / 360f)
            stage.setTrack(track("Snapshot", Color.rgb(255, 120, 0)))
            val picture = stage.snapshot(330)
            val px = IntArray(330 * 330).also { picture.getPixels(it, 0, 330, 0, 0, 330, 330) }
            val lit = px.count { Color.alpha(it) > 200 }
            assertTrue("Snapshot looks empty: $lit lit pixels", lit > 2_000)
        }
    }

    /** The wallpaper's version (no controls, no skip gestures) runs and draws without a window. */
    @Test fun wallpaperStageDrawsHeadless() {
        instrumentation.runOnMainSync {
            val stage = DotStage(context).apply { showControls = false; gestures = false }
            stage.resize(1080, 2400, 80f, 60f)
            stage.setTrack(track("Wallpaper", Color.CYAN))
            val canvas = android.graphics.Canvas(Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888))
            var now = 1_000_000_000L
            repeat(90) { now += 16_666_667L; stage.frame(now); stage.draw(canvas) }
            assertTrue(stage.tapAt(540f, -10_000f).not())
        }
    }

    /** A picked photo is saved, reloaded, and fills the screen; the dots draw see-through over it. */
    @Test fun wallpaperPhotoShowsThroughTheDots() {
        val src = java.io.File(context.cacheDir, "pick.png")
        Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(20, 120, 220)) }
            .let { b -> src.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        assertTrue(WallpaperPhoto.save(context, android.net.Uri.fromFile(src)))
        val photo = WallpaperPhoto.load(context)
        assertNotNull(photo)
        val screen = Bitmap.createBitmap(540, 1200, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(screen)
        WallpaperPhoto.drawCover(canvas, photo!!, 540, 1200, android.graphics.Matrix(), android.graphics.Paint())
        instrumentation.runOnMainSync {
            val stage = DotStage(context).apply { showControls = false; gestures = false; opaque = false }
            stage.resize(540, 1200, 40f, 30f)
            stage.setTrack(track("Over the photo", Color.YELLOW))
            stage.frame(1_000_000_000L)
            stage.draw(canvas)
        }
        // A corner far from the record still shows the photo's blue.
        val corner = screen.getPixel(4, 1190)
        assertTrue("Photo hidden behind the dots: ${Integer.toHexString(corner)}", Color.blue(corner) > 150)
        WallpaperPhoto.clear(context)
        assertTrue(!WallpaperPhoto.exists(context))
    }

    /** The island's dot record pulses on the beat, rebuilds on a song change, and draws. */
    @Test fun islandDotsPulseRebuildAndDraw() {
        instrumentation.runOnMainSync {
            val dots = IslandDots(15)
            dots.prepare(cover(Color.RED), mono = false, animate = false)
            var glowed = false
            repeat(90) { dots.step(1f / 60f, playing = true, spectrum = null, reduceMotion = false); glowed = glowed || dots.beatGlow > 0.3f }
            assertTrue("No beat ripple in 1.5 s of playing", glowed)
            dots.prepare(cover(Color.GREEN), mono = false, animate = true)
            repeat(60) { dots.step(1f / 60f, playing = true, spectrum = FloatArray(48) { 0.6f }, reduceMotion = false) }
            val bitmap = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            dots.drawRing(canvas, paint, 80f, 80f, 62f, 6f, 2.4f, 3, Color.YELLOW, true, 255)
            dots.drawRecord(canvas, paint, 80f, 80f, 56f, true, 255)
            dots.drawMeter(canvas, paint, 4f, 4f, 30f, 20f, 4, 3, Color.WHITE, 1f, 255)
            val px = IntArray(160 * 160).also { bitmap.getPixels(it, 0, 160, 0, 0, 160, 160) }
            assertTrue("Island dots drew nothing", px.count { Color.alpha(it) > 200 } > 1_500)

            // The whole-island field: white dots carving the cover, edge to edge.
            val field = IslandDotField()
            field.prepare(cover(Color.rgb(200, 40, 90)), animate = false)
            val pill = Bitmap.createBitmap(360, 90, Bitmap.Config.ARGB_8888)
            field.draw(android.graphics.Canvas(pill), paint, 0f, 0f, 360f, 90f, 12f, dots, true, 255)
            val fp = IntArray(360 * 90).also { pill.getPixels(it, 0, 360, 0, 0, 360, 90) }
            val left = fp.indices.count { it % 360 < 40 && Color.alpha(fp[it]) > 60 }
            val right = fp.indices.count { it % 360 > 320 && Color.alpha(fp[it]) > 60 }
            assertTrue("Dot field should reach both ends of the island ($left, $right)", left > 50 && right > 50)

            // A beat sends a visible ring of light out from the record across the field, and the
            // record itself is carved out of the field.
            val calm = IslandDots(9)
            calm.prepare(cover(Color.rgb(200, 40, 90)), mono = true, animate = false)
            calm.step(1f / 60f, playing = false, spectrum = null, reduceMotion = false)
            fun bright(d: IslandDots): Long {
                val bmp = Bitmap.createBitmap(360, 90, Bitmap.Config.ARGB_8888)
                field.draw(android.graphics.Canvas(bmp), paint, 0f, 0f, 360f, 90f, 6f, d, true, 120,
                    peak = 255, ox = 30f, oy = 45f, hole = 22f)
                val p = IntArray(360 * 90).also { bmp.getPixels(it, 0, 360, 0, 0, 360, 90) }
                assertTrue("Record clearing should stay empty", (0 until 6).all { dy -> Color.alpha(p[(42 + dy) * 360 + 30]) == 0 })
                return p.sumOf { Color.alpha(it).toLong() }
            }
            val before = bright(calm)
            calm.ripple(1f)
            repeat(12) { calm.step(1f / 60f, playing = false, spectrum = null, reduceMotion = false) }
            val during = bright(calm)
            assertTrue("Ripple should light the field ($before → $during)", during > before * 13 / 10)

            // A touch ripples out from the finger, not from the record.
            val touch = IslandDots(9)
            touch.prepare(cover(Color.rgb(200, 40, 90)), mono = true, animate = false)
            touch.step(1f / 60f, playing = false, spectrum = null, reduceMotion = false)
            touch.ripple(1f, 300f, 45f)
            repeat(12) { touch.step(1f / 60f, playing = false, spectrum = null, reduceMotion = false) }
            val reach = 333f
            val ring = touch.light(300f - 0.25f * reach, 45f, 30f, 45f, reach)
            val atRecord = touch.light(30f, 45f, 30f, 45f, reach)
            assertTrue("Touch ripple should ring round the finger ($ring vs $atRecord)", ring > 0.5f && atRecord < 0.05f)
        }
    }

    /** The player runs, survives song changes and play/pause, and keeps drawing frames. */
    @Test fun playerRunsThroughSongChanges() {
        ActivityScenario.launch(DotPlayerActivity::class.java).use { scenario ->
            var view: DotPlayerView? = null
            scenario.onActivity { view = it.window.decorView.findViewById<FrameLayout>(android.R.id.content).getChildAt(0) as DotPlayerView }
            instrumentation.runOnMainSync { view!!.setTrack(track("First Song", Color.RED)) }
            SystemClock.sleep(800)
            instrumentation.runOnMainSync { view!!.setTrack(track("A Much Longer Second Song Title That Scrolls", Color.GREEN), -1) }
            SystemClock.sleep(1_200)
            instrumentation.runOnMainSync { view!!.setTrack(track("A Much Longer Second Song Title That Scrolls", Color.GREEN, playing = false)) }
            SystemClock.sleep(600)
            instrumentation.runOnMainSync { view!!.spectrum = FloatArray(48) { (it % 7) / 7f } }
            SystemClock.sleep(600)
            instrumentation.runOnMainSync { view!!.setTrack(null) }
            SystemClock.sleep(600)
            assertTrue(view!!.isAttachedToWindow)
        }
    }
}
