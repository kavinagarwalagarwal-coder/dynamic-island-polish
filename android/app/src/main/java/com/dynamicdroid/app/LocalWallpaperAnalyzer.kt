package com.dynamicdroid.app

import android.content.Context
import android.graphics.*
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/** Bundled EfficientNet-Lite0 + DeepLab-v3. CPU inference runs once on import, off the UI thread. */
object LocalWallpaperAnalyzer {
    data class Analysis(val label: String, val confidence: Float, val effect: String,
        val x: Float = .5f, val y: Float = .5f, val radius: Float = .3f,
        val background: Bitmap? = null, val subject: Bitmap? = null)
    private fun interpreter(context: Context, path: String): Interpreter {
        val bytes = context.assets.open(path).use { it.readBytes() }
        val model = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply { put(bytes); rewind() }
        return Interpreter(model, Interpreter.Options().setNumThreads(2))
    }
    private fun input(bitmap: Bitmap, width: Int, height: Int, mean: Float = 127.5f, std: Float = 127.5f): ByteBuffer {
        val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)
        if (scaled !== bitmap) scaled.recycle()
        return ByteBuffer.allocateDirect(width * height * 3 * 4).order(ByteOrder.nativeOrder()).apply {
            for (pixel in pixels) {
                putFloat((Color.red(pixel) - mean) / std)
                putFloat((Color.green(pixel) - mean) / std)
                putFloat((Color.blue(pixel) - mean) / std)
            }
            rewind()
        }
    }
    fun analyze(context: Context, bitmap: Bitmap): Analysis {
        val labels = context.assets.open("wallpaper-labels.txt").bufferedReader().use { it.readLines() }
        val scores = interpreter(context, "wallpaper-classifier.tflite").use { model ->
            val shape = model.getInputTensor(0).shape()
            val output = ByteBuffer.allocateDirect(model.getOutputTensor(0).numBytes()).order(ByteOrder.nativeOrder())
            model.run(input(bitmap, shape[2], shape[1], 127f, 128f), output)
            output.rewind()
            FloatArray(model.getOutputTensor(0).numElements()) { output.float }
        }
        val best = scores.indices.maxByOrNull { scores[it] } ?: 0
        val label = labels.getOrNull(best) ?: "Unrecognised subject"
        val confidence = scores[best]
        // Classification proposes rotation; geometry must independently find a circular object.
        val record = scores.indices.any { scores[it] >= .15f &&
            labels.getOrNull(it)?.lowercase() in setOf("turntable", "cd player", "disk brake", "analog clock", "wall clock") }
        if (record) {
            DiscLocator.locate(bitmap)?.let { disc ->
                return Analysis(label, confidence, "spin", disc[0], disc[1], disc[2])
            }
        }
        val segmentation = segment(context, bitmap)
        if (segmentation != null) return segmentation.copy(label = "${segmentation.label} · $label", confidence = confidence)
        return Analysis(if (confidence >= .3f) label else "No confident object", confidence, "drift")
    }

    private fun segment(context: Context, source: Bitmap): Analysis? {
        return interpreter(context, "wallpaper-segmenter.tflite").use { model ->
            val inputShape = model.getInputTensor(0).shape()
            val shape = model.getOutputTensor(0).shape()
            val h = shape[1]; val w = shape[2]; val classes = shape[3]
            val output = ByteBuffer.allocateDirect(model.getOutputTensor(0).numBytes()).order(ByteOrder.nativeOrder())
            model.run(input(source, inputShape[2], inputShape[1]), output)
            output.rewind()
            val category = IntArray(w * h)
            val counts = IntArray(classes)
            for (i in category.indices) {
                var best = 0; var score = -Float.MAX_VALUE
                for (j in 0 until classes) { val v = output.float; if (v > score) { score = v; best = j } }
                category[i] = best
                counts[best]++
            }
            val chosen = (1 until classes).maxByOrNull { counts[it] } ?: return@use null
            val coverage = counts[chosen].toFloat() / category.size
            if (coverage < .025f || coverage > .85f) return@use null
            val pixels = IntArray(w * h) { if (category[it] == chosen) Color.WHITE else Color.TRANSPARENT }
            val small = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
            val mask = Bitmap.createScaledBitmap(small, source.width, source.height, true)
            if (small !== mask) small.recycle()
            val subject = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
            val c = Canvas(subject)
            c.drawBitmap(source, 0f, 0f, null)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
            c.drawBitmap(mask, 0f, 0f, p)
            val background = fillSubject(source, mask)
            val alpha = IntArray(source.width * source.height)
            mask.getPixels(alpha, 0, source.width, 0, 0, source.width, source.height)
            var left = source.width; var right = 0; var top = source.height; var bottom = 0
            for (y in 0 until source.height) for (x in 0 until source.width) if (Color.alpha(alpha[y * source.width + x]) > 128) {
                left = min(left, x); right = max(right, x); top = min(top, y); bottom = max(bottom, y)
            }
            mask.recycle()
            val names = listOf("background", "aeroplane", "bicycle", "bird", "boat", "bottle", "bus", "car", "cat", "chair", "cow", "table", "dog", "horse", "motorbike", "person", "plant", "sheep", "sofa", "train", "screen")
            val effect = when (chosen) {
                3, 16 -> "sway"
                1, 2, 4, 6, 7, 14, 19 -> "glide"
                else -> "breathe"
            }
            Analysis(names.getOrElse(chosen) { "Subject" }, 1f, effect,
                (left + right) / 2f / source.width, (top + bottom) / 2f / source.height,
                background = background, subject = subject)
        }
    }

    /** Small local hole fill. Subtle motion limits exposure; this is not generative inpainting. */
    private fun fillSubject(source: Bitmap, mask: Bitmap): Bitmap {
        val w = source.width; val h = source.height
        val pixels = IntArray(w * h); val alpha = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h); mask.getPixels(alpha, 0, w, 0, 0, w, h)
        val filled = pixels.copyOf()
        for (y in 0 until h) {
            var x = 0
            while (x < w) {
                if (Color.alpha(alpha[y * w + x]) < 8) { x++; continue }
                val start = x
                while (x < w && Color.alpha(alpha[y * w + x]) >= 8) x++
                val end = x
                val l = if (start > 0) pixels[y * w + start - 1] else if (end < w) pixels[y * w + end] else pixels[0]
                val r = if (end < w) pixels[y * w + end] else l
                for (i in start until end) {
                    val t = (i - start).toFloat() / max(1, end - start)
                    filled[y * w + i] = Color.rgb((Color.red(l) * (1 - t) + Color.red(r) * t).toInt(),
                        (Color.green(l) * (1 - t) + Color.green(r) * t).toInt(), (Color.blue(l) * (1 - t) + Color.blue(r) * t).toInt())
                }
            }
        }
        return Bitmap.createBitmap(filled, w, h, Bitmap.Config.ARGB_8888)
    }
}

/** Edge-supported circle search only used after a compatible semantic classification. */
object DiscLocator {
    fun locate(source: Bitmap): FloatArray? {
        val scale = 160f / max(source.width, source.height)
        val bitmap = Bitmap.createScaledBitmap(source, max(1, (source.width * scale).toInt()), max(1, (source.height * scale).toInt()), true)
        val w = bitmap.width; val h = bitmap.height
        val pixels = IntArray(w * h); bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        if (bitmap !== source) bitmap.recycle()
        val gray = FloatArray(pixels.size) { (Color.red(pixels[it]) * .299f + Color.green(pixels[it]) * .587f + Color.blue(pixels[it]) * .114f) }
        val cosines = FloatArray(48) { cos(it * 2 * PI / 48).toFloat() }
        val sines = FloatArray(48) { sin(it * 2 * PI / 48).toFloat() }
        var best = 0f; var found: FloatArray? = null
        for (r in 12 until min(w, h) / 2 step 3) for (cy in r + 2 until h - r - 2 step 3) for (cx in r + 2 until w - r - 2 step 3) {
            var score = 0f; var supported = 0
            for (i in 0 until 48) {
                val x1 = (cx + (r - 2) * cosines[i]).toInt(); val y1 = (cy + (r - 2) * sines[i]).toInt()
                val x2 = (cx + (r + 2) * cosines[i]).toInt(); val y2 = (cy + (r + 2) * sines[i]).toInt()
                val edge = abs(gray[y1 * w + x1] - gray[y2 * w + x2])
                score += min(edge, 80f)
                if (edge > 18f) supported++
            }
            score = score / 48 * (supported / 48f)
            if (supported >= 32 && score > best && score > 14f) {
                best = score; found = floatArrayOf(cx.toFloat() / w, cy.toFloat() / h, r.toFloat() / min(w, h))
            }
        }
        return found
    }
}
