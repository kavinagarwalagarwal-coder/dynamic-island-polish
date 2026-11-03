package com.dynamicdroid.app

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
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
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.widget.SeekBar
import androidx.appcompat.widget.SwitchCompat
import android.content.res.ColorStateList
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat

class MainActivity : AppCompatActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private val bluetoothPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) toast(getString(R.string.bluetooth_permission_denied))
        refreshCustomization()
    }
    private var features = IslandFeatureSet()
    private var headerLogo: LogoMarkView? = null
    private var shellView: LinearLayout? = null
    private var onboarding: OnboardingOverlay? = null
    private var currentPage: String? = null
    private var popupStatus: TextView? = null
    private lateinit var preview: IslandView
    private var demo: LiveActivity? = null
    private var demoPresentation = Presentation.EXPANDED
    private val buttons = HashMap<String, Button>()
    private var fit = IslandFit()
    private val fitSliders = ArrayList<SeekBar>()
    private var cameraSwitch: SwitchCompat? = null
    private var clockDiagnostic: TextView? = null
    private var readiness: TextView? = null
    private val pages = LinkedHashMap<String, LinearLayout>()
    private val navigation = LinkedHashMap<String, Button>()
    private var placementChoices: android.widget.RadioGroup? = null
    private var placementInfo: TextView? = null
    private var allowedAppsInfo: TextView? = null
    private var hiddenAppsInfo: TextView? = null
    private var quietSwitch: SwitchCompat? = null
    private var timerBubbleSwitch: SwitchCompat? = null
    private var quietStartButton: Button? = null
    private var quietEndButton: Button? = null
    private var timerLabelInput: android.widget.EditText? = null
    private var theme = IslandTheme()
    private var themeSwitch: SwitchCompat? = null
    private var swatchA: View? = null
    private var swatchB: View? = null
    private var wallpaperStyle = WallpaperStyle()
    private var wallpaperPreview: IslandView? = null
    private var wallpaperSwitch: SwitchCompat? = null
    private val wallpaperWorker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) wallpaperWorker.execute {
            val theme = runCatching { WallpaperSkinStore.decodeImage(applicationContext, uri).let { bitmap ->
                try { WallpaperTheme.fromBitmap(bitmap) } finally { bitmap.recycle() }
            } }.getOrNull()
            runOnUiThread { if (!isDestroyed) applyWallpaperTheme(theme) }
        }
    }
    private val pickWallpaper = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            buttons["wallpaper-pick"]?.isEnabled = false
            buttons["wallpaper-remove"]?.isEnabled = false
            wallpaperWorker.execute {
                val result = runCatching {
                    WallpaperSkinStore.importImage(applicationContext, uri)
                    WallpaperSkinStore.save(applicationContext, WallpaperSkinStore.read(applicationContext).copy(enabled = true))
                }
                runOnUiThread {
                    if (!isDestroyed) {
                        buttons["wallpaper-pick"]?.isEnabled = true
                        buttons["wallpaper-remove"]?.isEnabled = true
                        if (result.isSuccess) {
                            wallpaperStyle = WallpaperSkinStore.read(this)
                            wallpaperSwitch?.isChecked = true
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
        super.onCreate(savedInstanceState)
        fit = IslandPreferences.read(this)
        theme = IslandPreferences.readTheme(this)
        wallpaperStyle = WallpaperSkinStore.read(this)
        features = IslandFeatures.read(this)
        window.statusBarColor = BG
        setContentView(buildScreen())
        showDemo(demoMusic(true), Presentation.EXPANDED)
        applyThemeUpdate()
        // Opening flourish, then the tour on first run, then the notification permission prompt,
        // so a system dialog never lands on top of either.
        val firstRun = !Motion.underTest && !onboardingSeen()
        if (savedInstanceState == null) playLaunchFlourish { if (firstRun) showOnboarding() else askNotificationPermission() }
        else if (firstRun) showOnboarding()
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // ---------------------------------------------------------------- launch and onboarding

    private fun onboardingSeen() = getSharedPreferences("onboarding", MODE_PRIVATE).getBoolean("seen", false)

    /**
     * The logo builds itself (dot, pill, droplet), the dot-matrix name sweeps in, then the sheet
     * lifts away while the screen's parts rise into place one after another.
     */
    private fun playLaunchFlourish(then: () -> Unit) {
        val shell = shellView
        val root: FrameLayout? = findViewById(android.R.id.content)
        if (Motion.reduced(this) || shell == null || root == null) { then(); return }
        val logo = LogoMarkView(this)
        val word = DotHeading(this, "DYNAMIC DROID")
        val tagline = label(getString(R.string.launch_tagline), 14f, NothingStyle.muted).apply {
            gravity = Gravity.CENTER; alpha = 0f
        }
        val sheet = FrameLayout(this).apply {
            setBackgroundColor(BG)
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
        val parts = (0 until shell.childCount).map { shell.getChildAt(it) }
        parts.forEach { it.alpha = 0f }
        logo.play(80L, 1_050L)
        word.play(520L, 620L)
        tagline.animate().alpha(1f).setStartDelay(980L).setDuration(300L).start()
        sheet.postDelayed({
            logo.animate().scaleX(1.18f).scaleY(1.18f).setDuration(380L).start()
            sheet.animate().alpha(0f).setDuration(380L).withEndAction {
                root.removeView(sheet)
                then()
            }.start()
            Motion.staggerIn(parts, this, 60L, 45L, 22f)
            headerLogo?.play(220L, 900L)
        }, 1_650L)
    }

    private fun showOnboarding() {
        if (onboarding != null) return
        val root: FrameLayout = findViewById(android.R.id.content) ?: return
        val connected = androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        val tour = OnboardingOverlay(this, features, connected) { openSetup ->
            onboarding = null
            getSharedPreferences("onboarding", MODE_PRIVATE).edit().putBoolean("seen", true).apply()
            if (openSetup) selectPage("Setup") else headerLogo?.play(0L, 800L)
            askNotificationPermission()
        }
        onboarding = tour
        root.addView(tour, FrameLayout.LayoutParams(-1, -1))
        tour.requestApplyInsets()
        tour.enter()
    }

    @Deprecated("Back is routed to the tour while it is showing.")
    override fun onBackPressed() {
        val tour = onboarding
        if (tour != null) tour.back() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    override fun onResume() {
        super.onResume()
        clockDiagnostic?.text = TimerDiagnostics.read(this)
        val overlay = Settings.canDrawOverlays(this)
        buttons["overlay"]?.text = if (overlay) getString(R.string.setup_overlay_allowed) else getString(R.string.setup_overlay_allow)
        buttons["usage"]?.text = if (ForegroundWatcher.hasPermission(this)) getString(R.string.setup_usage_allowed) else getString(R.string.setup_usage_allow)
        val a11y = IslandAccessibilityService.isEnabled(this)
        // Switched on in Settings but not running: Android shows "Not working" after the app was
        // force-stopped, and only switching it off and on again brings it back.
        val a11yStuck = a11y && IslandAccessibilityService.instance == null
        buttons["a11y"]?.text = getString(when {
            a11yStuck -> R.string.setup_a11y_stuck
            a11y -> R.string.setup_a11y_enabled
            else -> R.string.setup_a11y_enable
        })
        val notifications = androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        buttons["notifications"]?.text = if (notifications) getString(R.string.setup_notifications_enabled) else getString(R.string.setup_notifications_connect)
        val ready = notifications && (overlay || a11y)
        readiness?.text = if (ready) getString(R.string.status_ready) else getString(R.string.status_setup_needed)
        readiness?.setTextColor(if (ready) 0xFF9BE4BE.toInt() else NothingStyle.foreground)
        placementInfo?.text = placementExplanation()
        if (overlay || a11y) startIsland()
        // A grant over ADB may have happened while we were away: let the service apply it.
        if (IslandFeatures.read(this).hidePhonePopups && (overlay || a11y)) refreshCustomization()
        popupStatus?.postDelayed({ refreshPopupStatus() }, 400L)
    }

    // ---------------------------------------------------------------- live preview

    private fun showDemo(a: LiveActivity, p: Presentation) {
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

    private fun demoMessage() = LiveActivity(
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
    private var demoRingerStep = 0

    internal fun demoDevice(lost: Boolean) = LiveActivity(
        key = "demo-device", packageName = packageName, mode = IslandMode.DEVICE,
        title = "Nothing Ear (a)", subtitle = getString(if (lost) R.string.island_disconnected else R.string.island_connected),
        appLabel = "Bluetooth", appIcon = null, artwork = null, accent = AppStyle.GREEN,
        progress = if (lost) null else .73f, lost = lost,
    )

    private fun demoDownload() = LiveActivity(
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

    private fun demoCalling() = demoCall().copy(key = "demo-calling", title = "Mom", subtitle = "",
        actions = listOf(IslandAction("Hang up", selfIntent())), callState = CallState.CALLING)

    private fun demoCall() = LiveActivity(
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

    // ---------------------------------------------------------------- screen

    private fun buildScreen(): View {
        val pad = dp(20)
        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(pad, dp(12), pad, 0)
        }
        shellView = shell
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            headerLogo = LogoMarkView(this@MainActivity).also { addView(it, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(10) }) }
            addView(label(getString(R.string.app_name), 28f, NothingStyle.foreground).apply {
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                ViewCompat.setAccessibilityHeading(this, true)
            })
        }
        shell.addView(header, row(-2))
        readiness = label(getString(R.string.status_checking), 13f, NothingStyle.muted).apply {
            setPadding(0, dp(6), 0, dp(12))
        }.also { shell.addView(it, row(-2)) }
        val navRows = listOf(listOf("Playground", "Setup"), listOf("Appearance", "Rules"), listOf("Phone fit", "Features"))
        navRows.forEach { names ->
            val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            names.forEach { name ->
                val button = chip(navLabel(name)) { selectPage(name) }
                button.contentDescription = navLabel(name)
                navigation[name] = button; strip.addView(button)
            }
            shell.addView(strip, row(-2).apply { bottomMargin = dp(6) })
        }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun page(name: String) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(18), 0, dp(28))
            pages[name] = this; content.addView(this, row(-2))
        }
        var column = page("Playground")
        column.addView(heading(getString(R.string.play_heading)), row(-2))
        column.addView(label(getString(R.string.play_hint), 14f, NothingStyle.muted), row(-2).apply { topMargin = dp(8) })
        column.addView(action("tour", getString(R.string.play_tour)) { showOnboarding() }, row(-2).apply { topMargin = dp(12) })
        preview = IslandView(this).apply {
            anchorToCutout = false
            callbacks = previewCallbacks
            applyFit(fit.copy(offsetY = 0f))
            applyTheme(theme)
            applyWallpaper(wallpaperStyle, WallpaperSkinStore.image(this@MainActivity))
            applyFeatures(features)
        }
        column.addView(FrameLayout(this).apply {
            background = GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(NothingStyle.surface) }
            setPadding(dp(6), dp(16), dp(6), dp(8))
            addView(preview, FrameLayout.LayoutParams(-1, -1))
        }, row(dp(280)).apply { topMargin = dp(16) })
        val musicRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        musicRow.addView(chip(getString(R.string.demo_music)) { showDemo(demoMusic(true), Presentation.EXPANDED) })
        musicRow.addView(chip(getString(R.string.demo_idle)) { demo = null; preview.setSecondary(null); preview.show(null, Presentation.IDLE) })
        musicRow.addView(chip(getString(R.string.demo_chat)) {
            preview.setSecondary(null)
            showDemo(demoMessage(), if (features.messagesMediumFirst) Presentation.COMPACT else Presentation.EXPANDED)
        })
        val activityRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        activityRow.addView(chip(getString(R.string.demo_call)) { showDemo(demoCall(), Presentation.EXPANDED) })
        activityRow.addView(chip(getString(R.string.mode_timer)) { preview.setSecondary(null); showDemo(demoTimer(), Presentation.EXPANDED) })
        activityRow.addView(chip(getString(R.string.demo_two_live)) {
            preview.setSecondary(demoTimer())
            showDemo(demoMusic(true), Presentation.COMPACT)
        })
        column.addView(musicRow, row(-2).apply { topMargin = dp(14) })
        column.addView(activityRow, row(-2).apply { topMargin = dp(8) })
        val systemRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        systemRow.addView(chip(getString(R.string.demo_volume)) { preview.setSecondary(null); showDemo(demoVolume(), Presentation.EXPANDED) })
        systemRow.addView(chip(getString(R.string.demo_headphones)) { preview.setSecondary(null); showDemo(demoDevice(false), Presentation.EXPANDED) })
        systemRow.addView(chip(getString(R.string.demo_bt_lost)) { preview.setSecondary(null); showDemo(demoDevice(true), Presentation.EXPANDED) })
        val eventRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        eventRow.addView(chip(getString(R.string.demo_download)) { preview.setSecondary(null); showDemo(demoDownload(), Presentation.EXPANDED) })
        eventRow.addView(chip(getString(R.string.demo_alarm)) { preview.setSecondary(null); showDemo(demoAlarm(), Presentation.EXPANDED) })
        eventRow.addView(chip(getString(R.string.demo_calling)) { preview.setSecondary(null); showDemo(demoCalling(), Presentation.EXPANDED) })
        val ringerRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        ringerRow.addView(chip(getString(R.string.demo_ringer)) {
            val states = listOf(RingerState.SILENT, RingerState.VIBRATE, RingerState.RING, RingerState.DND_ON)
            preview.setSecondary(null)
            showDemo(demoRinger(states[demoRingerStep++ % states.size]), Presentation.EXPANDED)
        })
        column.addView(systemRow, row(-2).apply { topMargin = dp(8) })
        column.addView(eventRow, row(-2).apply { topMargin = dp(8) })
        column.addView(ringerRow, row(-2).apply { topMargin = dp(8) })
        timerLabelInput = android.widget.EditText(this).apply {
            hint = getString(R.string.timer_name_hint); setSingleLine(); textSize = 15f
            setTextColor(NothingStyle.foreground); setHintTextColor(NothingStyle.muted)
            background = controlBackground(); setPadding(dp(14), dp(4), dp(14), dp(4))
        }
        column.addView(timerLabelInput, row(dp(52)).apply { topMargin = dp(10) })
        val presets = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(5, 10, 15, 25).forEach { mins -> presets.addView(chip(getString(R.string.timer_preset_minutes, mins)) { startTimerPreset(mins) }) }
        column.addView(label(getString(R.string.timer_presets), 13f, NothingStyle.muted), row(-2).apply { topMargin = dp(12) })
        column.addView(presets, row(-2))
        column.addView(chip(getString(R.string.demo_app_to_island)) {
            val a = demo?.takeIf { it.mode == IslandMode.MUSIC || it.mode == IslandMode.CALL } ?: demoMusic(true)
            demo = a; demoPresentation = Presentation.COMPACT
            preview.setSecondary(null); preview.show(a, Presentation.COMPACT, absorb = true)
        }, row(-2).apply { topMargin = dp(8) })
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            column.addView(chip(getString(R.string.demo_swiftui)) {
                startActivity(Intent(this, SwiftIslandMorphPreviewActivity::class.java))
            }, row(-2).apply { topMargin = dp(8) })
        } else {
            column.addView(label(getString(R.string.swiftui_requires), 13f, NothingStyle.muted), row(-2).apply { topMargin = dp(8) })
        }

        column.addView(label(getString(R.string.play_footer), 13f, NothingStyle.muted), row(-2).apply { topMargin = dp(16) })
        column = page("Rules")
        column.addView(heading(getString(R.string.rules_priority)), row(-2))
        column.addView(label(getString(R.string.rules_priority_hint), 14f, NothingStyle.muted), row(-2).apply { topMargin = dp(8) })
        timerBubbleSwitch = SwitchCompat(this).apply {
            text = getString(R.string.rules_timer_bubble); minHeight = dp(56)
            setTextColor(NothingStyle.foreground); isChecked = IslandCustomization.timerAsBubble(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                IslandCustomization.setTimerAsBubble(this@MainActivity, checked); refreshCustomization()
            }
        }
        column.addView(timerBubbleSwitch, row(-2))
        IslandMode.values().filter { it != IslandMode.VOLUME && it != IslandMode.DEVICE && it != IslandMode.RINGER }.forEach { mode ->
            val initial = IslandCustomization.priority(this, mode)
            column.addView(priorityControl(mode, initial), row(-2))
        }
        column.addView(heading(getString(R.string.rules_filters)), row(-2).apply { topMargin = dp(22) })
        allowedAppsInfo = label(getString(R.string.rules_all_allowed), 14f, NothingStyle.muted)
        column.addView(allowedAppsInfo, row(-2).apply { topMargin = dp(8) })
        column.addView(action("allowed-apps", getString(R.string.rules_choose_allowed)) { chooseApps(allowed = true) }, row(-2).apply { topMargin = dp(8) })
        hiddenAppsInfo = label(getString(R.string.rules_none_hidden), 14f, NothingStyle.muted)
        column.addView(hiddenAppsInfo, row(-2).apply { topMargin = dp(8) })
        column.addView(action("hidden-apps", getString(R.string.rules_hide_open)) { chooseApps(allowed = false) }, row(-2).apply { topMargin = dp(8) })
        column.addView(action("quick-actions", getString(R.string.rules_quick_actions)) { chooseQuickActions() }, row(-2).apply { topMargin = dp(8) })
        column.addView(action("colors", getString(R.string.rules_app_styles)) { chooseAppStyle() }, row(-2).apply { topMargin = dp(8) })
        column.addView(heading(getString(R.string.rules_quiet)), row(-2).apply { topMargin = dp(22) })
        quietSwitch = SwitchCompat(this).apply {
            text = getString(R.string.rules_quiet_switch); textSize = 16f; minHeight = dp(56)
            setTextColor(NothingStyle.foreground); isChecked = IslandCustomization.quietEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, enabled -> saveQuiet(enabled) }
        }
        column.addView(quietSwitch, row(-2))
        val quietTimes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        quietStartButton = chip(getString(R.string.rules_quiet_from, minuteLabel(IslandCustomization.quietStart(this)))) { chooseTime(true) }
        quietEndButton = chip(getString(R.string.rules_quiet_until, minuteLabel(IslandCustomization.quietEnd(this)))) { chooseTime(false) }
        quietTimes.addView(quietStartButton); quietTimes.addView(quietEndButton)
        column.addView(quietTimes, row(-2))
        column.addView(label(getString(R.string.rules_quiet_note), 13f, NothingStyle.muted), row(-2).apply { topMargin = dp(6) })

        column = page("Phone fit")
        column.addView(heading(getString(R.string.fit_heading)), row(-2))
        column.addView(label(getString(R.string.fit_hint), 14f, NothingStyle.muted), row(-2).apply { topMargin = dp(10) })
        placementInfo = label(placementExplanation(), 13f, NothingStyle.muted)
        column.addView(placementInfo, row(-2).apply { topMargin = dp(12) })
        placementChoices = android.widget.RadioGroup(this).apply {
            orientation = android.widget.RadioGroup.VERTICAL
            listOf(IslandPlacement.AUTO to getString(R.string.fit_auto),
                IslandPlacement.CAMERA to getString(R.string.fit_camera),
                IslandPlacement.BELOW_STATUS_BAR to getString(R.string.fit_below)).forEach { (mode, title) ->
                addView(android.widget.RadioButton(this@MainActivity).apply {
                    id = View.generateViewId(); tag = mode
                    text = title; textSize = 16f; minHeight = dp(52)
                    setTextColor(NothingStyle.foreground)
                    buttonTintList = ColorStateList.valueOf(NothingStyle.foreground)
                    isChecked = fit.placement == mode
                }, row(-2))
            }
            setOnCheckedChangeListener { group, id ->
                val selected = group.findViewById<android.widget.RadioButton>(id)?.tag as? IslandPlacement
                if (selected != null) { fit = fit.copy(placement = selected); updateFit() }
            }
        }
        column.addView(placementChoices, row(-2))
        column.addView(action("test-fit", getString(R.string.fit_test)) {
            if (Settings.canDrawOverlays(this) || IslandAccessibilityService.isEnabled(this)) {
                startService(Intent(this, IslandOverlayService::class.java).setAction(IslandOverlayService.ACTION_TEST))
                startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
            } else { selectPage("Setup"); toast(getString(R.string.fit_need_overlay)) }
        }, row(-2).apply { topMargin = dp(10) })
        column.addView(action("save-profile", getString(R.string.fit_save_profile)) { saveProfile() }, row(-2).apply { topMargin = dp(10) })
        column.addView(action("load-profile", getString(R.string.fit_load_profile)) { loadProfile() }, row(-2).apply { topMargin = dp(8) })
        column.addView(label(getString(R.string.fit_samsung_note), 14f, NothingStyle.muted), row(-2).apply { topMargin = dp(14) })
        column.addView(fitControl(getString(R.string.fit_size), 70, 130, (fit.scale * 100).toInt(), "%") { fit = fit.copy(scale = it / 100f); updateFit() }, row(-2))
        column.addView(fitControl(getString(R.string.fit_width), 70, 150, (fit.pillWidth * 100).toInt(), "%") { fit = fit.copy(pillWidth = it / 100f); updateFit() }, row(-2))
        column.addView(fitControl(getString(R.string.fit_left_right), -180, 180, fit.offsetX.toInt(), "dp") { fit = fit.copy(offsetX = it.toFloat()); updateFit() }, row(-2))
        column.addView(fitControl(getString(R.string.fit_up_down), -24, 160, fit.offsetY.toInt(), "dp") { fit = fit.copy(offsetY = it.toFloat()); updateFit() }, row(-2))
        cameraSwitch = SwitchCompat(this).apply {
            text = getString(R.string.fit_align_camera)
            textSize = 16f
            minHeight = dp(56)
            setPadding(0, dp(12), 0, dp(12))
            setTextColor(NothingStyle.foreground)
            typeface = NothingStyle.body
            isChecked = fit.alignCamera
            buttonTintList = ColorStateList.valueOf(NothingStyle.red)
            setOnCheckedChangeListener { _, checked -> fit = fit.copy(alignCamera = checked); updateFit() }
        }
        column.addView(cameraSwitch, row(-2))
        column.addView(action("reset", getString(R.string.fit_reset)) {
            fit = IslandFit()
            val defaults = listOf(30, 30, 180, 24)
            fitSliders.forEachIndexed { index, slider -> slider.progress = defaults[index] }
            cameraSwitch?.isChecked = true
            placementChoices?.let { group ->
                (0 until group.childCount).map { group.getChildAt(it) }.firstOrNull { it.tag == IslandPlacement.AUTO }
                    ?.let { group.check(it.id) }
            }
            updateFit()
        }, row(-2))

        column = page("Appearance")
        column.addView(heading(getString(R.string.look_heading)), row(-2))
        column.addView(label(getString(R.string.look_hint), 14f, NothingStyle.muted), row(-2).apply { topMargin = dp(10) })
        themeSwitch = SwitchCompat(this).apply {
            text = getString(R.string.look_match)
            textSize = 16f
            minHeight = dp(56)
            setPadding(0, dp(12), 0, dp(12))
            setTextColor(NothingStyle.foreground)
            typeface = NothingStyle.body
            isChecked = theme.enabled
            buttonTintList = ColorStateList.valueOf(NothingStyle.red)
            setOnCheckedChangeListener { _, checked -> theme = theme.copy(enabled = checked); applyThemeUpdate() }
        }
        column.addView(themeSwitch, row(-2))
        val swatches = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(8))
        }
        swatchA = swatch().also { swatches.addView(it) }
        swatchB = swatch().also { swatches.addView(it) }
        swatches.addView(label(getString(R.string.look_swatches), 13f, NothingStyle.muted))
        column.addView(swatches, row(-2))
        column.addView(action("wallpaper", getString(R.string.look_use_wallpaper)) {
            applyWallpaperTheme(WallpaperTheme.fromSystem(this))
        }, row(-2).apply { topMargin = dp(8) })
        column.addView(action("app-colors", getString(R.string.look_app_styles)) { chooseAppStyle() }, row(-2).apply { topMargin = dp(10) })
        column.addView(action("pick", getString(R.string.look_pick)) {
            pickImage.launch("image/*")
        }, row(-2).apply { topMargin = dp(10) })

        addWallpaperControls(column)

        addFeaturesPage(page("Features"))

        column = page("Setup")
        column.addView(heading(getString(R.string.setup_heading)), row(-2))
        column.addView(label(getString(R.string.setup_steps), 15f, NothingStyle.muted), row(-2).apply { topMargin = dp(12) })
        column.addView(label(getString(R.string.setup_fallback), 14f, NothingStyle.muted), row(-2).apply { topMargin = dp(12) })
        column.addView(action("tour-setup", getString(R.string.play_tour)) { showOnboarding() }, row(-2).apply { topMargin = dp(12) })
        column.addView(heading(getString(R.string.setup_clock_heading)), row(-2).apply { topMargin = dp(16) })
        clockDiagnostic = label(TimerDiagnostics.read(this), 13f, NothingStyle.muted)
        column.addView(clockDiagnostic, row(-2).apply { topMargin = dp(8) })


        column.addView(action("overlay", getString(R.string.setup_overlay_allow)) {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } else startIsland()
        }, row(-2).apply { topMargin = dp(16) })
        column.addView(action("notifications", getString(R.string.setup_notifications_allow)) {
            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        }, row(-2).apply { topMargin = dp(10) })
        column.addView(action("a11y", getString(R.string.setup_a11y_allow)) {
            toast(getString(R.string.setup_a11y_toast))
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }, row(-2).apply { topMargin = dp(10) })
        column.addView(action("usage", getString(R.string.setup_usage_allow)) {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }, row(-2).apply { topMargin = dp(10) })

        shell.addView(ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        ViewCompat.setOnApplyWindowInsetsListener(shell) { view, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            view.setPadding(pad + bars.left, dp(12) + bars.top, pad + bars.right, bars.bottom)
            insets
        }
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        val connected = androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        selectPage(if (connected) "Playground" else "Setup")
        return shell
    }

    // ---------------------------------------------------------------- features

    private fun addFeaturesPage(column: LinearLayout) {
        fun toggle(textId: Int, key: String, checked: Boolean, note: Int? = null, onChange: (Boolean) -> Unit = {}) {
            column.addView(SwitchCompat(this).apply {
                text = getString(textId); textSize = 16f; minHeight = dp(56)
                setTextColor(NothingStyle.foreground); typeface = NothingStyle.body
                isChecked = checked
                setOnCheckedChangeListener { _, value ->
                    IslandFeatures.setBoolean(this@MainActivity, key, value)
                    onChange(value)
                    applyFeaturesUpdate()
                }
            }, row(-2))
            note?.let { column.addView(label(getString(it), 13f, NothingStyle.muted), row(-2).apply { bottomMargin = dp(6) }) }
        }
        fun section(textId: Int, first: Boolean = false) =
            column.addView(heading(getString(textId)), row(-2).apply { topMargin = if (first) 0 else dp(22) })

        section(R.string.features_islands, first = true)
        column.addView(label(getString(R.string.features_islands_note), 14f, NothingStyle.muted), row(-2).apply { topMargin = dp(8) })
        toggle(R.string.feature_volume, "volume", features.volume, R.string.feature_volume_note)
        toggle(R.string.feature_headphones, "headphones", features.headphones, R.string.feature_headphones_note) { on ->
            if (on) requestBluetoothIfNeeded()
        }
        toggle(R.string.feature_bluetooth_lost, "bluetooth_lost", features.bluetoothLost) { on -> if (on) requestBluetoothIfNeeded() }
        toggle(R.string.feature_downloads, "downloads", features.downloads)
        toggle(R.string.feature_alarms, "alarms", features.alarms)
        toggle(R.string.feature_ringer, "ringer", features.ringer, R.string.feature_ringer_note)
        toggle(R.string.feature_ringer_controls, "ringer_controls", features.ringerControls, R.string.feature_ringer_controls_note)

        section(R.string.features_messages)
        toggle(R.string.feature_reply, "inline_reply", features.inlineReply)
        toggle(R.string.feature_medium, "messages_medium", features.messagesMediumFirst, R.string.feature_medium_note)
        toggle(R.string.feature_hide_popups, "hide_phone_popups", features.hidePhonePopups, R.string.feature_hide_popups_note)
        popupStatus = label("", 13f, NothingStyle.foreground).also { column.addView(it, row(-2).apply { topMargin = dp(2) }) }
        column.addView(action("popups-a11y", getString(R.string.setup_a11y_allow)) {
            toast(getString(R.string.setup_a11y_toast))
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }, row(-2).apply { topMargin = dp(8) })
        column.addView(action("popups-grant", getString(R.string.popups_grant_button)) { explainPopupGrant() },
            row(-2).apply { topMargin = dp(8) })
        refreshPopupStatus()

        section(R.string.features_look)
        toggle(R.string.feature_short, "short_compact", features.shortCompact, R.string.feature_short_note)
        val height = fitControl(getString(R.string.feature_height), IslandFeatureSet.MIN_COMPACT_DP.toInt(),
            IslandFeatureSet.MAX_COMPACT_DP.toInt(), features.compactHeightDp.toInt(), "dp") {
            IslandFeatures.setCompactHeight(this, it.toFloat()); applyFeaturesUpdate()
        }
        fitSliders.removeAt(fitSliders.lastIndex)
        column.addView(height, row(-2))
        toggle(R.string.feature_corners, "phone_corners", features.phoneCorners)
        toggle(R.string.feature_bubbles, "coloured_bubbles", features.colouredBubbles, R.string.feature_bubbles_note)
        toggle(R.string.feature_glow, "edge_glow", features.edgeGlow, R.string.feature_glow_note)
        toggle(R.string.feature_full_width, "full_width_open", features.fullWidthOpen, R.string.feature_full_width_note)
        toggle(R.string.feature_art_backdrop, "artwork_backdrop", features.artworkBackdrop, R.string.feature_art_backdrop_note)
        toggle(R.string.feature_idle_pill, "idle_pill", features.idlePill, R.string.feature_idle_pill_note)
        toggle(R.string.feature_hide_media, "hide_for_media", features.hideForMedia, R.string.feature_hide_media_note)
        column.addView(label(getString(R.string.feature_outline), 16f, NothingStyle.foreground).apply {
            typeface = NothingStyle.body; setPadding(0, dp(12), 0, 0)
        }, row(-2))
        column.addView(android.widget.RadioGroup(this).apply {
            orientation = android.widget.RadioGroup.HORIZONTAL
            listOf(OutlineMode.OFF to R.string.outline_off, OutlineMode.EVENTS to R.string.outline_events,
                OutlineMode.ALWAYS to R.string.outline_always).forEach { (mode, name) ->
                addView(android.widget.RadioButton(this@MainActivity).apply {
                    id = View.generateViewId(); tag = mode; text = getString(name); minHeight = dp(48)
                    setTextColor(NothingStyle.foreground)
                    buttonTintList = ColorStateList.valueOf(NothingStyle.foreground)
                    isChecked = features.outline == mode
                })
            }
            setOnCheckedChangeListener { group, id ->
                (group.findViewById<android.widget.RadioButton>(id)?.tag as? OutlineMode)?.let {
                    IslandFeatures.setOutline(this@MainActivity, it); applyFeaturesUpdate()
                }
            }
        }, row(-2))
        toggle(R.string.feature_burn_in, "burn_in", features.burnInCare, R.string.feature_burn_in_note)
        toggle(R.string.feature_lock, "lock_screen", features.lockScreen, R.string.feature_lock_note)

        section(R.string.features_touch)
        toggle(R.string.haptic_tap, "haptic_tap", features.hapticTap)
        toggle(R.string.haptic_hold, "haptic_hold", features.hapticHold)
        toggle(R.string.haptic_swipe, "haptic_swipe", features.hapticSwipe)
        toggle(R.string.haptic_up, "haptic_up", features.hapticUp)
        toggle(R.string.haptic_dnd, "haptic_dnd_quiet", features.quietHapticsInDnd)
    }

    private fun navLabel(key: String) = getString(when (key) {
        "Playground" -> R.string.nav_playground
        "Setup" -> R.string.nav_setup
        "Appearance" -> R.string.nav_appearance
        "Rules" -> R.string.nav_rules
        "Phone fit" -> R.string.nav_phone_fit
        else -> R.string.nav_features
    })

    private fun modeName(mode: IslandMode) = getString(when (mode) {
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
    })

    private fun accentName(index: Int) = getString(when (index) {
        1 -> R.string.accent_red; 2 -> R.string.accent_orange; 3 -> R.string.accent_yellow
        4 -> R.string.accent_green; 5 -> R.string.accent_blue; 6 -> R.string.accent_purple
        7 -> R.string.accent_pink; else -> R.string.accent_default
    })

    /** One line on whether the phone's own pop-ups are hidden right now, and why not if they aren't. */
    private fun refreshPopupStatus() {
        val status = popupStatus ?: return
        val wanted = IslandFeatures.read(this).hidePhonePopups
        val granted = HeadsUpControl.canControl(this)
        val a11y = IslandAccessibilityService.instance != null
        val working = HeadsUpControl.hiding(this) || !granted && a11y
        status.visibility = if (wanted) View.VISIBLE else View.GONE
        status.text = getString(when {
            HeadsUpControl.hiding(this) -> R.string.popups_status_active
            granted -> R.string.popups_status_waiting
            a11y -> R.string.popups_status_swipe
            else -> R.string.popups_status_needs_access
        })
        status.setTextColor(if (working) 0xFF9BE4BE.toInt() else NothingStyle.muted)
        // Without the system switch, swiping pop-ups away needs the accessibility service.
        buttons["popups-a11y"]?.visibility = if (wanted && !granted && !a11y) View.VISIBLE else View.GONE
        // The computer step is optional: it stops pop-ups outright instead of swiping them.
        buttons["popups-grant"]?.visibility = if (wanted && !granted) View.VISIBLE else View.GONE
    }

    private fun explainPopupGrant() {
        android.app.AlertDialog.Builder(this, R.style.IslandDialog)
            .setTitle(getString(R.string.popups_grant_title))
            .setMessage(getString(R.string.popups_grant_body, HeadsUpControl.GRANT_COMMAND))
            .setNegativeButton(getString(R.string.common_done), null)
            .setPositiveButton(getString(R.string.popups_copy)) { _, _ ->
                getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(
                    android.content.ClipData.newPlainText("adb", HeadsUpControl.GRANT_COMMAND))
                toast(getString(R.string.popups_copied))
            }.show()
    }

    private fun requestBluetoothIfNeeded() {
        val permission = DeviceMonitor.permission() ?: return
        if (!DeviceMonitor.hasPermission(this)) bluetoothPermission.launch(permission)
    }

    private fun applyFeaturesUpdate() {
        features = IslandFeatures.read(this)
        preview.applyFeatures(features)
        wallpaperPreview?.applyFeatures(features)
        refreshCustomization()
        // The service applies the change asynchronously; check again once it has.
        popupStatus?.postDelayed({ refreshPopupStatus() }, 400L)
        refreshPopupStatus()
    }

    private data class InstalledApp(val packageName: String, val label: String)

    private fun priorityControl(mode: IslandMode, initial: Int): LinearLayout {
        val title = label(getString(R.string.priority_label, modeName(mode), initial), 16f, NothingStyle.foreground).apply {
            typeface = NothingStyle.body
        }
        val slider = SeekBar(this).apply {
            max = 100; progress = initial; contentDescription = getString(R.string.priority_description, modeName(mode))
            progressTintList = ColorStateList.valueOf(NothingStyle.red)
            thumbTintList = ColorStateList.valueOf(NothingStyle.foreground)
        }
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) { title.text = getString(R.string.priority_label, modeName(mode), value) }
            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) {
                IslandCustomization.setPriority(this@MainActivity, mode, slider.progress); refreshCustomization()
            }
        })
        return LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(12), 0, 0); addView(title, row(-2)); addView(slider, row(dp(48))) }
    }

    private fun installedApps(): List<InstalledApp> = runCatching {
        packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { InstalledApp(it.activityInfo.packageName, it.loadLabel(packageManager).toString()) }
            .filter { it.packageName != packageName }.distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }.getOrDefault(emptyList())

    private fun chooseApps(allowed: Boolean) {
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

    private fun chooseQuickActions() {
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

    private fun chooseAppStyle() {
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

    private fun startTimerPreset(minutes: Int) {
        val intent = Intent(android.provider.AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(android.provider.AlarmClock.EXTRA_LENGTH, minutes * 60)
            val name = timerLabelInput?.text?.toString()?.trim().orEmpty().ifBlank { getString(R.string.timer_label) }
            putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, name)
            putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (intent.resolveActivity(packageManager) != null) runCatching { startActivity(intent) }
            .onFailure { toast(getString(R.string.timer_failed)) }
        else {
            val label = timerLabelInput?.text?.toString()?.trim().orEmpty().ifBlank { getString(R.string.demo_minutes, minutes) }
            preview.setSecondary(null); showDemo(demoTimer(minutes).copy(title = label), Presentation.EXPANDED)
        }
    }

    private fun saveQuiet(enabled: Boolean) {
        IslandCustomization.setQuietHours(this, enabled, IslandCustomization.quietStart(this), IslandCustomization.quietEnd(this))
        refreshCustomization()
    }
    private fun chooseTime(start: Boolean) {
        val minute = if (start) IslandCustomization.quietStart(this) else IslandCustomization.quietEnd(this)
        android.app.TimePickerDialog(this, { _, hour, mins ->
            val from = if (start) hour * 60 + mins else IslandCustomization.quietStart(this)
            val until = if (start) IslandCustomization.quietEnd(this) else hour * 60 + mins
            IslandCustomization.setQuietHours(this, IslandCustomization.quietEnabled(this), from, until)
            refreshCustomization()
        }, minute / 60, minute % 60, false).show()
    }
    private fun minuteLabel(value: Int) = "%02d:%02d".format(value / 60, value % 60)

    private fun saveProfile() {
        val input = android.widget.EditText(this).apply { hint = getString(R.string.profile_hint); setSingleLine() }
        android.app.AlertDialog.Builder(this, R.style.IslandDialog).setTitle(getString(R.string.profile_save_title)).setView(input)
            .setNegativeButton(getString(R.string.common_cancel), null).setPositiveButton(getString(R.string.common_save)) { _, _ ->
                val name = input.text.toString().trim().ifBlank { "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}" }
                IslandProfileStore.save(this, name, fit); refreshCustomization(); toast(getString(R.string.profile_saved))
            }.show()
    }
    private fun loadProfile() {
        val names = IslandProfileStore.names(this).sorted()
        if (names.isEmpty()) { toast(getString(R.string.profile_need_save)); return }
        android.app.AlertDialog.Builder(this, R.style.IslandDialog).setTitle(getString(R.string.profile_load_title)).setItems(names.toTypedArray()) { _, index ->
            IslandProfileStore.load(this, names[index])?.let { next ->
                fit = next
                val values = listOf((fit.scale * 100).toInt(), (fit.pillWidth * 100).toInt(), fit.offsetX.toInt(), fit.offsetY.toInt())
                fitSliders.take(4).forEachIndexed { i, slider -> slider.progress = values[i] - listOf(70, 70, -180, -24)[i] }
                cameraSwitch?.isChecked = fit.alignCamera
                placementChoices?.let { group -> (0 until group.childCount).map { group.getChildAt(it) }
                    .firstOrNull { it.tag == fit.placement }?.let { group.check(it.id) } }
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
    private fun refreshCustomization() {
        allowedAppsInfo?.text = when {
            !IslandCustomization.allowedFilterEnabled(this) -> getString(R.string.rules_all_allowed)
            IslandCustomization.allowedApps(this).isEmpty() -> getString(R.string.rules_none_allowed)
            else -> getString(R.string.rules_some_allowed, IslandCustomization.allowedApps(this).size)
        }
        hiddenAppsInfo?.text = IslandCustomization.hiddenWhenOpen(this).let { if (it.isEmpty()) getString(R.string.rules_none_hidden) else getString(R.string.rules_some_hidden, it.size) }
        quietSwitch?.isChecked = IslandCustomization.quietEnabled(this)
        quietStartButton?.text = getString(R.string.rules_quiet_from, minuteLabel(IslandCustomization.quietStart(this)))
        quietEndButton?.text = getString(R.string.rules_quiet_until, minuteLabel(IslandCustomization.quietEnd(this)))
        timerBubbleSwitch?.isChecked = IslandCustomization.timerAsBubble(this)
        if (Settings.canDrawOverlays(this) || IslandAccessibilityService.isEnabled(this))
            startService(Intent(this, IslandOverlayService::class.java).setAction(IslandOverlayService.ACTION_CUSTOMIZATION))
    }

    private fun selectPage(name: String) {
        if (name == "Rules") refreshCustomization()
        val changed = currentPage != null && currentPage != name
        currentPage = name
        pages.forEach { (key, view) -> view.visibility = if (key == name) View.VISIBLE else View.GONE }
        // A new page is a new window: the logo rebuilds and the page's parts rise in.
        if (changed) {
            headerLogo?.takeIf { !Motion.reduced(this) }?.play(0L, 750L)
            pages[name]?.let { page -> Motion.staggerIn((0 until minOf(page.childCount, 10)).map { page.getChildAt(it) }, this, 0L, 28L, 12f) }
        }
        navigation.forEach { (key, button) ->
            val selected = key == name
            button.isSelected = selected
            button.setTextColor(if (selected) Color.BLACK else NothingStyle.foreground)
            button.background = controlBackground(if (selected) NothingStyle.foreground else NothingStyle.surface)
            ViewCompat.setStateDescription(button, if (selected) getString(R.string.state_selected) else "")
        }
        pages[name]?.parent?.let { (it as? View)?.parent as? ScrollView }?.scrollTo(0, 0)
    }

    private fun placementExplanation(): String = when {
        fit.placement == IslandPlacement.BELOW_STATUS_BAR -> getString(R.string.placement_below)
        IslandAccessibilityService.instance != null -> getString(R.string.placement_camera)
        else -> getString(R.string.placement_standard)
    }

    private fun addWallpaperControls(column: LinearLayout) {
        column.addView(heading(getString(R.string.glass_heading)), row(-2).apply { topMargin = dp(28) })
        column.addView(label(getString(R.string.glass_hint), 14f, NothingStyle.muted), row(-2).apply { topMargin = dp(12) })
        column.addView(action("wallpaper-pick", getString(R.string.glass_choose)) { pickWallpaper.launch("image/*") }, row(-2).apply { topMargin = dp(12) })
        wallpaperSwitch = SwitchCompat(this).apply {
            text = getString(R.string.glass_inside); textSize = 16f; minHeight = dp(56)
            setTextColor(NothingStyle.foreground)
            isChecked = wallpaperStyle.enabled && WallpaperSkinStore.image(this@MainActivity) != null
            setOnCheckedChangeListener { _, checked ->
                if (checked && WallpaperSkinStore.image(this@MainActivity) == null) {
                    isChecked = false; pickWallpaper.launch("image/*")
                } else { wallpaperStyle = wallpaperStyle.copy(enabled = checked); applyWallpaperUpdate() }
            }
        }
        column.addView(wallpaperSwitch, row(-2))
        val status = label(WallpaperRecovery.status(this), 14f, NothingStyle.muted)
        column.addView(SwitchCompat(this).apply {
            text = getString(R.string.glass_refraction); minHeight = dp(56)
            setTextColor(NothingStyle.foreground); isChecked = wallpaperStyle.glass
            isEnabled = Build.VERSION.SDK_INT >= 33
            setOnCheckedChangeListener { _, checked ->
                wallpaperStyle = wallpaperStyle.copy(glass = checked)
                if (checked) WallpaperRecovery.clearStatus(this@MainActivity)
                status.text = WallpaperRecovery.status(this@MainActivity)
                applyWallpaperUpdate()
            }
        }, row(-2))
        column.addView(status, row(-2))
        wallpaperPreview = IslandView(this).apply {
            anchorToCutout = false
            applyFit(IslandFit(scale = .85f, alignCamera = false)); applyTheme(theme)
            applyWallpaper(wallpaperStyle, WallpaperSkinStore.image(this@MainActivity))
            callbacks = object : IslandView.Callbacks {
                override fun onExpandRequest() { show(activity, Presentation.EXPANDED) }
                override fun onCollapseRequest() { show(activity, Presentation.COMPACT) }
                override fun onOpen(activity: LiveActivity) { show(activity, if (presentation == Presentation.COMPACT) Presentation.EXPANDED else Presentation.COMPACT) }
            }
            // A paused preview needs no continuous frames; tap/hold still demonstrates morphs.
            show(demoMusic(false), Presentation.COMPACT)
        }
        column.addView(wallpaperPreview, row(dp(180)))
        fun control(name: String, value: Int, low: Int = 0, high: Int = 100, update: (Int) -> Unit) {
            val container = fitControl(name, low, high, value, "%", update)
            fitSliders.removeAt(fitSliders.lastIndex)
            column.addView(container, row(-2))
        }
        control(getString(R.string.glass_x), (wallpaperStyle.x * 100).toInt()) {
            wallpaperStyle = wallpaperStyle.copy(x = it / 100f); applyWallpaperUpdate()
        }
        control(getString(R.string.glass_y), (wallpaperStyle.y * 100).toInt()) {
            wallpaperStyle = wallpaperStyle.copy(y = it / 100f); applyWallpaperUpdate()
        }
        control(getString(R.string.glass_strength), (wallpaperStyle.strength * 100).toInt(), 15, 65) {
            wallpaperStyle = wallpaperStyle.copy(strength = it / 100f); applyWallpaperUpdate()
        }
        column.addView(action("wallpaper-remove", getString(R.string.glass_remove)) {
            WallpaperSkinStore.remove(this)
            wallpaperStyle = WallpaperSkinStore.read(this); wallpaperSwitch?.isChecked = false
            applyWallpaperUpdate()
        }, row(-2).apply { topMargin = dp(12) })
    }

    private fun applyWallpaperUpdate() {
        wallpaperStyle = wallpaperStyle.safe()
        WallpaperSkinStore.save(this, wallpaperStyle)
        val bitmap = WallpaperSkinStore.image(this)
        preview.applyWallpaper(wallpaperStyle, bitmap)
        wallpaperPreview?.applyWallpaper(wallpaperStyle, bitmap)
        if (Settings.canDrawOverlays(this) || IslandAccessibilityService.isEnabled(this)) {
            startService(Intent(this, IslandOverlayService::class.java).setAction(IslandOverlayService.ACTION_WALLPAPER))
        }
    }

    private fun chip(text: String, click: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 16f
        minHeight = dp(56)
        setPadding(dp(12), dp(14), dp(12), dp(14))
        contentDescription = getString(R.string.chip_preview, text)
        setTextColor(NothingStyle.foreground)
        typeface = NothingStyle.body
        background = controlBackground()
        setOnClickListener { click() }
        Motion.springPress(this)
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(3); marginEnd = dp(3)
        }
    }

    private fun action(key: String, text: String, click: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 16f
        minHeight = dp(56)
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(16), dp(16), dp(16))
        setTextColor(NothingStyle.foreground)
        typeface = NothingStyle.body
        background = controlBackground()
        setOnClickListener { click() }
        Motion.springPress(this, .975f)
        buttons[key] = this
    }

    private fun label(text: String, size: Float, color: Int) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        typeface = NothingStyle.body
    }

    private fun heading(text: String) = label(text, 14f, NothingStyle.foreground).apply {
        typeface = NothingStyle.mono
        ViewCompat.setAccessibilityHeading(this, true)
    }

    private fun controlBackground(color: Int = NothingStyle.surface): android.graphics.drawable.Drawable {
        val shape = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat(); setColor(color)
            setStroke(dp(1), NothingStyle.line)
        }
        return android.graphics.drawable.RippleDrawable(
            ColorStateList.valueOf(if (color == NothingStyle.foreground) 0x22000000 else 0x33FFFFFF), shape, null)
    }

    private fun fitControl(name: String, min: Int, max: Int, value: Int, unit: String, change: (Int) -> Unit): LinearLayout {
        val spokenUnit = if (unit == "%") getString(R.string.unit_percent) else getString(R.string.unit_dp)
        val title = label(getString(R.string.slider_title, name, value, unit), 16f, NothingStyle.foreground).apply {
            typeface = NothingStyle.body
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val slider = SeekBar(this).apply {
            id = View.generateViewId()
            this.max = max - min
            progress = value - min
            contentDescription = name
            ViewCompat.setStateDescription(this, getString(R.string.slider_state, value, spokenUnit))
            progressTintList = ColorStateList.valueOf(NothingStyle.red)
            thumbTintList = ColorStateList.valueOf(NothingStyle.foreground)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    title.text = getString(R.string.slider_title, name, progress + min, unit)
                    seekBar?.let { ViewCompat.setStateDescription(it, getString(R.string.slider_state, progress + min, spokenUnit)) }
                    if (fromUser) change(progress + min)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        fitSliders.add(slider)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(16), 0, 0)
            addView(title, row(-2))
            addView(slider, row(dp(48)))
        }
    }

    private fun updateFit() {
        IslandPreferences.save(this, fit)
        placementInfo?.text = placementExplanation()
        preview.applyFit(fit.copy(offsetY = 0f))
        if (Settings.canDrawOverlays(this) || IslandAccessibilityService.isEnabled(this)) {
            startService(Intent(this, IslandOverlayService::class.java).setAction(IslandOverlayService.ACTION_FIT))
        }
    }

    // ---------------------------------------------------------------- wallpaper theme

    private fun swatch() = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(10) }
        contentDescription = getString(R.string.theme_colour)
    }

    private fun applyWallpaperTheme(next: IslandTheme?) {
        if (next == null) {
            toast(getString(R.string.theme_failed))
            return
        }
        theme = next.copy(enabled = true).safe()
        themeSwitch?.isChecked = true
        applyThemeUpdate()
        toast(getString(R.string.theme_applied))
    }

    private fun applyThemeUpdate() {
        theme = theme.safe()
        IslandPreferences.saveTheme(this, theme)
        preview.applyTheme(theme)
        swatchA?.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (theme.enabled) theme.accent else NothingStyle.red)
            setStroke(2, NothingStyle.line)
        }
        swatchB?.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (theme.enabled) theme.secondary else NothingStyle.muted)
            setStroke(2, NothingStyle.line)
        }
        if (Settings.canDrawOverlays(this) || IslandAccessibilityService.isEnabled(this)) {
            startService(Intent(this, IslandOverlayService::class.java).setAction(IslandOverlayService.ACTION_THEME))
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    private fun row(height: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun startIsland() {
        startService(Intent(this, IslandOverlayService::class.java))
    }

    companion object {
        private val BG = NothingStyle.background
        private val SURFACE = NothingStyle.surface
        private val MUTED = NothingStyle.muted
    }
}
