package com.dynamicdroid.app

import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Renders every newer island state in the Playground preview and keeps a screenshot of each. */
@RunWith(AndroidJUnit4::class)
class FeatureIslandRenderTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun findIsland(view: View): IslandView? {
        if (view is IslandView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findIsland(view.getChildAt(i))?.let { return it }
        return null
    }

    private fun capture(name: String) {
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: error("No emulator screenshot")
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

    @Test fun newIslandsRenderInThePreview() {
        val context = instrumentation.targetContext
        val intent = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val reply = ReplyAction("Reply", intent, RemoteInput.Builder("reply").setLabel("Message").build())
        fun activity(key: String, mode: IslandMode, title: String, subtitle: String, app: String = "Test",
                     progress: Float? = null, actions: List<String> = emptyList(), callState: CallState? = null,
                     lost: Boolean = false, withReply: Boolean = false) =
            LiveActivity(key, "test.$key", mode, title, subtitle, app, null, null, Color.WHITE,
                actions = actions.map { IslandAction(it, intent) }, progress = progress, callState = callState,
                lost = lost, reply = if (withReply) reply else null)

        val timer = LiveActivity("timer", "test.clock", IslandMode.TIMER, "Tea", "", "Clock", null, null, Color.YELLOW,
            chronometerCountDown = true, clockBaseElapsed = SystemClock.elapsedRealtime() + 240_000)
        val music = LiveActivity("music", "test.music", IslandMode.MUSIC, "Midnight City", "M83", "Music", null, null,
            Color.WHITE, isPlaying = false)
        val scenes = listOf(
            Triple("feature-volume", activity("volume", IslandMode.VOLUME, "Media", "", progress = .6f), Presentation.EXPANDED),
            Triple("feature-headphones", activity("ear", IslandMode.DEVICE, "Nothing Ear (a)", "Connected", progress = .73f), Presentation.EXPANDED),
            Triple("feature-bt-lost", activity("lost", IslandMode.DEVICE, "Nothing Ear (a)", "Disconnected", lost = true), Presentation.EXPANDED),
            Triple("feature-download-expanded", activity("dl", IslandMode.DOWNLOAD, "holiday-photos.zip", "48 MB of 106 MB", "Chrome", .45f), Presentation.EXPANDED),
            Triple("feature-download-compact", activity("dl", IslandMode.DOWNLOAD, "holiday-photos.zip", "48 MB of 106 MB", "Chrome", .45f), Presentation.COMPACT),
            Triple("feature-alarm", activity("alarm", IslandMode.ALARM, "7:30 · Wake up", "", "Alarm", actions = listOf("Snooze", "Dismiss")), Presentation.EXPANDED),
            Triple("feature-calling", activity("calling", IslandMode.CALL, "Mom", "", "Phone", actions = listOf("Hang up"), callState = CallState.CALLING), Presentation.EXPANDED),
            Triple("feature-calling-compact", activity("calling", IslandMode.CALL, "Mom", "", "Phone", callState = CallState.CALLING), Presentation.COMPACT),
            Triple("feature-message-medium", activity("msg", IslandMode.MESSAGE, "Maya", "Liked your photo and left a comment", "Instagram", withReply = true), Presentation.COMPACT),
            Triple("feature-message-reply", activity("msg", IslandMode.MESSAGE, "Maya", "Liked your photo and left a comment", "Instagram", actions = listOf("Like"), withReply = true), Presentation.EXPANDED),
        )
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                fun click(view: View): Boolean {
                    if (view is android.widget.Button && view.text.toString() == "Playground") { view.performClick(); return true }
                    if (view is ViewGroup) for (i in 0 until view.childCount) if (click(view.getChildAt(i))) return true
                    return false
                }
                assertTrue(click(activity.window.decorView))
            }
            instrumentation.waitForIdleSync()
            for ((name, live, presentation) in scenes) {
                scenario.onActivity { activity ->
                    val view = findIsland(activity.window.decorView)!!
                    view.applyFeatures(IslandFeatureSet())
                    view.setSecondary(null)
                    view.show(live, presentation)
                }
                SystemClock.sleep(1300)
                scenario.onActivity { activity ->
                    val view = findIsland(activity.window.decorView)!!
                    assertEquals(name, live.key, view.activity?.key)
                    assertEquals(name, presentation, view.presentation)
                }
                capture(name)
            }
            // Look options: short pill with a coloured, time-left timer bubble, then an outline.
            scenario.onActivity { activity ->
                val view = findIsland(activity.window.decorView)!!
                view.applyFeatures(IslandFeatureSet(shortCompact = true, compactHeightDp = 32f, colouredBubbles = true))
                view.setSecondary(timer)
                view.show(music, Presentation.COMPACT)
            }
            SystemClock.sleep(1500)
            capture("feature-short-coloured-bubble")
            scenario.onActivity { activity ->
                val view = findIsland(activity.window.decorView)!!
                view.applyFeatures(IslandFeatureSet(outline = OutlineMode.ALWAYS, compactHeightDp = 40f))
                view.setSecondary(timer)
                view.show(music, Presentation.COMPACT)
            }
            SystemClock.sleep(1500)
            capture("feature-outline-tall")
        }
    }
}
