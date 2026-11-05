package com.dynamicdroid.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Dot music inside the island: a small version of the dot player's record.
 *
 * The cover becomes a round halftone of springy dots that spins while music plays, breathes,
 * ripples outward on every beat, and bursts apart and rebuilds itself when the song changes.
 * Around it, a ring of dots bounces like an equaliser. All state lives in flat arrays and the
 * caller's Paint; nothing is allocated per frame.
 */
class IslandDots(private val n: Int) {
    private val count = n * n
    private val alive = BooleanArray(count)
    private val homeR = FloatArray(count)
    private val homeA = FloatArray(count)
    private val lumFrom = FloatArray(count)
    private val lumTo = FloatArray(count)
    private val colFrom = IntArray(count)
    private val colTo = IntArray(count)
    // Offsets from each dot's home, in cells, with velocity: the springs.
    private val ox = FloatArray(count)
    private val oy = FloatArray(count)
    private val vx = FloatArray(count)
    private val vy = FloatArray(count)
    private var mix = 1f
    private var source: Bitmap? = null
    private var sourceMono = false
    private var ready = false

    private var spin = 0f
    private var spinSpeed = 0f
    private var t = 0f
    private val rippleT = FloatArray(4) { -10f }
    private val rippleS = FloatArray(4)
    private var nextRipple = 0
    private var lastBeat = 0f
    private var energyAvg = 0f
    /** 0..1, how much the last beat still glows. */
    var beatGlow = 0f
        private set

    private val bands = 24
    private val ring = FloatArray(bands)

    /** Sets the cover. A new song (not the first) makes the record burst and rebuild. */
    fun prepare(art: Bitmap?, mono: Boolean, animate: Boolean) {
        if (ready && art === source && mono == sourceMono) return
        val first = !ready
        source = art
        sourceMono = mono
        val sampled = art?.let { DotArt.sample(it, n) }
        val half = n / 2f
        for (y in 0 until n) for (x in 0 until n) {
            val i = y * n + x
            val dx = x + .5f - half
            val dy = y + .5f - half
            val d = sqrt(dx * dx + dy * dy)
            alive[i] = d <= half - .15f && d >= n * 0.08f
            homeR[i] = d
            homeA[i] = atan2(dy, dx)
            val c = sampled?.get(i) ?: Color.rgb(90, 90, 90)
            val lum = if (sampled == null) 0.3f + 0.25f * (1f - d / half) else DotArt.luminance(c)
            val color = if (mono || sampled == null) Color.WHITE else DotArt.vivid(c)
            colFrom[i] = if (!first) blend(colFrom[i], colTo[i], ease(mix)) else color
            lumFrom[i] = if (!first) lumFrom[i] + (lumTo[i] - lumFrom[i]) * ease(mix) else lum
            colTo[i] = color
            lumTo[i] = lum
        }
        ready = true
        mix = if (first || !animate) 1f else 0f
        if (!first && animate) scatter()
    }

    /** The record bursts outward; every dot springs back home as the new cover fades in. */
    private fun scatter() {
        for (i in 0 until count) {
            if (!alive[i]) continue
            val a = homeA[i] + spin
            val power = 9f + 14f * rand(i)
            vx[i] += cos(a) * power
            vy[i] += sin(a) * power
        }
    }

    /** A ripple from the centre, like tapping play. */
    fun ripple(strength: Float) {
        rippleT[nextRipple] = t
        rippleS[nextRipple] = strength
        nextRipple = (nextRipple + 1) % rippleT.size
        beatGlow = max(beatGlow, strength)
    }

    /**
     * Advances spin, springs, beats and the ring by [dt] seconds. [spectrum] (48 bands, 0..1) drives
     * real beats when given; otherwise a steady 116 bpm pulse plays while [playing].
     */
    fun step(dt: Float, playing: Boolean, spectrum: FloatArray?, reduceMotion: Boolean) {
        t += dt
        val target = if (playing && !reduceMotion) 2f * PI.toFloat() / 14f else 0f
        spinSpeed += (target - spinSpeed) * min(1f, dt * 1.5f)
        spin += spinSpeed * dt
        if (mix < 1f) mix = min(1f, mix + dt / 0.9f)

        // Springs pulling each knocked-away dot back home (offsets are in cells).
        val k = 190f
        val damp = 2f * sqrt(k) * 0.6f
        for (i in 0 until count) {
            if (!alive[i]) continue
            vx[i] += (-ox[i] * k - vx[i] * damp) * dt
            vy[i] += (-oy[i] * k - vy[i] * damp) * dt
            ox[i] += vx[i] * dt
            oy[i] += vy[i] * dt
        }

        if (spectrum != null && playing) {
            var bass = 0f
            for (b in 0 until 6) bass += spectrum[b]
            bass /= 6f
            energyAvg += (bass - energyAvg) * min(1f, dt * 1.6f)
            if (!reduceMotion && bass > energyAvg * 1.35f && bass > 0.18f && t - lastBeat > 0.22f) {
                lastBeat = t
                ripple((0.5f + (bass - energyAvg) * 2.2f).coerceIn(0.4f, 1.3f))
            }
            for (b in 0 until bands) {
                val v = spectrum[min(spectrum.size - 1, b * spectrum.size / bands)]
                ring[b] += (v - ring[b]) * min(1f, dt * if (v > ring[b]) 22f else 7f)
            }
        } else {
            val period = 60f / 116f
            if (playing && !reduceMotion && t - lastBeat > period) {
                lastBeat = t
                ripple(if (((t / period).roundToInt() % 4) == 0) 1f else 0.55f)
            }
            for (b in 0 until bands) {
                val a = b * 2f * PI.toFloat() / bands
                val v = if (!playing) 0.04f else (0.3f + 0.22f * sin(t * 2.3f + a * 3f) + 0.18f * sin(t * 3.7f - a * 5f) +
                    0.25f * exp(-(t - lastBeat) * 6f)).coerceIn(0f, 1f)
                ring[b] += (v - ring[b]) * min(1f, dt * 10f)
            }
        }
        beatGlow = max(0f, beatGlow - dt * 2.2f)
    }

    /** The record, [radius] pixels, centred on ([cx], [cy]). */
    fun drawRecord(canvas: Canvas, paint: Paint, cx: Float, cy: Float, radius: Float, playing: Boolean, alpha: Int) {
        if (!ready) return
        val cell = radius * 2f / n
        val m = ease(mix)
        val dim = if (playing) 1f else 0.6f
        for (i in 0 until count) {
            if (!alive[i]) continue
            val a = homeA[i] + spin
            val r0 = homeR[i] * cell
            val x = cx + r0 * cos(a) + ox[i] * cell
            val y = cy + r0 * sin(a) + oy[i] * cell
            val lum = lumFrom[i] + (lumTo[i] - lumFrom[i]) * m
            var r = DotArt.halftoneRadius(lum, cell)
            r *= 1f + (if (playing) 0.07f else 0.02f) * sin(t * 1.8f - homeR[i] * 0.45f)
            for (j in rippleT.indices) {
                val age = t - rippleT[j]
                if (age < 0f || age > 1.2f) continue
                val front = age * n * 0.9f - homeR[i]
                r *= 1f + 0.6f * rippleS[j] * exp(-front * front / 2f) * (1f - age / 1.2f)
            }
            paint.color = blend(colFrom[i], colTo[i], m)
            paint.alpha = (alpha * dim).toInt().coerceIn(0, 255)
            canvas.drawCircle(x, y, r, paint)
        }
        paint.color = DotArt.SPINDLE_RED
        paint.alpha = alpha
        canvas.drawCircle(cx, cy, cell * (0.6f + 0.25f * beatGlow), paint)
    }

    /** The equaliser ring: [stack] dots per band reaching outward from [inner], [pitch] apart. */
    fun drawRing(canvas: Canvas, paint: Paint, cx: Float, cy: Float, inner: Float, pitch: Float, dot: Float,
                 stack: Int, accent: Int, playing: Boolean, alpha: Int) {
        for (b in 0 until bands) {
            val a = b * 2f * PI.toFloat() / bands - PI.toFloat() / 2 + spin * 0.25f
            val level = ring[b]
            val lit = 1 + (level * (stack - 1) + 0.35f).toInt().coerceAtMost(stack - 1)
            for (s in 0 until lit) {
                val rr = inner + s * pitch
                paint.color = if (s == lit - 1 && level > 0.62f) accent else Color.WHITE
                paint.alpha = ((if (playing) 0.85f else 0.3f) * alpha * (1f - s * 0.2f)).toInt().coerceIn(0, 255)
                canvas.drawCircle(cx + rr * cos(a), cy + rr * sin(a), dot * (1f - s * 0.12f), paint)
            }
        }
    }

    /**
     * A small dot equaliser ([cols] columns of [rows] dots) in the box at ([left], [cy]): the
     * island's music meter in dots. Bottom dots stay lit; the rest follow the ring's bands.
     */
    fun drawMeter(canvas: Canvas, paint: Paint, left: Float, cy: Float, w: Float, h: Float, cols: Int, rows: Int,
                  color: Int, energy: Float, alpha: Int) {
        val px = w / cols
        val py = h / rows
        val dot = min(px, py) * 0.36f
        for (c in 0 until cols) {
            val level = ring[(c * 5 + 3) % bands] * energy
            val lit = 1 + (level * (rows - 1) + 0.4f).toInt().coerceAtMost(rows - 1)
            for (r in 0 until rows) {
                paint.color = color
                paint.alpha = (alpha * if (r < lit) 1f else 0.18f).toInt()
                canvas.drawCircle(left + (c + .5f) * px, cy + h / 2f - (r + .5f) * py, dot, paint)
            }
        }
    }

    private fun rand(i: Int): Float {
        var h = i * 374761393 + 668265263
        h = (h xor (h ushr 13)) * 1274126177
        return ((h xor (h ushr 16)) and 0xFFFF) / 65535f
    }

    private fun ease(v: Float) = 1f - (1f - v) * (1f - v) * (1f - v)

    private fun blend(a: Int, b: Int, f: Float): Int {
        if (f <= 0f) return a
        if (f >= 1f) return b
        return Color.rgb(
            (Color.red(a) + (Color.red(b) - Color.red(a)) * f).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * f).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f).toInt())
    }
}
