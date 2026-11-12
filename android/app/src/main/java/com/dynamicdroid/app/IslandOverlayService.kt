package com.dynamicdroid.app

import android.app.ActivityOptions
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.graphics.Color
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlin.math.max

/**
 * Hosts the island window and decides what it shows.
 *
 * - One ongoing activity (music, call, timer, navigation) lives in the compact island.
 * - Transient ones (messages, charging) briefly expand on top, then hand back.
 * - The ongoing activity hides while its app is open and flies into the island when you leave it.
 * - The window only ever grows before an animation and shrinks after it settles, so it never
 *   clips mid-animation and never blocks touches on the apps underneath.
 */
class IslandOverlayService : Service(), IslandBus.Listener, IslandView.Callbacks {
    private var windowManager: WindowManager? = null
    private var island: IslandView? = null
    private var params: WindowManager.LayoutParams? = null
    private val handler = Handler(Looper.getMainLooper())
    private val restoreTouchAfterReturn = Runnable { render() }

    private var ongoing: LiveActivity? = null
    private val activities = ActivityShelf<LiveActivity>({ it.key }, {
        IslandCustomization.priority(this, it.mode)
    }, { it.mode == IslandMode.TIMER && IslandCustomization.timerAsBubble(this) })
    private var transient: LiveActivity? = null
    /** A medium message island the user held open into its full card. */
    private var transientExpanded = false
    private var devices: DeviceMonitor? = null
    private var events: SystemEvents? = null
    /** The island's own stopwatch: when it started (elapsed) and, while paused, how far it got. */
    private var stopwatchStart = 0L
    private var stopwatchPausedAt: Long? = null
    private var shooting = false
    private var reply: ReplyPanel? = null
    private var lockShown = false
    private val audio by lazy { getSystemService(AudioManager::class.java) }
    private var placementTest: LiveActivity? = null
    private val clearPlacementTest = Runnable { placementTest = null; render() }
    private var presentation = Presentation.COMPACT
    private var foregroundPkg: String? = null
    private val keyboardPackages by lazy {
        runCatching { getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            .enabledInputMethodList.map { it.packageName }.toSet() }.getOrDefault(emptySet())
    }
    private val homePackages by lazy {
        packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .map { it.activityInfo.packageName }.toSet()
    }
    private var watcher: ForegroundWatcher? = null
    /** A newly started timer opens briefly, then morphs up into the pill. */
    private var timerPeek = false
    /** Foreground app hid the status bar (video, photos, games): tuck the island away. */
    private var fullscreen = false
    private var status: StatusMonitor? = null

    private val completions = TimerCompletion()
    private val checkCompletion = Runnable { render() }
    private val refreshQuietRules = object : Runnable {
        override fun run() {
            render()
            HeadsUpControl.sync(this@IslandOverlayService, island != null)
            if (IslandCustomization.quietEnabled(this@IslandOverlayService)) handler.postDelayed(this, 60_000L)
        }
    }

    private val clearTransient = Runnable {
        if (reply?.isShowing == true) return@Runnable
        transient = null; transientExpanded = false; render()
    }
    private val autoCollapse = Runnable {
        timerPeek = false
        if (transient == null && presentation == Presentation.EXPANDED) {
            presentation = Presentation.COMPACT
            activities.restoreAutomaticSelection()
            render()
        }
    }

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_POWER_CONNECTED) return
            val level = getSystemService(BatteryManager::class.java)
                ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: return
            onActivity(LiveActivity(
                key = "charging", packageName = packageName, mode = IslandMode.CHARGING,
                title = "$level%", subtitle = getString(R.string.island_charging), appLabel = getString(R.string.battery_label),
                appIcon = null, artwork = null, accent = AppStyle.GREEN,
            ))
        }
    }

    // ---------------------------------------------------------------- ring / vibrate / silent

    // Remembered so the broadcast Android sends on registration (and repeats) show nothing.
    private var lastRingerMode = -1
    private var lastDnd: Boolean? = null

    private val ringerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val state = when (intent?.action) {
                AudioManager.RINGER_MODE_CHANGED_ACTION -> {
                    val mode = audio?.ringerMode ?: return
                    if (mode == lastRingerMode) return
                    lastRingerMode = mode
                    when (mode) {
                        AudioManager.RINGER_MODE_SILENT -> RingerState.SILENT
                        AudioManager.RINGER_MODE_VIBRATE -> RingerState.VIBRATE
                        else -> RingerState.RING
                    }
                }
                NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED -> {
                    val on = IslandFeatures.dndActive(this@IslandOverlayService)
                    if (on == lastDnd) return
                    lastDnd = on
                    if (on) RingerState.DND_ON else RingerState.DND_OFF
                }
                else -> return
            }
            // With the volume island open, its buttons show the change; no second island on top.
            if (transient?.mode == IslandMode.VOLUME) { audio?.let { showVolume(it, volumeStream(it)) }; return }
            if (IslandFeatures.read(this@IslandOverlayService).ringer) showRinger(state)
        }
    }

    private fun showRinger(state: RingerState) {
        val label = getString(when (state) {
            RingerState.RING -> R.string.ringer_ring
            RingerState.VIBRATE -> R.string.ringer_vibrate
            RingerState.SILENT -> R.string.ringer_silent
            RingerState.DND_ON -> R.string.ringer_dnd_on
            RingerState.DND_OFF -> R.string.ringer_dnd_off
        })
        onActivity(LiveActivity(
            key = "ringer", packageName = packageName, mode = IslandMode.RINGER,
            title = label, subtitle = "", appLabel = getString(R.string.mode_ringer),
            appIcon = null, artwork = null, accent = Color.WHITE, ringer = state,
        ))
    }

    // ---------------------------------------------------------------- wake / unlock intro
    //
    // The intro used to fire on SCREEN_ON, i.e. behind the lock screen, so it was never seen.
    // Now the island hides as a dot when the screen turns off and grows out once the phone is
    // actually unlocked and the unlock animation has cleared.

    private var introArmed = false
    private val playIntro = Runnable { island?.playIntro() }
    private val waitForUnlock = object : Runnable {
        override fun run() {
            if (!introArmed) return
            val locked = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
            val screenOn = getSystemService(PowerManager::class.java)?.isInteractive != false
            when {
                !screenOn -> Unit
                !locked -> {
                    introArmed = false
                    if (lockShown) { lockShown = false; island?.setLockDim(false) }
                    else handler.postDelayed(playIntro, UNLOCK_SETTLE_MS)
                }
                else -> handler.postDelayed(this, 200L)
            }
        }
    }

    private val wakeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    handler.removeCallbacks(waitForUnlock)
                    handler.removeCallbacks(playIntro)
                    introArmed = true
                    fullscreen = false
                    lockShown = false
                    reply?.dismiss()
                    island?.hideForIntro()
                }
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    MusicCenter.scheduleTick()
                    if (intent.action == Intent.ACTION_USER_PRESENT) {
                        // The wallpaper may not have been readable before the first unlock.
                        island?.applyWallpaper(WallpaperSkinStore.read(this@IslandOverlayService),
                            WallpaperSkinStore.image(this@IslandOverlayService))
                    }
                    if (intent.action == Intent.ACTION_SCREEN_ON) showOnLockScreen()
                    handler.removeCallbacks(waitForUnlock)
                    handler.post(waitForUnlock)
                }
            }
        }
    }

    /**
     * Lock screen (optional): the island grows out as soon as the screen wakes, in grey, and
     * takes its colours back on unlock instead of replaying the intro. Accessibility overlays only;
     * app overlays are not drawn over the lock screen.
     */
    private fun showOnLockScreen() {
        val view = island ?: return
        if (!IslandFeatures.read(this).lockScreen || !view.accessibilityHost) return
        if (getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != true) return
        lockShown = true
        view.setLockDim(true)
        handler.removeCallbacks(playIntro)
        view.playIntro()
    }

    private fun serviceNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle(getString(R.string.service_title))
        .setContentText(getString(R.string.service_text))
        .setPriority(NotificationCompat.PRIORITY_MIN)
        .setCategory(Notification.CATEGORY_SERVICE)
        .setOngoing(true)
        .build()

    /**
     * The accessibility service already keeps Dynamic Droid alive, so a foreground service is only
     * needed without it. Dropping it keeps the app out of Android's "Active apps" list, whose Stop
     * button force-stops the app and leaves its accessibility service "Not working" until it is
     * switched off and on again.
     */
    private fun syncForeground() {
        if (IslandAccessibilityService.instance != null) {
            runCatching { stopForeground(Service.STOP_FOREGROUND_REMOVE) }
        } else {
            runCatching { startForeground(NOTIFICATION_ID, serviceNotification()) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        MusicCenter.attach(this)
        // Android requires this first for a service started with startForegroundService.
        startForeground(NOTIFICATION_ID, serviceNotification())
        syncForeground()
        lastRingerMode = audio?.ringerMode ?: -1
        lastDnd = IslandFeatures.dndActive(this)
        ContextCompat.registerReceiver(this, ringerReceiver, IntentFilter().apply {
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
        }, ContextCompat.RECEIVER_EXPORTED)
        ContextCompat.registerReceiver(
            this, powerReceiver, IntentFilter(Intent.ACTION_POWER_CONNECTED), ContextCompat.RECEIVER_EXPORTED
        )
        ContextCompat.registerReceiver(
            this, wakeReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            ContextCompat.RECEIVER_EXPORTED
        )
        watcher = ForegroundWatcher(this, ::onForegroundChanged)
        IslandAccessibilityService.onImmersiveMedia = { on ->
            immersiveMedia = on
            render()
        }
        IslandAccessibilityService.wantsMediaCheck = {
            IslandFeatures.read(this).hideForMedia && island != null &&
                (activities.all().isNotEmpty() || transient != null || IslandFeatures.read(this).idlePill)
        }
        IslandAccessibilityService.onForegroundPackage = { pkg ->
            if (pkg !in keyboardPackages && (pkg in homePackages || activities.all().any { it.packageName == pkg } ||
                    packageManager.getLaunchIntentForPackage(pkg) != null)) watcher?.hint(pkg)
        }
        status = StatusMonitor(this) { info -> island?.applyStatus(info) }.also { it.start() }
        IslandAccessibilityService.onChanged = { handler.post {
            syncForeground()
            reattach()
            if (activities.all().isNotEmpty()) watcher?.start()
        } }
        attachWindow()
        IslandBus.listener = this
        if (IslandCustomization.quietEnabled(this)) handler.postDelayed(refreshQuietRules, 60_000L)
        devices = DeviceMonitor(this) { onActivity(it) }.also { it.start() }
        events = SystemEvents(this, ::onActivity, ::onRemoved).also { it.start() }
        reply = ReplyPanel(this, ::onReplySent) { armTransientTimeout(transient) }
        IslandAccessibilityService.onVolumeKey = ::handleVolumeKey
        IslandBus.listenerChanged = { HeadsUpControl.sync(this, island != null) }
        IslandAccessibilityService.beforePopupSwipe = { passTouchesFor(it) }
        HeadsUpControl.sync(this, island != null)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A startForegroundService() call must always be answered, even when we're already running.
        runCatching { startForeground(NOTIFICATION_ID, serviceNotification()) }
        syncForeground()
        if (island == null && attachWindow()) render()
        if (intent?.action == ACTION_FIT) {
            island?.let { view ->
                // Reserve vertical room before moving or scaling the island.
                growWindow(resources.displayMetrics.widthPixels to resources.displayMetrics.heightPixels, false)
                view.applyFit(IslandPreferences.read(this))
                render()
            }
        }
        if (intent?.action == ACTION_TEST) {
            presentation = Presentation.COMPACT
            placementTest = LiveActivity("placement-test", packageName, IslandMode.MUSIC,
                getString(R.string.placement_test_title), getString(R.string.placement_test_text), getString(R.string.app_name), null, null,
                0xFFFFFFFF.toInt(), isPlaying = true)
            handler.removeCallbacks(clearPlacementTest)
            handler.postDelayed(clearPlacementTest, 12_000L)
            render()
        }
        if (intent?.action == ACTION_CUSTOMIZATION) {
            island?.applyFeatures(IslandFeatures.read(this))
            HeadsUpControl.sync(this, island != null)
            devices?.stop(); devices?.start()
            events?.refresh()
            handler.removeCallbacks(refreshQuietRules)
            if (IslandCustomization.quietEnabled(this)) handler.postDelayed(refreshQuietRules, 60_000L)
            IslandNotificationListener.instance?.refreshAllowedApps()
            render()
        }
        if (intent?.action == ACTION_QUICK) handleQuick(intent.getStringExtra(EXTRA_WHAT).orEmpty(), intent)
        if (intent?.action == ACTION_THEME) {
            island?.applyTheme(IslandPreferences.readTheme(this))
        }
        if (intent?.action == ACTION_WALLPAPER) {
            island?.applyWallpaper(WallpaperSkinStore.read(this), WallpaperSkinStore.image(this))
        }
        if (ongoing != null) watcher?.start()
        return START_STICKY
    }

    /**
     * Prefers an accessibility overlay (above the status bar, so taps at the top reach the island)
     * and falls back to a normal overlay (below the status bar) when that isn't switched on.
     */
    private fun attachWindow(): Boolean {
        val a11y = IslandAccessibilityService.instance
        if (a11y == null && !Settings.canDrawOverlays(this)) return false
        val host: Context = a11y ?: this
        val wm = host.getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager = wm
        val view = IslandView(host).also {
            it.callbacks = this
            it.accessibilityHost = a11y != null
            it.applyFit(IslandPreferences.read(this))
            it.applyTheme(IslandPreferences.readTheme(this))
            it.applyWallpaper(WallpaperSkinStore.read(this), WallpaperSkinStore.image(this))
            it.applyFeatures(IslandFeatures.read(this))
            status?.let { m -> it.applyStatus(m.current) }
        }
        island = view
        val (w, h) = view.windowSizeFor(null, Presentation.IDLE)
        val type = if (a11y != null) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val lp = WindowManager.LayoutParams(
            w, h, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 0
            if (Build.VERSION.SDK_INT >= 30) {
                setFitInsetsTypes(0)
                setFitInsetsSides(0)
            }
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30) WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        params = lp
        if (runCatching { wm.addView(view, drawParams(lp)) }.isFailure) {
            island = null; params = null
            return false
        }
        attachTouchWindow(wm, host, type)
        view.hideForIntro()
        view.post { view.playIntro() }
        return true
    }

    private fun detachWindow() {
        reply?.dismiss(notify = false)
        island?.let { v -> runCatching { windowManager?.removeView(v) } }
        touchView?.let { v -> runCatching { windowManager?.removeView(v) } }
        touchView = null; touchParams = null; touchApplied.setEmpty()
        island = null
        params = null
    }

    private fun reattach() {
        detachWindow()
        if (attachWindow()) render()
        HeadsUpControl.sync(this, island != null)
    }

    // ---------------------------------------------------------------- state

    private fun render(absorb: Boolean = false) {
        try { renderNow(absorb) } catch (t: Throwable) { CrashLog.caught(this, "render", t) }
    }

    private fun renderNow(absorb: Boolean) {
        val view = island ?: return
        val features = IslandFeatures.read(this)
        var t = transient?.takeIf { features.shows(it.mode) }
        handler.removeCallbacks(checkCompletion)
        var nextDeadline: Long? = null
        for (candidate in activities.all()) {
            if (candidate.mode != IslandMode.TIMER) continue
            val remaining = candidate.clockMs()
            if (completions.observe(candidate.key, remaining, candidate.pausedTimerMs != null, candidate.chronometerCountDown)) {
                if (IslandCustomization.allowed(this, candidate.packageName) && candidate.packageName != foregroundPkg && t == null) {
                    activities.select(candidate.key)
                    presentation = Presentation.EXPANDED
                    timerPeek = false
                    view.timerDidComplete()
                    transient = LiveActivity("timer-complete-${candidate.key}", candidate.packageName,
                        IslandMode.MESSAGE, candidate.title.ifBlank { getString(R.string.timer_label) }, getString(R.string.timer_complete),
                        candidate.appLabel, candidate.appIcon, candidate.artwork, candidate.accent)
                    handler.removeCallbacks(clearTransient)
                    handler.postDelayed(clearTransient, 4_000L)
                    t = transient
                }
            }
            if (candidate.chronometerCountDown && candidate.pausedTimerMs == null && remaining != null && remaining > 0) {
                nextDeadline = minOf(nextDeadline ?: Long.MAX_VALUE, remaining + 16L)
            }
        }
        nextDeadline?.let { handler.postDelayed(checkCompletion, it) }
        // A running timer remains visible beside other activities, including while Clock is open.
        val quiet = IslandCustomization.quietNow(this)
        val (o, secondary) = activities.pair {
            features.shows(it.mode) && IslandCustomization.allowed(this, it.packageName) &&
                (it.mode == IslandMode.TIMER || it.packageName != foregroundPkg)
        }
        ongoing = o
        view.setSecondary(if (t == null && placementTest == null && !quiet &&
            !IslandCustomization.isHiddenForForeground(this, foregroundPkg)) secondary?.let { appTint(this, it) } else null)
        val (a, p) = when {
            // Volume answers a key the user just pressed, so it ignores quiet hours and app rules.
            t?.mode == IslandMode.VOLUME -> t to Presentation.EXPANDED
            quiet || IslandCustomization.isHiddenForForeground(this, foregroundPkg) -> null to Presentation.IDLE
            placementTest != null -> placementTest to presentation
            t != null -> t to transientPresentation(t)
            o != null -> o to presentation
            else -> null to Presentation.IDLE
        }
        // Returning from an app is animated by the island itself (see IslandView.playReturn).
        if (absorb) {
            android.util.Log.i("IslandHandoff", "Native return started")
            handler.removeCallbacks(restoreTouchAfterReturn)
            handler.postDelayed(restoreTouchAfterReturn, 450L)
        }
        // Calls may show over fullscreen apps in portrait, if wanted; in landscape (games, films)
        // they never do: the phone's own call screen or pop-up handles them.
        val landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val callShows = a?.mode == IslandMode.CALL && features.callsOverFullscreen && !landscape
        val paused = IslandFeatures.paused(this)
        if (paused) { handler.removeCallbacks(pauseEnded); handler.postDelayed(pauseEnded, IslandFeatures.pausedUntil(this) - System.currentTimeMillis() + 50L) }
        val concealed = shooting || paused ||
            (fullscreen || immersiveMedia) && !callShows && a?.mode != IslandMode.ALARM && a?.mode != IslandMode.VOLUME
        view.setConcealed(concealed)
        // A settled idle or hidden window is touch-through; restore touches the moment something shows.
        params?.let { lp ->
            // The resting pill is touchable only when tapping it opens the home card.
            val idleTappable = a == null && features.idlePill && features.homeCard
            val touchable = (a != null || idleTappable) && !concealed && !absorb && !view.isAbsorbing && !passingTouches
            val isOff = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0
            if (touchable == isOff) {
                lp.flags = if (touchable) lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                else lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                applyIslandWindow()
            }
        }
        val changedPresentation = view.activity?.key != a?.key || view.presentation != p
        // Per-second notification updates must not keep restarting the timer's peek.
        if (changedPresentation) {
            handler.removeCallbacks(autoCollapse)
            if (t == null && p == Presentation.EXPANDED && a?.mode != IslandMode.CALL && a?.mode != IslandMode.ALARM) {
                val delay = if (timerPeek && a?.mode == IslandMode.TIMER) TIMER_PEEK_MS else EXPANDED_TIMEOUT_MS
                handler.postDelayed(autoCollapse, delay)
            }
        }
        growWindow(view.windowSizeFor(a, p), false)
        view.show(a?.let { appTint(this, it) }, p, false)
        updateTouchWindow()
        syncDotBeat(a, p)
    }

    /** Messages can start as a medium island; everything else transient opens fully. */
    private fun transientPresentation(t: LiveActivity): Presentation =
        if (t.mode == IslandMode.MESSAGE && !transientExpanded && IslandFeatures.read(this).messagesMediumFirst)
            Presentation.COMPACT else Presentation.EXPANDED

    private fun armTransientTimeout(activity: LiveActivity?) {
        handler.removeCallbacks(clearTransient)
        activity ?: return
        handler.postDelayed(clearTransient, when (activity.mode) {
            IslandMode.CHARGING -> 3_000L
            IslandMode.VOLUME -> VOLUME_TIMEOUT_MS
            IslandMode.DEVICE -> DEVICE_TIMEOUT_MS
            IslandMode.RINGER -> RINGER_TIMEOUT_MS
            IslandMode.EVENT -> DEVICE_TIMEOUT_MS
            IslandMode.HOME -> HOME_TIMEOUT_MS
            else -> if (transientExpanded) MESSAGE_TIMEOUT_MS * 2 else MESSAGE_TIMEOUT_MS
        })
    }

    override fun onActivity(activity: LiveActivity) {
        // Kinds of activity the user turned off stay with the phone's own UI (pop-ups included).
        if (!IslandFeatures.read(this).shows(activity.mode)) {
            if (transient?.key == activity.key || activities.all().any { it.key == activity.key }) onRemoved(activity.key)
            return
        }
        if (activity.isTransient) {
            // A different message (or system island) replaces the current one at medium size.
            if (activity.key != transient?.key) transientExpanded = false
            if (reply?.isShowing == true && activity.key != transient?.key) {
                // Never swap the card out from under someone who is typing a reply.
                if (activity.mode != IslandMode.VOLUME) return
                reply?.dismiss(notify = false)
            }
            transient = activity
            armTransientTimeout(activity)
            clearPhonePopup(activity)
        } else {
            val isNew = activities.put(activity)
            ongoing = activities.pair { it.mode == IslandMode.TIMER || it.packageName != foregroundPkg }.first
            if (isNew && activity.mode == IslandMode.TIMER && ongoing?.key != activity.key) {
                presentation = Presentation.COMPACT
                timerPeek = false
            }
            if (isNew && ongoing?.key == activity.key) {
                timerPeek = activity.mode == IslandMode.TIMER
                presentation = when (activity.mode) {
                    IslandMode.CALL, IslandMode.TIMER, IslandMode.ALARM -> Presentation.EXPANDED
                    else -> if (IslandCustomization.preferredExpanded(this, activity.packageName) == true)
                        Presentation.EXPANDED else Presentation.COMPACT
                }
            }
            watcher?.start()
        }
        render()
    }

    /** Until when the island window lets every touch through (while a pop-up is swiped away under it). */
    private var passTouchesUntil = 0L
    private val passingTouches get() = SystemClock.uptimeMillis() < passTouchesUntil

    private fun passTouchesFor(ms: Long) {
        passTouchesUntil = SystemClock.uptimeMillis() + ms
        val view = island ?: return
        params?.let { lp ->
            if (lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE == 0) {
                lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                applyIslandWindow()
            }
        }
        handler.postDelayed({ if (!passingTouches) render() }, ms + 16L)
    }

    /**
     * The island is showing this message, so the phone's own pop-up for it is redundant. With the
     * system switch granted there is none; otherwise the accessibility service swipes it up.
     */
    private fun clearPhonePopup(activity: LiveActivity) {
        if (activity.mode != IslandMode.MESSAGE || island == null) return
        if (!IslandFeatures.read(this).hidePhonePopups || HeadsUpControl.hiding(this)) return
        if (IslandCustomization.quietNow(this) || IslandCustomization.isHiddenForForeground(this, foregroundPkg)) return
        IslandAccessibilityService.instance?.swipeAwayPopup(activity.title, activity.subtitle)
    }

    override fun onRemoved(key: String) {
        if (transient?.key == key) {
            reply?.dismiss(notify = false)
            transient = null
            transientExpanded = false
            handler.removeCallbacks(clearTransient)
        }
        activities.remove(key)
        completions.remove(key)
        if (activities.all().isEmpty()) watcher?.stop()
        if (ongoing?.key == key) {
            ongoing = activities.pair { it.mode == IslandMode.TIMER || it.packageName != foregroundPkg }.first
            presentation = Presentation.COMPACT
            if (activities.all().isEmpty()) watcher?.stop()
        }
        render()
    }

    private fun onForegroundChanged(pkg: String) {
        val previous = foregroundPkg
        if (pkg == previous) return
        // Use the unfiltered shelf: the owner was deliberately hidden while its app was open.
        val source = activities.pair().first?.takeIf { it.mode == IslandMode.MUSIC || it.mode == IslandMode.CALL }
        foregroundPkg = pkg
        if (pkg !in homePackages) island?.cancelAppMorph()
        val unlocked = getSystemService(PowerManager::class.java)?.isInteractive == true &&
            getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == false
        val morph = AppReturnPolicy.shouldMorph(source?.packageName, previous, pkg, pkg in homePackages,
            source?.key != island?.activity?.key, unlocked)
        android.util.Log.d("IslandHandoff", "foreground $previous -> $pkg source=${source?.packageName} " +
            "home=${pkg in homePackages} unlocked=$unlocked morph=$morph")
        if (morph) { presentation = Presentation.COMPACT; fullscreen = false }
        if (IslandCustomization.isHiddenForForeground(this, pkg)) island?.cancelAppMorph()
        render(absorb = morph && !IslandCustomization.isHiddenForForeground(this, pkg))
    }

    // ---------------------------------------------------------------- island callbacks

    override fun onOpen(activity: LiveActivity) {
        launch(activity.contentIntent, activity.packageName)
        if (activity === transient || activity.key == transient?.key) {
            reply?.dismiss(notify = false)
            transient = null
            transientExpanded = false
            handler.removeCallbacks(clearTransient)
        } else {
            presentation = Presentation.COMPACT
        }
        render()
    }

    override fun onAction(activity: LiveActivity, action: IslandAction) {
        send(action.intent)
        if (activity.key == transient?.key) {
            transient = null
            handler.removeCallbacks(clearTransient)
            render()
        }
    }

    override fun onMedia(activity: LiveActivity, command: String, positionMs: Long?) {
        if (activity.key == placementTest?.key) {
            if (command == IslandBus.MEDIA_TOGGLE) {
                placementTest = placementTest?.copy(isPlaying = !activity.isPlaying); render()
            }
            return
        }
        if (IslandBus.mediaControl?.invoke(command, positionMs) == true) return
        // No media session: fall back to the notification's own buttons.
        val words = when (command) {
            IslandBus.MEDIA_PREVIOUS -> listOf("previous", "prev", "rewind", "back")
            IslandBus.MEDIA_NEXT -> listOf("next", "skip", "forward")
            IslandBus.MEDIA_TOGGLE -> listOf("pause", "play")
            else -> emptyList()
        }
        activity.actions.firstOrNull { a -> words.any { a.label.contains(it, ignoreCase = true) } }
            ?.let { send(it.intent) }
    }

    override fun onSwitchActivity() {
        val (_, other) = activities.pair { it.mode == IslandMode.TIMER || it.packageName != foregroundPkg }
        other?.let { activities.select(it.key) }
        presentation = Presentation.COMPACT
        timerPeek = false
        render()
    }

    override fun onSecondaryExpand(activity: LiveActivity) {
        activities.select(activity.key)
        timerPeek = false
        presentation = Presentation.EXPANDED
        render()
    }

    override fun onExpandRequest() {
        // Holding a medium message grows it into the full card with Reply.
        transient?.let { t ->
            if (t.mode == IslandMode.MESSAGE && !transientExpanded) {
                transientExpanded = true
                armTransientTimeout(t)
                render()
            }
            return
        }
        if (placementTest == null && ongoing == null) return
        timerPeek = false
        presentation = Presentation.EXPANDED
        render()
    }

    override fun onCollapseRequest() {
        activities.restoreAutomaticSelection()
        if (transient != null) {
            reply?.dismiss(notify = false)
            transient = null
            transientExpanded = false
            handler.removeCallbacks(clearTransient)
        } else {
            presentation = Presentation.COMPACT
        }
        render()
    }

    override fun onDismiss(activity: LiveActivity) = onCollapseRequest()

    // ---------------------------------------------------------------- home card, stopwatch, flashlight, screenshots

    private val pauseEnded = Runnable { render() }

    private fun quick(what: String, extra: Float? = null): PendingIntent = PendingIntent.getService(
        this, what.hashCode(), Intent(this, IslandOverlayService::class.java).setAction(ACTION_QUICK)
            .putExtra(EXTRA_WHAT, what).also { if (extra != null) it.putExtra(EXTRA_VALUE, extra) },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** The resting pill was tapped: the time, date and battery, with quick buttons. */
    override fun onIdleTap() {
        if (!IslandFeatures.read(this).homeCard) return
        val level = getSystemService(BatteryManager::class.java)?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 0
        val now = java.util.Date()
        val torchOn = events?.isTorchOn == true
        onActivity(LiveActivity(
            key = HOME_KEY, packageName = packageName, mode = IslandMode.HOME,
            title = android.text.format.DateFormat.getTimeFormat(this).format(now),
            subtitle = android.text.format.DateFormat.format("EEEE, d MMMM", now).toString(),
            appLabel = getString(R.string.app_name), appIcon = null, artwork = null, accent = AppStyle.GREEN,
            progress = level / 100f,
            actions = listOfNotNull(
                IslandAction(getString(R.string.home_timer), quick("timer5")),
                IslandAction(getString(R.string.home_stopwatch), quick("stopwatch")),
                IslandAction(getString(if (torchOn) R.string.home_torch_off else R.string.home_torch), quick("torch")),
                if (Build.VERSION.SDK_INT >= 28) IslandAction(getString(R.string.home_shot), quick("shot")) else null,
            ),
        ))
    }

    private fun handleQuick(what: String, intent: Intent) {
        // Buttons on the home card close it as they act.
        if (transient?.key == HOME_KEY && what != "torch-level") { transient = null; handler.removeCallbacks(clearTransient) }
        when (what) {
            "timer5" -> {
                val timer = Intent(android.provider.AlarmClock.ACTION_SET_TIMER)
                    .putExtra(android.provider.AlarmClock.EXTRA_LENGTH, 5 * 60)
                    .putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { startActivity(timer) }
            }
            "stopwatch" -> { stopwatchStart = SystemClock.elapsedRealtime(); stopwatchPausedAt = null; publishStopwatch(expand = true) }
            "stopwatch-pause" -> { stopwatchPausedAt = SystemClock.elapsedRealtime(); publishStopwatch() }
            "stopwatch-resume" -> {
                val paused = stopwatchPausedAt ?: return
                stopwatchStart += SystemClock.elapsedRealtime() - paused
                stopwatchPausedAt = null
                publishStopwatch()
            }
            "stopwatch-stop" -> { stopwatchPausedAt = null; stopwatchStart = 0L; onRemoved(STOPWATCH_KEY) }
            "torch" -> events?.toggleTorch()
            "torch-off" -> events?.setTorch(false)
            "torch-level" -> events?.setTorchLevel(intent.getFloatExtra(EXTRA_VALUE, 1f))
            "shot" -> cleanScreenshot(fromShade = false)
            "pause" -> Unit // the tile stores the pause; render() below applies it
            "shot-tile" -> cleanScreenshot(fromShade = true)
        }
        render()
    }

    private fun publishStopwatch(expand: Boolean = false) {
        if (stopwatchStart == 0L) return
        val paused = stopwatchPausedAt
        val elapsed = (paused ?: SystemClock.elapsedRealtime()) - stopwatchStart
        val activity = LiveActivity(
            key = STOPWATCH_KEY, packageName = packageName, mode = IslandMode.TIMER,
            title = getString(R.string.home_stopwatch), subtitle = "", appLabel = getString(R.string.home_stopwatch),
            appIcon = null, artwork = null, accent = AppStyle.ORANGE,
            chronometerBase = System.currentTimeMillis() - elapsed, chronometerCountDown = false,
            pausedTimerMs = if (paused != null) elapsed else null,
            actions = listOf(
                if (paused == null) IslandAction(getString(R.string.stopwatch_pause), quick("stopwatch-pause"))
                else IslandAction(getString(R.string.stopwatch_resume), quick("stopwatch-resume")),
                IslandAction(getString(R.string.stopwatch_stop), quick("stopwatch-stop")),
            ),
        )
        onActivity(activity)
        if (expand) { activities.select(STOPWATCH_KEY); presentation = Presentation.EXPANDED; timerPeek = true }
    }

    /** Hides the island, takes the screenshot through the accessibility service, then shows it again. */
    private fun cleanScreenshot(fromShade: Boolean) {
        val a11y = IslandAccessibilityService.instance
        if (Build.VERSION.SDK_INT < 28 || a11y == null) {
            android.widget.Toast.makeText(this, R.string.shot_needs_a11y, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        shooting = true
        render()
        // From Quick Settings the shade must slide away first.
        if (fromShade && Build.VERSION.SDK_INT >= 31)
            a11y.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        handler.postDelayed({
            a11y.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
            handler.postDelayed({ shooting = false; render() }, 1_400L)
        }, if (fromShade) 900L else 380L)
    }

    /** The flashlight card's bar was dragged. */
    override fun onTorchLevel(fraction: Float) { events?.setTorchLevel(fraction) }

    override fun onTorchOff() { events?.setTorch(false) }

    /** Real beats for the island's dot record, only while the music card is open and the setting is on. */
    private val dotBeat by lazy { BeatListener(this) { bands -> island?.dotSpectrum = bands } }
    private var dotBeatOn = false

    private fun syncDotBeat(a: LiveActivity?, p: Presentation) {
        val want = a?.mode == IslandMode.MUSIC && a.isPlaying && p == Presentation.EXPANDED &&
            IslandFeatures.read(this).dotMusic && DotPrefs.beat(this)
        if (want == dotBeatOn) return
        dotBeatOn = want
        if (want) dotBeat.start() else dotBeat.stop()
    }

    override fun onDotPlayer() {
        onCollapseRequest()
        runCatching {
            startActivity(Intent(this, DotPlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    override fun onFullscreenChanged(fullscreen: Boolean) {
        if (this.fullscreen == fullscreen) return
        this.fullscreen = fullscreen
        render()
    }

    /** A Short, Reel, full-screen player or photo viewer fills the screen while the status bar stays. */
    private var immersiveMedia = false

    override fun onInteraction() {
        // Keep things open while the user is touching them.
        transient?.let {
            handler.removeCallbacks(clearTransient)
            handler.postDelayed(clearTransient, MESSAGE_TIMEOUT_MS)
        }
        if (presentation == Presentation.EXPANDED && transient == null) {
            handler.removeCallbacks(autoCollapse)
            if (ongoing?.mode != IslandMode.CALL && ongoing?.mode != IslandMode.ALARM) handler.postDelayed(autoCollapse, EXPANDED_TIMEOUT_MS)
        }
    }

    // ---------------------------------------------------------------- reply

    override fun onReply(activity: LiveActivity) {
        val target = activity.reply ?: return
        val view = island ?: return
        val host: Context = IslandAccessibilityService.instance ?: this
        handler.removeCallbacks(clearTransient)
        reply?.show(host, view.accessibilityHost, view.expandedBottomPx(activity), target)
    }

    private fun onReplySent(text: String) {
        val t = transient ?: return
        // Confirm in place, then let the card go.
        transient = t.copy(subtitle = getString(R.string.island_sent) + " · " + text, reply = null, actions = emptyList())
        handler.removeCallbacks(clearTransient)
        handler.postDelayed(clearTransient, SENT_TIMEOUT_MS)
        render()
    }

    // ---------------------------------------------------------------- volume

    private fun volumeStream(am: AudioManager): Int =
        if (am.mode == AudioManager.MODE_IN_CALL || am.mode == AudioManager.MODE_IN_COMMUNICATION)
            AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_MUSIC

    /**
     * Called by the accessibility service for each volume key press. Returns false to leave the
     * key to the system: screen off, locked (unless the island shows on the lock screen), a
     * ringing phone (volume silences the ringer), or no island window.
     */
    private fun handleVolumeKey(up: Boolean): Boolean {
        val view = island ?: return false
        val features = IslandFeatures.read(this)
        if (!features.volume) return false
        if (getSystemService(PowerManager::class.java)?.isInteractive != true) return false
        val locked = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        if (locked && !(features.lockScreen && view.accessibilityHost && lockShown)) return false
        val am = audio ?: return false
        if (am.mode == AudioManager.MODE_RINGTONE) return false
        val stream = volumeStream(am)
        val before = am.getStreamVolume(stream)
        val ok = runCatching {
            am.adjustStreamVolume(stream, if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0)
        }.isSuccess
        if (!ok) return false
        // Android 17 can turn volume changes from a background app into silent no-ops. If the
        // level did not move although it could, hand the key back so the phone changes it.
        val min = if (Build.VERSION.SDK_INT >= 28) am.getStreamMinVolume(stream) else 0
        val couldMove = if (up) before < am.getStreamMaxVolume(stream) else before > min
        if (couldMove && am.getStreamVolume(stream) == before) return false
        showVolume(am, stream)
        return true
    }

    override fun onVolumeChange(fraction: Float) {
        val am = audio ?: return
        val stream = volumeStream(am)
        val max = am.getStreamMaxVolume(stream)
        val index = (fraction * max + 0.5f).toInt().coerceIn(0, max)
        if (index != am.getStreamVolume(stream)) runCatching { am.setStreamVolume(stream, index, 0) }
        showVolume(am, stream)
    }

    private fun showVolume(am: AudioManager, stream: Int) {
        val max = am.getStreamMaxVolume(stream).coerceAtLeast(1)
        val level = am.getStreamVolume(stream).toFloat() / max
        val features = IslandFeatures.read(this)
        onActivity(LiveActivity(
            key = "volume", packageName = packageName, mode = IslandMode.VOLUME,
            title = getString(if (stream == AudioManager.STREAM_VOICE_CALL) R.string.island_volume_call else R.string.island_volume_media),
            subtitle = "", appLabel = getString(R.string.mode_volume), appIcon = null, artwork = null, accent = NothingStyle.foreground,
            progress = level,
            ringer = if (features.ringer && features.ringerControls) currentRinger(am) else null,
            dndOn = IslandFeatures.dndActive(this),
        ))
    }

    private fun currentRinger(am: AudioManager) = when (am.ringerMode) {
        AudioManager.RINGER_MODE_SILENT -> RingerState.SILENT
        AudioManager.RINGER_MODE_VIBRATE -> RingerState.VIBRATE
        else -> RingerState.RING
    }

    /**
     * Ring and vibrate need nothing special. Silent and Do Not Disturb need Do Not Disturb access,
     * which Android already gives apps with notification access; without it, open that setting.
     */
    override fun onRingerMode(state: RingerState) {
        val am = audio ?: return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val needsPolicy = state == RingerState.SILENT || state == RingerState.DND_ON || state == RingerState.DND_OFF ||
            am.ringerMode == AudioManager.RINGER_MODE_SILENT
        if (needsPolicy && !nm.isNotificationPolicyAccessGranted) {
            android.widget.Toast.makeText(this, R.string.ringer_needs_access, android.widget.Toast.LENGTH_LONG).show()
            runCatching {
                startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            return
        }
        runCatching {
            when (state) {
                RingerState.RING -> am.ringerMode = AudioManager.RINGER_MODE_NORMAL
                RingerState.VIBRATE -> am.ringerMode = AudioManager.RINGER_MODE_VIBRATE
                RingerState.SILENT -> am.ringerMode = AudioManager.RINGER_MODE_SILENT
                RingerState.DND_ON -> nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                RingerState.DND_OFF -> nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
            }
        }
        // Keep whichever island was tapped open and showing the new state.
        if (transient?.mode == IslandMode.VOLUME) {
            showVolume(am, volumeStream(am))
        } else {
            showRinger(when (state) {
                RingerState.DND_ON, RingerState.DND_OFF -> state
                else -> currentRinger(am)
            })
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        reattach()
    }

    override fun onAnchorChanged() {
        val view = island ?: return
        view.post { if (island === view) growWindow(view.windowSizeFor(view.activity, view.presentation), false) }
    }

    override fun onSettled() {
        val view = island ?: return
        val lp = params ?: return
        val a = view.activity
        val (w, h) = view.windowSizeFor(a, view.presentation)
        var changed = false
        if (lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0 && !passingTouches) {
            lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            changed = true
        }
        if (lp.width != w || lp.height != h) {
            lp.width = w
            lp.height = h
            changed = true
        }
        // Idle island: the window still spans the top for the next intro, so let touches
        // fall through to the app underneath instead of deadening that strip.
        if ((a == null || view.isConcealed) && lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE == 0) {
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            changed = true
        }
        if (changed) applyIslandWindow() else updateTouchWindow()
    }

    // ---------------------------------------------------------------- window
    //
    // Two windows. The island draws in a wide window along the top that never takes a touch, so
    // swiping down anywhere else on the status bar still opens the shade, and apps' own top bars
    // stay tappable. A second, invisible window sits exactly over the island (and its bubble)
    // and hands its touches to the island. [params] keeps describing what the island wants;
    // FLAG_NOT_TOUCHABLE there now means "the island takes no touches right now".

    private var touchView: View? = null
    private var touchParams: WindowManager.LayoutParams? = null
    private val touchBounds = android.graphics.RectF()
    private val touchApplied = android.graphics.Rect()
    private val islandOnScreen = IntArray(2)

    private fun drawParams(lp: WindowManager.LayoutParams) = WindowManager.LayoutParams().also {
        it.copyFrom(lp)
        it.flags = it.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
    }

    private fun attachTouchWindow(wm: WindowManager, host: Context, type: Int) {
        val view = object : View(host) {
            override fun onTouchEvent(e: MotionEvent): Boolean {
                val target = island ?: return false
                val tp = touchParams ?: return false
                target.getLocationOnScreen(islandOnScreen)
                val ev = MotionEvent.obtain(e)
                if (e.actionMasked != MotionEvent.ACTION_OUTSIDE) {
                    ev.offsetLocation((tp.x - islandOnScreen[0]).toFloat(), (tp.y - islandOnScreen[1]).toFloat())
                }
                target.dispatchTouchEvent(ev)
                ev.recycle()
                return true
            }
        }.apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        val tp = WindowManager.LayoutParams(1, 1, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            if (Build.VERSION.SDK_INT >= 30) { setFitInsetsTypes(0); setFitInsetsSides(0) }
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30) WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        if (runCatching { wm.addView(view, tp) }.isSuccess) { touchView = view; touchParams = tp }
    }

    /** Push the island's wishes to both windows. */
    private fun applyIslandWindow() {
        val view = island ?: return
        val lp = params ?: return
        runCatching { windowManager?.updateViewLayout(view, drawParams(lp)) }
        updateTouchWindow()
    }

    /** Move the touch window over wherever the island is (or is heading); cheap when nothing moved. */
    private fun updateTouchWindow() {
        val view = island ?: return
        val lp = params ?: return
        val tv = touchView ?: return
        val tp = touchParams ?: return
        val wants = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE == 0 && !passingTouches
        val next = android.graphics.Rect()
        if (wants) {
            view.touchBounds(touchBounds)
            view.getLocationOnScreen(islandOnScreen)
            touchBounds.offset(islandOnScreen[0].toFloat(), islandOnScreen[1].toFloat())
            touchBounds.roundOut(next)
        }
        val on = wants && !next.isEmpty
        val flags = if (on) tp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            else tp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        if (flags == tp.flags && (!on || next == touchApplied)) return
        if (on) {
            tp.x = next.left; tp.y = next.top
            tp.width = next.width().coerceAtLeast(1); tp.height = next.height().coerceAtLeast(1)
            touchApplied.set(next)
        } else touchApplied.setEmpty()
        tp.flags = flags
        runCatching { windowManager?.updateViewLayout(tv, tp) }
    }

    private fun growWindow(target: Pair<Int, Int>, absorb: Boolean) {
        val view = island ?: return
        val lp = params ?: return
        val dm = resources.displayMetrics
        val (w, h) = if (absorb || view.isAbsorbing) {
            dm.widthPixels to dm.heightPixels
        } else {
            max(lp.width, target.first) to max(lp.height, target.second)
        }
        var flags = lp.flags
        // During the fly-in the window covers the screen, so let every touch pass through.
        if (absorb) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        if (lp.width == w && lp.height == h && lp.flags == flags) return
        lp.width = w
        lp.height = h
        lp.flags = flags
        applyIslandWindow()
    }

    // ---------------------------------------------------------------- launching

    private fun launch(pendingIntent: PendingIntent?, pkg: String) {
        if (send(pendingIntent)) return
        packageManager.getLaunchIntentForPackage(pkg)?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { startActivity(it) }
        }
    }

    /**
     * Android 14+ blocks activity launches from a PendingIntent sent by a background app unless
     * the sender opts in. This was why tapping "open" often did nothing.
     */
    private fun send(pendingIntent: PendingIntent?): Boolean {
        pendingIntent ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= 34) {
                val options = ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(
                        if (Build.VERSION.SDK_INT >= 36) ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
                        else @Suppress("DEPRECATION") ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                pendingIntent.send(this, 0, null, null, null, null, options.toBundle())
            } else {
                pendingIntent.send()
            }
            true
        } catch (_: PendingIntent.CanceledException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    override fun onDestroy() {
        if (IslandBus.listener === this) IslandBus.listener = null
        IslandAccessibilityService.onChanged = null
        IslandAccessibilityService.onForegroundPackage = null
        IslandAccessibilityService.onImmersiveMedia = null
        if (dotBeatOn) { dotBeat.stop(); dotBeatOn = false }
        IslandAccessibilityService.wantsMediaCheck = null
        if (IslandAccessibilityService.onVolumeKey != null) IslandAccessibilityService.onVolumeKey = null
        IslandBus.listenerChanged = null
        IslandAccessibilityService.beforePopupSwipe = null
        // The island is going away: the phone's own pop-ups must come back.
        HeadsUpControl.restore(this)
        devices?.stop()
        events?.stop()
        watcher?.stop()
        status?.stop()
        handler.removeCallbacksAndMessages(null)
        detachWindow()
        runCatching { unregisterReceiver(powerReceiver) }
        runCatching { unregisterReceiver(ringerReceiver) }
        runCatching { unregisterReceiver(wakeReceiver) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.service_channel), NotificationManager.IMPORTANCE_MIN)
            )
        }
    }

    companion object {
        const val ACTION_CUSTOMIZATION = "com.dynamicdroid.app.CUSTOMIZATION"
        const val ACTION_TEST = "com.dynamicdroid.app.TEST_PLACEMENT"
        const val ACTION_FIT = "com.dynamicdroid.app.UPDATE_FIT"
        const val ACTION_WALLPAPER = "com.dynamicdroid.app.UPDATE_WALLPAPER"
        const val ACTION_THEME = "com.dynamicdroid.app.UPDATE_THEME"
        const val ACTION_QUICK = "com.dynamicdroid.app.QUICK"
        const val EXTRA_WHAT = "what"
        const val EXTRA_VALUE = "value"
        const val HOME_KEY = "own:home"
        const val STOPWATCH_KEY = "own:stopwatch"
        private const val HOME_TIMEOUT_MS = 8_000L
        const val PAUSE_MS = 15 * 60 * 1000L
        private const val CHANNEL_ID = "dynamic_island"
        private const val NOTIFICATION_ID = 7104
        private const val EXPANDED_TIMEOUT_MS = 6_000L
        private const val MESSAGE_TIMEOUT_MS = 5_000L
        private const val TIMER_PEEK_MS = 2_600L
        private const val VOLUME_TIMEOUT_MS = 1_600L
        private const val DEVICE_TIMEOUT_MS = 3_600L
        private const val RINGER_TIMEOUT_MS = 2_000L
        private const val SENT_TIMEOUT_MS = 1_400L
        /** Lets the system's unlock animation finish so the intro is actually visible. */
        private const val UNLOCK_SETTLE_MS = 320L
    }
}
