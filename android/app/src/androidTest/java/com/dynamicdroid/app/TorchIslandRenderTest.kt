package com.dynamicdroid.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
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

/** Draws the flashlight island at several levels straight from the view, in both looks, small and open. */
@RunWith(AndroidJUnit4::class)
class TorchIslandRenderTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun findIsland(view: View): IslandView? {
        if (view is IslandView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findIsland(view.getChildAt(i))?.let { return it }
        return null
    }

    private fun save(name: String, bitmap: Bitmap) {
        val resolver = instrumentation.targetContext.contentResolver
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/IslandTorch")
        }
        val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
        resolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun torchRendersLevelsInBothLooks() {
        fun torch(level: Float?) = LiveActivity("torch-${level ?: "fixed"}", "test.torch", IslandMode.TORCH, "Flashlight",
            if (level != null) "Slide for brightness, tap to turn off" else "Tap to turn off", "Flashlight",
            null, null, SystemEvents.TORCH_YELLOW, progress = level)
        val dim = torch(0.15f); val mid = torch(0.6f); val full = torch(1f); val fixed = torch(null)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val deadline = SystemClock.uptimeMillis() + 15_000L
            var found = false
            while (!found && SystemClock.uptimeMillis() < deadline) {
                scenario.onActivity { found = findIsland(it.window.decorView)?.isAttachedToWindow == true }
                SystemClock.sleep(100)
            }
            assertTrue("Preview never attached", found)
            for (look in IslandLook.values()) for ((a, p) in listOf(mid to Presentation.COMPACT, dim to Presentation.EXPANDED,
                    mid to Presentation.EXPANDED, full to Presentation.EXPANDED, fixed to Presentation.EXPANDED)) {
                scenario.onActivity {
                    val view = findIsland(it.window.decorView)!!
                    view.applyFeatures(IslandFeatureSet(look = look))
                    view.setSecondary(null)
                    view.show(a, p)
                }
                SystemClock.sleep(2500)
                scenario.onActivity {
                    val view = findIsland(it.window.decorView)!!
                    assertEquals(p, view.presentation)
                    val bounds = RectF().also(view::islandBounds)
                    assertTrue("Island should be wider than tall: $bounds", bounds.width() > bounds.height())
                    val full = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                    val c = Canvas(full); c.drawColor(Color.rgb(28, 28, 30)); view.draw(c)
                    val pad = 24
                    val l = (bounds.left.toInt() - pad).coerceAtLeast(0); val t = (bounds.top.toInt() - pad).coerceAtLeast(0)
                    val r = (bounds.right.toInt() + pad).coerceAtMost(full.width); val b = (bounds.bottom.toInt() + pad).coerceAtMost(full.height)
                    save("torch-${look.name.lowercase()}-${a.key}-${p.name.lowercase()}", Bitmap.createBitmap(full, l, t, r - l, b - t))
                }
            }
        }
    }
}
