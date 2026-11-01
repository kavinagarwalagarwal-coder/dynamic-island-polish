package com.dynamicdroid.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Screenshots of the logo build-up and every page of the guided tour. */
@RunWith(AndroidJUnit4::class)
class TourAndLogoRenderTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun save(name: String, bitmap: Bitmap) {
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
        values.clear(); values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        bitmap.recycle()
    }

    private fun capture(name: String) = save(name, instrumentation.uiAutomation.takeScreenshot() ?: error("No emulator screenshot"))

    private fun findButton(view: View, label: String): Button? {
        if (view is Button && view.text.toString() == label && view.isShown) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findButton(view.getChildAt(i), label)?.let { return it }
        return null
    }

    @Test fun logoBuildsUpInStages() {
        val context = instrumentation.targetContext
        val size = 240
        val frames = listOf(.08f, .2f, .35f, .5f, .62f, .75f, .88f, 1f)
        val strip = Bitmap.createBitmap(size * frames.size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(strip)
        canvas.drawColor(NothingStyle.background)
        instrumentation.runOnMainSync {
            val logo = LogoMarkView(context)
            logo.measure(View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY))
            logo.layout(0, 0, size, size)
            frames.forEachIndexed { i, p ->
                logo.progress = p
                canvas.save(); canvas.translate((i * size).toFloat(), 0f); logo.draw(canvas); canvas.restore()
            }
        }
        // The final frame must contain the white pill and the red camera dot.
        val pill = strip.getPixel(size * 7 + size * 20 / 100, size / 2)
        assertTrue("Logo pill missing", android.graphics.Color.red(pill) > 200)
        val dot = strip.getPixel(size * 7 + size * 38 / 100, size / 2)
        assertTrue("Logo camera dot missing", android.graphics.Color.red(dot) > 200 && android.graphics.Color.green(dot) < 120)
        save("tour-logo-frames", strip)
    }

    @Test fun everyTourPageRenders() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val root: FrameLayout = activity.findViewById(android.R.id.content)!!
                val tour = OnboardingOverlay(activity, IslandFeatureSet(), connected = false) {}
                root.addView(tour, FrameLayout.LayoutParams(-1, -1))
                tour.enter()
            }
            // Each page gets time for its scripted gesture to land before the screenshot.
            val waits = listOf(3_000L, 1_900L, 1_850L, 1_750L, 2_400L, 1_600L, 1_500L)
            waits.forEachIndexed { page, wait ->
                SystemClock.sleep(wait)
                capture("tour-page-${page + 1}")
                if (page < waits.lastIndex) scenario.onActivity { activity ->
                    val next = findButton(activity.window.decorView, "Next") ?: error("Missing Next on page ${page + 1}")
                    assertTrue(next.performClick())
                }
            }
            scenario.onActivity { activity ->
                assertNotNull("Last page should offer Setup", findButton(activity.window.decorView, "Open Setup"))
            }
        }
    }
}
