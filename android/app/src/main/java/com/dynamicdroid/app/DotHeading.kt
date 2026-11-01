package com.dynamicdroid.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * Original dot-matrix lettering, generated locally rather than a proprietary brand font.
 * [reveal] sweeps the dots in from left to right, each one popping as it lands.
 */
class DotHeading(context: Context, private val text: String = "DYNAMIC") : View(context) {
    private val dots = ArrayList<FloatArray>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = NothingStyle.foreground }
    private var animator: ValueAnimator? = null
    var color: Int
        get() = paint.color
        set(value) { paint.color = value; invalidate() }
    var reveal = 1f
        set(value) { field = value.coerceIn(0f, 1f); invalidate() }

    init { contentDescription = context.getString(R.string.app_name) }

    fun play(delayMs: Long = 0L, durationMs: Long = 700L) {
        animator?.cancel()
        reveal = 0f
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs; startDelay = delayMs; interpolator = LinearInterpolator()
            addUpdateListener { reveal = it.animatedValue as Float }
            start()
        }
    }

    override fun onDetachedFromWindow() { animator?.cancel(); super.onDetachedFromWindow() }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w <= 0 || h <= 0) return
        dots.clear()
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val type = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = NothingStyle.mono
            textSize = 32f * resources.displayMetrics.density
            isFakeBoldText = true
        }
        if (type.measureText(text) > w) type.textSize *= w / type.measureText(text)
        if (type.textSize > h * .9f) type.textSize = h * .9f
        val left = (w - type.measureText(text)) / 2f
        val baseline = (h - type.fontMetrics.bottom - type.fontMetrics.top) / 2f
        canvas.drawText(text, left, baseline, type)
        val step = (3f * resources.displayMetrics.density).toInt().coerceAtLeast(2)
        for (y in step / 2 until h step step) for (x in step / 2 until w step step) {
            if (Color.alpha(bitmap.getPixel(x, y)) > 100) dots.add(floatArrayOf(x.toFloat(), y.toFloat(), x.toFloat() / w))
        }
        bitmap.recycle()
    }

    override fun onDraw(canvas: Canvas) {
        val radius = resources.displayMetrics.density * 0.85f
        val alpha = paint.alpha
        for (d in dots) {
            // Each dot lands a little after the sweep passes it, with a brief overshoot.
            val t = ((reveal * 1.3f - d[2]) / .3f).coerceIn(0f, 1f)
            if (t <= 0f) continue
            val pop = if (t < 1f) t * (1f + .6f * (1f - t)) else 1f
            paint.alpha = (alpha * t).toInt()
            canvas.drawCircle(d[0], d[1], radius * pop, paint)
        }
        paint.alpha = alpha
    }
}
