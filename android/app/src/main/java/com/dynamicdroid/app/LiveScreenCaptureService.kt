package com.dynamicdroid.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.roundToInt

/** Volatile, process-local frames. Nothing is written to disk or sent over the network. */
object LiveScreenFrames {
    fun interface Listener { fun onFrame(bitmap: Bitmap?) }

    private val listeners = CopyOnWriteArraySet<Listener>()
    @Volatile private var frame: Bitmap? = null
    var isActive: Boolean by mutableStateOf(false)
        private set

    fun latest(): Bitmap? = frame

    fun addListener(listener: Listener) {
        listeners += listener
        listener.onFrame(frame)
    }

    fun removeListener(listener: Listener) { listeners -= listener }

    internal fun start() {
        isActive = true
        IslandOverlayService.onLiveCaptureStateChanged()
        listeners.forEach { listener -> runCatching { listener.onFrame(frame) } }
    }

    internal fun publish(bitmap: Bitmap) {
        if (!isActive) {
            bitmap.recycle()
            return
        }
        frame = bitmap
        listeners.forEach { listener -> runCatching { listener.onFrame(bitmap) } }
    }

    internal fun stop() {
        isActive = false
        frame = null
        IslandOverlayService.onLiveCaptureStateChanged()
        listeners.forEach { listener -> runCatching { listener.onFrame(null) } }
    }
}

/** Captures only after Android's per-session MediaProjection consent and keeps frames in memory. */
class LiveScreenCaptureService : Service() {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var lastFrameNanos = 0L
    private var frameWidth = 0
    private var frameHeight = 0

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            LiveScreenFrames.stop()
            stopSelf()
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            if (width > 0 && height > 0 && (width != frameWidth || height != frameHeight)) {
                resizeCapture(width, height)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopCapture()
            return START_NOT_STICKY
        }
        if (projection != null) return START_NOT_STICKY

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData = intent?.let { parcelableIntent(it, EXTRA_RESULT_DATA) }
        if (resultCode != RESULT_OK || resultData == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        startCaptureForeground()
        startCapture(resultCode, resultData)
        return START_NOT_STICKY
    }

    private fun startCaptureForeground() {
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle(getString(R.string.live_glass_notification_title))
            .setContentText(getString(R.string.live_glass_notification_text))
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, getString(R.string.live_glass_stop), stopPendingIntent())
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startCapture(resultCode: Int, resultData: Intent) {
        try {
            captureThread = HandlerThread("DynamicDroidScreenFrames").apply { start() }
            captureHandler = Handler(captureThread!!.looper)
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val mediaProjection = requireNotNull(manager.getMediaProjection(resultCode, resultData)) {
                "MediaProjection is unavailable for this consent result"
            }
            projection = mediaProjection
            mediaProjection.registerCallback(projectionCallback, Handler(mainLooper))

            val (width, height) = screenSize()
            prepareReader(width, height)
            display = mediaProjection.createVirtualDisplay(
                "DynamicDroid liquid glass",
                frameWidth,
                frameHeight,
                resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader!!.surface,
                null,
                captureHandler
            )
            LiveScreenFrames.start()
        } catch (_: SecurityException) {
            stopCapture()
        } catch (_: Exception) {
            stopCapture()
        }
    }

    private fun screenSize(): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = getSystemService(android.view.WindowManager::class.java).maximumWindowMetrics.bounds
            return bounds.width() to bounds.height()
        }
        @Suppress("DEPRECATION")
        val displayMetrics = resources.displayMetrics
        return displayMetrics.widthPixels to displayMetrics.heightPixels
    }

    private fun prepareReader(width: Int, height: Int) {
        val scale = minOf(
            1f,
            MAX_CAPTURE_WIDTH.toFloat() / width.coerceAtLeast(1),
            MAX_CAPTURE_HEIGHT.toFloat() / height.coerceAtLeast(1)
        )
        frameWidth = (width * scale).roundToInt().coerceAtLeast(1)
        frameHeight = (height * scale).roundToInt().coerceAtLeast(1)
        val next = ImageReader.newInstance(frameWidth, frameHeight, PixelFormat.RGBA_8888, 2)
        next.setOnImageAvailableListener(::onImageAvailable, captureHandler)
        val previous = reader
        reader = next
        if (previous != null) {
            previous.setOnImageAvailableListener(null, null)
            previous.close()
        }
    }

    private fun resizeCapture(width: Int, height: Int) {
        val currentDisplay = display ?: return
        prepareReader(width, height)
        currentDisplay.resize(frameWidth, frameHeight, resources.displayMetrics.densityDpi)
        currentDisplay.setSurface(reader?.surface)
        lastFrameNanos = 0L
    }

    private fun onImageAvailable(source: ImageReader) {
        val image = try { source.acquireLatestImage() } catch (_: IllegalStateException) { null } ?: return
        try {
            val now = SystemClock.elapsedRealtimeNanos()
            if (now - lastFrameNanos < FRAME_INTERVAL_NANOS) return
            lastFrameNanos = now
            image.toBitmap()?.let(LiveScreenFrames::publish)
        } finally {
            image.close()
        }
    }

    private fun Image.toBitmap(): Bitmap? = runCatching {
        val plane = planes.firstOrNull() ?: return null
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        if (pixelStride <= 0 || rowStride <= 0) return null
        val paddedWidth = (rowStride.toFloat() / pixelStride).roundToInt().coerceAtLeast(width)
        val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(plane.buffer)
        if (paddedWidth == width) padded else Bitmap.createBitmap(padded, 0, 0, width, height).also { padded.recycle() }
    }.getOrNull()

    private fun stopCapture() {
        runCatching { projection?.unregisterCallback(projectionCallback) }
        runCatching { display?.release() }
        display = null
        runCatching { reader?.setOnImageAvailableListener(null, null) }
        runCatching { reader?.close() }
        reader = null
        runCatching { projection?.stop() }
        projection = null
        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null
        LiveScreenFrames.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopPendingIntent(): PendingIntent = PendingIntent.getService(
        this,
        STOP_REQUEST,
        Intent(this, LiveScreenCaptureService::class.java).setAction(ACTION_STOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.live_glass_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun parcelableIntent(source: Intent, key: String): Intent? =
        if (Build.VERSION.SDK_INT >= 33) source.getParcelableExtra(key, Intent::class.java)
        else {
            @Suppress("DEPRECATION")
            source.getParcelableExtra<Intent>(key)
        }

    override fun onDestroy() {
        if (projection != null || reader != null || display != null) stopCapture()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.dynamicdroid.app.LIVE_CAPTURE_STOP"
        const val EXTRA_RESULT_CODE = "capture_result_code"
        const val EXTRA_RESULT_DATA = "capture_result_data"
        private const val CHANNEL_ID = "live_glass_capture"
        private const val NOTIFICATION_ID = 4702
        private const val STOP_REQUEST = 4703
        private const val MAX_CAPTURE_WIDTH = 480
        private const val MAX_CAPTURE_HEIGHT = 960
        private const val FRAME_INTERVAL_NANOS = 66_666_667L

        fun start(context: Context, resultCode: Int, resultData: Intent) {
            val intent = Intent(context, LiveScreenCaptureService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, resultData)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, LiveScreenCaptureService::class.java).setAction(ACTION_STOP))
        }

        private const val RESULT_OK = android.app.Activity.RESULT_OK
    }
}
