package com.dynamicdroid.app

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Shared plumbing for the island's picture scenes (all 1024 x 434 pixel-art pictures the user
 * made): fixed zoom from the card's settled size so opening reveals the picture rather than
 * stretching it, picture-pixel coordinates for overlays, and soft shades so text stays readable.
 */
abstract class PictureScene(protected val res: Resources) {
    protected val opts = BitmapFactory.Options().apply { inScaled = false }
    protected val bmp = Paint(Paint.FILTER_BITMAP_FLAG)
    protected val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    protected val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    protected val r = RectF()
    private val shadeTop = Paint()
    private val shadeBottom = Paint()
    private var shadeKey = -1f
    private var saveCount = 0

    protected fun decode(id: Int): Bitmap = BitmapFactory.decodeResource(res, id, opts)

    protected fun glowBitmap(c: Int): Bitmap {
        val n = 64
        return Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888).also { b ->
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.shader = RadialGradient(n / 2f, n / 2f, n / 2f, intArrayOf(c, c and 0x00FFFFFF), null, Shader.TileMode.CLAMP)
            Canvas(b).drawCircle(n / 2f, n / 2f, n / 2f, p)
        }
    }

    /** Seconds on a clock that stands still under reduced motion. */
    protected fun seconds(still: Boolean) = if (still) 0f else (SystemClock.uptimeMillis() % 3_600_000L) / 1000f

    /** Starts drawing in picture pixels inside [card]. Pair with [end]. */
    protected fun begin(canvas: Canvas, card: RectF, settledW: Float, settledH: Float) {
        val sw = max(settledW, 1f); val sh = max(settledH, 1f)
        val scale = max(sw / W, sh / H)
        val x0 = W / 2f - card.width() / scale / 2f
        val y0 = H / 2f - card.height() / scale / 2f
        saveCount = canvas.save()
        canvas.clipRect(card)
        canvas.translate(card.left, card.top)
        canvas.scale(scale, scale)
        canvas.translate(-x0, -y0)
    }

    protected fun end(canvas: Canvas) = canvas.restoreToCount(saveCount)

    /** Dark shades at the top (and optionally the bottom) of the card, behind the island's text. */
    protected fun shade(canvas: Canvas, card: RectF, alpha: Int, top: Float, bottom: Float, strength: Int = 150) {
        val h = card.height()
        val key = h * 31f + top * 7f + bottom + strength
        if (key != shadeKey) {
            shadeKey = key
            shadeTop.shader = LinearGradient(0f, 0f, 0f, h * top, Color.argb(strength, 6, 6, 18), Color.argb(0, 6, 6, 18), Shader.TileMode.CLAMP)
            shadeBottom.shader = LinearGradient(0f, h * (1f - bottom), 0f, h, Color.argb(0, 6, 6, 18), Color.argb(strength, 6, 6, 18), Shader.TileMode.CLAMP)
        }
        canvas.save()
        canvas.translate(card.left, card.top)
        shadeTop.alpha = alpha
        if (top > 0f) canvas.drawRect(0f, 0f, card.width(), h * top, shadeTop)
        shadeBottom.alpha = alpha
        if (bottom > 0f) canvas.drawRect(0f, h * (1f - bottom), card.width(), h, shadeBottom)
        canvas.restore()
    }

    protected fun twinkle(canvas: Canvas, pts: FloatArray, t: Float, alpha: Int, size: Float, rgb: Int) {
        for (i in pts.indices step 2) {
            val k = i / 2
            val s = sin(t * (1.1f + (k % 5) * .45f) + k * 1.9f)
            if (s < .35f) continue
            val a = ((s - .35f) / .65f * alpha).toInt()
            val x = pts[i]; val y = pts[i + 1]
            fill.color = (a shl 24) or (rgb and 0xFFFFFF)
            canvas.drawCircle(x, y, size * .45f, fill)
            fill.color = ((a / 3) shl 24) or (rgb and 0xFFFFFF)
            canvas.drawRect(x - size * 1.4f, y - .45f, x + size * 1.4f, y + .45f, fill)
            canvas.drawRect(x - .45f, y - size * 1.4f, x + .45f, y + size * 1.4f, fill)
        }
    }

    companion object {
        const val W = 1024f
        const val H = 434f
        fun heightFor(width: Float) = width * H / W
    }
}

/**
 * The live timer as an hourglass in the desert at sunset. The sand really is the timer: the top
 * bulb holds what's left, the pile grows as it runs, and a thin stream falls only while it runs
 * (a stopwatch empties the glass once a minute). The sun glows and grains of sand blow over
 * the dunes.
 */
class TimerScene(res: Resources) : PictureScene(res) {
    private val bg by lazy { decode(R.drawable.timer_bg) }
    private val hl by lazy { decode(R.drawable.timer_hl) }
    private val sunGlow by lazy { glowBitmap(Color.argb(140, 255, 214, 120)) }
    private val topArea = FloatArray(NECK_ROW + 1)
    private val bottomRows = HW.size - BOTTOM_ROW
    private var topTotal = 0f
    private var bottomTotal = 0f

    init {
        for (i in 0..NECK_ROW) topTotal += 2f * HW[i]
        for (i in BOTTOM_ROW until HW.size) bottomTotal += 2f * HW[i]
    }

    fun draw(canvas: Canvas, card: RectF, settledW: Float, settledH: Float, alpha: Int, sandTop: Float, running: Boolean, still: Boolean) {
        val t = seconds(still)
        begin(canvas, card, settledW, settledH)
        bmp.alpha = alpha
        canvas.drawBitmap(bg, 0f, 0f, bmp)
        // The sun breathes.
        val g = 92f + 8f * sin(t * .8f)
        r.set(SUN_X - g, SUN_Y - g, SUN_X + g, SUN_Y + g)
        bmp.alpha = (alpha * (.5f + .15f * sin(t * .8f))).toInt()
        canvas.drawBitmap(sunGlow, null, r, bmp)
        twinkle(canvas, STARS, t, alpha, 2.2f, 0xFFE8C8)
        drawSand(canvas, sandTop.coerceIn(0f, 1f), running && !still, t, alpha)
        bmp.alpha = alpha
        canvas.drawBitmap(hl, HL_X, HL_Y, bmp)
        // Wind-blown grains skimming the dunes.
        if (!still) for (k in 0 until 14) {
            val speed = 26f + (k % 4) * 9f
            val x = ((t * speed + k * 97f) % 1180f) - 80f
            val y = 300f + (k * 37 % 110) + sin(t * 1.3f + k) * 5f
            fill.color = Color.argb(alpha * (90 + (k % 3) * 40) / 255, 255, 206, 140)
            canvas.drawRect(x, y, x + 2.2f, y + 1.2f, fill)
        }
        end(canvas)
        shade(canvas, card, alpha, .5f, .42f, 135)
    }

    private fun row(canvas: Canvas, i: Int, w: Float, c: Int) {
        val y = TOP_Y + i
        canvas.drawRect(CX - w, y.toFloat(), CX + w, y + 1f, fill.apply { color = c })
    }

    private fun drawSand(canvas: Canvas, top: Float, running: Boolean, t: Float, alpha: Int) {
        val fa = alpha
        val base = Color.argb(fa, 248, 158, 58)
        val dark = Color.argb(fa, 238, 140, 48)
        val light = Color.argb(fa, 255, 214, 128)
        // Top bulb: sand fills up from the neck to the level that holds what's left.
        var want = top * topTotal * .9f
        var surface = NECK_ROW + 1
        while (surface > 0 && want > 0f) { surface--; want -= 2f * HW[surface] }
        if (top > 0.002f) {
            for (i in surface..NECK_ROW) {
                val c = when { i == surface -> light; (i - surface) % 4 == 3 -> dark; else -> base }
                // A small dip in the middle where the sand drains.
                val w = HW[i]
                row(canvas, i, w, c)
            }
            if (running) {
                fill.color = Color.argb(fa, 214, 112, 36)
                val dip = min(3f, (NECK_ROW - surface) / 6f)
                canvas.drawRect(CX - 4f, (TOP_Y + surface).toFloat(), CX + 4f, TOP_Y + surface + dip, fill)
            }
        }
        // Bottom bulb: a flat bed with a mound on top.
        var bed = (1f - top) * bottomTotal * .78f
        var level = HW.size
        while (level > BOTTOM_ROW && bed > 0f) { level--; bed -= 2f * HW[level] }
        val mound = if (top < .998f) min(16f, 3f + (1f - top) * 18f) else 0f
        if (top < .998f) {
            for (i in level until HW.size) row(canvas, i, HW[i], if ((HW.size - i) % 5 == 0) dark else base)
            val mh = mound.toInt()
            for (k in 1..mh) {
                val i = level - k
                if (i < BOTTOM_ROW) break
                val w = HW[i] * (1f - k / (mh + 1f))
                row(canvas, i, w, if (k == mh) light else base)
            }
        }
        // The falling stream, with grains running down it.
        if (running && top > 0.002f) {
            val y0 = (TOP_Y + NECK_ROW).toFloat()
            val y1 = TOP_Y + level - mound
            fill.color = Color.argb(fa * 230 / 255, 255, 196, 92)
            canvas.drawRect(CX - .8f, y0, CX + .8f, y1, fill)
            fill.color = Color.argb(fa, 255, 226, 150)
            for (k in 0 until 4) {
                val y = y0 + ((t * 70f + k * 17f) % max(1f, y1 - y0))
                canvas.drawRect(CX - .6f, y, CX + .6f, y + 1.4f, fill)
            }
        }
    }

    companion object {
        private const val CX = 417.5f
        private const val TOP_Y = 139
        private const val NECK_ROW = 96   // y 235
        private const val BOTTOM_ROW = 99 // y 238
        private const val HL_X = 355f
        private const val HL_Y = 130f
        private const val SUN_X = 655f
        private const val SUN_Y = 140f
        private val STARS = floatArrayOf(965f, 12f, 260f, 14f, 600f, 20f)
        // Inner half-width of the glass for each row from y 139 down to y 333.
        private val HW = floatArrayOf(45.0f, 45.7f, 46.3f, 47.0f, 47.0f, 47.0f, 47.0f, 47.0f, 47.0f, 47.0f, 47.0f, 47.0f,
            47.0f, 47.0f, 47.0f, 47.0f, 47.0f, 47.0f, 47.0f, 47.0f, 47.0f, 47.0f, 47.0f, 47.0f, 47.0f, 47.0f, 47.0f, 47.0f,
            47.0f, 47.0f, 47.0f, 47.0f, 47.0f, 47.0f, 47.0f, 47.0f, 47.0f, 47.0f, 47.0f, 47.0f, 47.0f, 47.0f, 46.5f, 46.0f,
            45.5f, 45.0f, 44.5f, 44.0f, 43.5f, 43.0f, 42.5f, 42.0f, 41.3f, 40.5f, 39.8f, 39.1f, 38.3f, 37.6f, 36.9f, 36.1f,
            35.4f, 34.7f, 33.9f, 33.2f, 32.5f, 31.7f, 31.0f, 30.0f, 29.0f, 28.0f, 27.0f, 26.0f, 25.0f, 24.0f, 23.0f, 22.0f,
            21.0f, 20.0f, 19.0f, 18.3f, 17.6f, 16.9f, 16.2f, 15.5f, 14.8f, 14.2f, 13.5f, 12.8f, 12.1f, 11.4f, 10.7f, 10.0f,
            9.7f, 9.4f, 9.1f, 8.8f, 8.5f, 8.9f, 9.2f, 9.6f, 9.9f, 10.3f, 10.6f, 11.0f, 11.8f, 12.5f, 13.3f, 14.1f, 14.8f,
            15.6f, 16.4f, 17.2f, 17.9f, 18.7f, 19.5f, 20.2f, 21.0f, 22.2f, 23.5f, 24.8f, 26.0f, 27.2f, 28.5f, 29.8f, 31.0f,
            32.2f, 33.5f, 34.8f, 36.0f, 36.6f, 37.2f, 37.8f, 38.4f, 39.0f, 39.6f, 40.2f, 40.8f, 41.4f, 42.0f, 42.6f, 43.2f,
            43.8f, 44.4f, 45.0f, 45.1f, 45.3f, 45.4f, 45.5f, 45.7f, 45.8f, 45.9f, 46.1f, 46.2f, 46.3f, 46.5f, 46.6f, 46.7f,
            46.9f, 47.0f, 47.0f, 47.0f, 47.0f, 47.0f, 47.0f, 47.0f, 47.0f, 47.0f, 47.0f, 47.0f, 47.0f, 47.0f, 47.0f, 47.0f,
            47.0f, 47.0f, 47.0f, 47.0f, 47.0f, 47.0f, 47.0f, 47.0f, 47.0f, 47.0f, 47.0f, 46.4f, 45.9f, 45.3f, 44.8f, 44.2f,
            43.7f, 43.1f, 42.6f, 42.0f, 40.0f, 38.0f)
    }
}

/**
 * Do Not Disturb as a cosy bedroom at night: the cat sleeps and breathes, "z"s drift up, the
 * fairy lights and stars twinkle, steam curls from the mug, the lamp glows, and the bedside
 * clock shows the real time.
 */
class DndScene(res: Resources) : PictureScene(res) {
    private val bg by lazy { decode(R.drawable.dnd_bg) }
    private val cat by lazy { decode(R.drawable.dnd_cat) }
    private val warm by lazy { glowBitmap(Color.argb(150, 255, 200, 110)) }
    private val bulbGlow by lazy { glowBitmap(Color.argb(200, 255, 214, 140)) }
    private val clockPaint = Paint().apply {
        isAntiAlias = false
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textSize = 13f
        color = Color.rgb(150, 230, 140)
    }
    private val zPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) }
    private val catDst = RectF()

    fun draw(canvas: Canvas, card: RectF, settledW: Float, settledH: Float, alpha: Int, still: Boolean, is24h: Boolean) {
        val t = seconds(still)
        begin(canvas, card, settledW, settledH)
        bmp.alpha = alpha
        canvas.drawBitmap(bg, 0f, 0f, bmp)
        // Lamp glow breathes slowly.
        val g = 95f + 6f * sin(t * .6f)
        r.set(LAMP_X - g, LAMP_Y - g, LAMP_X + g, LAMP_Y + g)
        bmp.alpha = (alpha * (.35f + .1f * sin(t * .6f))).toInt()
        canvas.drawBitmap(warm, null, r, bmp)
        // Fairy lights.
        for (i in BULBS.indices step 2) {
            val k = i / 2
            val s = .5f + .5f * sin(t * (1.3f + (k % 4) * .4f) + k * 2.2f)
            val rr = 7f + 3f * s
            r.set(BULBS[i] - rr, BULBS[i + 1] - rr, BULBS[i] + rr, BULBS[i + 1] + rr)
            bmp.alpha = (alpha * (.25f + .55f * s)).toInt()
            canvas.drawBitmap(bulbGlow, null, r, bmp)
        }
        twinkle(canvas, STARS, t, alpha, 2f, 0xE6EEFF)
        // The cat breathes: a slow swell from its belly.
        val breath = .5f + .5f * sin(t * 1.5f)
        val sx = 1f + .012f * breath
        val sy = 1f + .03f * breath
        val cw = cat.width.toFloat(); val ch = cat.height.toFloat()
        val bx = CAT_X + cw * .55f; val by = CAT_Y + ch
        catDst.set(bx - cw * .55f * sx, by - ch * sy, bx + cw * .45f * sx, by)
        bmp.alpha = alpha
        canvas.drawBitmap(cat, null, catDst, bmp)
        // Zzz, rising and fading from the cat's head.
        for (k in 0 until 3) {
            val p = ((t * .28f + k / 3f) % 1f)
            val a = (alpha * sin(p * PI.toFloat()) * .9f).toInt()
            zPaint.textSize = 7f + p * 7f
            zPaint.color = Color.argb(a, 236, 232, 255)
            canvas.drawText("z", 300f - p * 26f + sin(p * 9f) * 3f, 286f - p * 46f, zPaint)
        }
        // Steam from the mug.
        stroke.strokeWidth = 1.6f
        for (k in 0 until 2) {
            val p = ((t * .35f + k * .5f) % 1f)
            stroke.color = Color.argb((alpha * .5f * sin(p * PI.toFloat())).toInt(), 255, 245, 235)
            val x = 846f + k * 6f + sin(p * 8f + k) * 2.5f
            val y = 247f - p * 22f
            canvas.drawLine(x, y, x + sin(p * 8f + 1f + k) * 2f, y - 5f, stroke)
        }
        // The bedside clock tells the real time.
        val cal = Calendar.getInstance()
        val hour = if (is24h) cal.get(Calendar.HOUR_OF_DAY) else (cal.get(Calendar.HOUR) .let { if (it == 0) 12 else it })
        val text = String.format("%02d:%02d", hour, cal.get(Calendar.MINUTE))
        clockPaint.alpha = alpha
        clockPaint.textSize = 13f
        val tw = clockPaint.measureText(text)
        if (tw > 38f) clockPaint.textSize = 13f * 38f / tw
        canvas.drawText(text, CLOCK_X - clockPaint.measureText(text) / 2f, CLOCK_Y, clockPaint)
        end(canvas)
        shade(canvas, card, alpha, .45f, 0f, 150)
    }

    companion object {
        private const val LAMP_X = 882f
        private const val LAMP_Y = 205f
        private const val CAT_X = 288f
        private const val CAT_Y = 280f
        private const val CLOCK_X = 915f
        private const val CLOCK_Y = 276f
        private val BULBS = floatArrayOf(244f, 32f, 507f, 20f, 307f, 35f, 289f, 24f, 485f, 30f, 313f, 50f, 455f, 39f,
            263f, 29f, 173f, 19f, 316f, 14f, 302f, 13f, 433f, 45f, 380f, 40f, 405f, 44f, 156f, 8f, 198f, 25f, 220f, 32f)
        private val STARS = floatArrayOf(772f, 144f, 608f, 125f, 61f, 117f, 638f, 46f, 700f, 116f, 644f, 74f, 718f, 51f,
            793f, 119f, 17f, 44f, 9f, 78f, 690f, 76f, 65f, 22f, 602f, 111f, 708f, 147f, 77f, 95f, 603f, 51f, 604f, 26f, 688f, 40f)
    }
}

/**
 * A Bluetooth device as a snowy forest under the northern lights: the aurora ripples and
 * brightens in slow waves, snow drifts down, stars twinkle, and the deer's breath puffs in the cold.
 */
class AuroraScene(res: Resources) : PictureScene(res) {
    private val bg by lazy { decode(R.drawable.bt_bg) }
    private val aurora by lazy { decode(R.drawable.bt_aurora) }
    private val add = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) }
    private val strip = android.graphics.Rect()
    private val stripDst = RectF()

    fun draw(canvas: Canvas, card: RectF, settledW: Float, settledH: Float, alpha: Int, still: Boolean) {
        val t = seconds(still)
        begin(canvas, card, settledW, settledH)
        bmp.alpha = alpha
        canvas.drawBitmap(bg, 0f, 0f, bmp)
        // The aurora, again on top in vertical strips that sway and brighten in a travelling wave.
        val a = aurora
        val n = 32
        val sw = a.width / n
        for (i in 0 until n) {
            val x = i * sw
            val wave = sin(t * .9f - i * .35f)
            val dy = sin(t * .7f + i * .45f) * 3.5f
            strip.set(x, 0, min(a.width, x + sw + 1), a.height)
            stripDst.set(x.toFloat(), dy, min(a.width, x + sw + 1).toFloat(), a.height + dy)
            add.alpha = (alpha * (.18f + .3f * max(0f, wave))).toInt()
            canvas.drawBitmap(a, strip, stripDst, add)
        }
        twinkle(canvas, STARS, t, alpha, 2.2f, 0xEAF2FF)
        // Breath puffs from the deer about every 4 seconds.
        val p = (t % 4f) / 1.6f
        if (p < 1f && !still) {
            fill.color = Color.argb((alpha * .45f * sin(p * PI.toFloat())).toInt(), 235, 242, 255)
            canvas.drawCircle(DEER_X + p * 9f, DEER_Y - p * 5f, 1.5f + p * 3.5f, fill)
        }
        // Snow.
        if (!still) for (k in 0 until 46) {
            val speed = 9f + (k % 5) * 4f
            val y = ((t * speed + k * 53f) % 470f) - 20f
            val x = (k * 131f % 1024f) + sin(t * .8f + k) * 6f
            val s = .8f + (k % 3) * .45f
            fill.color = Color.argb(alpha * (150 + (k % 3) * 35) / 255, 245, 248, 255)
            canvas.drawCircle(x, y, s, fill)
        }
        end(canvas)
        shade(canvas, card, alpha, .48f, 0f, 140)
    }

    companion object {
        private const val DEER_X = 353f
        private const val DEER_Y = 306f
        private val STARS = floatArrayOf(273f, 64f, 340f, 28f, 46f, 121f, 775f, 137f, 393f, 106f, 999f, 128f, 82f, 41f,
            34f, 91f, 907f, 123f, 27f, 66f, 496f, 113f, 448f, 123f, 208f, 49f, 495f, 35f, 724f, 120f, 436f, 145f, 440f, 81f,
            470f, 101f, 803f, 81f)
    }
}
