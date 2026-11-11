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
 * With "Hide during videos and photos" on, it looks at the class names and sizes of the views in
 * the app on screen (never their text) to notice a video or photo filling the screen.
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
        // Package metadata only; never event text. Window content is read only by the two
        // features documented on this class (pop-ups, and full-screen video and photos).
        event ?: return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || pkg == SYSTEM_UI) {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                pkg == packageName && event.className?.toString() == MainActivity::class.java.name) {
                onForegroundPackage?.invoke(pkg); reportImmersive(false)
            }
            return
        }
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                ensureIsland()
                onForegroundPackage?.invoke(pkg)
                scheduleMediaCheck(150L)
                // Screen changes animate; look again once the new screen has settled.
                if (mediaThreadLazy.isInitialized()) {
                    mediaHandler.removeCallbacks(mediaSettleCheck)
                    mediaHandler.postDelayed(mediaSettleCheck, 900L)
                }
            }
            // Swiping to the next Short / Reel, or a viewer opening inside the same screen.
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> scheduleMediaCheck(250L)
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ->
                if (SystemClock.uptimeMillis() - lastMediaCheck > CONTENT_CHECK_EVERY_MS) scheduleMediaCheck(300L)
        }
    }

    private var lastIslandStart = 0L

    /** If Android ever stopped the island while we kept running, bring it back. */
    private fun ensureIsland() {
        if (IslandBus.listener != null) return
        val now = SystemClock.uptimeMillis()
        if (now - lastIslandStart < 10_000L) return
        lastIslandStart = now
        runCatching { ContextCompat.startForegroundService(this, Intent(this, IslandOverlayService::class.java)) }
    }

    // ---------------------------------------------------------------- full-screen video and photos

    /** Checks run off the main thread: walking another app's views costs a few binder calls. */
    private val mediaThreadLazy = lazy { android.os.HandlerThread("island-media").apply { start() } }
    private val mediaHandler by lazy { Handler(mediaThreadLazy.value.looper) }
    @Volatile private var lastMediaCheck = 0L
    private var immersive = false
    private val mediaCheck = Runnable { checkImmersiveMedia() }
    private val mediaSettleCheck = Runnable { checkImmersiveMedia() }
    /** While hidden, keep confirming: a static screen sends no events to say the video is gone. */
    private val mediaStillThere = Runnable { checkImmersiveMedia() }

    private fun scheduleMediaCheck(delay: Long) {
        // Nothing to hide (feature off, or no island showing): skip the work entirely.
        if (wantsMediaCheck?.invoke() != true) { mediaDebug = "not wanted"; reportImmersive(false); return }
        mediaHandler.removeCallbacks(mediaCheck)
        mediaHandler.postDelayed(mediaCheck, delay)
    }

    /** What the last check saw, for test failure messages only. */
    @Volatile internal var mediaDebug = "never checked"

    private fun checkImmersiveMedia() {
        lastMediaCheck = SystemClock.uptimeMillis()
        val wanted = wantsMediaCheck?.invoke() == true
        val found = wanted && runCatching { findMediaSurface() }.getOrDefault(false)
        handler.post { reportImmersive(found) }
        mediaHandler.removeCallbacks(mediaStillThere)
        if (found) mediaHandler.postDelayed(mediaStillThere, STILL_THERE_MS)
    }

    private fun findMediaSurface(): Boolean {
        val root = rootInActiveWindow ?: run { mediaDebug = "no active window"; return false }
        val pkg = root.packageName?.toString() ?: return false
        val seen = StringBuilder(pkg)
        if (pkg == packageName || pkg == SYSTEM_UI) return false
        val metrics = resources.displayMetrics
        val screenW = metrics.widthPixels.toFloat()
        val screenArea = screenW * metrics.heightPixels
        val imageApp = imageApps.getOrPut(pkg) {
            runCatching { packageManager.getApplicationInfo(pkg, 0).category == android.content.pm.ApplicationInfo.CATEGORY_IMAGE }
                .getOrDefault(false)
        }
        val r = Rect()
        val stack = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var visited = 0
        while (stack.isNotEmpty() && visited++ < MEDIA_NODE_LIMIT) {
            val node = stack.removeLast()
            if (!node.isVisibleToUser) continue
            node.getBoundsInScreen(r)
            val area = r.width().toFloat() * r.height() / screenArea
            if (seen.length < 400) seen.append(' ').append(node.className?.toString()?.substringAfterLast('.')).append('@').append((area * 100).toInt())
            mediaDebug = seen.toString()
            if (ImmersiveMedia.isMediaSurface(node.className?.toString().orEmpty(), area, r.width() / screenW, imageApp)) return true
            // Small containers can't hold a full-screen surface; don't look inside them.
            if (!ImmersiveMedia.worthDescending(area)) continue
            for (i in 0 until node.childCount) node.getChild(i)?.let(stack::add)
        }
        return false
    }

    private val imageApps = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    private fun reportImmersive(on: Boolean) {
        if (on == immersive) return
        immersive = on
        onImmersiveMedia?.invoke(on)
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
        lastPopupCard = null
        swipes = 0
        val deadline = SystemClock.uptimeMillis() + POPUP_SEARCH_MS
        popupHunt = object : Runnable {
            override fun run() {
                if (runCatching { tryDismissPopup(title, text) }.getOrDefault(true)) { popupHunt = null; return }
                // After a swipe, keep watching a little longer in case it didn't take.
                val until = if (swipes > 0) maxOf(deadline, lastSwipeAt + POPUP_RETRY_MS * 2) else deadline
                if (SystemClock.uptimeMillis() < until) handler.postDelayed(this, 90L) else popupHunt = null
            }
        }.also { handler.postDelayed(it, 60L) }
    }

    private var lastPopupCard: Rect? = null
    private var swipes = 0
    private var lastSwipeAt = 0L

    /** True when done: the pop-up was swiped away, or there is nothing sensible to do. */
    private fun tryDismissPopup(title: String, text: String): Boolean {
        // The lock screen lists notifications instead of popping them up; never touch it.
        if (getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true) return true
        var seen = false
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
                // Wait until the pop-up has finished sliding in: a swipe on a card that's still
                // moving can be read by System UI as a pull on the shade instead of a dismissal.
                if (card != lastPopupCard) { lastPopupCard = card; return false }
                seen = true
                // A swipe that didn't take (System UI was busy, or the island's window was still
                // in the way) gets another try, a moment later; at most three in all.
                val now = SystemClock.uptimeMillis()
                if (swipes == 0 || now - lastSwipeAt > POPUP_RETRY_MS) {
                    if (swipes >= 3) return true
                    swipes++; lastSwipeAt = now
                    swipeUp(card)
                }
                return false
            }
        }
        // Gone after our swipe: done. Not seen yet: keep looking.
        if (!seen && swipes > 0) return true
        return false
    }

    private fun nodesWithText(root: AccessibilityNodeInfo, needles: List<String>): List<AccessibilityNodeInfo> {
        val found = ArrayList<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var visited = 0
        while (queue.isNotEmpty() && visited++ < 1200) {
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
        // Give WindowManager time to apply that before the finger lands (slow phones relayout late),
        // and keep it through a possible retry.
        beforePopupSwipe?.invoke(POPUP_RETRY_MS + 500L)
        handler.postDelayed({
            dispatchGesture(GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, 120L)).build(), null, null)
        }, 140L)
    }

    /** Keys whose press we took, so their release is consumed too and never reaches the system. */
    private val consumed = HashSet<Int>()

    /**
     * Holding a volume key keeps stepping. Android only generates key repeats after a key reaches
     * the app side, and we take the press before that, so the repeat is ours to make.
     */
    private var repeatingUp: Boolean? = null
    private val volumeRepeat = object : Runnable {
        override fun run() {
            val up = repeatingUp ?: return
            if (onVolumeKey?.invoke(up) == true) handler.postDelayed(this, VOLUME_REPEAT_MS) else repeatingUp = null
        }
    }

    private fun stopVolumeRepeat() {
        repeatingUp = null
        handler.removeCallbacks(volumeRepeat)
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN) return false
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                // Any system repeats are already covered by our own.
                if (event.repeatCount > 0) return code in consumed
                val up = code == KeyEvent.KEYCODE_VOLUME_UP
                val handled = onVolumeKey?.invoke(up) == true
                stopVolumeRepeat()
                if (handled) {
                    consumed += code
                    repeatingUp = up
                    handler.postDelayed(volumeRepeat, VOLUME_REPEAT_DELAY_MS)
                } else consumed -= code
                handled
            }
            KeyEvent.ACTION_UP -> {
                if (repeatingUp == (code == KeyEvent.KEYCODE_VOLUME_UP)) stopVolumeRepeat()
                consumed.remove(code)
            }
            else -> code in consumed
        }
    }

    /** Same path as a real volume button; Android only lets the system call [onKeyEvent]. */
    internal fun deliverKey(event: KeyEvent): Boolean = onKeyEvent(event)

    override fun onUnbind(intent: Intent?): Boolean {
        release()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        release()
        if (mediaThreadLazy.isInitialized()) mediaThreadLazy.value.quitSafely()
        super.onDestroy()
    }

    private fun release() {
        stopVolumeRepeat()
        if (mediaThreadLazy.isInitialized()) mediaHandler.removeCallbacksAndMessages(null)
        reportImmersive(false)
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
        private const val POPUP_RETRY_MS = 650L
        private const val CONTENT_CHECK_EVERY_MS = 1500L
        private const val MEDIA_NODE_LIMIT = 400
        private const val STILL_THERE_MS = 2_000L
        /** Like a held key on a keyboard: a short pause, then steady steps. */
        const val VOLUME_REPEAT_DELAY_MS = 380L
        const val VOLUME_REPEAT_MS = 80L

        @Volatile var instance: IslandAccessibilityService? = null
            private set

        /** Set by the overlay service so it can move its window when this connects/disconnects. */
        var onChanged: (() -> Unit)? = null
        var onForegroundPackage: ((String) -> Unit)? = null
        /** Set by the overlay service: a video or photo now fills the screen (or stopped). */
        var onImmersiveMedia: ((Boolean) -> Unit)? = null
        /** Set by the overlay service: whether checking is worth it right now. */
        var wantsMediaCheck: (() -> Boolean)? = null
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
