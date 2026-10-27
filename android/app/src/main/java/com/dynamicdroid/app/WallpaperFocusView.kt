package com.dynamicdroid.app

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

/** Visible source-photo selector, not a screenshot or screen-content reader. */
class WallpaperFocusView(context: Context) : View(context) {
    var image: Bitmap? = null
        set(value) { field = value; invalidate() }
    var motion = WallpaperMotion()
        set(value) { field = value.safe(); invalidate() }
    var onFocus: ((Float, Float) -> Unit)? = null
    private val bounds = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    init { contentDescription = "Wallpaper motion focus. Drag the circle onto the record; use the position sliders for precise adjustment." }
    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.rgb(19, 19, 19))
        val bitmap = image ?: return
        val scale = min(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
        val w = bitmap.width * scale; val h = bitmap.height * scale
        bounds.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
        paint.color = Color.WHITE; paint.alpha = 255; paint.style = Paint.Style.FILL
        canvas.drawBitmap(bitmap, null, bounds, paint)
        val radius = min(w, h) * motion.radius
        val cx = (bounds.left + w * motion.x).coerceIn(bounds.left + radius, bounds.right - radius)
        val cy = (bounds.top + h * motion.y).coerceIn(bounds.top + radius, bounds.bottom - radius)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 2 * resources.displayMetrics.density
        paint.color = Color.WHITE
        canvas.drawCircle(cx, cy, radius, paint)
        canvas.drawLine(cx - 8, cy, cx + 8, cy, paint)
        canvas.drawLine(cx, cy - 8, cx, cy + 8, paint)
        paint.style = Paint.Style.FILL
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (image == null || bounds.isEmpty) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                onFocus?.invoke(((event.x - bounds.left) / bounds.width()).coerceIn(0f, 1f),
                    ((event.y - bounds.top) / bounds.height()).coerceIn(0f, 1f))
                return true
            }
            MotionEvent.ACTION_UP -> { parent?.requestDisallowInterceptTouchEvent(false); performClick(); return true }
            MotionEvent.ACTION_CANCEL -> { parent?.requestDisallowInterceptTouchEvent(false); return true }
        }
        return false
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
