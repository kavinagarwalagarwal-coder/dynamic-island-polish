package com.dynamicdroid.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
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
 * sit *above* the status bar, so the island gets its own touches there.
 *
 * It also filters key events so the volume keys can drive the volume island instead of the system
 * slider. Only the two volume keys are ever looked at; every other key passes straight through.
 *
 * With "Hide the phone's pop-up notifications" on, it also swipes the system's own pop-up away
 * the moment the island shows the same notification. Swiping a pop-up up only tucks it into the
 * shade, so nothing is lost. For that it looks at System UI's windows only, only right after a new
 * notification, and only for the title the island is already showing.
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

    private val handler = Handler(Looper.getMainLooper())
    private var popupHunt: Runnable? = null

    /**
     * Looks for System UI's pop-up showing [title] for a short while and swipes it up, out of the way.
     * Pop-ups appear a moment after the notification is posted, so this polls briefly.
     */
    fun swipeAwayPopup(title: String, text: String) {
        if (title.length < 2 && text.length < 2) return
        popupHunt?.let(handler::removeCallbacks)
        val deadline = SystemClock.uptimeMillis() + POPUP_SEARCH_MS
        popupHunt = object : Runnable {
            override fun run() {
                if (runCatching { tryDismissPopup(title, text) }.getOrDefault(true)) { popupHunt = null; return }
                if (SystemClock.uptimeMillis() < deadline) handler.postDelayed(this, 90L) else popupHunt = null
            }
        }.also { handler.postDelayed(it, 60L) }
    }

    /** True when done: the pop-up was swiped away, or there is nothing sensible to do. */
    private fun tryDismissPopup(title: String, text: String): Boolean {
        // The lock screen lists notifications instead of popping them up; never touch it.
        if (getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true) return true
        val metrics = resources.displayMetrics
        val maxTop = metrics.heightPixels * 0.3f
        val minCard = 48f * metrics.density
        for (window in windows) {
            val root = window.root ?: continue
            if (root.packageName?.toString() != SYSTEM_UI) continue
            // findAccessibilityNodeInfosByText misses Compose-drawn System UI, so walk the tree.
            val needles = listOf(title, text.take(40)).filter { it.length >= 2 }
            for (hit in nodesWithText(root, needles)) {
                val bounds = Rect().also(hit::getBoundsInScreen)
                if (bounds.isEmpty || bounds.top > maxTop) continue
                val card = cardBounds(hit, metrics.heightPixels * 0.35f)
                // Pop-up cards are a few lines tall; the status bar itself is not.
                if (card.height() < minCard || card.top > maxTop) continue
                swipeUp(card)
                return true
            }
        }
        return false
    }

    private fun nodesWithText(root: AccessibilityNodeInfo, needles: List<String>): List<AccessibilityNodeInfo> {
        val found = ArrayList<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var visited = 0
        while (queue.isNotEmpty() && visited++ < 600) {
            val node = queue.removeFirst()
            val label = (node.text ?: node.contentDescription)?.toString()
            if (label != null && needles.any { label.contains(it, ignoreCase = true) }) found += node
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
        }
        return found
    }

    /** The largest ancestor that still looks like one notification card, not the whole panel. */
    private fun cardBounds(node: AccessibilityNodeInfo, maxHeight: Float): Rect {
        var best = Rect().also(node::getBoundsInScreen)
        var parent = node.parent
        while (parent != null) {
            val r = Rect().also(parent::getBoundsInScreen)
            if (r.height() > maxHeight) break
            best = r
            parent = parent.parent
        }
        return best
    }

    private fun swipeUp(card: Rect) {
        // Off to the side, so the gesture never lands on the island in the middle.
        val x = (card.left + card.width() * 0.12f).coerceAtLeast(1f)
        val path = Path().apply {
            moveTo(x, card.exactCenterY())
            lineTo(x, 1f)
        }
        // The island's own window spans the top of the screen; let the swipe pass through it.
        beforePopupSwipe?.invoke(450L)
        handler.postDelayed({
            dispatchGesture(GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, 120L)).build(), null, null)
        }, 48L)
    }

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
        popupHunt?.let(handler::removeCallbacks)
        popupHunt = null
        if (instance === this) {
            instance = null
            onChanged?.invoke()
        }
    }

    companion object {
        private const val SYSTEM_UI = "com.android.systemui"
        private const val POPUP_SEARCH_MS = 1500L

        @Volatile var instance: IslandAccessibilityService? = null
            private set

        /** Set by the overlay service so it can move its window when this connects/disconnects. */
        var onChanged: (() -> Unit)? = null
        var onForegroundPackage: ((String) -> Unit)? = null
        /** Set by the overlay service; returns true when the island handled the key. */
        /** Set by the overlay service: make the island window touch-through for this many ms. */
        var beforePopupSwipe: ((Long) -> Unit)? = null
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
