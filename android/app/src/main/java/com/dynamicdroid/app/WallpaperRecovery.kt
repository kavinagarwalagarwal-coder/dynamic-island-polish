package com.dynamicdroid.app

import android.app.Application
import android.content.Context
import android.os.SystemClock

/** Local restart marker also covers native GPU failures, which Java cannot catch. No telemetry. */
object WallpaperRecovery {
    private var marked = false
    fun onProcessStart(context: Context, now: Long = SystemClock.elapsedRealtime()): Boolean {
        val p = context.getSharedPreferences("wallpaper_recovery", Context.MODE_PRIVATE)
        val since = now - p.getLong("processStart", -60_000)
        val recover = p.getBoolean("glassActive", false) && since in 0L..30_000L
        if (recover) {
            WallpaperSkinStore.save(context, WallpaperSkinStore.read(context).copy(glass = false))
            p.edit().putString("status", STATUS_RESTART).commit()
        }
        p.edit().putLong("processStart", now).putBoolean("glassActive", false).commit()
        marked = false
        return recover
    }
    fun markRendering(context: Context) {
        if (marked) return
        marked = true
        context.getSharedPreferences("wallpaper_recovery", Context.MODE_PRIVATE).edit().putBoolean("glassActive", true).apply()
    }
    fun shaderFailed(context: Context) {
        WallpaperSkinStore.save(context, WallpaperSkinStore.read(context).copy(glass = false))
        context.getSharedPreferences("wallpaper_recovery", Context.MODE_PRIVATE).edit()
            .putString("status", STATUS_UNAVAILABLE).apply()
    }
    fun status(context: Context): String {
        val stored = context.getSharedPreferences("wallpaper_recovery", Context.MODE_PRIVATE).getString("status", null)
        return context.getString(when {
            stored == STATUS_RESTART || stored?.startsWith("Glass paused") == true -> R.string.glass_status_restart
            stored == STATUS_UNAVAILABLE || stored?.startsWith("Glass is unavailable") == true -> R.string.glass_status_unavailable
            else -> R.string.glass_status_default
        })
    }
    private const val STATUS_RESTART = "restart"
    private const val STATUS_UNAVAILABLE = "unavailable"
    fun clearStatus(context: Context) {
        context.getSharedPreferences("wallpaper_recovery", Context.MODE_PRIVATE).edit().remove("status").apply()
    }
}

class DynamicDroidApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        WallpaperSkinStore.migrate(this)
        WallpaperRecovery.onProcessStart(this)
    }
}
