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

/** Draws the two-team cricket island straight from the view, in both looks, small and open. */
@RunWith(AndroidJUnit4::class)
class CricketIslandRenderTest {
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
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/IslandCricket")
        }
        val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
        resolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun cricketScoreboardRendersBothTeams() {
        fun live(key: String, board: Scoreboard) = LiveActivity(key, "test.$key", IslandMode.LIVE, "", board.summary,
            "Cricket", null, null, Color.WHITE, scoreboard = board)
        val odi = live("odi", Scoreboard(
            Scoreboard.Team("IND", "245/3", "42.1 ov", batting = true, color = 0xFF050CEB.toInt(), target = 275),
            Scoreboard.Team("AUS", "274/8", "50 ov", color = 0xFFF3F702.toInt()),
            "Live", "India need 30 runs from 47 balls", "Australia tour of India"))
        val test = live("test", Scoreboard(
            Scoreboard.Team("NZ", "368"), Scoreboard.Team("IND", "486/4", "125 ov", batting = true),
            "Stumps", "India lead by 118 runs", "India tour of New Zealand"))
        val result = live("result", Scoreboard(
            Scoreboard.Team("CCC", "427"), Scoreboard.Team("TAM", "131", "43.1 ov", previous = "179"),
            "Result", "Colombo won by an innings and 117 runs", "Major League", live = false))

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val deadline = SystemClock.uptimeMillis() + 15_000L
            var found = false
            while (!found && SystemClock.uptimeMillis() < deadline) {
                scenario.onActivity { found = findIsland(it.window.decorView)?.isAttachedToWindow == true }
                SystemClock.sleep(100)
            }
            assertTrue("Preview never attached", found)
            for (look in IslandLook.values()) for ((a, p) in listOf(odi to Presentation.COMPACT, odi to Presentation.EXPANDED,
                    test to Presentation.COMPACT, test to Presentation.EXPANDED, result to Presentation.EXPANDED)) {
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
                    save("cricket-${look.name.lowercase()}-${a.key}-${p.name.lowercase()}", Bitmap.createBitmap(full, l, t, r - l, b - t))
                }
            }
        }
    }
}
