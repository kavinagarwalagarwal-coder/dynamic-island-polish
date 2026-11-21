package com.dynamicdroid.app

import android.app.PendingIntent
import android.content.Intent
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

/** Draws every everyday island straight from the view, in both looks, small and open, for side-by-side review. */
@RunWith(AndroidJUnit4::class)
class IslandTourRenderTest {
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
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/IslandTour")
        }
        val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
        resolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun everydayIslandsRenderInBothLooks() {
        val context = instrumentation.targetContext
        val intent = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val now = SystemClock.elapsedRealtime()
        fun act(key: String, mode: IslandMode, title: String, subtitle: String = "", app: String = "Test", accent: Int = Color.WHITE,
                actions: List<String> = emptyList()) =
            LiveActivity(key, "test.$key", mode, title, subtitle, app, null, null, accent, actions = actions.map { IslandAction(it, intent) })
        val shots = listOf(
            act("timer", IslandMode.TIMER, "Tea", app = "Clock", accent = Color.rgb(255, 159, 10), actions = listOf("Pause", "Cancel"))
                .copy(chronometerCountDown = true, clockBaseElapsed = now + 254_000, progress = .4f),
            act("music", IslandMode.MUSIC, "Midnight City", "M83", "Music").copy(isPlaying = true, durationMs = 243_000, positionMs = 61_000, hasMediaSession = true),
            act("call-in", IslandMode.CALL, "Mom", "mobile", "Phone", actions = listOf("Decline", "Answer")).copy(callState = CallState.INCOMING),
            act("call-on", IslandMode.CALL, "Mom", "", "Phone", actions = listOf("Hang up"))
                .copy(callState = CallState.ACTIVE, clockBaseElapsed = now - 83_000),
            act("charging", IslandMode.CHARGING, "64%", app = "Battery"),
            act("nav", IslandMode.NAVIGATION, "200 m", "Turn right onto Park St", "Maps").copy(detail = "12 min · 4.1 km · 11:04 arrival"),
            act("nav-uturn", IslandMode.NAVIGATION, "Make a U-turn", "0.3 mi", "Maps").copy(detail = "8 min · 2.2 mi"),
            act("nav-round", IslandMode.NAVIGATION, "In 500 ft, at the roundabout take the 2nd exit", "", "Maps"),
            act("nav-keep", IslandMode.NAVIGATION, "1,2 km", "Keep left toward A4", "Maps"),
            act("alarm", IslandMode.ALARM, "7:30 · Wake up", app = "Alarm", actions = listOf("Snooze", "Stop")),
            act("rec", IslandMode.RECORDING, "Screen recording", app = "Recorder", accent = Color.rgb(255, 69, 58)).copy(clockBaseElapsed = now - 12_000, glyph = Glyph.SCREEN),
            act("event", IslandMode.EVENT, "Wi-Fi", "Home Network", "Wi-Fi").copy(glyph = Glyph.WIFI),
            act("dl", IslandMode.DOWNLOAD, "holiday-photos.zip", "48 MB of 106 MB", "Chrome").copy(progress = .45f),
            act("ringer", IslandMode.RINGER, "Silent").copy(ringer = RingerState.SILENT),
            act("home", IslandMode.HOME, "Home"),
        )

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val deadline = SystemClock.uptimeMillis() + 15_000L
            var found = false
            while (!found && SystemClock.uptimeMillis() < deadline) {
                scenario.onActivity { found = findIsland(it.window.decorView)?.isAttachedToWindow == true }
                SystemClock.sleep(100)
            }
            assertTrue("Preview never attached", found)
            val only = InstrumentationRegistry.getArguments().getString("only")
            for (look in IslandLook.values()) for (a in shots) for (p in listOf(Presentation.COMPACT, Presentation.EXPANDED)) {
                if (only != null && only.split(',').none { a.key.startsWith(it) }) continue
                scenario.onActivity {
                    val view = findIsland(it.window.decorView)!!
                    view.applyFeatures(IslandFeatureSet(look = look))
                    view.setSecondary(null)
                    view.show(a, p)
                }
                SystemClock.sleep(1800)
                scenario.onActivity {
                    val view = findIsland(it.window.decorView)!!
                    val bounds = RectF().also(view::islandBounds)
                    val full = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                    val c = Canvas(full); c.drawColor(Color.rgb(28, 28, 30)); view.draw(c)
                    val pad = 24
                    val l = (bounds.left.toInt() - pad).coerceAtLeast(0); val t = (bounds.top.toInt() - pad).coerceAtLeast(0)
                    val r = (bounds.right.toInt() + pad).coerceAtMost(full.width); val b = (bounds.bottom.toInt() + pad).coerceAtMost(full.height)
                    if (r > l && b > t) save("tour-${look.name.lowercase()}-${a.key}-${p.name.lowercase()}-${view.presentation.name.lowercase()}",
                        Bitmap.createBitmap(full, l, t, r - l, b - t))
                }
            }
        }
    }
}
