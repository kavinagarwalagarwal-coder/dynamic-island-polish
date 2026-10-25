package com.dynamicdroid.app

import android.app.*
import android.content.*
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class IslandOverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private var island: IslandView? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var contentIntent: PendingIntent? = null
    private var actionIntents: ArrayList<PendingIntent> = arrayListOf()
    private var actionLabels: ArrayList<String> = arrayListOf()
    private var mediaSessionAvailable = false
    private var currentNotificationKey: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private val collapse = Runnable { island?.expanded = false }

    private val activityReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val view = island ?: return
            view.mode = intent?.getStringExtra(EXTRA_MODE)?.let {
                runCatching { IslandMode.valueOf(it) }.getOrNull()
            } ?: IslandMode.MESSAGE
            view.title = intent?.getStringExtra(EXTRA_TITLE) ?: "New activity"
            view.subtitle = intent?.getStringExtra(EXTRA_SUBTITLE) ?: ""
            currentNotificationKey = intent?.getStringExtra(EXTRA_KEY)
            contentIntent = parcelable(intent, EXTRA_CONTENT_INTENT)
            actionIntents = parcelableList(intent, EXTRA_ACTION_INTENTS)
            actionLabels = intent?.getStringArrayListExtra(EXTRA_ACTION_LABELS) ?: arrayListOf()
            mediaSessionAvailable = intent?.getBooleanExtra(
                IslandNotificationListener.EXTRA_HAS_MEDIA_SESSION,
                false
            ) == true
            view.artwork = intent?.getByteArrayExtra(IslandNotificationListener.EXTRA_MEDIA_ARTWORK)
                ?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            view.isPlaying = intent?.getBooleanExtra(
                IslandNotificationListener.EXTRA_MEDIA_IS_PLAYING,
                false
            ) == true
            view.playbackPositionMs = intent?.getLongExtra(
                IslandNotificationListener.EXTRA_MEDIA_POSITION,
                0L
            ) ?: 0L
            view.durationMs = intent?.getLongExtra(
                IslandNotificationListener.EXTRA_MEDIA_DURATION,
                0L
            ) ?: 0L
            view.primaryActionLabel = intent?.getStringArrayListExtra(EXTRA_ACTION_LABELS)
                ?.firstOrNull()?.uppercase() ?: "OPEN"
            view.onPreviousAction = { dispatchMediaAction(IslandNotificationListener.MEDIA_PREVIOUS) }
            view.onTogglePlayback = { dispatchMediaAction(IslandNotificationListener.MEDIA_TOGGLE) }
            view.onNextAction = { dispatchMediaAction(IslandNotificationListener.MEDIA_NEXT) }
            view.onSeekAction = { position ->
                if (mediaSessionAvailable) {
                    sendMediaControl(IslandNotificationListener.MEDIA_SEEK, position)
                }
            }
            view.expanded = true
        }
    }

    private val removalReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.getStringExtra(EXTRA_KEY) == currentNotificationKey) {
                island?.expanded = false
                island?.mode = IslandMode.IDLE
                island?.artwork = null
                currentNotificationKey = null
                mediaSessionAvailable = false
            }
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_BATTERY_CHANGED) return
            val plugged = intent.getIntExtra("plugged", 0) != 0
            if (!plugged) return
            val level = intent.getIntExtra("level", 0)
            island?.apply {
                mode = IslandMode.CHARGING
                title = "$level% Charged"
                subtitle = if (level >= 100) "Fully charged" else "Charging"
                artwork = null
                primaryActionLabel = "BATTERY"
                expanded = true
            }
            mediaSessionAvailable = false
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Dynamic Droid is active")
            .setContentText("Live activities can appear over other apps")
            .setOngoing(true)
            .build())

        ContextCompat.registerReceiver(
            this,
            activityReceiver,
            IntentFilter(ACTION_ACTIVITY),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            this,
            removalReceiver,
            IntentFilter(ACTION_REMOVED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (Settings.canDrawOverlays(this)) showOverlay()
    }

    private fun showOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        island = IslandView(this).apply {
            mode = IslandMode.IDLE
            onOpen = { send(contentIntent) }
            onPrimaryAction = { send(actionIntents.getOrNull(0) ?: contentIntent) }
            onBoundsChanged = { updateBounds(it) }
        }
        val type = if (Build.VERSION.SDK_INT >= 26)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE
        val params = WindowManager.LayoutParams(
            dp(COMPACT_WIDTH),
            dp(COMPACT_HEIGHT),
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = 0 }
        windowParams = params
        windowManager.addView(island, params)
    }

    private fun updateBounds(expanded: Boolean) {
        val view = island ?: return
        val params = windowParams ?: return
        handler.removeCallbacks(collapse)
        params.width = dp(if (expanded) EXPANDED_WIDTH else COMPACT_WIDTH)
        params.height = dp(if (expanded) EXPANDED_HEIGHT else COMPACT_HEIGHT)
        windowManager.updateViewLayout(view, params)
        if (expanded) handler.postDelayed(collapse, EXPANDED_DURATION_MS)
    }

    private fun send(pendingIntent: PendingIntent?) {
        try {
            pendingIntent?.send()
        } catch (_: PendingIntent.CanceledException) {
            island?.expanded = false
        }
    }

    private fun dispatchMediaAction(command: String) {
        if (mediaSessionAvailable) {
            sendMediaControl(command)
            return
        }

        val labels = actionLabels.map { it.lowercase() }
        val index = when (command) {
            IslandNotificationListener.MEDIA_PREVIOUS ->
                labels.indexOfFirst { it.contains("previous") || it.contains("rewind") }
            IslandNotificationListener.MEDIA_NEXT ->
                labels.indexOfFirst { it.contains("next") || it.contains("skip") }
            IslandNotificationListener.MEDIA_TOGGLE ->
                labels.indexOfFirst { it.contains("play") || it.contains("pause") }
            else -> -1
        }
        if (index >= 0) send(actionIntents.getOrNull(index))
    }

    private fun sendMediaControl(command: String, position: Long? = null) {
        sendBroadcast(Intent(IslandNotificationListener.ACTION_MEDIA_CONTROL).apply {
            setPackage(packageName)
            putExtra(IslandNotificationListener.EXTRA_MEDIA_COMMAND, command)
            position?.let { putExtra(IslandNotificationListener.EXTRA_MEDIA_POSITION, it) }
        })
    }

    override fun onDestroy() {
        island?.let { windowManager.removeView(it) }
        handler.removeCallbacksAndMessages(null)
        unregisterReceiver(activityReceiver)
        unregisterReceiver(removalReceiver)
        unregisterReceiver(batteryReceiver)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Dynamic Island service", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    @Suppress("DEPRECATION")
    private fun parcelable(intent: Intent?, key: String): PendingIntent? =
        if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra(key, PendingIntent::class.java)
        else intent?.getParcelableExtra(key)

    @Suppress("DEPRECATION")
    private fun parcelableList(intent: Intent?, key: String): ArrayList<PendingIntent> =
        if (Build.VERSION.SDK_INT >= 33)
            intent?.getParcelableArrayListExtra(key, PendingIntent::class.java) ?: arrayListOf()
        else intent?.getParcelableArrayListExtra(key) ?: arrayListOf()

    companion object {
        const val ACTION_ACTIVITY = "com.dynamicdroid.app.LIVE_ACTIVITY"
        const val ACTION_REMOVED = "com.dynamicdroid.app.LIVE_ACTIVITY_REMOVED"
        const val EXTRA_MODE = "mode"
        const val EXTRA_TITLE = "title"
        const val EXTRA_SUBTITLE = "subtitle"
        const val EXTRA_KEY = "notification_key"
        const val EXTRA_CONTENT_INTENT = "content_intent"
        const val EXTRA_ACTION_INTENTS = "action_intents"
        const val EXTRA_ACTION_LABELS = "action_labels"
        private const val CHANNEL_ID = "dynamic_island"
        private const val NOTIFICATION_ID = 7104
        private const val COMPACT_WIDTH = 124
        private const val COMPACT_HEIGHT = 52
        private const val EXPANDED_WIDTH = 310
        private const val EXPANDED_HEIGHT = 154
        private const val EXPANDED_DURATION_MS = 5_500L
    }
}
