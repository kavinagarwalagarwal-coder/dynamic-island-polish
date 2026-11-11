package com.dynamicdroid.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.PI
import kotlin.math.abs
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
    // Home position as x/y (cells from the centre), so spinning is a rotation, not trig per dot.
    private val homeX = FloatArray(count)
    /** Halftone dot size for each dot's current cover brightness, per cell (pow is slow). */
    private val radTo = FloatArray(count)
    private val homeY = FloatArray(count)
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
    // The waveform's columns, eased separately from the ring: quick to rise, slow to fall.
    private val meter = FloatArray(METER_COLS)

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
            homeX[i] = dx
            homeY[i] = dy
            val c = sampled?.get(i) ?: Color.rgb(90, 90, 90)
            val lum = if (sampled == null) 0.3f + 0.25f * (1f - d / half) else DotArt.luminance(c)
            val color = if (mono || sampled == null) Color.WHITE else DotArt.vivid(c)
            colFrom[i] = if (!first) blend(colFrom[i], colTo[i], ease(mix)) else color
            lumFrom[i] = if (!first) lumFrom[i] + (lumTo[i] - lumFrom[i]) * ease(mix) else lum
            colTo[i] = color
            lumTo[i] = lum
            radTo[i] = DotArt.halftoneRadius(lum, 1f)
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

        // Each waveform column listens to a slice of the ring (averaged, so it doesn't flicker)
        // and gets a share of the beat; middle columns reach higher, like a voice.
        val since = t - lastBeat
        val kick = if (playing && !reduceMotion) exp(-since * 7f) * 0.35f else 0f
        for (c in 0 until METER_COLS) {
            val from = c * bands / METER_COLS
            val to = (c + 1) * bands / METER_COLS
            var v = 0f
            for (b in from until to) v += ring[b]
            v /= (to - from)
            val shape = 0.62f + 0.38f * sin(PI.toFloat() * (c + 0.5f) / METER_COLS)
            val target = if (playing) ((v * 1.15f + kick) * shape).coerceIn(0f, 1f) else 0f
            val rate = if (target > meter[c]) 16f else 4.5f
            meter[c] += (target - meter[c]) * min(1f, dt * rate)
        }
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
    fun light(px: Float, py: Float, ox: Float, oy: Float, reach: Float, recordDist: Float = Float.NaN,
              minStrength: Float = 0f): Float {
        var k = 0f
        for (j in rippleT.indices) {
            val age = t - rippleT[j]
            if (age < 0f || age > RIPPLE_LIFE || rippleS[j] < minStrength) continue
            val dist = if (rippleX[j].isNaN()) {
                if (recordDist.isNaN()) { val dx = px - ox; val dy = py - oy; sqrt(dx * dx + dy * dy) / reach } else recordDist
            } else { val dx = px - rippleX[j]; val dy = py - rippleY[j]; sqrt(dx * dx + dy * dy) / reach }
            val front = age * 1.25f - dist
            if (front > 0.14f || front < -0.14f) continue
            val fade = 1f - age / RIPPLE_LIFE
            k = max(k, rippleS[j] * exp(-front * front / (2f * 0.0025f)) * sqrt(fade))
        }
        return k
    }

    /**
     * How far the ripples push a dot at ([px], [py]) away from their source, as a fraction of a
     * cell, written to [out] (x, y). Like the full player, dots are shoved outward as a ring passes.
     */
    fun push(px: Float, py: Float, ox: Float, oy: Float, reach: Float, out: FloatArray, minStrength: Float = 0f) {
        out[0] = 0f; out[1] = 0f
        for (j in rippleT.indices) {
            val age = t - rippleT[j]
            if (age < 0f || age > RIPPLE_LIFE || rippleS[j] < minStrength) continue
            val sx = if (rippleX[j].isNaN()) ox else rippleX[j]
            val sy = if (rippleY[j].isNaN()) oy else rippleY[j]
            val dx = px - sx; val dy = py - sy
            val d = sqrt(dx * dx + dy * dy)
            if (d < 0.5f) continue
            val front = age * 1.25f - d / reach
            if (front > 0.14f || front < -0.14f) continue
            val e = rippleS[j] * exp(-front * front / (2f * 0.0025f)) * (1f - age / RIPPLE_LIFE)
            out[0] += dx / d * e; out[1] += dy / d * e
        }
    }

    /** True while any ripple is still crossing the island. */
    val rippling: Boolean get() {
        for (j in rippleT.indices) { val age = t - rippleT[j]; if (age >= 0f && age <= RIPPLE_LIFE) return true }
        return false
    }

    /**
     * Where the live ripples' rings are, for skipping dots they can't touch: fills [fronts] with
     * each record ripple's ring distance (0..1+) and returns how many; -1 if a touch ripple is
     * live (its rings aren't centred on the record, so nothing can be skipped).
     */
    fun recordFronts(fronts: FloatArray, minStrength: Float = 0f): Int {
        var n = 0
        for (j in rippleT.indices) {
            val age = t - rippleT[j]
            if (age < 0f || age > RIPPLE_LIFE || rippleS[j] < minStrength) continue
            if (!rippleX[j].isNaN()) return -1
            fronts[n++] = age * 1.25f
        }
        return n
    }

    /** The record, [radius] pixels, centred on ([cx], [cy]). */
    // Per-frame ripple fronts for the record, so each dot checks a few numbers instead of
    // working every ripple out again.
    private val liveFront = FloatArray(4)
    private val liveGain = FloatArray(4)

    fun drawRecord(canvas: Canvas, paint: Paint, cx: Float, cy: Float, radius: Float, playing: Boolean, alpha: Int) {
        if (!ready) return
        val cell = radius * 2f / n
        val m = ease(mix)
        val dim = if (playing) 1f else 0.6f
        var live = 0
        for (j in rippleT.indices) {
            val age = t - rippleT[j]
            if (age < 0f || age > RIPPLE_LIFE) continue
            liveFront[live] = age * n * 1.1f
            liveGain[live] = rippleS[j] * (1f - age / RIPPLE_LIFE)
            live++
        }
        val cs = cos(spin) * cell
        val sn = sin(spin) * cell
        val breathe = if (playing) 0.07f else 0.02f
        val phase = t * 1.8f
        for (i in 0 until count) {
            if (!alive[i]) continue
            val x = cx + homeX[i] * cs - homeY[i] * sn + ox[i] * cell
            val y = cy + homeX[i] * sn + homeY[i] * cs + oy[i] * cell
            val frac = if (m >= 1f) radTo[i] else
                DotArt.halftoneRadius(lumFrom[i] + (lumTo[i] - lumFrom[i]) * m, 1f)
            var r = max(frac * cell, cell * 0.16f)
            r *= 1f + breathe * sin(phase - homeR[i] * 0.45f)
            var lit = 0f
            for (k in 0 until live) {
                val front = liveFront[k] - homeR[i]
                if (front > 3.5f || front < -3.5f) continue
                lit = max(lit, liveGain[k] * exp(-front * front / 1.6f))
            }
            r = min(r * (1f + 0.9f * lit), cell * 0.62f)
            val base = if (m >= 1f) colTo[i] else blend(colFrom[i], colTo[i], m)
            paint.color = if (lit > 0.01f) blend(base, Color.WHITE, min(1f, lit * 0.8f)) else base
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
            val ca = cos(a); val sa = sin(a)
            for (s in 0 until lit) {
                val rr = inner + s * pitch
                paint.color = if (s == lit - 1 && level > 0.62f) accent else Color.WHITE
                paint.alpha = ((if (playing) 0.85f else 0.3f) * alpha * (1f - s * 0.2f)).toInt().coerceIn(0, 255)
                canvas.drawCircle(cx + rr * ca, cy + rr * sa, dot * (1f - s * 0.12f), paint)
            }
        }
    }

    /**
     * The island's waveform in dots: [cols] columns of up to [rows] dots (odd) centred on [cy] in
     * the box from [left], [w] by [h]. Each column grows out from its middle dot both ways, and the
     * dot at its tip swells and fades in smoothly rather than popping on, so the shape reads as a
     * waveform and moves fluidly. [energy] (0 paused .. 1 playing) settles it to a row of dots.
     */
    fun drawMeter(canvas: Canvas, paint: Paint, left: Float, cy: Float, w: Float, h: Float, cols: Int, rows: Int,
                  color: Int, energy: Float, alpha: Int) {
        val px = w / cols
        val half = rows / 2
        val py = h / max(1, rows - 1)
        val dot = min(px * 0.34f, py * 0.42f)
        val e = energy.coerceIn(0f, 1f)
        paint.color = color
        for (c in 0 until cols) {
            val level = meter[min(METER_COLS - 1, c * METER_COLS / cols)] * e
            val reach = level * half
            val x = left + (c + .5f) * px
            paint.alpha = alpha
            canvas.drawCircle(x, cy, dot, paint)
            for (k in 1..half) {
                val f = (reach - (k - 1)).coerceIn(0f, 1f)
                if (f < 0.02f) break
                val r = dot * (0.35f + 0.65f * f)
                paint.alpha = (alpha * f).toInt()
                canvas.drawCircle(x, cy - k * py, r, paint)
                canvas.drawCircle(x, cy + k * py, r, paint)
            }
        }
    }

    private companion object { const val RIPPLE_LIFE = 1.1f; const val METER_COLS = 8 }

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
        // Layers are re-baked for the new cover; the view crossfades from the old ones.
        mix = 1f
        for (i in cov.indices) covFrom[i] = cov[i]
        version++
        ready = true
    }

    fun step(dt: Float) { if (mix < 1f) mix = min(1f, mix + dt / 0.9f) }

    /**
     * One pre-drawn halftone of the cover for a fixed island size: the dots are drawn once into an
     * alpha bitmap and then just blitted every frame, instead of hundreds of circles per frame.
     */
    class Layer {
        var bitmap: Bitmap? = null
        var w = 0f; var h = 0f; var pitch = 0f; var holeX = 0f; var holeY = 0f; var hole = 0f
        var scale = 0f; var version = -1
        /** The build in progress, so the same layer isn't requested twice. */
        var pending: String? = null
        fun matches(w: Float, h: Float, pitch: Float, holeX: Float, holeY: Float, hole: Float, scale: Float, version: Int) =
            bitmap != null && this.version == version && abs(this.w - w) < 0.5f && abs(this.h - h) < 0.5f &&
                this.pitch == pitch && abs(this.holeX - holeX) < 0.5f && abs(this.holeY - holeY) < 0.5f &&
                this.hole == hole && this.scale == scale
        // Dropped, not recycled: a frame already recorded may still be drawing it.
        fun release() { bitmap = null }
    }

    /** Bumped whenever the cover changes, so cached layers rebuild. */
    private var version = 0
    val coverVersion: Int get() = version

    /** Coverage (0..1) of the halftone cell at [u],[v] in the cover, for the current cover. */
    private fun coverage(u: Float, v: Float): Float = coverageOf(cov, u, v)

    private fun cells(w: Float, h: Float, pitch: Float) =
        Triple((w / pitch).toInt() + 1, (h / pitch).toInt() + 1, max(w, h))

    /**
     * Makes sure [layer] holds the field for a [w]×[h] island (local units, drawn at [scale]
     * pixels per unit) with dots [pitch] apart and a clearing of radius [hole] around
     * ([holeX], [holeY]) for the record. Rebuilt only when one of those or the cover changes.
     * With [async] the dots are drawn on a worker thread (the old layer keeps showing meanwhile)
     * and [onReady] runs on the main thread once the new one is in place.
     */
    fun bake(layer: Layer, w: Float, h: Float, pitch: Float, holeX: Float, holeY: Float, hole: Float, scale: Float,
             async: Boolean = false, onReady: () -> Unit = {}) {
        if (!ready || w < pitch || h < pitch) return
        if (layer.matches(w, h, pitch, holeX, holeY, hole, scale, version)) return
        val key = "$w|$h|$pitch|$holeX|$holeY|$hole|$scale|$version"
        if (layer.pending == key) return
        layer.pending = key
        val coverage = cov.copyOf()
        val v = version
        val job: () -> Unit = {
            val bitmap = render(coverage, w, h, pitch, holeX, holeY, hole, scale)
            val apply: () -> Unit = {
                if (layer.pending == key && bitmap != null) {
                    layer.bitmap = bitmap
                    layer.w = w; layer.h = h; layer.pitch = pitch; layer.holeX = holeX; layer.holeY = holeY
                    layer.hole = hole; layer.scale = scale; layer.version = v; layer.pending = null
                    onReady()
                }
            }
            if (async) main.post { apply() } else apply()
        }
        if (async) worker.execute { job() } else job()
    }

    private fun render(coverage: FloatArray, w: Float, h: Float, pitch: Float, holeX: Float, holeY: Float,
                       hole: Float, scale: Float): Bitmap? {
        val bw = (w * scale).toInt().coerceAtLeast(1)
        val bh = (h * scale).toInt().coerceAtLeast(1)
        val bitmap = runCatching { Bitmap.createBitmap(bw, bh, Bitmap.Config.ALPHA_8) }.getOrNull() ?: return null
        val canvas = Canvas(bitmap)
        canvas.scale(scale, scale)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val (cols, rows, side) = cells(w, h, pitch)
        val gx = (w - (cols - 1) * pitch) / 2f
        val gy = (h - (rows - 1) * pitch) / 2f
        val u0 = (side - w) / 2f / side
        val v0 = (side - h) / 2f / side
        val holeIn = hole * 0.92f
        val holeOut = hole * 1.3f
        for (yy in 0 until rows) {
            val py = gy + yy * pitch
            for (xx in 0 until cols) {
                val px = gx + xx * pitch
                val c = coverageOf(coverage, u0 + px / side, v0 + py / side)
                var fade = 1f
                if (hole > 0f) {
                    val dx = px - holeX; val dy = py - holeY
                    val d = sqrt(dx * dx + dy * dy)
                    if (d <= holeIn) continue
                    if (d < holeOut) { val k = (d - holeIn) / (holeOut - holeIn); fade = k * k * (3f - 2f * k) }
                }
                val a = 255f * (0.12f + 0.88f * c) * fade
                if (a < 3f) continue
                paint.alpha = a.toInt().coerceIn(0, 255)
                canvas.drawCircle(px, py, min(pitch * (0.07f + 0.31f * c), pitch * 0.55f), paint)
            }
        }
        return bitmap
    }

    private fun coverageOf(coverage: FloatArray, u: Float, v: Float): Float {
        val i = (v.coerceIn(0f, 0.999f) * size).toInt() * size + (u.coerceIn(0f, 0.999f) * size).toInt()
        return coverage[i]
    }

    private companion object {
        const val FIELD_RIPPLE_MIN = 0.8f
        val worker: java.util.concurrent.ExecutorService = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "island-dots").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }
        }
        val main = android.os.Handler(android.os.Looper.getMainLooper())
    }

    private val dst = android.graphics.RectF()
    private val fronts = FloatArray(4)
    private var marks = BooleanArray(64)

    /** Blits [layer] with its top-left at ([left], [top]); [alpha] is the field's strength. */
    fun drawLayer(canvas: Canvas, paint: Paint, layer: Layer, left: Float, top: Float, alpha: Int) {
        val bitmap = layer.bitmap ?: return
        if (alpha <= 2) return
        paint.color = Color.WHITE
        paint.alpha = alpha.coerceIn(0, 255)
        dst.set(left, top, left + layer.w, top + layer.h)
        canvas.drawBitmap(bitmap, null, dst, paint)
    }

    /**
     * The beat ripples on top of a baked [layer] at ([left], [top]): only the dots the ring of
     * light is crossing are drawn, brighter, larger and shoved outward, so a beat costs a few
     * dozen circles instead of the whole field. Ripples start at ([ox], [oy]) (island units).
     */
    fun drawRipples(canvas: Canvas, paint: Paint, layer: Layer, left: Float, top: Float,
                    dots: IslandDots, ox: Float, oy: Float, reach: Float, peak: Int) {
        if (layer.bitmap == null || peak <= 2 || !dots.rippling) return
        // Across the whole island only the strong beats ripple (taps, song changes, the
        // downbeats); every beat still pulses the record itself.
        val strong = FIELD_RIPPLE_MIN
        val w = layer.w; val h = layer.h; val pitch = layer.pitch
        val (cols, rows, side) = cells(w, h, pitch)
        val gx = left + (w - (cols - 1) * pitch) / 2f
        val gy = top + (h - (rows - 1) * pitch) / 2f
        val u0 = (side - w) / 2f / side
        val v0 = (side - h) / 2f / side
        val holeX = left + layer.holeX; val holeY = top + layer.holeY
        val holeOut2 = (layer.hole * 1.3f).let { it * it }
        val count = dots.recordFronts(fronts, strong)
        if (count == 0) return
        paint.color = Color.WHITE
        if (marks.size < cols) marks = BooleanArray(cols + 16)
        val band = 0.12f * reach
        for (yy in 0 until rows) {
            val py = gy + yy * pitch
            val dy = py - oy
            if (count >= 0) {
                // Only the cells this row shares with a ring: work them out per row from the
                // ring's radius instead of testing every dot on the island.
                java.util.Arrays.fill(marks, 0, cols, false)
                var any = false
                for (k in 0 until count) {
                    val r = fronts[k] * reach
                    val outer = r + band
                    if (abs(dy) >= outer) continue
                    val inner = r - band
                    val far = sqrt(outer * outer - dy * dy)
                    val near = if (inner > abs(dy)) sqrt(inner * inner - dy * dy) else 0f
                    for (side2 in 0..1) {
                        val sign = if (side2 == 0) -1f else 1f
                        val a0 = ox + sign * near; val a1 = ox + sign * far
                        val from = ((min(a0, a1) - gx) / pitch).toInt().coerceAtLeast(0)
                        val to = (kotlin.math.ceil((max(a0, a1) - gx) / pitch).toInt()).coerceAtMost(cols - 1)
                        for (c in from..to) { marks[c] = true; any = true }
                    }
                }
                if (!any) continue
            }
            for (xx in 0 until cols) {
                if (count >= 0 && !marks[xx]) continue
                val px = gx + xx * pitch
                val dx = px - ox
                val dist = sqrt(dx * dx + dy * dy) / reach
                val lit = min(1f, dots.light(px, py, ox, oy, reach, dist, strong))
                if (lit < 0.08f) continue
                val hx = px - holeX; val hy = py - holeY
                if (hx * hx + hy * hy < holeOut2) continue
                val c = coverage(u0 + (px - left) / side, v0 + (py - top) / side)
                val rad = min(pitch * (0.07f + 0.31f * c) * (1f + 1.2f * lit), pitch * 0.55f)
                dots.push(px, py, ox, oy, reach, shove, strong)
                paint.alpha = (peak * lit).toInt().coerceIn(0, 255)
                canvas.drawCircle(px + shove[0] * pitch * 0.6f, py + shove[1] * pitch * 0.6f, rad, paint)
            }
        }
    }

    private fun ease(v: Float) = 1f - (1f - v) * (1f - v) * (1f - v)
}
