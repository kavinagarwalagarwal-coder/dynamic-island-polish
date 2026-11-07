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
import kotlin.math.pow
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
    // Where a ripple started, in the island's pixels; NaN = from the record.
    private val rippleX = FloatArray(4) { Float.NaN }
    private val rippleY = FloatArray(4) { Float.NaN }
    private var nextRipple = 0
    private var lastBeat = 0f
    private var lastHeard = -10f
    private var energyAvg = 0f
    private var wasPlaying = false
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

    /**
     * A ripple from the centre, like tapping play: a bright ring runs out across the record and
     * the island, and the record's dots get a small outward kick that springs back.
     */
    fun ripple(strength: Float, x: Float = Float.NaN, y: Float = Float.NaN) {
        rippleT[nextRipple] = t
        rippleS[nextRipple] = strength
        rippleX[nextRipple] = x
        rippleY[nextRipple] = y
        nextRipple = (nextRipple + 1) % rippleT.size
        beatGlow = max(beatGlow, strength)
        if (!ready) return
        val half = n / 2f
        for (i in 0 until count) {
            if (!alive[i]) continue
            val a = homeA[i] + spin
            val kick = strength * 3.2f * (homeR[i] / half)
            vx[i] += cos(a) * kick
            vy[i] += sin(a) * kick
        }
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

        // A beat to mark resuming playback.
        if (playing && !wasPlaying && ready && !reduceMotion) ripple(0.9f)
        wasPlaying = playing
        // Real beats when the visualiser hears the music; if it only hears silence (some players
        // and phones hide their audio from it), fall back to the steady pulse after a moment.
        if (spectrum != null && playing) {
            var level = 0f
            for (v in spectrum) level += v
            if (level > spectrum.size * 0.02f) lastHeard = t
        }
        val heard = spectrum != null && t - lastHeard < 1.6f
        if (spectrum != null && playing && heard) {
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

    /** Seconds since this started; the dot field breathes on the same clock. */
    val time: Float get() = t

    /**
     * How brightly a beat's ripple lights a dot at [dist] (0 at the ripple's source, 1 at the far
     * corner), 0..~1.3: a narrow ring of light that runs outward and fades, so the dot field
     * ripples in step with the record.
     */
    fun light(dist: Float): Float = light(Float.NaN, Float.NaN, 0f, 0f, 1f, dist)

    /**
     * The same for a dot at ([px], [py]): ripples from the record are measured from ([ox], [oy]),
     * ones from a touch from where the finger landed; [reach] turns pixels into 0..1.
     */
    fun light(px: Float, py: Float, ox: Float, oy: Float, reach: Float, recordDist: Float = Float.NaN): Float {
        var k = 0f
        for (j in rippleT.indices) {
            val age = t - rippleT[j]
            if (age < 0f || age > RIPPLE_LIFE) continue
            val dist = if (rippleX[j].isNaN()) {
                if (recordDist.isNaN()) { val dx = px - ox; val dy = py - oy; sqrt(dx * dx + dy * dy) / reach } else recordDist
            } else { val dx = px - rippleX[j]; val dy = py - rippleY[j]; sqrt(dx * dx + dy * dy) / reach }
            val front = age * 1.25f - dist
            if (front > 0.22f || front < -0.22f) continue
            val fade = 1f - age / RIPPLE_LIFE
            k = max(k, rippleS[j] * exp(-front * front / (2f * 0.0045f)) * sqrt(fade))
        }
        return k
    }

    /**
     * How far the ripples push a dot at ([px], [py]) away from their source, as a fraction of a
     * cell, written to [out] (x, y). Like the full player, dots are shoved outward as a ring passes.
     */
    fun push(px: Float, py: Float, ox: Float, oy: Float, reach: Float, out: FloatArray) {
        out[0] = 0f; out[1] = 0f
        for (j in rippleT.indices) {
            val age = t - rippleT[j]
            if (age < 0f || age > RIPPLE_LIFE) continue
            val sx = if (rippleX[j].isNaN()) ox else rippleX[j]
            val sy = if (rippleY[j].isNaN()) oy else rippleY[j]
            val dx = px - sx; val dy = py - sy
            val d = sqrt(dx * dx + dy * dy)
            if (d < 0.5f) continue
            val front = age * 1.25f - d / reach
            if (front > 0.22f || front < -0.22f) continue
            val e = rippleS[j] * exp(-front * front / (2f * 0.0045f)) * (1f - age / RIPPLE_LIFE)
            out[0] += dx / d * e; out[1] += dy / d * e
        }
    }

    /** True while any ripple is still crossing the island. */
    val rippling: Boolean get() = rippleT.any { t - it in 0f..RIPPLE_LIFE }

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
            var r = max(DotArt.halftoneRadius(lum, cell), cell * 0.16f)
            r *= 1f + (if (playing) 0.07f else 0.02f) * sin(t * 1.8f - homeR[i] * 0.45f)
            var lit = 0f
            for (j in rippleT.indices) {
                val age = t - rippleT[j]
                if (age < 0f || age > RIPPLE_LIFE) continue
                val front = age * n * 1.1f - homeR[i]
                lit = max(lit, rippleS[j] * exp(-front * front / 1.6f) * (1f - age / RIPPLE_LIFE))
            }
            r = min(r * (1f + 0.9f * lit), cell * 0.62f)
            paint.color = blend(blend(colFrom[i], colTo[i], m), Color.WHITE, min(1f, lit * 0.8f))
            paint.alpha = (alpha * min(1f, dim + lit)).toInt().coerceIn(0, 255)
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

    private companion object { const val RIPPLE_LIFE = 1.1f }

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


/**
 * The whole island as white dots with the album cover carved into them: a halftone where the
 * cover's bright parts are big dots and its dark parts small ones, edge to edge across the pill.
 * It breathes and ripples with [IslandDots], and fades from one cover into the next.
 *
 * The cover is reduced once per song to a small brightness map; each frame only looks values up.
 */
class IslandDotField {
    private val shove = FloatArray(2)
    private val size = 40
    // Halftone coverage (0..1) per cell, already shaped by the contrast curve.
    private val cov = FloatArray(size * size)
    private val covFrom = FloatArray(size * size)
    private var source: Bitmap? = null
    private var mix = 1f
    private var ready = false

    fun prepare(art: Bitmap?, animate: Boolean) {
        if (ready && art === source) return
        source = art
        val m = ease(mix)
        for (i in cov.indices) covFrom[i] = if (ready) covFrom[i] + (cov[i] - covFrom[i]) * m else 0f
        if (art == null) {
            for (y in 0 until size) for (x in 0 until size) {
                val dx = x - size / 2f; val dy = y - size / 2f
                cov[y * size + x] = 0.2f + 0.2f * (1f - min(1f, sqrt(dx * dx + dy * dy) / size))
            }
        } else {
            val colors = DotArt.sample(art, size)
            var lo = 1f; var hi = 0f
            for (i in colors.indices) { val l = DotArt.luminance(colors[i]); cov[i] = l; lo = min(lo, l); hi = max(hi, l) }
            // Stretch the contrast so even a dark cover reads as a picture in dots; a nearly
            // flat cover keeps its own brightness instead of being stretched to nothing.
            if (hi - lo > 0.06f) {
                val span = max(0.2f, hi - lo)
                for (i in cov.indices) cov[i] = ((cov[i] - lo) / span).coerceIn(0f, 1f)
            }
            // Deepen the darks so the picture stands out of the field instead of a flat grid.
            for (i in cov.indices) cov[i] = cov[i].pow(1.35f)
        }
        mix = if (ready && animate) 0f else 1f
        if (!ready || !animate) for (i in cov.indices) covFrom[i] = cov[i]
        ready = true
    }

    fun step(dt: Float) { if (mix < 1f) mix = min(1f, mix + dt / 0.9f) }

    /**
     * Fills [l],[t],[r],[b] (already clipped to the island's shape) with dots [pitch] apart.
     * [alpha] is the field's resting strength, low enough that text on top stays readable; the
     * beat ripples from ([ox], [oy]) light dots up toward [peak]. Dots within [hole] of the
     * ripple's source fade out, carving room for the record.
     */
    fun draw(canvas: Canvas, paint: Paint, l: Float, t: Float, r: Float, b: Float, pitch: Float,
             dots: IslandDots, playing: Boolean, alpha: Int, peak: Int = alpha,
             ox: Float = (l + r) / 2f, oy: Float = (t + b) / 2f, hole: Float = 0f) {
        if (!ready || alpha <= 2) return
        val w = r - l
        val h = b - t
        if (w < pitch || h < pitch) return
        val cols = (w / pitch).toInt() + 1
        val rows = (h / pitch).toInt() + 1
        val gx = l + (w - (cols - 1) * pitch) / 2f
        val gy = t + (h - (rows - 1) * pitch) / 2f
        // Centre-crop the square cover to the island's shape.
        val side = max(w, h)
        val u0 = (side - w) / 2f / side
        val v0 = (side - h) / 2f / side
        // Ripples are measured from their source to the farthest corner.
        val reach = max(1f, sqrt(max((ox - l) * (ox - l), (r - ox) * (r - ox)) + max((oy - t) * (oy - t), (b - oy) * (b - oy))))
        val m = ease(mix)
        val time = dots.time
        val breathe = if (playing) 0.07f else 0.02f
        val rippling = dots.rippling
        val holeIn = hole * 0.92f
        val holeOut = hole * 1.3f
        paint.color = Color.WHITE
        for (yy in 0 until rows) {
            val py = gy + yy * pitch
            val v = (v0 + (py - t) / side).coerceIn(0f, 0.999f)
            for (xx in 0 until cols) {
                var px = gx + xx * pitch
                val u = (u0 + (px - l) / side).coerceIn(0f, 0.999f)
                val i = (v * size).toInt() * size + (u * size).toInt()
                val c = covFrom[i] + (cov[i] - covFrom[i]) * m
                val dx = px - ox
                val dy = py - oy
                val d = sqrt(dx * dx + dy * dy)
                var fade = 1f
                if (hole > 0f && d < holeOut) {
                    if (d <= holeIn) continue
                    val k = (d - holeIn) / (holeOut - holeIn)
                    fade = k * k * (3f - 2f * k)
                }
                val dist = d / reach
                val lit = if (rippling) min(1f, dots.light(px, py, ox, oy, reach, dist)) else 0f
                var rad = pitch * (0.07f + 0.31f * c) * (1f + breathe * sin(time * 1.8f - dist * 6f))
                var a = alpha * (0.12f + 0.88f * c)
                var y = py
                if (lit > 0.01f) {
                    rad *= 1f + 1.2f * lit
                    a += (peak - a) * lit
                    // The ring shoves dots outward as it passes, like the full player.
                    dots.push(px, py, ox, oy, reach, shove)
                    px += shove[0] * pitch * 0.6f; y += shove[1] * pitch * 0.6f
                }
                a *= fade
                if (a < 3f) continue
                paint.alpha = a.toInt().coerceIn(0, 255)
                canvas.drawCircle(px, y, min(rad, pitch * 0.55f), paint)
            }
        }
    }

    private fun ease(v: Float) = 1f - (1f - v) * (1f - v) * (1f - v)
}
