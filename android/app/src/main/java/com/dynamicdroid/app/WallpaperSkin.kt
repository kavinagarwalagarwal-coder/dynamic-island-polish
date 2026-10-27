package com.dynamicdroid.app

import android.content.Context
import android.graphics.*
import android.media.ExifInterface
import android.net.Uri
import android.util.AtomicFile
import java.io.File
import kotlin.math.*

/** The single imported image stays in private, non-backed-up storage. No gallery permission. */
object WallpaperSkinStore {
    private var cached: Bitmap? = null
    private var cachedBackground: Bitmap? = null
    private var cachedSubject: Bitmap? = null
    private fun layerFile(context: Context, kind: String) = File(context.noBackupFilesDir, "island-wallpaper-$kind.png")
    @Synchronized fun background(context: Context): Bitmap? {
        if (cachedBackground == null && layerFile(context, "background").exists()) cachedBackground = BitmapFactory.decodeFile(layerFile(context, "background").path)
        return cachedBackground
    }
    @Synchronized fun subject(context: Context): Bitmap? {
        if (cachedSubject == null && layerFile(context, "subject").exists()) cachedSubject = BitmapFactory.decodeFile(layerFile(context, "subject").path)
        return cachedSubject
    }
    @Synchronized fun saveAnalysis(context: Context, analysis: LocalWallpaperAnalyzer.Analysis) {
        for ((kind, bitmap) in listOf("background" to analysis.background, "subject" to analysis.subject)) {
            if (bitmap == null) AtomicFile(layerFile(context, kind)).delete() else {
                val atomic = AtomicFile(layerFile(context, kind)); val stream = atomic.startWrite()
                try { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)); atomic.finishWrite(stream) }
                catch (e: Exception) { atomic.failWrite(stream); throw e }
            }
        }
        cachedBackground = analysis.background; cachedSubject = analysis.subject
    }
    private fun file(context: Context) = File(context.noBackupFilesDir, "island-wallpaper.png")
    private fun prefs(context: Context) = context.getSharedPreferences("wallpaper_motion", Context.MODE_PRIVATE)

    fun read(context: Context): WallpaperMotion = prefs(context).let { p ->
        WallpaperMotion(enabled = p.getBoolean("enabled", false), spin = p.getBoolean("spin", true), x = p.getFloat("x", .5f),
            y = p.getFloat("y", .5f), radius = p.getFloat("radius", .3f), strength = p.getFloat("strength", .45f), periodSeconds = p.getFloat("period", 16f),
            automatic = p.getBoolean("automatic", true), autoEffect = p.getString("autoEffect", "drift") ?: "drift",
            autoLabel = p.getString("autoLabel", "Choose an image to recognise its subject") ?: "",
            autoConfidence = p.getFloat("autoConfidence", 0f), glass = p.getBoolean("glass", true)).safe()
    }
    fun save(context: Context, motion: WallpaperMotion) {
        val m = motion.safe()
        prefs(context).edit().putBoolean("enabled", m.enabled).putBoolean("spin", m.spin)
            .putFloat("x", m.x).putFloat("y", m.y).putFloat("radius", m.radius)
            .putFloat("strength", m.strength).putFloat("period", m.periodSeconds)
            .putBoolean("automatic", m.automatic).putString("autoEffect", m.autoEffect).putString("autoLabel", m.autoLabel)
            .putFloat("autoConfidence", m.autoConfidence).putBoolean("glass", m.glass).apply()
    }
    @Synchronized fun image(context: Context): Bitmap? {
        if (cached == null && file(context).exists()) cached = runCatching { AtomicFile(file(context)).openRead().use { BitmapFactory.decodeStream(it) } }.getOrNull()
        return cached
    }
    @Synchronized fun remove(context: Context) {
        AtomicFile(file(context)).delete()
        // Existing views can retain the old bitmap until their next settings refresh.
        cached = null
        cachedSubject = null; cachedBackground = null
        AtomicFile(layerFile(context, "subject")).delete(); AtomicFile(layerFile(context, "background")).delete()
        save(context, read(context).copy(enabled = false, autoLabel = "Choose an image to recognise its subject", autoConfidence = 0f, autoEffect = "drift"))
    }
    fun importImage(context: Context, uri: Uri): Bitmap {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Not a supported image" }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > 1024) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: error("Image could not be read")
        val orientation = runCatching { resolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } }.getOrNull()
        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(270f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
            }
        }
        val bitmap = if (matrix.isIdentity) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (bitmap !== decoded) decoded.recycle()
        val atomic = AtomicFile(file(context))
        val stream = atomic.startWrite()
        try {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
            atomic.finishWrite(stream)
        } catch (e: Exception) { atomic.failWrite(stream); throw e }
        synchronized(this) {
            cached = bitmap; cachedSubject = null; cachedBackground = null
            AtomicFile(layerFile(context, "subject")).delete(); AtomicFile(layerFile(context, "background")).delete()
        }
        return bitmap
    }
}

/** Reuses its paint, matrix and clip; only the selected disc rotates, the photograph stays still. */
class WallpaperSkinRenderer {
    var image: Bitmap? = null
    var background: Bitmap? = null
    var subject: Bitmap? = null
    var motion = WallpaperMotion()
    private var elapsedMs = 0L
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val matrix = Matrix()
    private val circle = Path()
    val enabled get() = motion.enabled && image != null
    fun advance(dtSeconds: Float) { elapsedMs += (dtSeconds * 1000).toLong() }
    fun draw(canvas: Canvas, rect: RectF, alpha: Int) {
        val bitmap = image ?: return
        if (!enabled || alpha <= 0) return
        val m = motion.safe()
        val phase = m.angle(elapsedMs)
        val effect = m.effect
        val spin = effect == "spin"
        val layered = effect in setOf("sway", "glide", "breathe") && background != null && subject != null
        val scale = max(rect.width() / bitmap.width, rect.height() / bitmap.height) * if (spin || layered) 1f else 1.08f
        val radius = min(bitmap.width, bitmap.height) * m.radius
        val px = (bitmap.width * m.x).coerceIn(radius, bitmap.width - radius)
        val py = (bitmap.height * m.y).coerceIn(radius, bitmap.height - radius)
        val driftX = if (spin || layered) 0f else sin(phase * PI / 180).toFloat() * rect.width() * .025f
        val driftY = if (spin || layered) 0f else cos(phase * PI / 180).toFloat() * rect.height() * .025f
        val dx = (rect.centerX() - px * scale + driftX).coerceIn(rect.right - bitmap.width * scale, rect.left)
        val dy = (rect.centerY() - py * scale + driftY).coerceIn(rect.bottom - bitmap.height * scale, rect.top)
        matrix.setScale(scale, scale); matrix.postTranslate(dx, dy)
        paint.color = Color.WHITE; paint.alpha = (alpha * m.strength).toInt()
        canvas.drawBitmap(if (layered) background!! else bitmap, matrix, paint)
        if (spin) {
            val cx = dx + px * scale; val cy = dy + py * scale
            circle.reset(); circle.addCircle(cx, cy, radius * scale, Path.Direction.CW)
            canvas.save(); canvas.clipPath(circle)
            // Replace rather than alpha-stack the disc so its strength matches the still image.
            paint.color = Color.BLACK; paint.alpha = alpha
            canvas.drawCircle(cx, cy, radius * scale, paint)
            canvas.rotate(phase, cx, cy)
            paint.color = Color.WHITE; paint.alpha = (alpha * m.strength).toInt()
            canvas.drawBitmap(bitmap, matrix, paint)
            canvas.restore()
        } else if (layered) {
            val wave = sin(phase * PI / 180).toFloat()
            val cx = dx + px * scale; val cy = dy + py * scale
            canvas.save()
            when (effect) {
                "sway" -> canvas.rotate(wave * 1.2f, cx, dy + bitmap.height * scale)
                "glide" -> canvas.translate(wave * rect.width() * .015f, 0f)
                else -> canvas.scale(1.015f + wave * .01f, 1.015f + wave * .01f, cx, cy)
            }
            canvas.drawBitmap(subject!!, matrix, paint)
            canvas.restore()
        }
    }
}
