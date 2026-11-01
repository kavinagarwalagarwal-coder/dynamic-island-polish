package com.dynamicdroid.app

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat

/**
 * Exists only to own an accessibility overlay window.
 *
 * Normal "display over other apps" windows sit *below* the status bar, so taps at the very top
 * of the screen go to the system and pull down the notification shade. Accessibility overlays
 * sit *above* the status bar, so the island gets its own touches there. It reads no screen content.
 *
 * It also filters key events so the volume keys can drive the volume island instead of the system
 * slider. Only the two volume keys are ever looked at; every other key passes straight through.
 */
class IslandAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        onChanged?.invoke()
        // Bring the island up if it isn't running yet.
        runCatching { ContextCompat.startForegroundService(this, Intent(this, IslandOverlayService::class.java)) }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Package metadata only. Never access event text or retrieve window content.
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: return
            // Ignore events from our non-focusable overlay; allow our actual settings activity.
            if (pkg == packageName && event.className?.toString() != MainActivity::class.java.name) return
            onForegroundPackage?.invoke(pkg)
        }
    }
    override fun onInterrupt() = Unit

    /** Keys whose press we took, so their release is consumed too and never reaches the system. */
    private val consumed = HashSet<Int>()

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN) return false
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                val handled = onVolumeKey?.invoke(code == KeyEvent.KEYCODE_VOLUME_UP) == true
                if (handled) consumed += code else consumed -= code
                handled
            }
            KeyEvent.ACTION_UP -> consumed.remove(code)
            else -> code in consumed
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        release()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        release()
        super.onDestroy()
    }

    private fun release() {
        if (instance === this) {
            instance = null
            onChanged?.invoke()
        }
    }

    companion object {
        @Volatile var instance: IslandAccessibilityService? = null
            private set

        /** Set by the overlay service so it can move its window when this connects/disconnects. */
        var onChanged: (() -> Unit)? = null
        var onForegroundPackage: ((String) -> Unit)? = null
        /** Set by the overlay service; returns true when the island handled the key. */
        var onVolumeKey: ((up: Boolean) -> Boolean)? = null

        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val me = ComponentName(context, IslandAccessibilityService::class.java)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
