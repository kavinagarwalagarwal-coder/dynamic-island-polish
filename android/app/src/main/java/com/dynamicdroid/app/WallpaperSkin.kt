package com.dynamicdroid.app

import android.content.Context
import android.graphics.*
import android.media.ExifInterface
import android.net.Uri
import android.util.AtomicFile
import java.io.File
import kotlin.math.max

/** One sampled still image, private and excluded from Android backup. */
object WallpaperSkinStore {
    private var cached: Bitmap? = null
    private var loaded = false
    private fun file(context: Context) = File(context.noBackupFilesDir, "island-wallpaper.png")
    private fun prefs(context: Context) = context.getSharedPreferences("wallpaper_motion", Context.MODE_PRIVATE)

    fun read(context: Context): WallpaperStyle = prefs(context).let { p ->
        WallpaperStyle(p.getBoolean("enabled", false), p.getBoolean("glass", true),
            p.getFloat("x", .5f), p.getFloat("y", .5f), p.getFloat("strength", .45f)).safe()
    }
    fun save(context: Context, style: WallpaperStyle) {
        val s = style.safe()
        prefs(context).edit().putBoolean("enabled", s.enabled).putBoolean("glass", s.glass)
            .putFloat("x", s.x).putFloat("y", s.y).putFloat("strength", s.strength).apply()
    }
    /** Release old segmentation assets on upgrade, retaining the user's original selected photo. */
    fun migrate(context: Context) {
        for (kind in listOf("background", "subject")) AtomicFile(File(context.noBackupFilesDir, "island-wallpaper-$kind.png")).delete()
        prefs(context).edit().remove("automatic").remove("spin").remove("radius").remove("period")
            .remove("autoEffect").remove("autoLabel").remove("autoConfidence").apply()
    }
    @Synchronized fun image(context: Context): Bitmap? {
        if (!loaded) {
            cached = runCatching { AtomicFile(file(context)).openRead().use { BitmapFactory.decodeStream(it) } }.getOrNull()
            loaded = true
        }
        return cached
    }
    @Synchronized fun remove(context: Context) {
        AtomicFile(file(context)).delete()
        cached = null; loaded = true
        migrate(context)
        save(context, read(context).copy(enabled = false))
    }
    fun decodeImage(context: Context, uri: Uri): Bitmap {
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
        return bitmap
    }
    fun importImage(context: Context, uri: Uri): Bitmap {
        val bitmap = decodeImage(context, uri)
        val atomic = AtomicFile(file(context)); val stream = atomic.startWrite()
        try { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)); atomic.finishWrite(stream) }
        catch (e: Exception) { atomic.failWrite(stream); throw e }
        synchronized(this) { cached = bitmap; loaded = true }
        return bitmap
    }
}

/** Static source, reused by cached glass RenderNodes and the older Android fallback. */
class WallpaperSkinRenderer {
    var image: Bitmap? = null
    var style = WallpaperStyle()
        set(value) { field = value.safe() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val matrix = Matrix()
    val enabled get() = style.enabled && image?.isRecycled == false
    fun draw(canvas: Canvas, rect: RectF, alpha: Int) {
        val bitmap = image ?: return
        if (!enabled || alpha <= 0 || !rect.width().isFinite() || !rect.height().isFinite() || rect.width() <= 0f || rect.height() <= 0f) return
        val scale = max(rect.width() / bitmap.width, rect.height() / bitmap.height)
        val dx = WallpaperCrop.offset(rect.left, rect.right, bitmap.width * scale, bitmap.width * style.x * scale, 0f)
        val dy = WallpaperCrop.offset(rect.top, rect.bottom, bitmap.height * scale, bitmap.height * style.y * scale, 0f)
        matrix.setScale(scale, scale); matrix.postTranslate(dx, dy)
        paint.alpha = (alpha * style.strength).toInt().coerceIn(0, 255)
        canvas.drawBitmap(bitmap, matrix, paint)
    }
}
