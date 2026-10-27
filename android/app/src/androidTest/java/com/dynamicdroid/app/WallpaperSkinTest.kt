package com.dynamicdroid.app

import android.graphics.*
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class WallpaperSkinTest {
    @Test fun realBundledModelsRecogniseAndSeparateCatOffline() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED,
            context.packageManager.checkPermission("android.permission.INTERNET", context.packageName))
        val photo = instrumentation.context.assets.open("cat.jpg").use { BitmapFactory.decodeStream(it) }
        try {
            val result = LocalWallpaperAnalyzer.analyze(context, photo)
            assertTrue("Wrong subject: ${result.label}", result.label.contains("cat", true))
            assertEquals("breathe", result.effect)
            assertNotNull("Foreground mask missing", result.subject)
            assertNotNull("Separated background missing", result.background)
            assertTrue(result.confidence > .1f)
            result.subject?.recycle(); result.background?.recycle()
        } finally { photo.recycle() }
    }

    @Test fun circleSearchFindsTheRecordWithoutUserCoordinates() {
        val image = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888)
        val c = Canvas(image); c.drawColor(Color.WHITE)
        c.drawCircle(120f, 120f, 70f, Paint().apply { color = Color.BLACK })
        val disc = DiscLocator.locate(image)
        assertNotNull("Circular record was not located", disc)
        assertEquals(.5f, disc!![0], .06f)
        assertEquals(.5f, disc[1], .06f)
        assertEquals(70f / 240, disc[2], .06f)
        image.recycle()
    }

    @Test fun selectedDiscMovesWhileTheRestOfThePhotoStaysStill() {
        val image = Bitmap.createBitmap(240, 160, Bitmap.Config.ARGB_8888)
        val c = Canvas(image)
        c.drawColor(Color.BLUE)
        val paint = Paint().apply { color = Color.RED }
        c.drawRect(80f, 40f, 120f, 120f, paint)
        paint.color = Color.GREEN
        c.drawRect(120f, 40f, 160f, 120f, paint)
        val renderer = WallpaperSkinRenderer().apply {
            this.image = image
            motion = WallpaperMotion(enabled = true, automatic = false, radius = .25f, strength = .65f)
        }
        fun frame(): Bitmap = Bitmap.createBitmap(240, 160, Bitmap.Config.ARGB_8888).also {
            Canvas(it).apply { drawColor(Color.BLACK); renderer.draw(this, RectF(0f, 0f, 240f, 160f), 255) }
        }
        val before = frame()
        renderer.advance(4f)
        val after = frame()
        assertNotEquals("Selected record did not rotate", before.getPixel(100, 90), after.getPixel(100, 90))
        assertEquals("Outside the record moved", before.getPixel(20, 80), after.getPixel(20, 80))
        renderer.motion = renderer.motion.copy(enabled = false)
        val disabled = frame()
        assertEquals(Color.BLACK, disabled.getPixel(100, 90))
        listOf(image, before, after, disabled).forEach(Bitmap::recycle)
    }

    @Test fun importedImageIsSampledAndKeptOutsideAndroidBackup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val input = File(context.cacheDir, "test-wallpaper.png")
        val image = Bitmap.createBitmap(2048, 1024, Bitmap.Config.ARGB_8888)
        input.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
        try {
            val imported = WallpaperSkinStore.importImage(context, Uri.fromFile(input))
            assertTrue(imported.width <= 1024 && imported.height <= 1024)
            assertTrue(File(context.noBackupFilesDir, "island-wallpaper.png").isFile)
            WallpaperSkinStore.remove(context)
            assertNull(WallpaperSkinStore.image(context))
            assertFalse(File(context.noBackupFilesDir, "island-wallpaper.png").exists())
        } finally { input.delete(); WallpaperSkinStore.remove(context) }
    }
}
