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
    @Test fun fractionalMorphBoundsRenderWithoutTheOldCropCrash() {
        val source = Bitmap.createBitmap(512, 256, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.BLUE)
        val output = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
        val renderer = WallpaperSkinRenderer().apply { image = source; style = WallpaperStyle(enabled = true) }
        renderer.draw(Canvas(output), RectF(114.45830535888672f, 0f, 245.54168701171875f, 40.4f), 255)
        assertTrue(Color.blue(output.getPixel(150, 20)) > 0)
        source.recycle(); output.recycle()
    }
    @Test fun previousWallpaperMigratesAndOldLayersAreDeleted() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("wallpaper_motion", 0)
        prefs.edit().putBoolean("enabled", true).putBoolean("glass", true).putFloat("strength", .55f)
            .putBoolean("automatic", true).putString("autoLabel", "cat").commit()
        val original = File(context.noBackupFilesDir, "island-wallpaper.png")
        val background = File(context.noBackupFilesDir, "island-wallpaper-background.png")
        val subject = File(context.noBackupFilesDir, "island-wallpaper-subject.png")
        original.writeText("fixture"); background.writeText("fixture"); subject.writeText("fixture")
        try {
            WallpaperSkinStore.migrate(context)
            assertTrue(original.exists()); assertFalse(background.exists()); assertFalse(subject.exists())
            assertFalse(prefs.contains("autoLabel")); assertFalse(prefs.contains("automatic"))
            assertTrue(WallpaperSkinStore.read(context).enabled)
            assertEquals(.55f, WallpaperSkinStore.read(context).strength, 0f)
        } finally { WallpaperSkinStore.remove(context) }
    }
    @Test fun rapidRestartFallsBackToPlainWallpaper() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("wallpaper_recovery", 0)
        val oldStyle = WallpaperSkinStore.read(context)
        try {
            WallpaperSkinStore.save(context, WallpaperStyle(enabled = true, glass = true))
            prefs.edit().putLong("processStart", 100_000L).putBoolean("glassActive", true).commit()
            assertTrue(WallpaperRecovery.onProcessStart(context, 105_000L))
            assertTrue(WallpaperSkinStore.read(context).enabled)
            assertFalse(WallpaperSkinStore.read(context).glass)
            assertFalse(WallpaperRecovery.onProcessStart(context, 110_000L))
        } finally {
            prefs.edit().clear().commit(); WallpaperSkinStore.save(context, oldStyle)
        }
    }
    @Test fun importedImageIsSampledAndKeptOutsideAndroidBackup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED,
            context.packageManager.checkPermission("android.permission.INTERNET", context.packageName))
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
