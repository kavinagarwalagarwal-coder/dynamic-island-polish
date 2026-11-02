package com.dynamicdroid.app

import android.content.Intent
import android.media.AudioManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.KeyEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Holding a volume key keeps stepping, and ringer changes show the ringer island. */
@RunWith(AndroidJUnit4::class)
class VolumeAndRingerTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation = instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private val context = instrumentation.targetContext
    private val audio = context.getSystemService(AudioManager::class.java)

    private fun shell(command: String) = automation.executeShellCommand(command).let {
        ParcelFileDescriptor.AutoCloseInputStream(it).use { input -> String(input.readBytes()) }
    }

    private fun waitFor(timeoutMs: Long, check: () -> Boolean): Boolean {
        val until = SystemClock.uptimeMillis() + timeoutMs
        do {
            var done = false
            instrumentation.runOnMainSync { done = check() }
            if (done) return true
            Thread.sleep(60)
        } while (SystemClock.uptimeMillis() < until)
        return false
    }

    private fun transient(): LiveActivity? {
        val service = IslandBus.listener as? IslandOverlayService ?: return null
        return service.javaClass.getDeclaredField("transient").apply { isAccessible = true }.get(service) as LiveActivity?
    }

    private var keyCalls = 0

    /**
     * Keys typed by the test tool skip the accessibility key filter (only real buttons go through
     * it), so hand the service the same events a real volume button would.
     */
    private fun key(action: Int) {
        var taken = false
        instrumentation.runOnMainSync {
            taken = IslandAccessibilityService.instance!!.deliverKey(KeyEvent(action, KeyEvent.KEYCODE_VOLUME_UP))
        }
        assertTrue("The island should take the volume key: " + state(), taken)
    }

    private fun state(): String {
        val service = IslandBus.listener as? IslandOverlayService
        val island = service?.javaClass?.getDeclaredField("island")?.apply { isAccessible = true }?.get(service)
        val locked = context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked
        return "music=${audio.getStreamVolume(AudioManager.STREAM_MUSIC)} ring=${audio.getStreamVolume(AudioManager.STREAM_RING)} " +
            "keyCalls=$keyCalls island=${island != null} locked=$locked transient=${transient()?.mode} " +
            "a11y=${IslandAccessibilityService.instance != null} audioMode=${audio.mode}"
    }

    @Test fun heldVolumeKeyKeepsSteppingAndRingerChangesShow() {
        shell("appops set com.dynamicdroid.app SYSTEM_ALERT_WINDOW allow")
        val startVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val startRinger = audio.ringerMode
        ActivityScenario.launch(MainActivity::class.java).use {
            try {
                shell("settings put secure enabled_accessibility_services com.dynamicdroid.app/com.dynamicdroid.app.IslandAccessibilityService")
                shell("settings put secure accessibility_enabled 1")
                assertTrue("Overlay service not attached", waitFor(20_000) {
                    IslandBus.listener is IslandOverlayService && IslandAccessibilityService.instance != null
                })
                shell("input keyevent KEYCODE_HOME")
                Thread.sleep(800)
                // Count presses the island handles, without changing what happens.
                instrumentation.runOnMainSync {
                    val real = IslandAccessibilityService.onVolumeKey
                    IslandAccessibilityService.onVolumeKey = { up -> keyCalls++; real?.invoke(up) == true }
                }

                // One press: one step.
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, 1, 0)
                key(KeyEvent.ACTION_DOWN); key(KeyEvent.ACTION_UP)
                if (!waitFor(2_000) { audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 2 }) fail("A single press should step once: " + state())
                Thread.sleep(600)
                assertEquals("A single press must not repeat", 2, audio.getStreamVolume(AudioManager.STREAM_MUSIC))

                // Held for 1.5 s: keeps going.
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, 1, 0)
                key(KeyEvent.ACTION_DOWN)
                Thread.sleep(1_500)
                key(KeyEvent.ACTION_UP)
                Thread.sleep(300)
                val held = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                assertTrue("Holding volume up only reached $held: " + state(), held >= 6)
                assertEquals("The volume island should be showing", IslandMode.VOLUME, transient()?.mode)

                // Ringer: switching to vibrate shows the ringer island.
                audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
                Thread.sleep(2_500)
                audio.ringerMode = AudioManager.RINGER_MODE_VIBRATE
                assertTrue("Ringer island did not show vibrate", waitFor(3_000) {
                    transient()?.let { it.mode == IslandMode.RINGER && it.ringer == RingerState.VIBRATE } == true
                })
                audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
                assertTrue("Ringer island did not show ring", waitFor(3_000) {
                    transient()?.let { it.mode == IslandMode.RINGER && it.ringer == RingerState.RING } == true
                })
                Thread.sleep(2_500)

                // From the island: the volume island carries the mode buttons; tapping one switches it.
                key(KeyEvent.ACTION_DOWN); key(KeyEvent.ACTION_UP)
                assertTrue("Volume island has no ring/silent buttons: " + state(), waitFor(2_000) {
                    transient()?.let { it.mode == IslandMode.VOLUME && it.ringer == RingerState.RING } == true
                })
                instrumentation.runOnMainSync { (IslandBus.listener as IslandOverlayService).onRingerMode(RingerState.VIBRATE) }
                assertTrue("Island did not switch the phone to vibrate", waitFor(2_000) {
                    audio.ringerMode == AudioManager.RINGER_MODE_VIBRATE &&
                        transient()?.let { it.mode == IslandMode.VOLUME && it.ringer == RingerState.VIBRATE } == true
                })
                instrumentation.runOnMainSync { (IslandBus.listener as IslandOverlayService).onRingerMode(RingerState.RING) }
                assertTrue("Island did not switch the phone back to ring", waitFor(2_000) {
                    audio.ringerMode == AudioManager.RINGER_MODE_NORMAL
                })
            } finally {
                runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, startVolume, 0) }
                runCatching { audio.ringerMode = startRinger }
                shell("settings delete secure enabled_accessibility_services")
                shell("settings put secure accessibility_enabled 0")
                context.stopService(Intent(context, IslandOverlayService::class.java))
                shell("appops set com.dynamicdroid.app SYSTEM_ALERT_WINDOW deny")
            }
        }
    }
}
