package com.dynamicdroid.app

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.core.graphics.ColorUtils
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Little dot-matrix scenes that stand in for plain level bars, in the island's pixel style:
 * volume is a car driving down a pixel street, a download is a rocket flying to a planet, and
 * charging fills a pixel battery with a spark running through it. Every sprite is original and
 * drawn cell by cell on a grid, so the scenes stay crisp at any size and allocate nothing per frame.
 */
class PixelScenes(private val density: Float) {
    private val paint = Paint().apply { isAntiAlias = false }
    private var facing = 1f

    private fun dp(v: Float) = v * density

    /** Draws [rows] as pixel cells: each char is a colour from [palette], '.' is empty. */
    private fun sprite(canvas: Canvas, rows: Array<String>, x: Float, y: Float, cell: Float, alpha: Int, flip: Boolean,
                       palette: (Char) -> Int) {
        val w = rows[0].length
        for (ry in rows.indices) {
            val row = rows[ry]
            for (rx in row.indices) {
                val ch = row[rx]
                if (ch == '.') continue
                val col = if (flip) w - 1 - rx else rx
                paint.color = ColorUtils.setAlphaComponent(palette(ch), (Color.alpha(palette(ch)) * alpha / 255))
                canvas.drawRect(x + col * cell, y + ry * cell, x + (col + 1) * cell - cell * .08f, y + (ry + 1) * cell - cell * .08f, paint)
            }
        }
    }

    private fun cellRect(canvas: Canvas, x: Float, y: Float, cell: Float, color: Int, alpha: Int) {
        paint.color = ColorUtils.setAlphaComponent(color, Color.alpha(color) * alpha / 255)
        canvas.drawRect(x, y, x + cell - cell * .08f, y + cell - cell * .08f, paint)
    }

    // ------------------------------------------------------------------------------- volume

    private var scenery: android.graphics.Bitmap? = null
    private var night: android.graphics.Bitmap? = null
    private var sceneryKey = 0L
    private var wheelPhase = 0f
    private var lastCarX = Float.NaN
    private val bitmapPaint = Paint()

    /** Where the road runs, as a fraction of the scene's height (the car drives along it). */
    val roadTop = .70f

    private val body = arrayOf(
        ".......OOOOOOOOOO........",
        "......OBBBBBBBBBBO.......",
        ".....OBWWWWBBWWWWBO......",
        "....OBBWWWWBBWWWWBBO.....",
        "..OOBBBBBBBBBBBBBBBBOOOO.",
        ".OBBBBBBBBBBBBBBBBBBBBBBO",
        "OBBBBDBBBBBBBBBBBBDBBBBLO",
        "OBBBBBBBBBBBBBBBBBBBBBBBO",
        ".OOOOOOOOOOOOOOOOOOOOOOO.",
    )
    private val wheel = arrayOf(".KKK.", "KKKKK", "KKHKK", "KKKKK", ".KKK.")

    /**
     * Volume as a road trip through a pixel landscape (sunset, mountains, pines, a farmhouse).
     * The car is the slider: its place on the road is the volume, its wheels roll as it moves
     * and it faces the way it's going. A mute sign waits at the left end and a loudspeaker at
     * the right. At mute the sky turns to night and the car parks, grey and dozing.
     * The scenery is painted once into a bitmap; each frame only draws it and the car.
     */
    fun roadTrip(canvas: Canvas, scene: android.graphics.RectF, settledW: Float, settledH: Float, carX: Float, level: Float,
                 now: Long, accent: Int, alpha: Int) {
        val cell = scene.height() / 32f
        // Painted once at the card's settled size (not every frame while it opens), then scaled.
        val key = (settledW.toInt().toLong() shl 20) or settledH.toInt().toLong()
        if (scenery == null || key != sceneryKey) {
            sceneryKey = key
            val c = settledH / 32f
            scenery = paintScenery(settledW, settledH, c, false)
            night = paintScenery(settledW, settledH, c, true)
        }
        bitmapPaint.alpha = alpha
        canvas.drawBitmap(scenery!!, null, scene, bitmapPaint)
        val dark = (1f - level / .08f).coerceIn(0f, 1f)
        if (dark > 0f) { bitmapPaint.alpha = (alpha * dark).toInt(); canvas.drawBitmap(night!!, null, scene, bitmapPaint) }
        // Wheels roll by the distance travelled; the car faces where it's heading.
        if (!lastCarX.isNaN()) {
            val dx = carX - lastCarX
            if (abs(dx) > .5f) facing = if (dx > 0f) 1f else -1f
            wheelPhase += dx / (cell * 5f * 3.14f / 4f)
        }
        lastCarX = carX
        val parked = level <= .001f
        val w = cell * body[0].length
        val roadY = scene.top + scene.height() * roadTop
        val bob = if (!parked && (now / 240L) % 2L == 0L) cell * .5f else 0f
        // Wheels sit in the middle of the road, not on its edge.
        val top = roadY - cell * (body.size - 2) + bob
        val left = carX - w / 2f
        val paintColor = if (parked) Color.rgb(140, 140, 140) else accent
        sprite(canvas, body, left, top, cell, alpha, flip = facing < 0f) { ch ->
            when (ch) {
                'O' -> Color.WHITE
                'B' -> paintColor
                'D' -> ColorUtils.blendARGB(paintColor, Color.BLACK, .35f)
                'W' -> 0xFF8FD3FF.toInt()
                'L' -> if (parked) Color.rgb(90, 90, 90) else 0xFFFFE08A.toInt()
                else -> paintColor
            }
        }
        // Two wheels with a hub that turns as they roll.
        val spin = ((wheelPhase % 4f) + 4f) % 4f
        for (wx in floatArrayOf(4f, 18f)) {
            val cxw = if (facing < 0f) left + w - cell * (wx + 5f) else left + cell * wx
            val wy = top + cell * (body.size - 2)
            sprite(canvas, wheel, cxw, wy, cell, alpha, false) { ch -> if (ch == 'H') Color.rgb(200, 200, 200) else Color.rgb(28, 28, 28) }
            // The spoke: one light cell walking round the rim.
            val (sx, sy) = when (spin.toInt()) { 0 -> 2 to 0; 1 -> 4 to 2; 2 -> 2 to 4; else -> 0 to 2 }
            cellRect(canvas, cxw + sx * cell, wy + sy * cell, cell, Color.rgb(150, 150, 150), alpha)
        }
        if (parked && (now / 600L) % 2L == 0L) {
            val z = arrayOf("ZZZ", "..Z", ".Z.", "Z..", "ZZZ")
            sprite(canvas, z, left + w * .7f, top - cell * 6f, cell * .8f, alpha, false) { Color.WHITE }
        }
    }

    /** Mute and loudspeaker signs at the ends of the road, in pixels. */
    fun endSign(canvas: Canvas, x: Float, centerY: Float, cell: Float, loud: Boolean, alpha: Int) {
        val speaker = arrayOf("...W..", "..WW..", "WWWW..", "WWWW..", "..WW..", "...W..")
        sprite(canvas, speaker, x, centerY - cell * 3f, cell, alpha, false) { Color.WHITE }
        if (loud) {
            for (k in 0..1) for (dy in -(1 + k)..(1 + k)) cellRect(canvas, x + cell * (7 + k * 2) - abs(dy) * cell * .4f, centerY + dy * cell - cell / 2f, cell,
                ColorUtils.setAlphaComponent(Color.WHITE, 230 - k * 60), alpha)
        } else {
            val cross = arrayOf("W.W", ".W.", "W.W")
            sprite(canvas, cross, x + cell * 7f, centerY - cell * 1.5f, cell, alpha, false) { Color.WHITE }
        }
    }

    private fun paintScenery(w: Float, h: Float, cell: Float, dark: Boolean): android.graphics.Bitmap {
        val bmp = android.graphics.Bitmap.createBitmap(max(1, w.toInt()), max(1, h.toInt()), android.graphics.Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cols = (w / cell).toInt() + 1
        val rows = (h / cell).toInt() + 1
        fun px(x: Int, y: Int, color: Int) = run { paint.color = color; c.drawRect(x * cell, y * cell, (x + 1) * cell, (y + 1) * cell, paint) }
        fun shade(col: Int) = if (dark) ColorUtils.blendARGB(col, 0xFF0B1030.toInt(), .62f) else col
        // Sky in bands, warm at the horizon.
        val bands = if (dark) intArrayOf(0xFF0A0F2A.toInt(), 0xFF111A3D.toInt(), 0xFF1B2552.toInt(), 0xFF27306A.toInt())
        else intArrayOf(0xFF3E5DA8.toInt(), 0xFF6E86C8.toInt(), 0xFFE7A37A.toInt(), 0xFFF6C27C.toInt())
        val horizon = (rows * .55f).toInt()
        for (y in 0 until horizon) for (x in 0 until cols) px(x, y, bands[min(bands.size - 1, y * bands.size / max(1, horizon))])
        if (dark) {
            for (k in 0 until cols / 4) px((k * 37) % cols, (k * 11) % (horizon - 2), 0xFFDDE4FF.toInt())
            val mx = (cols * .72f).toInt(); val my = (rows * .14f).toInt()
            for (dy in -2..2) for (dx in -2..2) if (dx * dx + dy * dy <= 5) px(mx + dx, my + dy, 0xFFEFEFD8.toInt())
        } else {
            val sx = (cols * .42f).toInt(); val sy = horizon - 2
            for (dy in -4..4) for (dx in -4..4) if (dx * dx + dy * dy <= 17 && sy + dy < horizon) px(sx + dx, sy + dy, 0xFFFFE08A.toInt())
        }
        // Mountains: a far purple range with snow, then a nearer, darker one.
        fun range(peaks: IntArray, height: Int, base: Int, color: Int, snow: Boolean) {
            for (x in 0 until cols) {
                var top = base
                for (p in peaks) top = min(top, base - (height - abs(x - p) * height / 9).coerceAtLeast(0))
                for (y in top until base) px(x, y, shade(if (snow && y < top + 2 && base - top > height - 3) 0xFFEDEFF6.toInt() else color))
            }
        }
        val step = max(8, cols / 6)
        range(IntArray(7) { it * step + 3 }, (rows * .3f).toInt(), horizon + 2, 0xFF8A83A8.toInt(), true)
        range(IntArray(6) { it * step + step / 2 + 5 }, (rows * .18f).toInt(), horizon + 3, 0xFF5D5C7E.toInt(), false)
        // Meadow down to the road, with darker tufts.
        val road = (rows * roadTop).toInt()
        for (y in horizon + 3 until road) for (x in 0 until cols)
            px(x, y, shade(if ((x * 7 + y * 13) % 11 == 0) 0xFF3F8F3A.toInt() else 0xFF5DAE4A.toInt()))
        // Pines and a little farmhouse.
        val pine = arrayOf("..G..", ".GGG.", "..G..", ".GGG.", "GGGGG", "..T..")
        for (tx in intArrayOf(2, cols / 4, cols / 2 + 6, cols - 9)) {
            val ty = road - pine.size
            for (yy in pine.indices) for (xx in pine[yy].indices) when (pine[yy][xx]) {
                'G' -> px(tx + xx, ty + yy, shade(0xFF2E6B3A.toInt())); 'T' -> px(tx + xx, ty + yy, shade(0xFF6B4A2E.toInt()))
            }
        }
        val house = arrayOf("..RRRR..", ".RRRRRR.", "RRRRRRRR", ".WWWWWW.", ".WDWWYW.", ".WDWWWW.")
        val hx = (cols * .64f).toInt(); val hy = road - house.size
        for (yy in house.indices) for (xx in house[yy].indices) when (house[yy][xx]) {
            'R' -> px(hx + xx, hy + yy, shade(0xFFB0503A.toInt())); 'W' -> px(hx + xx, hy + yy, shade(0xFFE6D7B0.toInt()))
            'D' -> px(hx + xx, hy + yy, shade(0xFF7A4E30.toInt())); 'Y' -> px(hx + xx, hy + yy, if (dark) 0xFFFFD27A.toInt() else 0xFF8FC8E8.toInt())
        }
        // The road, with a white edge and marker posts.
        for (y in road until rows) for (x in 0 until cols) px(x, y, shade(0xFF4A4A54.toInt()))
        for (x in 0 until cols) px(x, road, shade(0xFFD8D8D8.toInt()))
        val mid = road + (rows - road) / 2
        for (x in 0 until cols step 6) for (k in 0..2) px(x + k, mid, shade(0xFFE8E8E8.toInt()))
        for (x in 0 until cols) px(x, rows - 1, shade(0xFF6A6A72.toInt()))
        return bmp
    }

    // ----------------------------------------------------------------------------- download

    private val rocket = arrayOf(
        "..WW....",
        ".WWWWW..",
        "RWWBWWWN",
        ".WWWWW..",
        "..WW....",
    )

    /** A download as a rocket flying past twinkling stars to a planet it reaches at 100%. */
    fun launch(canvas: Canvas, left: Float, right: Float, centerY: Float, height: Float, progress: Float,
               now: Long, accent: Int, alpha: Int) {
        val cell = max(dp(1.4f), height / 9f)
        val top = centerY - height / 2f
        // Stars.
        for (i in 0 until 14) {
            val sx = left + ((i * 97) % 100) / 100f * (right - left)
            val sy = top + ((i * 53) % 9) * cell
            val tw = ((now / 300L) + i) % 4L
            if (tw != 0L) cellRect(canvas, sx, sy, cell * .7f, ColorUtils.setAlphaComponent(Color.WHITE, if (tw == 3L) 160 else 70), alpha)
        }
        // Planet waiting at the end.
        val pr = 3
        val pcx = right - cell * (pr + 1)
        for (py in -pr..pr) for (px in -pr..pr) {
            if (px * px + py * py > pr * pr + 1) continue
            val ring = py == 0 && abs(px) <= pr
            cellRect(canvas, pcx + px * cell, centerY + py * cell - cell / 2f, cell,
                if (ring) ColorUtils.setAlphaComponent(Color.WHITE, 200) else ColorUtils.blendARGB(accent, Color.BLACK, .35f), alpha)
        }
        // The rocket: its place is the progress; the flame flickers.
        val w = cell * rocket[0].length
        val travel = (right - left - w - cell * (pr * 2 + 3)).coerceAtLeast(0f)
        val rx = left + cell * 2f + travel * progress.coerceIn(0f, 1f)
        val ry = centerY - cell * rocket.size / 2f + if ((now / 200L) % 2L == 0L) cell * .4f else 0f
        sprite(canvas, rocket, rx, ry, cell, alpha, false) { ch ->
            when (ch) {
                'W' -> Color.WHITE
                'B' -> 0xFF5AC8FA.toInt()
                'R' -> accent
                'N' -> accent
                else -> Color.WHITE
            }
        }
        val flame = ((now / 80L) % 3L).toInt()
        for (f in 0..flame) cellRect(canvas, rx - cell * (f + 1), centerY - cell / 2f, cell,
            if (f == 0) 0xFFFFD60A.toInt() else 0xFFFF6B35.toInt(), alpha)
        // A trail of dots back to the start.
        var tx = left
        while (tx < rx - cell * 4f) {
            cellRect(canvas, tx, centerY - cell / 2f, cell * .6f, ColorUtils.setAlphaComponent(accent, 110), alpha)
            tx += cell * 3f
        }
    }

    // ----------------------------------------------------------------------------- charging

    /** A pixel battery whose columns fill to the level, with a spark running through the charge. */
    fun battery(canvas: Canvas, right: Float, centerY: Float, height: Float, level: Int, now: Long, color: Int, alpha: Int): Float {
        val rows = 7
        val cols = 13
        val cell = height / rows
        val w = cell * (cols + 1)
        val l = right - w
        val top = centerY - height / 2f
        val frame = ColorUtils.setAlphaComponent(Color.WHITE, 150)
        for (cx in 0 until cols) { cellRect(canvas, l + cx * cell, top, cell, frame, alpha); cellRect(canvas, l + cx * cell, top + (rows - 1) * cell, cell, frame, alpha) }
        for (cy in 0 until rows) { cellRect(canvas, l, top + cy * cell, cell, frame, alpha); cellRect(canvas, l + (cols - 1) * cell, top + cy * cell, cell, frame, alpha) }
        for (cy in 2..4) cellRect(canvas, l + cols * cell, top + cy * cell, cell, frame, alpha)
        val inner = cols - 2
        val filled = floor(inner * level.coerceIn(0, 100) / 100f).toInt()
        val spark = ((now / 110L) % max(1, filled + 2)).toInt()
        for (cx in 0 until inner) for (cy in 1 until rows - 1) {
            val on = cx < filled || cx == filled && (now / 400L) % 2L == 0L
            if (!on) continue
            val c = if (cx == spark - 1 && cy in 2..4) Color.WHITE else color
            cellRect(canvas, l + (cx + 1) * cell, top + cy * cell, cell, c, alpha)
        }
        return l
    }

    companion object {
        fun accentFor(c: Int) = if (ColorUtils.calculateLuminance(c) < .12) Color.WHITE else c
        fun clampLevel(v: Float) = min(1f, max(0f, v))
    }
}
