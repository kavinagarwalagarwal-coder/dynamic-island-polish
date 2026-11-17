package com.dynamicdroid.app

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The open music card as a night drive: the view through a car's windshield toward a neon city,
 * with the dashboard below. The picture is split into two layers (the world outside, and the car
 * around it), and the moving parts are drawn between and on top of them:
 *
 *  - street lamps and lane dashes stream past in perspective while music plays,
 *  - rain on the glass is pushed up the windshield by the wind,
 *  - the speedometer and rev counter follow the car's speed,
 *  - the tape's reels turn, and the radio's display scrolls the song that's playing.
 *
 * Pausing lets the car roll to a gentle stop and then everything rests (no frames at all).
 * Everything is placed in the picture's own pixel coordinates (1024 x 434), so the overlays
 * line up with the art at any card size.
 */
class MusicDrive(private val res: Resources) {

    private val opts = BitmapFactory.Options().apply { inScaled = false }
    private val bg: Bitmap by lazy { BitmapFactory.decodeResource(res, R.drawable.music_drive_bg, opts) }
    private val fg: Bitmap by lazy { BitmapFactory.decodeResource(res, R.drawable.music_drive_fg, opts) }

    private val bmp = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pixelBmp = Paint()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val path = Path()
    private val glow: Bitmap by lazy {
        val n = 64
        Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888).also { b ->
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.shader = RadialGradient(n / 2f, n / 2f, n / 2f,
                intArrayOf(Color.argb(200, 255, 176, 92), Color.argb(70, 255, 120, 60), Color.argb(0, 255, 90, 60)),
                floatArrayOf(0f, .45f, 1f), Shader.TileMode.CLAMP)
            Canvas(b).drawCircle(n / 2f, n / 2f, n / 2f, p)
        }
    }
    private val scrim = Paint()
    private var scrimH = -1f

    // Where the picture sits in the card (picture pixels → card pixels).
    private var scale = 1f
    private var x0 = 0f
    private var y0 = 0f
    private var cardL = 0f
    private var cardT = 0f

    // Motion. dist is how far the car has driven, in the scene's road units.
    private var speed = 0f
    private var dist = 0f
    private var reel = 0f
    private var clock = 0f
    private var marquee = 0f
    private var lastMs = 0L

    /** True while the car is still moving (rolling to a stop after a pause included). */
    val moving get() = speed > 0.003f

    // Rain on the windshield: x, y, radius.
    private val drops = FloatArray(DROPS * 3)
    private var seed = 12345
    private fun rnd(): Float { seed = seed * 1103515245 + 12345; return ((seed ushr 8) and 0xFFFF) / 65535f }

    init {
        for (i in 0 until DROPS) respawn(i, anywhere = true)
    }

    private fun respawn(i: Int, anywhere: Boolean) {
        drops[i * 3] = 140f + rnd() * 760f
        drops[i * 3 + 1] = if (anywhere) 40f + rnd() * 230f else 200f + rnd() * 80f
        drops[i * 3 + 2] = .7f + rnd() * 1f
    }

    // The radio's display: the song as a tiny pixel bitmap, scrolled behind the display's window.
    private var lcdText: String? = null
    private var lcd: Bitmap? = null
    private val lcdPaint = Paint().apply {
        isAntiAlias = false
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textSize = 10f
        color = Color.rgb(74, 36, 6)
    }
    private val lcdClip = RectF(LCD_L, LCD_T, LCD_R, LCD_B)

    /** Picture x → card x. */
    fun x(sx: Float) = cardL + (sx - x0) * scale
    /** Picture y → card y. */
    fun y(sy: Float) = cardT + (sy - y0) * scale
    /** Picture pixels → card pixels. */
    fun len(s: Float) = s * scale

    /** Height of the card for a given width, so the whole drive fits without letterboxing. */
    fun heightFor(width: Float) = width * (WIN_B - WIN_T) / (WIN_R - WIN_L)

    /**
     * Steps the drive and draws it into [card]. Call once per frame while the card is open.
     * [still] keeps the car parked (reduced motion).
     */
    fun draw(canvas: Canvas, card: RectF, settledW: Float, settledH: Float, alpha: Int, playing: Boolean, still: Boolean, title: String, artist: String) {
        val now = android.os.SystemClock.uptimeMillis()
        val dt = if (lastMs == 0L) 0f else ((now - lastMs) / 1000f).coerceIn(0f, .1f)
        lastMs = now
        val target = if (playing && !still) 1f else 0f
        // Ease toward cruising speed, a little slower to stop than to set off.
        speed += (target - speed) * (1f - exp(-dt / if (target > speed) .9f else .7f))
        if (!moving && target == 0f) speed = 0f
        dist += speed * dt * ROAD_SPEED
        reel += speed * dt * 300f
        clock += speed * dt
        if (playing && !still) marquee += dt * 16f
        stepRain(dt)

        // Zoom is fixed by the card's settled size, so while the card opens the drive is revealed
        // around its middle rather than stretched or zoomed.
        val sw = max(settledW, 1f); val sh0 = max(settledH, 1f)
        scale = max(sw / (WIN_R - WIN_L), sh0 / (WIN_B - WIN_T))
        val midX = ((WIN_L + WIN_R) / 2f).coerceIn(sw / scale / 2f, W - sw / scale / 2f)
        val midY = (WIN_B - sh0 / scale / 2f).coerceIn(sh0 / scale / 2f, H - sh0 / scale / 2f)
        x0 = midX - card.width() / scale / 2f
        y0 = midY - card.height() / scale / 2f
        cardL = card.left; cardT = card.top

        val save = canvas.save()
        canvas.clipRect(card)
        canvas.translate(card.left, card.top)
        canvas.scale(scale, scale)
        canvas.translate(-x0, -y0)
        bmp.alpha = alpha
        canvas.drawBitmap(bg, 0f, 0f, bmp)
        drawStars(canvas, alpha)
        drawLamps(canvas, alpha)
        drawDashes(canvas, alpha)
        drawRain(canvas, alpha)
        // The car shakes a touch on the road; the world outside doesn't.
        val bob = if (speed > 0f) (sin(clock * 23f) * .35f + sin(clock * 9.7f) * .25f) * speed else 0f
        canvas.translate(0f, bob)
        canvas.drawBitmap(fg, 0f, 0f, bmp)
        drawGauges(canvas, alpha)
        drawReels(canvas, alpha)
        drawLcd(canvas, alpha, title, artist)
        canvas.restoreToCount(save)

        // A soft shade under the song's name at the top of the card.
        val sh = card.height() * .42f
        if (sh != scrimH) {
            scrimH = sh
            scrim.shader = LinearGradient(0f, 0f, 0f, sh, Color.argb(150, 6, 2, 16), Color.argb(0, 6, 2, 16), Shader.TileMode.CLAMP)
        }
        scrim.alpha = alpha
        canvas.save()
        canvas.translate(card.left, card.top)
        canvas.drawRect(0f, 0f, card.width(), sh, scrim)
        canvas.restore()
    }

    private fun px(x: Float, z: Float) = VX + F * x / z
    private fun py(y: Float, z: Float) = VY + F * y / z

    private fun drawStars(canvas: Canvas, alpha: Int) {
        for (i in STARS.indices step 2) {
            val tw = .5f + .5f * sin(clock * (2.3f + (i % 5) * .7f) * 3f + i * 1.7f)
            if (tw < .55f) continue
            val a = ((tw - .55f) / .45f * 200f * alpha / 255f).toInt()
            val sx = STARS[i]; val sy = STARS[i + 1]
            fill.color = Color.argb(a, 236, 220, 255)
            canvas.drawCircle(sx, sy, .9f, fill)
            fill.color = Color.argb(a / 3, 200, 170, 255)
            canvas.drawRect(sx - 2.5f, sy - .4f, sx + 2.5f, sy + .4f, fill)
            canvas.drawRect(sx - .4f, sy - 2.5f, sx + .4f, sy + 2.5f, fill)
        }
    }

    private val glowRect = RectF()

    private fun drawLamps(canvas: Canvas, alpha: Int) {
        val phase = dist % LAMP_GAP
        for (k in LAMPS downTo 1) {
            val z = k * LAMP_GAP - phase
            if (z < 3f) continue
            val fade = ((LAMPS * LAMP_GAP - z) / 260f).coerceIn(0f, 1f) * (alpha / 255f)
            if (fade <= 0.01f) continue
            val a = (255 * fade).toInt()
            val poleX = px(LAMP_X, z)
            val top = py(LAMP_TOP, z)
            val w = max(.8f, 240f / z)
            // Light pool on the wet road under the lamp.
            val gx = px(LAMP_HEAD + .3f, z); val gy = py(1f, z)
            glowRect.set(gx - 2600f / z, gy - 260f / z, gx + 2600f / z, gy + 260f / z)
            bmp.alpha = (a * .55f).toInt()
            canvas.drawBitmap(glow, null, glowRect, bmp)
            // Pole and arm.
            stroke.strokeWidth = w
            stroke.color = Color.argb(a, 58, 26, 34)
            canvas.drawLine(poleX, py(1f, z), poleX, top, stroke)
            stroke.strokeWidth = max(.6f, w * .4f)
            stroke.color = Color.argb(a, 150, 78, 50)
            canvas.drawLine(poleX + w * .25f, py(1f, z), poleX + w * .25f, top, stroke)
            path.rewind()
            path.moveTo(poleX, top)
            path.quadTo(poleX, py(LAMP_ARM, z), px(LAMP_X + .9f, z), py(LAMP_ARM, z))
            path.lineTo(px(LAMP_HEAD, z), py(LAMP_ARM, z))
            stroke.strokeWidth = max(.7f, 150f / z)
            stroke.color = Color.argb(a, 70, 32, 38)
            canvas.drawPath(path, stroke)
            // Head and halo.
            val hx = px(LAMP_HEAD - .4f, z); val hy = py(LAMP_ARM + .12f, z)
            val r = 1900f / z
            glowRect.set(hx - r, hy - r, hx + r, hy + r)
            bmp.alpha = a
            canvas.drawBitmap(glow, null, glowRect, bmp)
            fill.color = Color.argb(a, 255, 238, 196)
            val hw = 600f / z; val hh = max(.7f, 120f / z)
            canvas.drawRoundRect(hx - hw, hy - hh, hx + hw, hy + hh, hh, hh, fill)
        }
        bmp.alpha = alpha
    }

    private fun drawDashes(canvas: Canvas, alpha: Int) {
        val phase = dist % DASH_GAP
        for (lane in LANES) {
            var k = 0
            while (true) {
                val near = k * DASH_GAP - phase + 2f
                k++
                if (near > DASH_FAR) break
                if (near < 1.5f) continue
                val far = near + DASH_LEN
                val fade = ((DASH_FAR - near) / 45f).coerceIn(0f, 1f)
                val a = (205 * fade * alpha / 255f).toInt()
                if (a < 3) continue
                val wN = 36f / near; val wF = 36f / far
                path.rewind()
                path.moveTo(px(lane, near) - wN, py(1f, near))
                path.lineTo(px(lane, near) + wN, py(1f, near))
                path.lineTo(px(lane, far) + wF, py(1f, far))
                path.lineTo(px(lane, far) - wF, py(1f, far))
                path.close()
                fill.color = Color.argb(a, 206, 194, 214)
                canvas.drawPath(path, fill)
            }
        }
    }

    private fun stepRain(dt: Float) {
        if (speed <= 0f || dt <= 0f) return
        for (i in 0 until DROPS) {
            val r = drops[i * 3 + 2]
            drops[i * 3 + 1] -= speed * dt * (26f + 30f * r)
            drops[i * 3] += (drops[i * 3] - VX) / 400f * speed * dt * 22f
            if (drops[i * 3 + 1] < 30f || drops[i * 3] < 60f || drops[i * 3] > 970f) respawn(i, anywhere = false)
        }
    }

    private fun drawRain(canvas: Canvas, alpha: Int) {
        val f = alpha / 255f
        for (i in 0 until DROPS) {
            val x = drops[i * 3]; val y = drops[i * 3 + 1]; val r = drops[i * 3 + 2]
            if (speed > .05f) {
                stroke.strokeWidth = r * .9f
                stroke.color = Color.argb((40 * speed * f).toInt(), 210, 200, 240)
                canvas.drawLine(x, y, x - (x - VX) / 400f * 3f * speed, y + 6f * r * speed, stroke)
            }
            fill.color = Color.argb((70 * f).toInt(), 200, 190, 235)
            canvas.drawCircle(x, y, r, fill)
            fill.color = Color.argb((150 * f).toInt(), 240, 236, 255)
            canvas.drawCircle(x - r * .3f, y - r * .3f, r * .35f, fill)
        }
    }

    private fun needle(canvas: Canvas, cx: Float, cy: Float, length: Float, deg: Float, alpha: Int) {
        val rad = deg * PI.toFloat() / 180f
        val c = cos(rad); val s = sin(rad)
        stroke.strokeWidth = 1.6f
        stroke.color = Color.argb(alpha, 255, 116, 46)
        canvas.drawLine(cx - c * 3f, cy - s * 3f, cx + c * length, cy + s * length, stroke)
        stroke.strokeWidth = .7f
        stroke.color = Color.argb(alpha, 255, 214, 150)
        canvas.drawLine(cx, cy, cx + c * length * .9f, cy + s * length * .9f, stroke)
        fill.color = Color.argb(alpha, 44, 22, 18)
        canvas.drawCircle(cx, cy, 2.4f, fill)
        fill.color = Color.argb(alpha, 160, 90, 50)
        canvas.drawCircle(cx, cy, 1.1f, fill)
    }

    private fun drawGauges(canvas: Canvas, alpha: Int) {
        val wobble = sin(clock * 6.1f) * 1.4f + sin(clock * 2.3f) * 1.1f
        val kmh = speed * 92f + wobble * speed
        needle(canvas, SPEEDO_X, SPEEDO_Y, 23f, DIAL_START + kmh * 1.5f, alpha)
        val rpm = .8f + speed * 2.3f + wobble * .06f * speed
        needle(canvas, TACHO_X, TACHO_Y, 24f, DIAL_START + rpm * 30f, alpha)
    }

    private fun drawReels(canvas: Canvas, alpha: Int) {
        stroke.strokeWidth = 1f
        stroke.color = Color.argb(alpha, 92, 52, 18)
        for (cx in REELS) {
            for (j in 0 until 3) {
                val rad = (reel + j * 120f) * PI.toFloat() / 180f
                canvas.drawLine(cx + cos(rad) * 1.1f, REEL_Y + sin(rad) * 1.1f, cx + cos(rad) * 3.4f, REEL_Y + sin(rad) * 3.4f, stroke)
            }
            fill.color = Color.argb(alpha, 92, 52, 18)
            canvas.drawCircle(cx, REEL_Y, 1f, fill)
        }
    }

    private fun drawLcd(canvas: Canvas, alpha: Int, title: String, artist: String) {
        val text = listOf(title, artist).filter { it.isNotBlank() }.joinToString("  ·  ").uppercase()
        if (text != lcdText) {
            lcdText = text
            lcd?.recycle()
            val w = max(1, lcdPaint.measureText(text).toInt() + 2)
            lcd = Bitmap.createBitmap(min(w, 2048), 12, Bitmap.Config.ARGB_8888).also {
                Canvas(it).drawText(text, 1f, 9.5f, lcdPaint)
            }
            marquee = 0f
        }
        val b = lcd ?: return
        val room = LCD_R - LCD_L - 4f
        canvas.save()
        canvas.clipRect(lcdClip)
        pixelBmp.alpha = alpha
        val top = (LCD_T + LCD_B) / 2f - 6f
        if (b.width <= room) {
            canvas.drawBitmap(b, (LCD_L + LCD_R - b.width) / 2f, top, pixelBmp)
        } else {
            val loop = b.width + 28f
            val off = marquee % loop
            canvas.drawBitmap(b, LCD_L + 2f - off, top, pixelBmp)
            canvas.drawBitmap(b, LCD_L + 2f - off + loop, top, pixelBmp)
        }
        canvas.restore()
    }

    companion object {
        const val W = 1024f
        const val H = 434f
        // The part of the picture the card shows.
        const val WIN_L = 120f
        const val WIN_R = 900f
        const val WIN_T = 20f
        const val WIN_B = 434f

        // Perspective: vanishing point and focal length, in picture pixels.
        private const val VX = 498f
        private const val VY = 194f
        private const val F = 1000f
        private const val ROAD_SPEED = 26f

        private const val LAMPS = 16
        private const val LAMP_GAP = 40f
        private const val LAMP_X = -8f
        private const val LAMP_TOP = -6.0f
        private const val LAMP_ARM = -6.45f
        private const val LAMP_HEAD = -5.4f

        private val LANES = floatArrayOf(-2.0f, .18f, 2.2f)
        private const val DASH_GAP = 9f
        private const val DASH_LEN = 4f
        private const val DASH_FAR = 95f

        private const val DROPS = 22

        private const val SPEEDO_X = 252f
        private const val SPEEDO_Y = 359f
        private const val TACHO_X = 336f
        private const val TACHO_Y = 361f
        private const val DIAL_START = 148f

        private val REELS = floatArrayOf(544f, 583f)
        private const val REEL_Y = 386f

        private const val LCD_L = 518f
        private const val LCD_T = 345f
        private const val LCD_R = 611f
        private const val LCD_B = 358f

        private val STARS = floatArrayOf(240f, 43f, 811f, 64f, 841f, 43f, 552f, 52f, 703f, 48f, 892f, 74f, 475f, 54f,
            913f, 50f, 441f, 67f, 585f, 75f, 288f, 116f, 207f, 70f, 638f, 49f, 733f, 67f, 763f, 52f, 319f, 41f)

        // Where the island's controls sit on the dashboard, in picture pixels.
        const val BAR_L = 430f
        const val BAR_R = 870f
        const val BAR_Y = 318f
        const val CONTROLS_X = 770f
        const val CONTROLS_GAP = 62f
        const val CONTROLS_Y = 397f
    }
}
