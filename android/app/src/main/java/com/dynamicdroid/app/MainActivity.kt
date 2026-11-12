package com.dynamicdroid.app

import android.Manifest
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat

/**
 * The settings app. Its screens are Jetpack Compose (see MainScreens.kt and FluidUi.kt); the live
 * previews are the real [IslandView], hosted inside them, so what you see is what the phone shows.
 */
class MainActivity : AppCompatActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private val bluetoothPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) toast(getString(R.string.bluetooth_permission_denied))
        refreshCustomization()
    }

    // ---------------------------------------------------------------- screen state (Compose)

    internal var page by mutableStateOf("Playground")
    internal var features by mutableStateOf(IslandFeatureSet())
    internal var fit by mutableStateOf(IslandFit())
    internal var theme by mutableStateOf(IslandTheme())
    internal var wallpaperStyle by mutableStateOf(WallpaperStyle())
    /** Bumped whenever something outside Compose may have changed (permissions, the service). */
    internal var tick by mutableIntStateOf(0)
    /** False while the launch flourish covers the screen; content flows in when it lifts. */
    internal var entered by mutableStateOf(true)
    internal var wallpaperBusy by mutableStateOf(false)
    internal var timerLabel by mutableStateOf("")
    internal var hasDotPhoto by mutableStateOf(false)
    /** Counts page visits, so each visit flows its content in afresh. */
    internal var visit by mutableIntStateOf(0)

    internal var headerLogo: LogoMarkView? = null
    private var onboarding: OnboardingOverlay? = null
    /**
     * Back goes to the tour while it shows. A callback, not onBackPressed: from Android 16 on,
     * apps built for it get predictive back and onBackPressed is no longer called.
     */
    private val tourBack = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { onboarding?.back() ?: run { isEnabled = false } }
    }
    internal lateinit var preview: IslandView
    internal var wallpaperPreview: IslandView? = null
    private var demo: LiveActivity? = null
    private var demoPresentation = Presentation.EXPANDED
    private val wallpaperWorker = java.util.concurrent.Executors.newSingleThreadExecutor()

    internal val pickDotPhoto = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        toast(getString(if (WallpaperPhoto.save(this, uri)) R.string.dot_photo_saved else R.string.dot_photo_failed))
        refreshDotPhoto()
    }
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    internal val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) wallpaperWorker.execute {
            val theme = runCatching { WallpaperSkinStore.decodeImage(applicationContext, uri).let { bitmap ->
                try { WallpaperTheme.fromBitmap(bitmap) } finally { bitmap.recycle() }
            } }.getOrNull()
            runOnUiThread { if (!isDestroyed) applyWallpaperTheme(theme) }
        }
    }
    internal val pickWallpaper = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            wallpaperBusy = true
            wallpaperWorker.execute {
                val result = runCatching {
                    WallpaperSkinStore.importImage(applicationContext, uri)
                    WallpaperSkinStore.save(applicationContext, WallpaperSkinStore.read(applicationContext).copy(enabled = true))
                }
                runOnUiThread {
                    if (!isDestroyed) {
                        wallpaperBusy = false
                        if (result.isSuccess) {
                            wallpaperStyle = WallpaperSkinStore.read(this)
                            applyWallpaperUpdate()
                            toast(getString(R.string.ui_wallpaper_ready))
                        } else toast(getString(R.string.ui_image_unreadable))
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        wallpaperWorker.shutdown()
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        onBackPressedDispatcher.addCallback(this, tourBack)
        super.onCreate(savedInstanceState)
        fit = IslandPreferences.read(this)
        theme = IslandPreferences.readTheme(this)
        wallpaperStyle = WallpaperSkinStore.read(this)
        features = IslandFeatures.read(this)
        hasDotPhoto = WallpaperPhoto.exists(this)
        window.statusBarColor = NothingStyle.background
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        buildPreviews()
        val connected = androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        page = if (connected) "Playground" else "Setup"
        setContent { MainScreen() }
        showDemo(demoMusic(true), Presentation.EXPANDED)
        applyThemeUpdate()
        // Opening flourish, then the tour on first run, then the notification permission prompt,
        // so a system dialog never lands on top of either.
        val firstRun = !Motion.underTest && !onboardingSeen()
        if (savedInstanceState == null) playLaunchFlourish { if (firstRun) showOnboarding() else askNotificationPermission() }
        else if (firstRun) showOnboarding()
    }

    /** The real islands the Playground and wallpaper sections show; Compose hosts them. */
    private fun buildPreviews() {
        preview = IslandView(this).apply {
            anchorToCutout = false
            callbacks = previewCallbacks
            applyFit(fit.copy(offsetY = 0f))
            applyTheme(theme)
            applyWallpaper(wallpaperStyle, WallpaperSkinStore.image(this@MainActivity))
            applyFeatures(features)
        }
        wallpaperPreview = IslandView(this).apply {
            anchorToCutout = false
            applyFit(IslandFit(scale = .85f, alignCamera = false)); applyTheme(theme)
            applyWallpaper(wallpaperStyle, WallpaperSkinStore.image(this@MainActivity))
            applyFeatures(features)
            callbacks = object : IslandView.Callbacks {
                override fun onExpandRequest() { show(activity, Presentation.EXPANDED) }
                override fun onCollapseRequest() { show(activity, Presentation.COMPACT) }
                override fun onOpen(activity: LiveActivity) { show(activity, if (presentation == Presentation.COMPACT) Presentation.EXPANDED else Presentation.COMPACT) }
            }
            // A paused preview needs no continuous frames; tap/hold still demonstrates morphs.
            show(demoMusic(false), Presentation.COMPACT)
        }
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // ---------------------------------------------------------------- launch and onboarding

    private fun onboardingSeen() = getSharedPreferences("onboarding", MODE_PRIVATE).getBoolean("seen", false)

    /**
     * The logo builds itself (dot, pill, droplet), the dot-matrix name sweeps in, then the sheet
     * lifts away while the screen's parts flow up into place one after another.
     */
    private fun playLaunchFlourish(then: () -> Unit) {
        val root: FrameLayout? = findViewById(android.R.id.content)
        if (Motion.reduced(this) || root == null) { then(); return }
        entered = false
        val logo = LogoMarkView(this)
        val word = DotHeading(this, "DYNAMIC DROID")
        val tagline = TextView(this).apply {
            text = getString(R.string.launch_tagline); textSize = 14f; setTextColor(NothingStyle.muted)
            typeface = NothingStyle.body; gravity = Gravity.CENTER; alpha = 0f
        }
        val sheet = FrameLayout(this).apply {
            setBackgroundColor(NothingStyle.background)
            isClickable = true
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(logo, LinearLayout.LayoutParams(dp(150), dp(150)))
                addView(word, LinearLayout.LayoutParams(dp(280), dp(44)).apply { topMargin = dp(6) })
                addView(tagline, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
            }, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        }
        root.addView(sheet, FrameLayout.LayoutParams(-1, -1))
        logo.play(80L, 1_050L)
        word.play(520L, 620L)
        tagline.animate().alpha(1f).setStartDelay(980L).setDuration(300L).start()
        sheet.postDelayed({
            logo.animate().scaleX(1.18f).scaleY(1.18f).setDuration(380L).start()
            sheet.animate().alpha(0f).setDuration(380L).withEndAction {
                root.removeView(sheet)
                then()
            }.start()
            entered = true
            visit++
            headerLogo?.play(220L, 900L)
        }, 1_650L)
    }

    internal fun showOnboarding() {
        if (onboarding != null) return
        val root: FrameLayout = findViewById(android.R.id.content) ?: return
        val connected = androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        val tour = OnboardingOverlay(this, features, connected) { openSetup ->
            onboarding = null
            tourBack.isEnabled = false
            getSharedPreferences("onboarding", MODE_PRIVATE).edit().putBoolean("seen", true).apply()
            if (openSetup) selectPage("Setup") else headerLogo?.play(0L, 800L)
            askNotificationPermission()
        }
        onboarding = tour
        tourBack.isEnabled = true
        root.addView(tour, FrameLayout.LayoutParams(-1, -1))
        tour.requestApplyInsets()
        tour.enter()
    }

    override fun onResume() {
        super.onResume()
        tick++
        val overlay = Settings.canDrawOverlays(this)
        val a11y = IslandAccessibilityService.isEnabled(this)
        if (overlay || a11y) startIsland()
        // A grant over ADB may have happened while we were away: let the service apply it.
        if (IslandFeatures.read(this).hidePhonePopups && (overlay || a11y)) refreshCustomization()
        window.decorView.postDelayed({ tick++ }, 400L)
    }

    /** Switches page; the logo rebuilds and the new page's parts flow in. */
    internal fun selectPage(name: String) {
        if (name == "Rules") refreshCustomization()
        if (name == page) return
        page = name
        visit++
        headerLogo?.takeIf { !Motion.reduced(this) }?.play(0L, 750L)
    }

    @VisibleForTesting fun openPage(name: String) = selectPage(name)

    // ---------------------------------------------------------------- live preview

    // ---------------------------------------------------------------- live preview

    internal fun showDemo(a: LiveActivity, p: Presentation) {
        demo = a
        demoPresentation = p
        preview.contentDescription = getString(R.string.preview_description, modeName(a.mode))
        preview.show(appTint(this, a), p)
    }

    private val previewCallbacks = object : IslandView.Callbacks {
        override fun onOpen(activity: LiveActivity) = toast(getString(R.string.toast_opens, activity.appLabel))
        override fun onAction(activity: LiveActivity, action: IslandAction) {
            if (activity.mode != IslandMode.TIMER) { toast(action.label); return }
            if (action.label == "Cancel") { demo = null; preview.setSecondary(null); preview.show(null, Presentation.IDLE); return }
            val paused = activity.pausedTimerMs == null
            val ms = activity.clockMs() ?: 0L
            val next = activity.copy(pausedTimerMs = if (paused) ms else null,
                clockBaseElapsed = if (paused) activity.clockBaseElapsed else SystemClock.elapsedRealtime() + ms,
                actions = listOf(IslandAction(if (paused) "Resume" else "Pause", selfIntent()), IslandAction("Cancel", selfIntent())))
            showDemo(next, demoPresentation)
        }
        override fun onMedia(activity: LiveActivity, command: String, positionMs: Long?) {
            val a = demo ?: return
            if (command == IslandBus.MEDIA_TOGGLE) showDemo(demoMusic(!a.isPlaying, a.livePositionMs()), demoPresentation)
            if (command == IslandBus.MEDIA_SEEK && positionMs != null) showDemo(demoMusic(a.isPlaying, positionMs), demoPresentation)
            if (command == IslandBus.MEDIA_NEXT || command == IslandBus.MEDIA_PREVIOUS) showDemo(demoMusic(a.isPlaying, 0L), demoPresentation)
        }
        override fun onSecondaryExpand(activity: LiveActivity) {
            val previous = demo
            showDemo(activity, Presentation.EXPANDED)
            preview.setSecondary(previous)
        }
        override fun onExpandRequest() { demo?.let { showDemo(it, Presentation.EXPANDED) } }
        override fun onCollapseRequest() { demo?.let { showDemo(it, Presentation.COMPACT) } }
        override fun onDismiss(activity: LiveActivity) = onCollapseRequest()
        override fun onReply(activity: LiveActivity) = toast(getString(R.string.demo_reply_toast))
        override fun onVolumeChange(fraction: Float) {
            demo?.takeIf { it.mode == IslandMode.VOLUME }?.let { showDemo(it.copy(progress = fraction), Presentation.EXPANDED) }
        }
        // The preview only acts out the change; it never touches the phone's real ringer.
        override fun onRingerMode(state: RingerState) {
            val d = demo ?: return
            when {
                d.mode == IslandMode.VOLUME -> showDemo(when (state) {
                    RingerState.DND_ON -> d.copy(dndOn = true)
                    RingerState.DND_OFF -> d.copy(dndOn = false)
                    else -> d.copy(ringer = state)
                }, Presentation.EXPANDED)
                d.mode == IslandMode.RINGER -> showDemo(demoRinger(state), Presentation.EXPANDED)
            }
        }
    }

    internal fun demoMusic(playing: Boolean, position: Long = 107_000L): LiveActivity {
        val art = gradientArt(NothingStyle.red, NothingStyle.surface)
        return LiveActivity(
            key = "demo-music", packageName = "com.spotify.music", mode = IslandMode.MUSIC,
            title = "Midnight City", subtitle = "M83", appLabel = "Spotify",
            appIcon = AppStyle.icon(this, "com.spotify.music"), artwork = art,
            accent = NothingStyle.foreground,
            isPlaying = playing, positionMs = position, positionUpdatedAt = SystemClock.elapsedRealtime(), durationMs = 243_000L,
        )
    }

    internal fun demoMessage() = LiveActivity(
        key = "demo-msg", packageName = "com.whatsapp", mode = IslandMode.MESSAGE,
        title = "Aisha", subtitle = "Are we still on for dinner at 8? 🍝", appLabel = "WhatsApp",
        appIcon = AppStyle.icon(this, "com.whatsapp"), artwork = gradientArt(Color.rgb(255, 196, 120), Color.rgb(220, 90, 120)),
        accent = AppStyle.accent(this, "com.whatsapp"),
        actions = listOf(IslandAction("Mark as read", selfIntent())),
        reply = if (features.inlineReply) ReplyAction(getString(R.string.island_reply), selfIntent(),
            android.app.RemoteInput.Builder("demo_reply").setLabel(getString(R.string.island_reply_hint)).build()) else null,
    )

    internal fun demoVolume(level: Float = .6f) = LiveActivity(
        key = "demo-volume", packageName = packageName, mode = IslandMode.VOLUME,
        title = getString(R.string.island_volume_media), subtitle = "", appLabel = "Volume",
        appIcon = null, artwork = null, accent = NothingStyle.foreground, progress = level,
        ringer = IslandFeatures.read(this).let { if (it.ringer && it.ringerControls) RingerState.RING else null },
    )

    internal fun demoRinger(state: RingerState) = LiveActivity(
        key = "demo-ringer", packageName = packageName, mode = IslandMode.RINGER,
        title = getString(when (state) {
            RingerState.RING -> R.string.ringer_ring
            RingerState.VIBRATE -> R.string.ringer_vibrate
            RingerState.SILENT -> R.string.ringer_silent
            RingerState.DND_ON -> R.string.ringer_dnd_on
            RingerState.DND_OFF -> R.string.ringer_dnd_off
        }), subtitle = "", appLabel = getString(R.string.mode_ringer),
        appIcon = null, artwork = null, accent = Color.WHITE, ringer = state,
    )

    /** Each tap of the demo chip shows the next ringer state. */
    internal var demoRingerStep = 0
    private var demoEventStep = 0

    internal fun demoEvent(): LiveActivity {
        val kinds = listOf(
            Triple(Glyph.WIFI, R.string.event_wifi_on, R.string.event_wifi_on_note) to AppStyle.BLUE,
            Triple(Glyph.BATTERY_LOW, R.string.event_battery_low, R.string.event_demo_low) to NothingStyle.red,
            Triple(Glyph.PLANE, R.string.event_airplane_on, R.string.event_airplane_on_note) to AppStyle.ORANGE,
            Triple(Glyph.PAY, R.string.event_paid, R.string.event_demo_paid) to AppStyle.GREEN,
            Triple(Glyph.ALARM, R.string.event_demo_alarm, R.string.event_demo_sleep) to AppStyle.PURPLE,
        )
        val (k, accent) = kinds[demoEventStep++ % kinds.size]
        return LiveActivity("demo-event", packageName, IslandMode.EVENT, getString(k.second), getString(k.third),
            getString(R.string.app_name), null, null, accent, glyph = k.first)
    }

    internal fun demoTorch() = LiveActivity("demo-torch", packageName, IslandMode.TORCH, getString(R.string.torch_title),
        getString(R.string.torch_note_levels), getString(R.string.torch_title), null, null, SystemEvents.TORCH_YELLOW, progress = 0.6f)

    internal fun demoSleep(): LiveActivity {
        val at = System.currentTimeMillis() + (7 * 60 + 25) * 60_000L
        return LiveActivity("demo-sleep", packageName, IslandMode.SLEEP,
            android.text.format.DateFormat.getTimeFormat(this).format(java.util.Date(at)),
            getString(R.string.sleep_left, getString(R.string.duration_hm, 7, 25)), getString(R.string.sleep_title),
            null, null, AppStyle.PURPLE, alarmAt = at)
    }

    internal fun demoHome(): LiveActivity {
        val now = java.util.Date()
        val noop = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return LiveActivity("demo-home", packageName, IslandMode.HOME,
            android.text.format.DateFormat.getTimeFormat(this).format(now),
            android.text.format.DateFormat.format("EEEE, d MMMM", now).toString(), getString(R.string.app_name),
            null, null, AppStyle.GREEN, progress = 0.72f,
            actions = listOf(R.string.home_timer, R.string.home_stopwatch, R.string.home_torch, R.string.home_shot)
                .map { IslandAction(getString(it), noop) })
    }

    internal fun demoDevice(lost: Boolean) = LiveActivity(
        key = "demo-device", packageName = packageName, mode = IslandMode.DEVICE,
        title = "Nothing Ear (a)", subtitle = getString(if (lost) R.string.island_disconnected else R.string.island_connected),
        appLabel = "Bluetooth", appIcon = null, artwork = null, accent = AppStyle.GREEN,
        progress = if (lost) null else .73f, lost = lost,
    )

    internal fun demoDownload() = LiveActivity(
        key = "demo-download", packageName = "com.android.chrome", mode = IslandMode.DOWNLOAD,
        title = "holiday-photos.zip", subtitle = "48 MB of 106 MB", appLabel = "Chrome",
        appIcon = AppStyle.icon(this, "com.android.chrome"), artwork = null, accent = NothingStyle.foreground,
        progress = .45f,
    )

    internal fun demoAlarm() = LiveActivity(
        key = "demo-alarm", packageName = "com.google.android.deskclock", mode = IslandMode.ALARM,
        title = "7:30 · Wake up", subtitle = "", appLabel = getString(R.string.island_alarm),
        appIcon = AppStyle.icon(this, "com.google.android.deskclock"), artwork = null,
        accent = AppStyle.accent(this, "com.google.android.deskclock"),
        actions = listOf(IslandAction("Snooze", selfIntent()), IslandAction("Dismiss", selfIntent())),
    )

    internal fun demoCalling() = demoCall().copy(key = "demo-calling", title = "Mom", subtitle = "",
        actions = listOf(IslandAction("Hang up", selfIntent())), callState = CallState.CALLING)

    internal fun demoCall() = LiveActivity(
        key = "demo-call", packageName = "com.whatsapp", mode = IslandMode.CALL,
        title = "Rahul", subtitle = "WhatsApp voice call", appLabel = "WhatsApp",
        appIcon = AppStyle.icon(this, "com.whatsapp"), artwork = gradientArt(Color.rgb(90, 200, 250), Color.rgb(40, 80, 200)),
        accent = AppStyle.accent(this, "com.whatsapp"),
        actions = listOf(IslandAction("Decline", selfIntent()), IslandAction("Answer", selfIntent())),
    )

    internal fun demoTimer(minutes: Int = 9) = LiveActivity(
        key = "demo-timer", packageName = "com.google.android.deskclock", mode = IslandMode.TIMER,
        title = if (minutes == 9) "Pasta" else getString(R.string.demo_minutes, minutes), subtitle = "Timer", appLabel = "Clock",
        appIcon = AppStyle.icon(this, "com.google.android.deskclock"), artwork = null,
        accent = AppStyle.accent(this, "com.google.android.deskclock"),
        actions = listOf(IslandAction("Pause", selfIntent()), IslandAction("Cancel", selfIntent())),
        chronometerBase = System.currentTimeMillis() + minutes * 60_000L, chronometerCountDown = true,
    )

    private fun selfIntent() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
    )

    private fun gradientArt(c1: Int, c2: Int): Bitmap {
        val b = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 256f, 256f, c1, c2, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, 256f, 256f, p)
        p.shader = null
        p.color = 0x40FFFFFF
        canvas.drawCircle(184f, 76f, 58f, p)
        return b
    }

    // ---------------------------------------------------------------- status lines (read by Compose)

    internal fun readinessText(): Pair<String, Boolean> {
        val overlay = Settings.canDrawOverlays(this)
        val a11y = IslandAccessibilityService.isEnabled(this)
        val notifications = androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        val ready = notifications && (overlay || a11y)
        return getString(if (ready) R.string.status_ready else R.string.status_setup_needed) to ready
    }

    internal fun setupLabel(key: String): String = when (key) {
        "overlay" -> getString(if (Settings.canDrawOverlays(this)) R.string.setup_overlay_allowed else R.string.setup_overlay_allow)
        "usage" -> getString(if (ForegroundWatcher.hasPermission(this)) R.string.setup_usage_allowed else R.string.setup_usage_allow)
        "notifications" -> getString(if (androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName))
            R.string.setup_notifications_enabled else R.string.setup_notifications_connect)
        else -> {
            val a11y = IslandAccessibilityService.isEnabled(this)
            // Switched on in Settings but not running: Android shows "Not working" after the app was
            // force-stopped, and only switching it off and on again brings it back.
            getString(when {
                a11y && IslandAccessibilityService.instance == null -> R.string.setup_a11y_stuck
                a11y -> R.string.setup_a11y_enabled
                else -> R.string.setup_a11y_enable
            })
        }
    }

    /** Whether the phone's own pop-ups are hidden right now, and why not if they aren't. */
    internal data class PopupStatus(val text: String, val working: Boolean, val showA11y: Boolean, val showGrant: Boolean)

    internal fun popupStatus(): PopupStatus? {
        if (!features.hidePhonePopups) return null
        val granted = HeadsUpControl.canControl(this)
        val a11y = IslandAccessibilityService.instance != null
        val working = HeadsUpControl.hiding(this) || !granted && a11y
        val text = getString(when {
            HeadsUpControl.hiding(this) -> R.string.popups_status_active
            granted -> R.string.popups_status_waiting
            a11y -> R.string.popups_status_swipe
            else -> R.string.popups_status_needs_access
        })
        return PopupStatus(text, working, showA11y = !granted && !a11y, showGrant = !granted)
    }

    internal fun placementExplanation(): String = when {
        fit.placement == IslandPlacement.BELOW_STATUS_BAR -> getString(R.string.placement_below)
        IslandAccessibilityService.instance != null -> getString(R.string.placement_camera)
        else -> getString(R.string.placement_standard)
    }

    internal fun allowedAppsText(): String = when {
        !IslandCustomization.allowedFilterEnabled(this) -> getString(R.string.rules_all_allowed)
        IslandCustomization.allowedApps(this).isEmpty() -> getString(R.string.rules_none_allowed)
        else -> getString(R.string.rules_some_allowed, IslandCustomization.allowedApps(this).size)
    }

    internal fun hiddenAppsText(): String = IslandCustomization.hiddenWhenOpen(this).let {
        if (it.isEmpty()) getString(R.string.rules_none_hidden) else getString(R.string.rules_some_hidden, it.size)
    }

    // ---------------------------------------------------------------- actions

    internal fun openAccessibility() {
        toast(getString(R.string.setup_a11y_toast))
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    internal fun requestOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        } else startIsland()
    }

    internal fun testFit() {
        if (Settings.canDrawOverlays(this) || IslandAccessibilityService.isEnabled(this)) {
            startService(Intent(this, IslandOverlayService::class.java).setAction(IslandOverlayService.ACTION_TEST))
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
        } else { selectPage("Setup"); toast(getString(R.string.fit_need_overlay)) }
    }

    internal fun setFeature(key: String, value: Boolean) {
        IslandFeatures.setBoolean(this, key, value)
        applyFeaturesUpdate()
    }

    internal fun setDotBeat(on: Boolean) {
        DotPrefs.setBeat(this, on)
        if (on && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
        tick++
    }

    internal fun setDotWallpaper() {
        val intent = Intent(android.app.WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(
            android.app.WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, ComponentName(this, DotWallpaper::class.java))
        runCatching { startActivity(intent) }.onFailure {
            runCatching { startActivity(Intent(android.app.WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)) }
        }
    }

    internal fun refreshDotPhoto() { hasDotPhoto = WallpaperPhoto.exists(this) }

    internal fun appToIsland() {
        val a = demo?.takeIf { it.mode == IslandMode.MUSIC || it.mode == IslandMode.CALL } ?: demoMusic(true)
        demo = a; demoPresentation = Presentation.COMPACT
        preview.setSecondary(null); preview.show(a, Presentation.COMPACT, absorb = true)
    }

    internal fun showIdle() { demo = null; preview.setSecondary(null); preview.show(null, Presentation.IDLE) }

    internal fun applyFeaturesUpdate() {
        features = IslandFeatures.read(this)
        preview.applyFeatures(features)
        wallpaperPreview?.applyFeatures(features)
        refreshCustomization()
        // The service applies the change asynchronously; check again once it has.
        window.decorView.postDelayed({ tick++ }, 400L)
    }

    internal data class InstalledApp(val packageName: String, val label: String)

    private fun installedApps(): List<InstalledApp> = runCatching {
        packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { InstalledApp(it.activityInfo.packageName, it.loadLabel(packageManager).toString()) }
            .filter { it.packageName != packageName }.distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }.getOrDefault(emptyList())

    internal fun chooseApps(allowed: Boolean) {
        val apps = installedApps(); if (apps.isEmpty()) return
        val stored = if (allowed) IslandCustomization.allowedApps(this) else IslandCustomization.hiddenWhenOpen(this)
        val selected = if (allowed && !IslandCustomization.allowedFilterEnabled(this))
            apps.map { it.packageName }.toMutableSet() else stored.toMutableSet()
        val search = android.widget.EditText(this).apply {
            hint = getString(R.string.search_apps); setSingleLine(); textSize = 15f
            setTextColor(NothingStyle.foreground); setHintTextColor(NothingStyle.muted)
            background = controlBackground(); setPadding(dp(14), dp(4), dp(14), dp(4))
        }
        val list = android.widget.ListView(this).apply { choiceMode = android.widget.ListView.CHOICE_MODE_MULTIPLE }
        val filtered = apps.toMutableList()
        val adapter = object : android.widget.BaseAdapter() {
            override fun getCount() = filtered.size
            override fun getItem(position: Int) = filtered[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = (convertView as? android.widget.CheckedTextView) ?: android.widget.CheckedTextView(this@MainActivity).apply {
                    setPadding(dp(16), dp(12), dp(16), dp(12)); textSize = 16f
                    setTextColor(NothingStyle.foreground)
                    setCheckMarkDrawable(ContextCompat.getDrawable(this@MainActivity, android.R.drawable.checkbox_on_background))
                }
                val app = filtered[position]
                row.text = app.label
                row.isChecked = app.packageName in selected
                return row
            }
        }
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            val app = filtered[position]
            if (!selected.add(app.packageName)) selected.remove(app.packageName)
            adapter.notifyDataSetChanged()
        }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString()?.trim().orEmpty()
                filtered.clear()
                filtered.addAll(apps.filter { it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true) })
                adapter.notifyDataSetChanged()
            }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        val picker = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0)
            addView(search, row(dp(50)))
            addView(list, LinearLayout.LayoutParams(-1, dp(280)).apply { topMargin = dp(8) })
        }
        val dialog = android.app.AlertDialog.Builder(this, R.style.IslandDialog)
            .setTitle(if (allowed) getString(R.string.apps_allowed_title) else getString(R.string.apps_hidden_title))
            .setView(picker)
            .setNegativeButton(getString(R.string.common_cancel), null)
            .setNeutralButton(if (allowed) getString(R.string.apps_allow_all) else getString(R.string.apps_clear_rules)) { _, _ ->
                if (allowed) IslandCustomization.setAllowedApps(this, emptySet(), filterEnabled = false)
                else IslandCustomization.setHiddenWhenOpen(this, emptySet())
                refreshCustomization()
            }
            .setPositiveButton(getString(R.string.common_save)) { _, _ ->
                if (allowed) IslandCustomization.setAllowedApps(this,
                    if (selected.size == apps.size) emptySet() else selected,
                    filterEnabled = selected.size != apps.size)
                else IslandCustomization.setHiddenWhenOpen(this, selected)
                refreshCustomization()
            }.create()
        dialog.show()
    }

    internal fun chooseQuickActions() {
        val activity = IslandBus.allOngoing().lastOrNull { it.mode == IslandMode.MUSIC || it.actions.isNotEmpty() }
            ?: demo?.takeIf { it.mode == IslandMode.MUSIC || it.actions.isNotEmpty() } ?: demoMusic(false)
        if (activity.mode == IslandMode.MUSIC) {
            val options = listOf(IslandBus.MEDIA_PREVIOUS to getString(R.string.media_previous), IslandBus.MEDIA_TOGGLE to getString(R.string.media_toggle),
                IslandBus.MEDIA_NEXT to getString(R.string.media_next), IslandBus.MEDIA_SEEK to getString(R.string.media_seek))
            val current = IslandCustomization.mediaSelection(this, activity.packageName) ?: options.map { it.first }.toSet()
            val selected = current.toMutableSet()
            android.app.AlertDialog.Builder(this, R.style.IslandDialog).setTitle(getString(R.string.music_controls_title, activity.appLabel))
                .setMultiChoiceItems(options.map { it.second }.toTypedArray(), BooleanArray(options.size) { options[it].first in selected }) { _, index, checked ->
                    if (checked) selected += options[index].first else selected -= options[index].first
                }.setNeutralButton(getString(R.string.common_use_defaults)) { _, _ ->
                    IslandCustomization.setMediaSelection(this, activity.packageName, null); refreshCustomization()
                }.setNegativeButton(getString(R.string.common_cancel), null)
                .setPositiveButton(getString(R.string.common_save)) { _, _ -> IslandCustomization.setMediaSelection(this, activity.packageName, selected); refreshCustomization() }
                .show()
            return
        }
        val actions = activity.actions
        if (actions.isEmpty()) { toast(getString(R.string.no_quick_actions)); return }
        val current = IslandCustomization.actionSelection(this, activity.packageName)
        val selected = (current ?: actions.take(3).map { it.label }.toSet()).toMutableSet()
        android.app.AlertDialog.Builder(this, R.style.IslandDialog).setTitle(getString(R.string.quick_actions_title, activity.appLabel))
            .setMultiChoiceItems(actions.map { it.label }.toTypedArray(), BooleanArray(actions.size) { actions[it].label in selected }) { _, index, checked ->
                val label = actions[index].label
                if (checked && selected.size >= 3) { toast(getString(R.string.quick_actions_max)); return@setMultiChoiceItems }
                if (checked) selected += label else selected -= label
            }
            .setNeutralButton(getString(R.string.use_app_defaults)) { _, _ ->
                IslandCustomization.setActionSelection(this, activity.packageName, null); refreshCustomization()
            }
            .setNegativeButton(getString(R.string.common_cancel), null)
            .setPositiveButton(getString(R.string.common_save)) { _, _ -> IslandCustomization.setActionSelection(this, activity.packageName, selected); refreshCustomization() }
            .show()
    }

    internal fun chooseAppStyle() {
        val apps = installedApps(); if (apps.isEmpty()) return
        android.app.AlertDialog.Builder(this, R.style.IslandDialog).setTitle(getString(R.string.style_choose_app))
            .setItems(apps.map { it.label }.toTypedArray()) { _, index -> editAppStyle(apps[index]) }.show()
    }

    private fun editAppStyle(app: InstalledApp) {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(4)) }
        column.addView(label(getString(R.string.style_how_opens), 14f, NothingStyle.muted), row(-2))
        val layout = android.widget.RadioGroup(this).apply { orientation = android.widget.RadioGroup.VERTICAL }
        val modes = listOf(getString(R.string.style_default), getString(R.string.style_compact), getString(R.string.style_expanded))
        val selectedMode = when (IslandCustomization.preferredExpanded(this, app.packageName)) { true -> 2; false -> 1; null -> 0 }
        modes.forEachIndexed { index, mode -> layout.addView(android.widget.RadioButton(this).apply {
            id = View.generateViewId(); text = mode; isChecked = index == selectedMode
            setTextColor(NothingStyle.foreground)
        }) }
        column.addView(layout, row(-2))
        column.addView(label(getString(R.string.style_speed), 14f, NothingStyle.muted).apply { setPadding(0, dp(8), 0, 0) }, row(-2))
        val speedLabel = label(getString(R.string.percent_value, (IslandCustomization.motionSpeed(this, app.packageName) * 100).toInt()), 13f, NothingStyle.muted)
        val speed = SeekBar(this).apply { max = 85; progress = ((IslandCustomization.motionSpeed(this@MainActivity, app.packageName) - .65f) * 100).toInt() }
        speed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                speedLabel.text = getString(R.string.percent_value, value + 65)
            }
            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })
        column.addView(speedLabel, row(-2)); column.addView(speed, row(dp(48)))
        val accentButton = Button(this).apply {
            text = getString(R.string.style_color, accentName(IslandCustomization.accentChoice(this@MainActivity, app.packageName)))
            isAllCaps = false; background = controlBackground(); setTextColor(NothingStyle.foreground)
            setOnClickListener {
                android.app.AlertDialog.Builder(this@MainActivity, R.style.IslandDialog).setTitle(getString(R.string.style_color_title, app.label))
                    .setSingleChoiceItems(IslandCustomization.accents.indices.map { accentName(it) }.toTypedArray(),
                        IslandCustomization.accentChoice(this@MainActivity, app.packageName)) { dialog, choice ->
                        IslandCustomization.setAccentChoice(this@MainActivity, app.packageName, choice)
                        text = getString(R.string.style_color, accentName(choice)); dialog.dismiss()
                    }.show()
            }
        }
        column.addView(accentButton, row(-2).apply { topMargin = dp(8) })
        android.app.AlertDialog.Builder(this, R.style.IslandDialog).setTitle(app.label).setView(column)
            .setNeutralButton(getString(R.string.common_use_defaults)) { _, _ ->
                IslandCustomization.setPreferredExpanded(this, app.packageName, null)
                IslandCustomization.setMotionSpeed(this, app.packageName, 1f)
                IslandCustomization.setAccentChoice(this, app.packageName, 0)
                refreshCustomization()
            }.setNegativeButton(getString(R.string.common_cancel), null)
            .setPositiveButton(getString(R.string.common_save)) { _, _ ->
                val checked = layout.checkedRadioButtonId
                val mode = (0 until layout.childCount).firstOrNull { layout.getChildAt(it).id == checked } ?: 0
                IslandCustomization.setPreferredExpanded(this, app.packageName, when (mode) { 1 -> false; 2 -> true; else -> null })
                IslandCustomization.setMotionSpeed(this, app.packageName, (speed.progress + 65) / 100f)
                IslandCustomization.saveStyledApps(this, IslandCustomization.savedApps(this) + app.packageName)
                refreshCustomization(); toast(getString(R.string.style_saved, app.label))
            }.show()
    }

    internal fun startTimerPreset(minutes: Int) {
        val intent = Intent(android.provider.AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(android.provider.AlarmClock.EXTRA_LENGTH, minutes * 60)
            val name = timerLabel.trim().ifBlank { getString(R.string.timer_label) }
            putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, name)
            putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (intent.resolveActivity(packageManager) != null) runCatching { startActivity(intent) }
            .onFailure { toast(getString(R.string.timer_failed)) }
        else {
            val label = timerLabel.trim().ifBlank { getString(R.string.demo_minutes, minutes) }
            preview.setSecondary(null); showDemo(demoTimer(minutes).copy(title = label), Presentation.EXPANDED)
        }
    }

    internal fun saveQuiet(enabled: Boolean) {
        IslandCustomization.setQuietHours(this, enabled, IslandCustomization.quietStart(this), IslandCustomization.quietEnd(this))
        refreshCustomization()
    }
    internal fun chooseTime(start: Boolean) {
        val minute = if (start) IslandCustomization.quietStart(this) else IslandCustomization.quietEnd(this)
        android.app.TimePickerDialog(this, { _, hour, mins ->
            val from = if (start) hour * 60 + mins else IslandCustomization.quietStart(this)
            val until = if (start) IslandCustomization.quietEnd(this) else hour * 60 + mins
            IslandCustomization.setQuietHours(this, IslandCustomization.quietEnabled(this), from, until)
            refreshCustomization()
        }, minute / 60, minute % 60, false).show()
    }
    internal fun minuteLabel(value: Int) = "%02d:%02d".format(value / 60, value % 60)

    internal fun saveProfile() {
        val input = android.widget.EditText(this).apply { hint = getString(R.string.profile_hint); setSingleLine() }
        android.app.AlertDialog.Builder(this, R.style.IslandDialog).setTitle(getString(R.string.profile_save_title)).setView(input)
            .setNegativeButton(getString(R.string.common_cancel), null).setPositiveButton(getString(R.string.common_save)) { _, _ ->
                val name = input.text.toString().trim().ifBlank { "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}" }
                IslandProfileStore.save(this, name, fit); refreshCustomization(); toast(getString(R.string.profile_saved))
            }.show()
    }
    internal fun loadProfile() {
        val names = IslandProfileStore.names(this).sorted()
        if (names.isEmpty()) { toast(getString(R.string.profile_need_save)); return }
        android.app.AlertDialog.Builder(this, R.style.IslandDialog).setTitle(getString(R.string.profile_load_title)).setItems(names.toTypedArray()) { _, index ->
            IslandProfileStore.load(this, names[index])?.let { next ->
                fit = next
                updateFit(); toast(getString(R.string.profile_loaded, names[index]))
            }
        }.setNegativeButton(getString(R.string.common_cancel), null).setNeutralButton(getString(R.string.common_manage)) { _, _ -> manageProfiles(names) }.show()
    }
    private fun manageProfiles(names: List<String>) {
        if (names.isEmpty()) return
        android.app.AlertDialog.Builder(this, R.style.IslandDialog).setTitle(getString(R.string.profile_remove_title)).setItems(names.toTypedArray()) { _, index ->
            IslandProfileStore.remove(this, names[index]); toast(getString(R.string.profile_removed))
        }.setNegativeButton(getString(R.string.common_done), null).show()
    }
    internal fun navLabel(key: String) = getString(when (key) {
        "Playground" -> R.string.nav_playground
        "Setup" -> R.string.nav_setup
        "Appearance" -> R.string.nav_appearance
        "Rules" -> R.string.nav_rules
        "Phone fit" -> R.string.nav_phone_fit
        else -> R.string.nav_features
    })

    internal fun modeName(mode: IslandMode) = getString(when (mode) {
        IslandMode.MUSIC -> R.string.mode_music
        IslandMode.CALL -> R.string.mode_call
        IslandMode.TIMER -> R.string.mode_timer
        IslandMode.NAVIGATION -> R.string.mode_navigation
        IslandMode.CHARGING -> R.string.mode_charging
        IslandMode.MESSAGE -> R.string.mode_message
        IslandMode.VOLUME -> R.string.mode_volume
        IslandMode.DEVICE -> R.string.mode_device
        IslandMode.DOWNLOAD -> R.string.mode_download
        IslandMode.ALARM -> R.string.mode_alarm
        IslandMode.RINGER -> R.string.mode_ringer
        IslandMode.EVENT -> R.string.mode_event
        IslandMode.TORCH -> R.string.mode_torch
        IslandMode.SLEEP -> R.string.mode_sleep
        IslandMode.HOME -> R.string.mode_home
    })

    private fun accentName(index: Int) = getString(when (index) {
        1 -> R.string.accent_red; 2 -> R.string.accent_orange; 3 -> R.string.accent_yellow
        4 -> R.string.accent_green; 5 -> R.string.accent_blue; 6 -> R.string.accent_purple
        7 -> R.string.accent_pink; else -> R.string.accent_default
    })
    internal fun explainPopupGrant() {
        android.app.AlertDialog.Builder(this, R.style.IslandDialog)
            .setTitle(getString(R.string.popups_grant_title))
            .setMessage(getString(R.string.popups_grant_body, HeadsUpControl.grantCommand(this)))
            .setNegativeButton(getString(R.string.common_done), null)
            .setPositiveButton(getString(R.string.popups_copy)) { _, _ ->
                getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(
                    android.content.ClipData.newPlainText("adb", HeadsUpControl.grantCommand(this)))
                toast(getString(R.string.popups_copied))
            }.show()
    }

    internal fun requestBluetoothIfNeeded() {
        val permission = DeviceMonitor.permission() ?: return
        if (!DeviceMonitor.hasPermission(this)) bluetoothPermission.launch(permission)
    }
    internal fun pinDotWidget(cls: Class<*>) {
        val manager = getSystemService(android.appwidget.AppWidgetManager::class.java)
        if (manager?.isRequestPinAppWidgetSupported == true) manager.requestPinAppWidget(ComponentName(this, cls), null, null)
        else toast(getString(R.string.dot_pin_unsupported))
    }

    internal fun refreshCustomization() {
        tick++
        if (Settings.canDrawOverlays(this) || IslandAccessibilityService.isEnabled(this))
            startService(Intent(this, IslandOverlayService::class.java).setAction(IslandOverlayService.ACTION_CUSTOMIZATION))
    }

    internal fun applyWallpaperUpdate() {
        wallpaperStyle = wallpaperStyle.safe()
        WallpaperSkinStore.save(this, wallpaperStyle)
        val bitmap = WallpaperSkinStore.image(this)
        preview.applyWallpaper(wallpaperStyle, bitmap)
        wallpaperPreview?.applyWallpaper(wallpaperStyle, bitmap)
        if (Settings.canDrawOverlays(this) || IslandAccessibilityService.isEnabled(this)) {
            startService(Intent(this, IslandOverlayService::class.java).setAction(IslandOverlayService.ACTION_WALLPAPER))
        }
    }

    internal fun updateFit() {
        IslandPreferences.save(this, fit)
        preview.applyFit(fit.copy(offsetY = 0f))
        tick++
        if (Settings.canDrawOverlays(this) || IslandAccessibilityService.isEnabled(this)) {
            startService(Intent(this, IslandOverlayService::class.java).setAction(IslandOverlayService.ACTION_FIT))
        }
    }

    // ---------------------------------------------------------------- wallpaper theme

    internal fun applyWallpaperTheme(next: IslandTheme?) {
        if (next == null) {
            toast(getString(R.string.theme_failed))
            return
        }
        theme = next.copy(enabled = true).safe()
        applyThemeUpdate()
        toast(getString(R.string.theme_applied))
    }

    internal fun applyThemeUpdate() {
        theme = theme.safe()
        IslandPreferences.saveTheme(this, theme)
        preview.applyTheme(theme)
        wallpaperPreview?.applyTheme(theme)
        if (Settings.canDrawOverlays(this) || IslandAccessibilityService.isEnabled(this)) {
            startService(Intent(this, IslandOverlayService::class.java).setAction(IslandOverlayService.ACTION_THEME))
        }
    }

    internal fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    private fun row(height: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun label(text: String, size: Float, color: Int) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(color); typeface = NothingStyle.body
    }
    private fun controlBackground(color: Int = NothingStyle.surface): android.graphics.drawable.Drawable {
        val shape = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(18).toFloat(); setColor(color)
            setStroke(dp(1), NothingStyle.line)
        }
        return android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x33FFFFFF), shape, null)
    }

    internal fun startIsland() {
        startService(Intent(this, IslandOverlayService::class.java))
    }
}
