package com.dynamicdroid.app

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import java.util.Calendar
import kotlin.math.max
import kotlin.math.sin

/**
 * The home card (tap the resting island) as a mountain valley that follows the real time of day:
 * a sunny day, a moonlit night, and a slow fade between them at dawn and dusk.
 *
 * Small living touches on top of the picture: the river glitters, the sun or moon breathes,
 * stars twinkle, a flock of birds crosses the sky now and then by day, fireflies drift by the
 * trees at night, and once in a while a shooting star. Everything is placed in the picture's own
 * pixel coordinates (1024 x 434).
 */
class HomeScene(private val res: Resources) {

    private val opts = BitmapFactory.Options().apply { inScaled = false }
    private val day: Bitmap by lazy { BitmapFactory.decodeResource(res, R.drawable.home_day, opts) }
    private val night: Bitmap by lazy { BitmapFactory.decodeResource(res, R.drawable.home_night, opts) }

    private val bmp = Paint(Paint.FILTER_BITMAP_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val topShade = Paint()
    private val bottomShade = Paint()
    private var shadeFor = -1f
    private val sunGlow: Bitmap by lazy { glowBitmap(Color.argb(150, 255, 255, 236)) }
    private val moonGlow: Bitmap by lazy { glowBitmap(Color.argb(120, 170, 210, 255)) }
    private val r = RectF()

    private fun glowBitmap(c: Int): Bitmap {
        val n = 64
        return Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888).also { b ->
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.shader = RadialGradient(n / 2f, n / 2f, n / 2f, intArrayOf(c, Color.argb(0, Color.red(c), Color.green(c), Color.blue(c))),
                null, Shader.TileMode.CLAMP)
            Canvas(b).drawCircle(n / 2f, n / 2f, n / 2f, p)
        }
    }

    /**
     * How much of the night picture shows: 0 by day, 1 at night, fading over an hour around
     * sunrise (5:30 to 6:30) and an hour and a half around sunset (17:30 to 19:00).
     */
    fun nightness(cal: Calendar = Calendar.getInstance()): Float {
        val h = cal.get(Calendar.HOUR_OF_DAY) + cal.get(Calendar.MINUTE) / 60f
        return when {
            h < 5.5f -> 1f
            h < 6.5f -> 1f - (h - 5.5f)
            h < 17.5f -> 0f
            h < 19f -> (h - 17.5f) / 1.5f
            else -> 1f
        }.coerceIn(0f, 1f)
    }

    fun draw(canvas: Canvas, card: RectF, settledW: Float, settledH: Float, alpha: Int, still: Boolean) {
        val t = if (still) 0f else (SystemClock.uptimeMillis() % 3_600_000L) / 1000f
        val n = nightness()
        // Fixed zoom from the settled card, so opening reveals the valley instead of stretching it.
        val sw = max(settledW, 1f); val sh = max(settledH, 1f)
        val scale = max(sw / W, sh / H)
        val x0 = W / 2f - card.width() / scale / 2f
        val y0 = H / 2f - card.height() / scale / 2f

        val save = canvas.save()
        canvas.clipRect(card)
        canvas.translate(card.left, card.top)
        canvas.scale(scale, scale)
        canvas.translate(-x0, -y0)
        if (n < 1f) {
            bmp.alpha = alpha
            canvas.drawBitmap(day, 0f, 0f, bmp)
        }
        if (n > 0f) {
            bmp.alpha = (alpha * n).toInt()
            canvas.drawBitmap(night, 0f, 0f, bmp)
        }
        if (n < 1f) drawDay(canvas, t, (alpha * (1f - n)).toInt())
        if (n > 0f) drawNight(canvas, t, (alpha * n).toInt())
        canvas.restoreToCount(save)

        // Shade behind the time at the top and the shortcuts at the bottom, so they always read.
        val h = card.height()
        if (h != shadeFor) {
            shadeFor = h
            topShade.shader = LinearGradient(0f, 0f, 0f, h * .55f, Color.argb(140, 4, 10, 24), Color.argb(0, 4, 10, 24), Shader.TileMode.CLAMP)
            bottomShade.shader = LinearGradient(0f, h * .55f, 0f, h, Color.argb(0, 4, 10, 24), Color.argb(150, 4, 10, 24), Shader.TileMode.CLAMP)
        }
        canvas.save()
        canvas.translate(card.left, card.top)
        topShade.alpha = alpha
        bottomShade.alpha = alpha
        canvas.drawRect(0f, 0f, card.width(), h * .55f, topShade)
        canvas.drawRect(0f, h * .55f, card.width(), h, bottomShade)
        canvas.restore()
    }

    private fun glitter(canvas: Canvas, pts: FloatArray, t: Float, alpha: Int, rgb: Int) {
        for (i in pts.indices step 2) {
            val k = i / 2
            val s = sin(t * (1.6f + (k % 7) * .35f) + k * 2.1f)
            if (s < .6f) continue
            val a = ((s - .6f) / .4f * alpha).toInt()
            fill.color = (a shl 24) or (rgb and 0xFFFFFF)
            val x = pts[i] + sin(t * .8f + k) * 1.5f
            canvas.drawRect(x - 1.5f, pts[i + 1] - .6f, x + 1.5f, pts[i + 1] + .6f, fill)
        }
    }

    private fun drawDay(canvas: Canvas, t: Float, alpha: Int) {
        if (alpha < 3) return
        // The sun breathes.
        val g = 70f + 8f * sin(t * .9f)
        r.set(SUN_X - g, SUN_Y - g, SUN_X + g, SUN_Y + g)
        bmp.alpha = (alpha * (.55f + .15f * sin(t * .9f))).toInt()
        canvas.drawBitmap(sunGlow, null, r, bmp)
        glitter(canvas, DAY_RIVER, t, alpha, 0xFFFFFF)
        // Now and then a small flock crosses the sky, right to left, about every 40 seconds.
        val cycle = t % 40f
        if (cycle < 22f) {
            val p = cycle / 22f
            val bx = 1080f - p * 1200f
            val by = 120f + sin(p * 6f) * 10f
            stroke.color = Color.argb(alpha * 190 / 255, 50, 60, 80)
            stroke.strokeWidth = 1.6f
            for (j in 0 until 5) {
                val x = bx + j * 13f + (j % 2) * 4f
                val y = by + (if (j % 2 == 0) 0f else 7f) + j * 1.5f
                val flap = sin(t * 9f + j) * 3f
                canvas.drawLine(x - 4f, y - flap, x, y, stroke)
                canvas.drawLine(x, y, x + 4f, y - flap, stroke)
            }
        }
    }

    private fun drawNight(canvas: Canvas, t: Float, alpha: Int) {
        if (alpha < 3) return
        val g = 72f + 6f * sin(t * .7f)
        r.set(MOON_X - g, MOON_Y - g, MOON_X + g, MOON_Y + g)
        bmp.alpha = (alpha * (.45f + .15f * sin(t * .7f))).toInt()
        canvas.drawBitmap(moonGlow, null, r, bmp)
        // Stars twinkle.
        for (i in STARS.indices step 2) {
            val k = i / 2
            val s = sin(t * (1.1f + (k % 5) * .5f) + k * 1.9f)
            if (s < .3f) continue
            val a = ((s - .3f) / .7f * alpha).toInt()
            val x = STARS[i]; val y = STARS[i + 1]
            fill.color = Color.argb(a, 230, 240, 255)
            canvas.drawCircle(x, y, 1.3f, fill)
            fill.color = Color.argb(a / 3, 200, 220, 255)
            canvas.drawRect(x - 4f, y - .5f, x + 4f, y + .5f, fill)
            canvas.drawRect(x - .5f, y - 4f, x + .5f, y + 4f, fill)
        }
        glitter(canvas, NIGHT_RIVER, t, alpha, 0xD8E8FF)
        // Fireflies by the trees.
        for (k in 0 until 7) {
            val fx = FIREFLY_X[k] + sin(t * .45f + k * 2.3f) * 18f
            val fy = 360f + sin(t * .6f + k * 1.4f) * 22f + k * 6f
            val on = .5f + .5f * sin(t * 2.2f + k * 1.7f)
            val a = (alpha * on * on).toInt()
            fill.color = Color.argb(a / 3, 210, 255, 120)
            canvas.drawCircle(fx, fy, 4f, fill)
            fill.color = Color.argb(a, 240, 255, 170)
            canvas.drawCircle(fx, fy, 1.4f, fill)
        }
        // A shooting star about every 14 seconds, quick and faint.
        val c = t % 14f
        if (c < 1.1f) {
            val p = c / 1.1f
            val sx = 260f + (k(t) % 5) * 90f + p * 220f
            val sy = 30f + p * 70f
            val a = (alpha * sin(p * Math.PI.toFloat())).toInt()
            stroke.strokeWidth = 1.5f
            stroke.color = Color.argb(a, 235, 245, 255)
            canvas.drawLine(sx - 40f, sy - 13f, sx, sy, stroke)
            fill.color = Color.argb(a, 255, 255, 255)
            canvas.drawCircle(sx, sy, 1.6f, fill)
        }
    }

    private fun k(t: Float) = (t / 14f).toInt()

    companion object {
        const val W = 1024f
        const val H = 434f
        private const val SUN_X = 708f
        private const val SUN_Y = 70f
        private const val MOON_X = 512f
        private const val MOON_Y = 86f
        private val FIREFLY_X = floatArrayOf(120f, 230f, 300f, 700f, 780f, 880f, 960f)
        private val STARS = floatArrayOf(674f, 64f, 949f, 42f, 812f, 56f, 702f, 14f, 372f, 42f, 701f, 77f, 610f, 55f,
            176f, 59f, 207f, 26f, 307f, 20f, 395f, 18f, 721f, 107f, 680f, 132f, 570f, 17f)
        private val DAY_RIVER = floatArrayOf(575f, 289f, 637f, 308f, 601f, 309f, 649f, 310f, 535f, 312f, 602f, 320f,
            568f, 322f, 554f, 324f, 520f, 328f, 486f, 333f, 398f, 338f, 409f, 341f, 473f, 342f, 414f, 342f, 520f, 343f,
            507f, 350f, 463f, 352f, 530f, 353f, 412f, 355f, 557f, 357f, 477f, 359f, 468f, 360f, 527f, 362f, 507f, 363f,
            559f, 364f, 580f, 372f, 506f, 376f, 597f, 377f, 632f, 377f, 596f, 380f, 592f, 383f, 538f, 383f, 533f, 384f,
            604f, 389f, 636f, 397f, 573f, 398f, 586f, 398f, 718f, 401f, 540f, 405f, 571f, 405f, 626f, 406f, 489f, 407f,
            553f, 409f, 668f, 409f, 632f, 410f, 720f, 412f, 518f, 413f, 634f, 414f, 529f, 416f, 609f, 417f, 475f, 419f,
            499f, 420f, 563f, 424f, 592f, 424f, 585f, 426f, 496f, 426f, 554f, 430f)
        private val NIGHT_RIVER = floatArrayOf(408f, 252f, 656f, 252f, 646f, 253f, 461f, 253f, 432f, 253f, 568f, 254f,
            511f, 256f, 644f, 258f, 490f, 258f, 658f, 258f, 585f, 259f, 551f, 259f, 381f, 260f, 539f, 260f, 501f, 262f,
            504f, 263f, 469f, 265f, 611f, 265f, 638f, 267f, 513f, 267f, 639f, 269f, 477f, 273f, 508f, 280f, 494f, 283f,
            549f, 283f, 516f, 284f, 626f, 293f, 593f, 296f, 500f, 306f, 491f, 307f, 481f, 314f, 536f, 318f, 513f, 319f,
            503f, 323f, 479f, 324f, 508f, 339f, 485f, 339f, 525f, 340f, 545f, 344f, 535f, 345f, 526f, 345f, 615f, 349f,
            577f, 349f, 595f, 350f, 597f, 354f, 549f, 359f, 538f, 359f, 572f, 360f, 529f, 364f, 463f, 369f, 530f, 371f,
            475f, 372f, 482f, 373f, 495f, 376f, 485f, 377f, 469f, 378f, 427f, 378f, 469f, 379f, 443f, 380f)
    }
}
