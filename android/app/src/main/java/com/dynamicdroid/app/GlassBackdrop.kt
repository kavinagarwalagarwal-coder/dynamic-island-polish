package com.dynamicdroid.app

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.accessibility.AccessibilityWindowInfo

/** Android 14 window capture excludes our accessibility overlay: no recursive island reflection.
 * Frames are cropped to the island strip, held in memory, and never persisted or transmitted.
 */
class GlassBackdrop(private val service: AccessibilityService, private val view: IslandView) {
    private val handler = Handler(Looper.getMainLooper())
    private var stopped = false
    private var inFlight = false
    private var generation = 0
    private var windowId = -1
    private val tick = object : Runnable {
        override fun run() {
            if (stopped) return
            capture()
            handler.postDelayed(this, 400L)
        }
    }

    fun start() { handler.post(tick) }
    fun invalidateWindow() { generation++; windowId = -1; view.clearBackdrop() }
    fun stop() {
        stopped = true; generation++
        handler.removeCallbacks(tick)
        view.clearBackdrop()
    }

    private fun capture() {
        if (Build.VERSION.SDK_INT < 34 || inFlight) return
        if (!GlassSettings.read(service).enabled || !view.isShown || view.isConcealed ||
            service.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true ||
            service.getSystemService(PowerManager::class.java)?.isInteractive == false) {
            view.clearBackdrop(); return
        }
        val window = service.windows.firstOrNull {
            it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive
        } ?: run { view.clearBackdrop(); return }
        val id = window.id
        if (id != windowId) { invalidateWindow(); windowId = id }
        val windowBounds = Rect().also(window::getBoundsInScreen)
        val region = view.backdropRegion()
        // Never smear content from the application's top edge into the system status bar.
        if (!region.intersect(windowBounds)) { view.clearBackdrop(); return }
        val token = generation
        inFlight = true
        try {
            service.takeScreenshotOfWindow(id, service.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    inFlight = false
                    val buffer = result.hardwareBuffer
                    try {
                        if (stopped || token != generation || !GlassSettings.read(service).enabled || view.isConcealed ||
                            service.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true ||
                            service.getSystemService(PowerManager::class.java)?.isInteractive == false) return
                        val hardware = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace) ?: return
                        try {
                            // A window screenshot is window-local; map using its actual pixel dimensions.
                            val sx = hardware.width.toFloat() / windowBounds.width().coerceAtLeast(1)
                            val sy = hardware.height.toFloat() / windowBounds.height().coerceAtLeast(1)
                            val x = ((region.left - windowBounds.left) * sx).toInt().coerceIn(0, hardware.width - 1)
                            val y = ((region.top - windowBounds.top) * sy).toInt().coerceIn(0, hardware.height - 1)
                            val w = (region.width() * sx).toInt().coerceIn(1, hardware.width - x)
                            val h = (region.height() * sy).toInt().coerceIn(1, hardware.height - y)
                            val crop = Bitmap.createBitmap(hardware, x, y, w, h)
                            val software = crop.copy(Bitmap.Config.ARGB_8888, false)
                            if (crop !== hardware) crop.recycle()
                            if (software != null) {
                                val scaled = Bitmap.createScaledBitmap(software, region.width(), region.height(), true)
                                if (scaled !== software) software.recycle()
                                view.updateBackdrop(scaled, region)
                            }
                        } finally { hardware.recycle() }
                    } catch (_: RuntimeException) { view.clearBackdrop() }
                    finally { buffer.close() }
                }
                override fun onFailure(errorCode: Int) {
                    inFlight = false
                    view.clearBackdrop() // Secure content, rate limiting, or unsupported OEM: use material fallback.
                }
            })
        } catch (_: RuntimeException) { inFlight = false; view.clearBackdrop() }
    }
}
