package com.dynamicdroid.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat

/**
 * A guided tour. Each page drives a real [IslandView] with synthetic touches, so the island the
 * user watches answers exactly as it will on their phone: tap, hold, swipe up, side swipe, volume.
 * Touching the stage pauses the script so people can try the gesture themselves.
 */
class OnboardingOverlay(
    private val host: MainActivity,
    features: IslandFeatureSet,
    private val connected: Boolean,
    private val onFinish: (openSetup: Boolean) -> Unit,
) : FrameLayout(host) {

    private data class Step(val title: Int, val body: Int)

    private val steps = listOf(
        Step(R.string.onb_meet_title, R.string.onb_meet_body),
        Step(R.string.onb_tap_title, R.string.onb_tap_body),
        Step(R.string.onb_hold_title, R.string.onb_hold_body),
        Step(R.string.onb_up_title, R.string.onb_up_body),
        Step(R.string.onb_side_title, R.string.onb_side_body),
        Step(R.string.onb_volume_title, R.string.onb_volume_body),
        if (connected) Step(R.string.onb_ready_title, R.string.onb_ready_body)
        else Step(R.string.onb_finish_title, R.string.onb_finish_body),
    )

    private val handler = Handler(Looper.getMainLooper())
    private var index = 0
    private var generation = 0
    private var finished = false
    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private fun dpi(v: Float) = (v * density).toInt()
    private val reduced = Motion.reduced(host)

    // ---------------------------------------------------------------- demo island

    private var demo: LiveActivity? = null
    private var waiting: LiveActivity? = null
    private val bounds = RectF()

    private val island = IslandView(host).apply {
        anchorToCutout = false
        applyFit(IslandFit(alignCamera = false))
        applyFeatures(features)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    private val callbacks = object : IslandView.Callbacks {
        override fun onOpen(activity: LiveActivity) = caption(host.getString(R.string.toast_opens, activity.appLabel))
        override fun onExpandRequest() { demo?.let { show(it, Presentation.EXPANDED) } }
        override fun onCollapseRequest() { demo?.let { show(it, Presentation.COMPACT) } }
        override fun onDismiss(activity: LiveActivity) = onCollapseRequest()
        override fun onSwitchActivity() {
            val next = waiting ?: return
            waiting = demo
            island.setSecondary(waiting)
            show(next, Presentation.COMPACT)
        }
        override fun onSecondaryExpand(activity: LiveActivity) {
            waiting = demo
            island.setSecondary(waiting)
            show(activity, Presentation.EXPANDED)
        }
        override fun onMedia(activity: LiveActivity, command: String, positionMs: Long?) {
            if (command == IslandBus.MEDIA_TOGGLE) demo?.let { show(it.copy(isPlaying = !it.isPlaying), island.presentation) }
        }
        override fun onVolumeChange(fraction: Float) {
            demo?.takeIf { it.mode == IslandMode.VOLUME }?.let { show(it.copy(progress = fraction), Presentation.EXPANDED) }
        }
    }

    private fun show(a: LiveActivity?, p: Presentation) {
        demo = a
        island.show(a, if (a == null) Presentation.IDLE else p)
    }

    // ---------------------------------------------------------------- views

    private val finger = FingerView(host)
    private val captionView = TextView(host).apply {
        textSize = 13f; setTextColor(NothingStyle.background); typeface = NothingStyle.body
        setPadding(dpi(14f), dpi(7f), dpi(14f), dpi(7f))
        background = GradientDrawable().apply { setColor(NothingStyle.foreground); cornerRadius = dp(16f) }
        alpha = 0f
    }
    private val bigLogo = LogoMarkView(host).apply { visibility = GONE }
    private val stage = object : FrameLayout(host) {
        // A real touch on the stage pauses the script and lets the person try it.
        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) userTouched()
            return false
        }
    }.apply {
        background = GradientDrawable().apply { setColor(NothingStyle.surface); cornerRadius = dp(28f) }
        clipToOutline = true
        addView(island, LayoutParams(-1, -1))
        addView(bigLogo, LayoutParams(dpi(150f), dpi(150f), Gravity.CENTER))
        addView(captionView, LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dpi(16f) })
        addView(finger, LayoutParams(dpi(FingerView.SIZE_DP), dpi(FingerView.SIZE_DP)))
    }
    private val counter = text(13f, NothingStyle.muted)
    private val title = text(26f, NothingStyle.foreground).apply {
        typeface = android.graphics.Typeface.create(NothingStyle.body, android.graphics.Typeface.BOLD)
        ViewCompat.setAccessibilityHeading(this, true)
    }
    private val body = text(16f, NothingStyle.muted).apply { setLineSpacing(0f, 1.15f) }
    private val tryHint = text(13f, NothingStyle.muted).apply { text = host.getString(R.string.onb_try) }
    private val textBlock = LinearLayout(host).apply {
        orientation = LinearLayout.VERTICAL
        addView(title, LinearLayout.LayoutParams(-1, -2))
        addView(body, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(10f) })
        addView(tryHint, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(14f) })
    }
    private val dots = PageDots(host, steps.size)
    private val back = flatButton(host.getString(R.string.onb_back)) { back() }
    private val next = Button(host).apply {
        isAllCaps = false; textSize = 16f; typeface = NothingStyle.body
        setTextColor(NothingStyle.background); minHeight = dpi(52f); minWidth = dpi(132f)
        background = GradientDrawable().apply { setColor(NothingStyle.foreground); cornerRadius = dp(26f) }
        setOnClickListener { advance() }
        Motion.springPress(this)
    }
    private val skip = flatButton(host.getString(R.string.onb_skip)) { finish(false) }

    private val column = LinearLayout(host).apply {
        orientation = LinearLayout.VERTICAL
        val top = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(LogoMarkView(host).also { logo -> if (!reduced) logo.play(200L, 900L) }, LinearLayout.LayoutParams(dpi(34f), dpi(34f)))
            addView(View(host), LinearLayout.LayoutParams(0, 1, 1f))
            addView(counter, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dpi(8f) })
            addView(skip, LinearLayout.LayoutParams(-2, -2))
        }
        addView(top, LinearLayout.LayoutParams(-1, -2))
        addView(stage, LinearLayout.LayoutParams(-1, dpi(290f)).apply { topMargin = dpi(18f) })
        addView(textBlock, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(26f) })
        addView(View(host), LinearLayout.LayoutParams(-1, 0, 1f))
        addView(dots, LinearLayout.LayoutParams(-1, dpi(14f)).apply { bottomMargin = dpi(18f) })
        val bottom = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(back, LinearLayout.LayoutParams(-2, -2))
            addView(View(host), LinearLayout.LayoutParams(0, 1, 1f))
            addView(next, LinearLayout.LayoutParams(-2, -2))
        }
        addView(bottom, LinearLayout.LayoutParams(-1, -2))
    }

    init {
        setBackgroundColor(NothingStyle.background)
        isClickable = true // nothing underneath reacts while the tour is up
        addView(column, LayoutParams(-1, -1))
        island.callbacks = callbacks
        padFor(null)
        bind()
    }

    /** Keep the sheet clear of the status bar, cutout and navigation bar. */
    @Suppress("DEPRECATION")
    private fun padFor(insets: android.view.WindowInsets?) {
        val side = dpi(22f)
        val l = insets?.systemWindowInsetLeft ?: 0
        val t = maxOf(insets?.systemWindowInsetTop ?: 0, insets?.stableInsetTop ?: 0)
        val r = insets?.systemWindowInsetRight ?: 0
        val b = maxOf(insets?.systemWindowInsetBottom ?: 0, insets?.stableInsetBottom ?: 0)
        column.setPadding(side + l, dpi(14f) + t, side + r, dpi(18f) + b)
    }

    override fun onApplyWindowInsets(insets: android.view.WindowInsets): android.view.WindowInsets {
        padFor(insets)
        return super.onApplyWindowInsets(insets)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Insets may already have been dispatched before the sheet was added; read them directly.
        padFor(rootWindowInsets)
    }

    /** Entrance: the sheet fades up, then its parts arrive one after another. */
    fun enter() {
        alpha = 0f
        animate().alpha(1f).setDuration(if (reduced) 0L else 260L).start()
        Motion.staggerIn((0 until column.childCount).map { column.getChildAt(it) }, host, 80L, 55L, 18f)
        runStep()
    }

    fun back() {
        if (index > 0) go(index - 1) else finish(false)
    }

    private fun advance() {
        if (index < steps.lastIndex) go(index + 1) else finish(openSetup = !connected)
    }

    fun finish(openSetup: Boolean) {
        if (finished) return
        finished = true
        generation++
        handler.removeCallbacksAndMessages(null)
        animate().alpha(0f).scaleX(.97f).scaleY(.97f).setDuration(if (reduced) 0L else 240L).withEndAction {
            (parent as? ViewGroup)?.removeView(this)
            onFinish(openSetup)
        }.start()
    }

    override fun onDetachedFromWindow() {
        generation++
        handler.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }

    private fun go(to: Int) {
        if (to !in steps.indices || to == index) return
        val dir = if (to > index) 1f else -1f
        index = to
        dots.moveTo(index)
        if (reduced) { bind(); runStep(); return }
        textBlock.animate().alpha(0f).translationX(-dir * dp(28f)).setDuration(130L).withEndAction {
            bind()
            textBlock.translationX = dir * dp(28f)
            textBlock.animate().alpha(1f).translationX(0f).setDuration(300L).setInterpolator(DecelerateInterpolator(2f)).start()
        }.start()
        runStep()
    }

    private fun bind() {
        val step = steps[index]
        title.text = host.getString(step.title)
        body.text = host.getString(step.body)
        counter.text = host.getString(R.string.onb_step, index + 1, steps.size)
        back.visibility = if (index == 0) INVISIBLE else VISIBLE
        val last = index == steps.lastIndex
        next.text = host.getString(when {
            !last -> R.string.onb_next
            connected -> R.string.common_done
            else -> R.string.onb_done_setup
        })
        skip.visibility = if (last) INVISIBLE else VISIBLE
        tryHint.visibility = if (index in 1..4) VISIBLE else GONE
    }

    // ---------------------------------------------------------------- scripted demos

    private fun at(delay: Long, block: () -> Unit) {
        val g = generation
        handler.postDelayed({ if (g == generation && isAttachedToWindow && !finished) block() }, delay)
    }

    private fun userTouched() {
        generation++
        finger.hide()
        at(5_000L) { runStep() }
    }

    private fun runStep() {
        generation++
        finger.hide()
        captionView.animate().alpha(0f).setDuration(120L).start()
        val music = host.demoMusic(true)
        bigLogo.visibility = if (index == steps.lastIndex) VISIBLE else GONE
        island.visibility = if (index == steps.lastIndex) INVISIBLE else VISIBLE
        if (index != 4) { waiting = null; island.setSecondary(null) }
        when (index) {
            0 -> {
                show(null, Presentation.IDLE)
                at(650L) { show(music, Presentation.COMPACT) }
                at(2_500L) { show(music, Presentation.EXPANDED) }
                at(4_400L) { show(music, Presentation.COMPACT) }
                at(6_000L) { runStep() }
            }
            1 -> {
                show(music, Presentation.COMPACT)
                at(600L) { aimAtIsland() }
                at(1_250L) { tap() }
                at(3_400L) { runStep() }
            }
            2 -> {
                show(music, Presentation.COMPACT)
                at(600L) { aimAtIsland() }
                at(1_250L) { press(true) }
                at(1_950L) { press(false) }
                at(2_300L) { finger.hide() }
                at(4_000L) { show(music, Presentation.COMPACT) }
                at(4_900L) { runStep() }
            }
            3 -> {
                show(music, Presentation.EXPANDED)
                at(900L) { aimAtIsland(.5f, .22f) }
                at(1_500L) { drag(0f, -dp(90f)) }
                at(3_600L) { runStep() }
            }
            4 -> {
                waiting = host.demoTimer()
                island.setSecondary(waiting)
                show(music, Presentation.COMPACT)
                at(900L) { aimAtIsland(.32f, .5f) }
                at(1_500L) { drag(dp(110f), 0f) }
                at(3_900L) { aimAtIsland(.32f, .5f) }
                at(4_500L) { drag(dp(110f), 0f) }
                at(6_800L) { runStep() }
            }
            5 -> {
                var level = .3f
                show(host.demoVolume(level), Presentation.EXPANDED)
                for (i in 1..5) at(500L + i * 230L) { level += .08f; show(host.demoVolume(level), Presentation.EXPANDED) }
                for (i in 1..3) at(2_000L + i * 230L) { level -= .08f; show(host.demoVolume(level), Presentation.EXPANDED) }
                at(3_200L) { show(host.demoDevice(false), Presentation.EXPANDED) }
                at(5_000L) { show(host.demoAlarm(), Presentation.EXPANDED) }
                at(7_000L) { runStep() }
            }
            else -> {
                show(null, Presentation.IDLE)
                if (!reduced) bigLogo.play(150L, 1_100L) else bigLogo.progress = 1f
            }
        }
    }

    private fun aimAtIsland(fx: Float = .5f, fy: Float = .5f) {
        island.islandBounds(bounds)
        if (bounds.isEmpty) return
        finger.moveTo(bounds.left + bounds.width() * fx, bounds.top + bounds.height() * fy, reduced)
    }

    // Synthetic touches go straight to the island, so they never trip the stage's pause.
    private var downAt = 0L
    private fun touch(action: Int, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) downAt = now
        val e = MotionEvent.obtain(downAt, now, action, x, y, 0)
        island.dispatchTouchEvent(e)
        e.recycle()
    }

    private fun tap() {
        press(true)
        at(120L) { press(false) }
    }

    private fun press(down: Boolean) {
        finger.press(down)
        touch(if (down) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_UP, finger.centerX, finger.centerY)
    }

    /** Down, a smooth slide by (dx, dy), up: fast enough to count as a swipe. */
    private fun drag(dx: Float, dy: Float) {
        val x0 = finger.centerX; val y0 = finger.centerY
        finger.press(true)
        touch(MotionEvent.ACTION_DOWN, x0, y0)
        val frames = 10
        for (i in 1..frames) at(i * 22L) {
            val t = i / frames.toFloat()
            val x = x0 + dx * t; val y = y0 + dy * t
            finger.jumpTo(x, y)
            touch(MotionEvent.ACTION_MOVE, x, y)
        }
        at(frames * 22L + 30L) {
            touch(MotionEvent.ACTION_UP, x0 + dx, y0 + dy)
            finger.press(false)
            at(260L) { finger.hide() }
        }
    }

    private fun caption(text: String) {
        captionView.text = text
        captionView.animate().cancel()
        captionView.alpha = 0f; captionView.translationY = dp(8f)
        captionView.animate().alpha(1f).translationY(0f).setDuration(220L).withEndAction {
            captionView.animate().alpha(0f).setStartDelay(1_300L).setDuration(260L).start()
        }.start()
    }

    // ---------------------------------------------------------------- small views

    private fun text(size: Float, color: Int) = TextView(host).apply {
        textSize = size; setTextColor(color); typeface = NothingStyle.body
    }

    private fun flatButton(label: String, click: () -> Unit) = Button(host).apply {
        text = label; isAllCaps = false; textSize = 15f; typeface = NothingStyle.body
        setTextColor(NothingStyle.foreground); minHeight = dpi(48f); minWidth = dpi(48f)
        background = null
        contentDescription = label
        setOnClickListener { click() }
        Motion.springPress(this)
    }

    /** A soft fingertip that glides to targets and ripples when it presses. */
    private class FingerView(context: Context) : View(context) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE }
        private var pressed = 0f
        private var ripple = 0f
        private var animator: ValueAnimator? = null
        private val half get() = width / 2f
        val centerX get() = translationX + half
        val centerY get() = translationY + half

        init { alpha = 0f; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

        fun moveTo(x: Float, y: Float, instant: Boolean) {
            if (alpha < .05f) { jumpTo(x, y); animate().alpha(1f).setDuration(180L).start(); return }
            animate().translationX(x - half).translationY(y - half).alpha(1f)
                .setDuration(if (instant) 0L else 420L).setInterpolator(DecelerateInterpolator(2.2f)).start()
        }

        fun jumpTo(x: Float, y: Float) { translationX = x - half; translationY = y - half }

        fun hide() { animate().cancel(); animate().alpha(0f).setDuration(200L).start() }

        fun press(down: Boolean) {
            animator?.cancel()
            animator = ValueAnimator.ofFloat(pressed, if (down) 1f else 0f).apply {
                duration = if (down) 110L else 260L
                interpolator = if (down) DecelerateInterpolator() else OvershootInterpolator(2f)
                addUpdateListener { pressed = it.animatedValue as Float; invalidate() }
                start()
            }
            if (down) ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 420L
                addUpdateListener { ripple = it.animatedValue as Float; invalidate() }
                start()
            }
        }

        override fun onDraw(canvas: Canvas) {
            val r = half * .42f * (1f - .18f * pressed)
            fill.alpha = (200 + 40 * pressed).toInt()
            canvas.drawCircle(half, half, r, fill)
            if (ripple in .001f..0.999f) {
                ring.strokeWidth = half * .08f
                ring.alpha = ((1f - ripple) * 180).toInt()
                canvas.drawCircle(half, half, r + (half - r) * ripple, ring)
            }
        }

        companion object { const val SIZE_DP = 56f }
    }

    /** Page dots; the current one stretches into a pill and slides between positions. */
    private class PageDots(context: Context, private val count: Int) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var position = 0f
        private var animator: ValueAnimator? = null
        private val d = context.resources.displayMetrics.density

        fun moveTo(target: Int) {
            animator?.cancel()
            animator = ValueAnimator.ofFloat(position, target.toFloat()).apply {
                duration = 360L
                interpolator = OvershootInterpolator(1.4f)
                addUpdateListener { position = it.animatedValue as Float; invalidate() }
                start()
            }
        }

        override fun onDraw(canvas: Canvas) {
            val dot = 6f * d; val gap = 8f * d; val active = 20f * d
            val total = (count - 1) * (dot + gap) + active
            var x = (width - total) / 2f
            val cy = height / 2f
            for (i in 0 until count) {
                val weight = (1f - kotlin.math.abs(position - i)).coerceIn(0f, 1f)
                val w = dot + (active - dot) * weight
                paint.color = androidx.core.graphics.ColorUtils.blendARGB(NothingStyle.line, NothingStyle.foreground, weight)
                canvas.drawRoundRect(x, cy - dot / 2f, x + w, cy + dot / 2f, dot / 2f, dot / 2f, paint)
                x += w + gap
            }
        }
    }
}
