package com.dynamicdroid.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The Dynamic Droid mark: a red camera dot, a white island pill that grows out of it, and a
 * droplet that pinches off the right end, the same story the island tells on the phone.
 * Original artwork drawn in code; [progress] 0..1 scrubs the build-up animation.
 */
class LogoMarkView(context: Context) : View(context) {
    var progress = 1f
        set(value) { field = value.coerceIn(0f, 1f); invalidate() }

    var pillColor = NothingStyle.foreground
    var dotColor = NothingStyle.red

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val neck = Path()
    private var animator: ValueAnimator? = null

    init {
        contentDescription = context.getString(R.string.app_name)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** Plays the build-up from nothing. Honours the system animation scale (0 = instant). */
    fun play(delayMs: Long = 0L, durationMs: Long = 1100L, onEnd: (() -> Unit)? = null) {
        animator?.cancel()
        progress = 0f
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            startDelay = delayMs
            interpolator = LinearInterpolator()
            addUpdateListener { progress = it.animatedValue as Float }
            if (onEnd != null) addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) = onEnd()
            })
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        // Artwork lives in a 100 x 100 box, centred and scaled to fit.
        val s = min(w, h) / 100f
        canvas.save()
        canvas.translate((w - 100f * s) / 2f, (h - 100f * s) / 2f)
        canvas.scale(s, s)
        drawMark(canvas, progress)
        canvas.restore()
    }

    private fun drawMark(canvas: Canvas, p: Float) {
        val cy = 50f
        val pillCx = 42f
        val fullW = 70f
        val fullH = 30f
        // 1. Camera dot pops in.
        val dotT = seg(p, 0f, .2f)
        // 2. Pill grows out from the dot with a soft overshoot.
        val pillT = spring(seg(p, .12f, .62f))
        // 3. Droplet stretches out and pinches off.
        val dropT = seg(p, .48f, .9f)

        val pillW = lerp(fullH * .5f, fullW, pillT)
        val pillH = lerp(fullH * .5f, fullH, min(1f, pillT * 1.4f))
        if (pillT > 0f) {
            fill.color = pillColor
            fill.alpha = (255 * min(1f, pillT * 3f)).toInt()
            canvas.drawRoundRect(pillCx - pillW / 2f, cy - pillH / 2f, pillCx + pillW / 2f, cy + pillH / 2f,
                pillH / 2f, pillH / 2f, fill)
        }
        if (dropT > 0f) {
            val pillRight = pillCx + pillW / 2f
            val r = 11f * spring(dropT).coerceAtLeast(.2f)
            val travel = lerp(pillRight - r * .8f, 89f, easeOut(dropT))
            fill.color = pillColor
            fill.alpha = 255
            // Neck: a pinched bridge that thins out and snaps as the droplet leaves.
            val neckStrength = (1f - seg(dropT, .45f, .8f)) * min(1f, dropT * 4f)
            if (neckStrength > .02f) {
                val half = pillH * .3f * neckStrength
                val x1 = pillRight - 4f
                val x2 = travel - r * .6f
                val mid = (x1 + x2) / 2f
                neck.reset()
                neck.moveTo(x1, cy - half)
                neck.cubicTo(mid, cy - half * .15f, mid, cy - half * .15f, x2, cy - half)
                neck.lineTo(x2, cy + half)
                neck.cubicTo(mid, cy + half * .15f, mid, cy + half * .15f, x1, cy + half)
                neck.close()
                canvas.drawPath(neck, fill)
            }
            canvas.drawCircle(travel, cy, r, fill)
        }
        if (dotT > 0f) {
            // The dot keeps its spot as the camera hole inside the pill.
            val pop = if (dotT < 1f) 1f + .25f * sin(dotT * PI.toFloat()) else 1f
            fill.color = dotColor
            fill.alpha = 255
            canvas.drawCircle(pillCx, cy, 5.5f * min(1f, dotT * 1.6f) * pop, fill)
        }
    }

    private fun seg(p: Float, from: Float, to: Float) = ((p - from) / (to - from)).coerceIn(0f, 1f)
    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
    private fun easeOut(t: Float) = 1f - (1f - t) * (1f - t) * (1f - t)
    /** Under-damped step response: rises past 1, settles back, like the island's springs. */
    private fun spring(t: Float): Float {
        if (t <= 0f) return 0f
        if (t >= 1f) return 1f
        return (1f - exp(-6f * t) * kotlin.math.cos(9f * t)).let { max(0f, it) }
    }
}
