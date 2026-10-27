package com.dynamicdroid.app

import android.graphics.*
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WallpaperGlassRenderingTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun find(view: View): IslandView? {
        if (view is IslandView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
        return null
    }
    private fun capture(name: String) {
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: error("No screenshot")
        val resolver = instrumentation.targetContext.contentResolver
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/IslandSmoke")
            put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
        resolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        values.clear(); values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null); bitmap.recycle()
    }
    @Test fun gpuLensSurvivesSplitExpandAndInterruptedMorph() {
        val image = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        val c = Canvas(image)
        c.drawColor(Color.rgb(30, 125, 185))
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        for (i in 0 until 16) {
            p.color = if (i % 2 == 0) Color.rgb(240, 140, 35) else Color.rgb(90, 35, 145)
            c.drawRect(i * 32f, 0f, i * 32f + 14f, 512f, p)
        }
        p.color = Color.BLACK; c.drawCircle(256f, 256f, 150f, p)
        p.color = Color.rgb(230, 100, 60); c.drawCircle(256f, 256f, 55f, p)
        p.color = Color.WHITE; c.drawRect(235f, 115f, 270f, 225f, p)
        val music = LiveActivity("wallpaper-test", "test.music", IslandMode.MUSIC, "Wallpaper motion", "Local preview", "Music", null, null, Color.WHITE, isPlaying = true)
        val timer = LiveActivity("timer", "test.clock", IslandMode.TIMER, "Tea", "", "Clock", null, null, Color.YELLOW,
            chronometerCountDown = true, clockBaseElapsed = android.os.SystemClock.elapsedRealtime() + 300000)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val view = find(activity.window.decorView)!!
                view.applyWallpaper(WallpaperMotion(enabled = true, automatic = false, strength = .65f), image)
                view.setSecondary(timer); view.show(music, Presentation.COMPACT)
            }
            android.os.SystemClock.sleep(1000)
            scenario.onActivity { activity ->
                val view = find(activity.window.decorView)!!
                val glass = IslandView::class.java.getDeclaredField("liquidGlass").apply { isAccessible = true }.get(view) as WallpaperLiquidGlass
                assertTrue("AGSL shader did not compile", glass.hasRuntimeShader)
                assertTrue("Lens never executed on GPU", glass.wasHardwareRendered)
                view.show(music, Presentation.EXPANDED)
            }
            android.os.SystemClock.sleep(1000); capture("wallpaper-glass-expanded")
            scenario.onActivity { find(it.window.decorView)!!.show(music, Presentation.COMPACT) }
            android.os.SystemClock.sleep(90)
            scenario.onActivity { find(it.window.decorView)!!.show(music, Presentation.EXPANDED) }
            android.os.SystemClock.sleep(100)
            scenario.onActivity { find(it.window.decorView)!!.show(music, Presentation.COMPACT) }
            android.os.SystemClock.sleep(1000); capture("wallpaper-glass-split")
        }
        image.recycle()
    }
}
