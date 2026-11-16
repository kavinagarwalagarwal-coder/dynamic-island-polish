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
    private var roadOffset = 0f
    private var lastAt = 0L
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

    private val car = arrayOf(
        "...BBBBB....",
        "..BWWBWWB...",
        ".BBBBBBBBBBL",
        "BBBBBBBBBBBB",
        ".KK.....KK..",
    )
    private val carRolling = arrayOf(
        "...BBBBB....",
        "..BWWBWWB...",
        ".BBBBBBBBBBL",
        "BBBBBBBBBBBB",
        ".kk.....kk..",
    )

    /**
     * Volume as a drive: the car's place on the street is the level. The street scrolls and
     * exhaust puffs while the level changes, the wheels turn, the car faces the way it's going,
     * a checkered flag waits at full volume, and at mute it's parked, grey, and dozing.
     */
    fun drive(canvas: Canvas, left: Float, right: Float, centerY: Float, height: Float, level: Float, speed: Float,
              now: Long, accent: Int, alpha: Int) {
        val cell = max(dp(1.6f), height / 12f)
        val dt = if (lastAt == 0L) 0f else ((now - lastAt).coerceIn(0L, 100L) / 1000f)
        lastAt = now
        val moving = abs(speed) > .02f
        if (moving) facing = if (speed > 0f) 1f else -1f
        roadOffset = (roadOffset + (speed * 26f + if (moving) 0f else 0f) * dt * cell * 4f) % (cell * 6f)
        val top = centerY - height / 2f
        val bottom = centerY + height / 2f
        // Skyline: dim buildings with a few lit windows, scrolling slower than the road.
        val sky = ColorUtils.setAlphaComponent(Color.WHITE, 26)
        val lit = ColorUtils.setAlphaComponent(0xFFFFD27A.toInt(), 120)
        var bx = left - (roadOffset * .4f) % (cell * 5f)
        var k = 0
        while (bx < right) {
            val tall = 3 + (k * 7 % 5)
            for (h in 0 until tall) {
                val y = bottom - cell * (4 + h)
                if (y < top) break
                for (wx in 0 until 3) {
                    val x = bx + wx * cell
                    if (x + cell > right || x < left) continue
                    val window = h in 1 until tall - 1 && wx == 1 && (k + h) % 3 == 0
                    cellRect(canvas, x, y, cell, if (window) lit else sky, alpha)
                }
            }
            bx += cell * 5f; k++
        }
        // Road: asphalt, and a dashed centre line that scrolls as you drive.
        val road = ColorUtils.setAlphaComponent(Color.WHITE, 46)
        val lane = ColorUtils.setAlphaComponent(Color.WHITE, 170)
        var x = left
        while (x < right - cell * .5f) {
            cellRect(canvas, x, bottom - cell * 3f, cell, road, alpha)
            cellRect(canvas, x, bottom - cell, cell, road, alpha)
            x += cell
        }
        var d = left - roadOffset
        while (d < right) {
            for (s in 0 until 3) {
                val dx = d + s * cell
                if (dx >= left && dx + cell <= right) cellRect(canvas, dx, bottom - cell * 2f, cell, lane, alpha)
            }
            d += cell * 6f
        }
        // Finish flag at full volume.
        val flagX = right - cell * 3f
        for (fy in 0 until 4) for (fx in 0 until 2) {
            val on = (fx + fy) % 2 == 0
            cellRect(canvas, flagX + fx * cell, bottom - cell * (8 - fy), cell, if (on) Color.WHITE else Color.DKGRAY, alpha)
        }
        for (fy in 0 until 5) cellRect(canvas, flagX + 2 * cell, bottom - cell * (8 - fy), cell, Color.LTGRAY, alpha)
        // The car: position is the level; it rocks a pixel while the engine idles.
        val carW = cell * car[0].length
        val travel = (right - left - carW - cell * 3f).coerceAtLeast(0f)
        val cx = left + travel * level.coerceIn(0f, 1f)
        val parked = level <= .001f
        val bob = if (!parked && (now / 260L) % 2L == 0L) cell * .5f else 0f
        val carY = bottom - cell * 3f - cell * car.size + cell + bob
        val body = if (parked) Color.GRAY else accent
        val frames = if (moving && (now / 90L) % 2L == 0L) carRolling else car
        sprite(canvas, frames, cx, carY, cell, alpha, flip = facing < 0f) { ch ->
            when (ch) {
                'B' -> body
                'W' -> 0xFF9FD8FF.toInt()
                'L' -> if (parked) Color.DKGRAY else 0xFFFFE08A.toInt()
                'K' -> Color.rgb(30, 30, 30)
                'k' -> Color.rgb(90, 90, 90)
                else -> body
            }
        }
        // Exhaust puffs behind it while driving; speed lines when it's quick.
        if (moving) {
            val back = if (facing > 0f) cx - cell else cx + carW
            val step = if (facing > 0f) -1f else 1f
            val phase = ((now / 120L) % 3L).toInt()
            for (p in 0..phase) cellRect(canvas, back + step * cell * (p * 1.6f), carY + cell * (3 - p), cell,
                ColorUtils.setAlphaComponent(Color.WHITE, 140 - p * 40), alpha)
            if (abs(speed) > .25f) for (l in 0..1) {
                val ly = carY + cell * (1 + l * 2)
                for (s in 0 until 3) cellRect(canvas, back + step * cell * (2.5f + s + l), ly, cell,
                    ColorUtils.setAlphaComponent(Color.WHITE, 90), alpha)
            }
        }
        // Muted: a dozing "z" above the parked car.
        if (parked) {
            val zx = cx + carW * .6f
            val zy = carY - cell * 4f
            val z = arrayOf("ZZZ", "..Z", ".Z.", "Z..", "ZZZ")
            if ((now / 600L) % 2L == 0L && zy > top - cell * 2f) sprite(canvas, z, zx, zy, cell * .6f, alpha, false) { Color.LTGRAY }
        }
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
