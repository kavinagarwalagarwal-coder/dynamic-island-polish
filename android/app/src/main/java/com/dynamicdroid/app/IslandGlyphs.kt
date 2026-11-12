package com.dynamicdroid.app

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * Small line pictures for the event, flashlight, sleep and home islands, drawn with the island's
 * own paint (no image assets), centred on (cx, cy) inside a box [s] wide.
 */
object IslandGlyphs {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val oval = RectF()

    fun draw(canvas: Canvas, glyph: Glyph, cx: Float, cy: Float, s: Float, color: Int) {
        stroke.color = color; stroke.strokeWidth = s * 0.085f
        fill.color = color
        val u = s / 2f
        when (glyph) {
            Glyph.WIFI, Glyph.WIFI_OFF -> {
                for (i in 1..3) {
                    val r = u * (0.3f + 0.3f * i)
                    oval.set(cx - r, cy + u * 0.55f - r, cx + r, cy + u * 0.55f + r)
                    canvas.drawArc(oval, -135f, 90f, false, stroke)
                }
                canvas.drawCircle(cx, cy + u * 0.5f, s * 0.07f, fill)
                if (glyph == Glyph.WIFI_OFF) canvas.drawLine(cx - u * 0.8f, cy - u * 0.75f, cx + u * 0.8f, cy + u * 0.75f, stroke)
            }
            Glyph.BATTERY_LOW, Glyph.UNPLUGGED -> {
                val w = s * 0.78f; val h = s * 0.42f
                oval.set(cx - w / 2, cy - h / 2, cx + w / 2 - s * 0.06f, cy + h / 2)
                canvas.drawRoundRect(oval, h * 0.25f, h * 0.25f, stroke)
                canvas.drawRoundRect(cx + w / 2 - s * 0.02f, cy - h * 0.2f, cx + w / 2 + s * 0.05f, cy + h * 0.2f, 2f, 2f, fill)
                if (glyph == Glyph.BATTERY_LOW) {
                    canvas.drawRoundRect(oval.left + s * 0.07f, oval.top + s * 0.07f, oval.left + s * 0.17f, oval.bottom - s * 0.07f, 2f, 2f, fill)
                } else {
                    canvas.drawLine(cx - u * 0.75f, cy - u * 0.7f, cx + u * 0.75f, cy + u * 0.7f, stroke)
                }
            }
            Glyph.PLANE -> {
                path.reset()
                path.moveTo(cx, cy - u * 0.9f)
                path.lineTo(cx, cy + u * 0.55f)
                canvas.drawPath(path, stroke)
                path.reset()
                path.moveTo(cx - u * 0.85f, cy + u * 0.05f); path.lineTo(cx, cy - u * 0.3f); path.lineTo(cx + u * 0.85f, cy + u * 0.05f)
                canvas.drawPath(path, stroke)
                path.reset()
                path.moveTo(cx - u * 0.38f, cy + u * 0.85f); path.lineTo(cx, cy + u * 0.6f); path.lineTo(cx + u * 0.38f, cy + u * 0.85f)
                canvas.drawPath(path, stroke)
            }
            Glyph.USB -> {
                canvas.drawLine(cx, cy - u * 0.85f, cx, cy + u * 0.7f, stroke)
                canvas.drawCircle(cx, cy + u * 0.72f, s * 0.08f, fill)
                canvas.drawLine(cx, cy + u * 0.2f, cx - u * 0.5f, cy - u * 0.15f, stroke)
                canvas.drawLine(cx - u * 0.5f, cy - u * 0.15f, cx - u * 0.5f, cy - u * 0.35f, stroke)
                canvas.drawLine(cx, cy, cx + u * 0.5f, cy - u * 0.35f, stroke)
                canvas.drawRect(cx + u * 0.4f, cy - u * 0.55f, cx + u * 0.6f, cy - u * 0.35f, fill)
                path.reset(); path.moveTo(cx - u * 0.2f, cy - u * 0.6f); path.lineTo(cx, cy - u * 0.95f); path.lineTo(cx + u * 0.2f, cy - u * 0.6f); path.close()
                canvas.drawPath(path, fill)
            }
            Glyph.UNLOCK -> {
                canvas.drawRoundRect(cx - u * 0.62f, cy - u * 0.05f, cx + u * 0.62f, cy + u * 0.8f, s * 0.08f, s * 0.08f, stroke)
                oval.set(cx - u * 0.38f, cy - u * 0.85f, cx + u * 0.38f, cy - u * 0.1f)
                canvas.drawArc(oval, 180f, 160f, false, stroke)
                canvas.drawLine(cx - u * 0.38f, cy - u * 0.47f, cx - u * 0.38f, cy - u * 0.05f, stroke)
            }
            Glyph.BRIGHTNESS -> {
                canvas.drawCircle(cx, cy, u * 0.36f, stroke)
                for (i in 0 until 8) {
                    val a = i * Math.PI.toFloat() / 4f
                    val c = kotlin.math.cos(a); val sn = kotlin.math.sin(a)
                    canvas.drawLine(cx + c * u * 0.62f, cy + sn * u * 0.62f, cx + c * u * 0.88f, cy + sn * u * 0.88f, stroke)
                }
            }
            Glyph.ALARM -> {
                canvas.drawCircle(cx, cy + u * 0.08f, u * 0.68f, stroke)
                canvas.drawLine(cx, cy + u * 0.08f, cx, cy - u * 0.32f, stroke)
                canvas.drawLine(cx, cy + u * 0.08f, cx + u * 0.3f, cy + u * 0.25f, stroke)
                canvas.drawLine(cx - u * 0.75f, cy - u * 0.62f, cx - u * 0.45f, cy - u * 0.88f, stroke)
                canvas.drawLine(cx + u * 0.75f, cy - u * 0.62f, cx + u * 0.45f, cy - u * 0.88f, stroke)
            }
            Glyph.PAY -> {
                canvas.drawRoundRect(cx - u * 0.85f, cy - u * 0.55f, cx + u * 0.85f, cy + u * 0.55f, s * 0.09f, s * 0.09f, stroke)
                canvas.drawLine(cx - u * 0.85f, cy - u * 0.2f, cx + u * 0.85f, cy - u * 0.2f, stroke)
                canvas.drawLine(cx - u * 0.55f, cy + u * 0.25f, cx - u * 0.15f, cy + u * 0.25f, stroke)
            }
            Glyph.TORCH -> {
                path.reset()
                path.moveTo(cx - u * 0.55f, cy - u * 0.85f); path.lineTo(cx + u * 0.55f, cy - u * 0.85f)
                path.lineTo(cx + u * 0.55f, cy - u * 0.5f); path.lineTo(cx + u * 0.28f, cy - u * 0.15f)
                path.lineTo(cx + u * 0.28f, cy + u * 0.88f); path.lineTo(cx - u * 0.28f, cy + u * 0.88f)
                path.lineTo(cx - u * 0.28f, cy - u * 0.15f); path.lineTo(cx - u * 0.55f, cy - u * 0.5f); path.close()
                canvas.drawPath(path, stroke)
                canvas.drawCircle(cx, cy + u * 0.25f, s * 0.06f, fill)
            }
            Glyph.MOON -> {
                path.reset()
                oval.set(cx - u * 0.75f, cy - u * 0.75f, cx + u * 0.75f, cy + u * 0.75f)
                path.addOval(oval, Path.Direction.CW)
                val cut = Path().apply { addCircle(cx + u * 0.42f, cy - u * 0.3f, u * 0.62f, Path.Direction.CW) }
                path.op(cut, Path.Op.DIFFERENCE)
                canvas.drawPath(path, fill)
            }
            Glyph.STOPWATCH -> {
                canvas.drawCircle(cx, cy + u * 0.12f, u * 0.7f, stroke)
                canvas.drawLine(cx, cy + u * 0.12f, cx, cy - u * 0.28f, stroke)
                canvas.drawLine(cx - u * 0.22f, cy - u * 0.85f, cx + u * 0.22f, cy - u * 0.85f, stroke)
                canvas.drawLine(cx, cy - u * 0.85f, cx, cy - u * 0.58f, stroke)
            }
            Glyph.CAMERA -> {
                canvas.drawRoundRect(cx - u * 0.85f, cy - u * 0.5f, cx + u * 0.85f, cy + u * 0.65f, s * 0.1f, s * 0.1f, stroke)
                canvas.drawCircle(cx, cy + u * 0.08f, u * 0.3f, stroke)
                canvas.drawLine(cx - u * 0.3f, cy - u * 0.5f, cx - u * 0.18f, cy - u * 0.75f, stroke)
                canvas.drawLine(cx - u * 0.18f, cy - u * 0.75f, cx + u * 0.18f, cy - u * 0.75f, stroke)
                canvas.drawLine(cx + u * 0.18f, cy - u * 0.75f, cx + u * 0.3f, cy - u * 0.5f, stroke)
            }
        }
    }
}
