package com.dynamicdroid.app

import android.content.Context
import android.os.Build
import android.os.SystemClock
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the most recent island errors on the phone, so they can be copied from Setup and sent
 * in. The island catches errors in its drawing, frames and touches (records them and carries on
 * with the next frame) instead of taking the whole overlay down; anything that still escapes is
 * written here by the process-wide handler before Android ends the app.
 */
object CrashLog {
    private const val FILE = "island-errors.txt"
    private const val MAX_BYTES = 24_000
    private var lastKey = ""
    private var lastAt = 0L

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(app, "crash on ${thread.name}", error) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Records an error the island survived; the same error is written at most every 10 seconds. */
    fun caught(context: Context, where: String, error: Throwable) {
        val key = where + (error.stackTrace.firstOrNull()?.toString() ?: error.javaClass.name)
        val now = SystemClock.uptimeMillis()
        if (key == lastKey && now - lastAt < 10_000L) return
        lastKey = key; lastAt = now
        android.util.Log.e("DynamicDroid", "Island error in $where", error)
        runCatching { write(context.applicationContext, "caught in $where", error) }
    }

    fun read(context: Context): String? =
        runCatching { File(context.filesDir, FILE).takeIf { it.exists() }?.readText() }.getOrNull()?.takeIf { it.isNotBlank() }

    fun clear(context: Context) { runCatching { File(context.filesDir, FILE).delete() } }

    @Synchronized
    private fun write(context: Context, what: String, error: Throwable) {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val entry = "== $stamp · $what · v$version · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}\n" +
            trace.lineSequence().take(40).joinToString("\n") + "\n\n"
        val file = File(context.filesDir, FILE)
        // Newest first, trimmed so the report stays small enough to paste.
        val old = runCatching { file.readText() }.getOrDefault("")
        file.writeText((entry + old).take(MAX_BYTES))
    }
}
