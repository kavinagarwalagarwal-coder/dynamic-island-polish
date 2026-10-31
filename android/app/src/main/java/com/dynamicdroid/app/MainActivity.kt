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
                            toast("Wallpaper ready")
                        } else toast("Couldn't read that image. Try a JPG or PNG.")
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
        window.statusBarColor = BG
        setContentView(buildScreen())
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        showDemo(demoMusic(true), Presentation.EXPANDED)
        applyThemeUpdate()
    }

    override fun onResume() {
        super.onResume()
        clockDiagnostic?.text = TimerDiagnostics.read(this)
        val overlay = Settings.canDrawOverlays(this)
        buttons["overlay"]?.text = if (overlay) "Display over other apps: allowed" else "Allow display over other apps"
        buttons["usage"]?.text = if (ForegroundWatcher.hasPermission(this)) "Usage access: allowed" else "Allow usage access (optional)"
        val a11y = IslandAccessibilityService.isEnabled(this)
        buttons["a11y"]?.text = if (a11y) "✓ Camera-area overlay enabled" else "Enable camera-area overlay"
        val notifications = androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        buttons["notifications"]?.text = if (notifications) "✓ Notification access enabled" else "Connect notifications"
        val ready = notifications && (overlay || a11y)
        readiness?.text = if (ready) "Ready • Your island is connected" else "Setup needed • Connect notifications and an overlay"
        readiness?.setTextColor(if (ready) 0xFF9BE4BE.toInt() else NothingStyle.foreground)
        placementInfo?.text = placementExplanation()
        if (overlay || a11y) startIsland()
    }

    // ---------------------------------------------------------------- live preview

    private fun showDemo(a: LiveActivity, p: Presentation) {
        demo = a
        demoPresentation = p
        preview.contentDescription = "${a.mode.name.lowercase()} preview. Hold to expand; swipe up to collapse."
        preview.show(appTint(this, a), p)
    }

    private val previewCallbacks = object : IslandView.Callbacks {
        override fun onOpen(activity: LiveActivity) = toast("Opens ${activity.appLabel}")
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
    }

    private fun demoMusic(playing: Boolean, position: Long = 107_000L): LiveActivity {
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
    )

    private fun demoCall() = LiveActivity(
        key = "demo-call", packageName = "com.whatsapp", mode = IslandMode.CALL,
        title = "Rahul", subtitle = "WhatsApp voice call", appLabel = "WhatsApp",
        appIcon = AppStyle.icon(this, "com.whatsapp"), artwork = gradientArt(Color.rgb(90, 200, 250), Color.rgb(40, 80, 200)),
        accent = AppStyle.accent(this, "com.whatsapp"),
        actions = listOf(IslandAction("Decline", selfIntent()), IslandAction("Answer", selfIntent())),
    )

    private fun demoTimer(minutes: Int = 9) = LiveActivity(
        key = "demo-timer", packageName = "com.google.android.deskclock", mode = IslandMode.TIMER,
        title = if (minutes == 9) "Pasta" else "$minutes min", subtitle = "Timer", appLabel = "Clock",
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
        shell.addView(label("Dynamic Droid", 28f, NothingStyle.foreground).apply {
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            ViewCompat.setAccessibilityHeading(this, true)
        }, row(-2))
        readiness = label("Checking your connections…", 13f, NothingStyle.muted).apply {
            setPadding(0, dp(6), 0, dp(12))
        }.also { shell.addView(it, row(-2)) }
        val navRows = listOf(listOf("Playground", "Setup"), listOf("Appearance", "Rules"), listOf("Phone fit"))
        navRows.forEach { names ->
            val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            names.forEach { name ->
                val button = chip(name) { selectPage(name) }
                button.contentDescription = name
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
        column.addView(heading("TRY YOUR ISLAND"), row(-2))
        column.addView(label("Tap a scene. Hold the island to expand it, or swipe up to collapse.", 14f, NothingStyle.muted), row(-2).apply { topMargin = dp(8) })
        preview = IslandView(this).apply {
            anchorToCutout = false
            callbacks = previewCallbacks
            applyFit(fit.copy(offsetY = 0f))
            applyTheme(theme)
            applyWallpaper(wallpaperStyle, WallpaperSkinStore.image(this@MainActivity))
        }
        column.addView(FrameLayout(this).apply {
            background = GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(NothingStyle.surface) }
            setPadding(dp(6), dp(16), dp(6), dp(8))
            addView(preview, FrameLayout.LayoutParams(-1, -1))
        }, row(dp(280)).apply { topMargin = dp(16) })
        val musicRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        musicRow.addView(chip("Music") { showDemo(demoMusic(true), Presentation.EXPANDED) })
        musicRow.addView(chip("Idle") { demo = null; preview.setSecondary(null); preview.show(null, Presentation.IDLE) })
        musicRow.addView(chip("Chat") { showDemo(demoMessage(), Presentation.EXPANDED) })
        val activityRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        activityRow.addView(chip("Call") { showDemo(demoCall(), Presentation.EXPANDED) })
        activityRow.addView(chip("Timer") { preview.setSecondary(null); showDemo(demoTimer(), Presentation.EXPANDED) })
        activityRow.addView(chip("Two live") {
            preview.setSecondary(demoTimer())
            showDemo(demoMusic(true), Presentation.COMPACT)
        })
        column.addView(musicRow, row(-2).apply { topMargin = dp(14) })
        column.addView(activityRow, row(-2).apply { topMargin = dp(8) })
        timerLabelInput = android.widget.EditText(this).apply {
            hint = "Timer name (optional)"; setSingleLine(); textSize = 15f
            setTextColor(NothingStyle.foreground); setHintTextColor(NothingStyle.muted)
            background = controlBackground(); setPadding(dp(14), dp(4), dp(14), dp(4))
        }
        column.addView(timerLabelInput, row(dp(52)).apply { topMargin = dp(10) })
        val presets = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(5, 10, 15, 25).forEach { mins -> presets.addView(chip("${mins}m") { startTimerPreset(mins) }) }
        column.addView(label("Timer presets", 13f, NothingStyle.muted), row(-2).apply { topMargin = dp(12) })
        column.addView(presets, row(-2))
        column.addView(chip("App → island") {
            val a = demo?.takeIf { it.mode == IslandMode.MUSIC || it.mode == IslandMode.CALL } ?: demoMusic(true)
            demo = a; demoPresentation = Presentation.COMPACT
            preview.setSecondary(null); preview.show(a, Presentation.COMPACT, absorb = true)
        }, row(-2).apply { topMargin = dp(8) })
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            column.addView(chip("Preview SwiftUI morph") {
                startActivity(Intent(this, SwiftIslandMorphPreviewActivity::class.java))
            }, row(-2).apply { topMargin = dp(8) })
        } else {
            column.addView(label("SwiftUI morph preview requires Android 9 or newer.", 13f, NothingStyle.muted), row(-2).apply { topMargin = dp(8) })
        }

        column.addView(label("Previews use sample content. Your real activities appear after Setup.", 13f, NothingStyle.muted), row(-2).apply { topMargin = dp(16) })
        column = page("Rules")
        column.addView(heading("ACTIVITY PRIORITY"), row(-2))
        column.addView(label("When several activities are live, higher scores stay in the main pill.", 14f, NothingStyle.muted), row(-2).apply { topMargin = dp(8) })
        timerBubbleSwitch = SwitchCompat(this).apply {
            text = "Keep timers in the separate bubble"; minHeight = dp(56)
            setTextColor(NothingStyle.foreground); isChecked = IslandCustomization.timerAsBubble(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                IslandCustomization.setTimerAsBubble(this@MainActivity, checked); refreshCustomization()
            }
        }
        column.addView(timerBubbleSwitch, row(-2))
        IslandMode.values().forEach { mode ->
            val initial = IslandCustomization.priority(this, mode)
            column.addView(priorityControl(mode, initial), row(-2))
        }
        column.addView(heading("APP FILTERS & RULES"), row(-2).apply { topMargin = dp(22) })
        allowedAppsInfo = label("All supported apps are currently allowed", 14f, NothingStyle.muted)
        column.addView(allowedAppsInfo, row(-2).apply { topMargin = dp(8) })
        column.addView(action("allowed-apps", "Choose apps allowed in the island") { chooseApps(allowed = true) }, row(-2).apply { topMargin = dp(8) })
        hiddenAppsInfo = label("No apps are hidden while open", 14f, NothingStyle.muted)
        column.addView(hiddenAppsInfo, row(-2).apply { topMargin = dp(8) })
        column.addView(action("hidden-apps", "Hide island while apps are open") { chooseApps(allowed = false) }, row(-2).apply { topMargin = dp(8) })
        column.addView(action("quick-actions", "Choose quick actions") { chooseQuickActions() }, row(-2).apply { topMargin = dp(8) })
        column.addView(action("colors", "Set per-app island styles") { chooseAppStyle() }, row(-2).apply { topMargin = dp(8) })
        column.addView(heading("QUIET HOURS"), row(-2).apply { topMargin = dp(22) })
        quietSwitch = SwitchCompat(this).apply {
            text = "Hide the island on a schedule"; textSize = 16f; minHeight = dp(56)
            setTextColor(NothingStyle.foreground); isChecked = IslandCustomization.quietEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, enabled -> saveQuiet(enabled) }
        }
        column.addView(quietSwitch, row(-2))
        val quietTimes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        quietStartButton = chip("From ${minuteLabel(IslandCustomization.quietStart(this))}") { chooseTime(true) }
        quietEndButton = chip("Until ${minuteLabel(IslandCustomization.quietEnd(this))}") { chooseTime(false) }
        quietTimes.addView(quietStartButton); quietTimes.addView(quietEndButton)
        column.addView(quietTimes, row(-2))
        column.addView(label("Quiet hours use your phone’s local time. Timer notifications remain available when quiet hours end.", 13f, NothingStyle.muted), row(-2).apply { topMargin = dp(6) })

        column = page("Phone fit")
        column.addView(heading("FIT YOUR PHONE"), row(-2))
        column.addView(label("Choose where the island lives. Automatic adapts to the overlay connection, cutout and screen orientation.", 14f, NothingStyle.muted), row(-2).apply { topMargin = dp(10) })
        placementInfo = label(placementExplanation(), 13f, NothingStyle.muted)
        column.addView(placementInfo, row(-2).apply { topMargin = dp(12) })
        placementChoices = android.widget.RadioGroup(this).apply {
            orientation = android.widget.RadioGroup.VERTICAL
            listOf(IslandPlacement.AUTO to "Automatic (recommended)",
                IslandPlacement.CAMERA to "Around camera cutout",
                IslandPlacement.BELOW_STATUS_BAR to "Below status bar").forEach { (mode, title) ->
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
        column.addView(action("test-fit", "Test on my home screen · 12 seconds") {
            if (Settings.canDrawOverlays(this) || IslandAccessibilityService.isEnabled(this)) {
                ContextCompat.startForegroundService(this, Intent(this, IslandOverlayService::class.java).setAction(IslandOverlayService.ACTION_TEST))
                startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
            } else { selectPage("Setup"); toast("Enable an overlay connection first") }
        }, row(-2).apply { topMargin = dp(10) })
        column.addView(action("save-profile", "Save this placement profile") { saveProfile() }, row(-2).apply { topMargin = dp(10) })
        column.addView(action("load-profile", "Load a saved placement profile") { loadProfile() }, row(-2).apply { topMargin = dp(8) })
        column.addView(label("If Samsung or another phone covers the island, select Below status bar, then test again. Adjust the sliders only if needed.", 14f, NothingStyle.muted), row(-2).apply { topMargin = dp(14) })
        column.addView(fitControl("Size", 70, 130, (fit.scale * 100).toInt(), "%") { fit = fit.copy(scale = it / 100f); updateFit() }, row(-2))
        column.addView(fitControl("Pill width", 70, 150, (fit.pillWidth * 100).toInt(), "%") { fit = fit.copy(pillWidth = it / 100f); updateFit() }, row(-2))
        column.addView(fitControl("Left / right", -180, 180, fit.offsetX.toInt(), "dp") { fit = fit.copy(offsetX = it.toFloat()); updateFit() }, row(-2))
        column.addView(fitControl("Up / down", -24, 160, fit.offsetY.toInt(), "dp") { fit = fit.copy(offsetY = it.toFloat()); updateFit() }, row(-2))
        cameraSwitch = SwitchCompat(this).apply {
            text = "Align with camera"
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
        column.addView(action("reset", "Reset phone fit") {
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
        column.addView(heading("MAKE IT YOURS"), row(-2))
        column.addView(label("Choose wallpaper accents or a refracted wallpaper background. Changes apply immediately.", 14f, NothingStyle.muted), row(-2).apply { topMargin = dp(10) })
        themeSwitch = SwitchCompat(this).apply {
            text = "Match island to wallpaper"
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
        swatches.addView(label("Island accent colours", 13f, NothingStyle.muted))
        column.addView(swatches, row(-2))
        column.addView(action("wallpaper", "Use my current wallpaper") {
            applyWallpaperTheme(WallpaperTheme.fromSystem(this))
        }, row(-2).apply { topMargin = dp(8) })
        column.addView(action("app-colors", "Customize each app’s island style") { chooseAppStyle() }, row(-2).apply { topMargin = dp(10) })
        column.addView(action("pick", "Pick an image instead") {
            pickImage.launch("image/*")
        }, row(-2).apply { topMargin = dp(10) })

        addWallpaperControls(column)

        column = page("Setup")
        column.addView(heading("CONNECT YOUR ISLAND"), row(-2))
        column.addView(label("1. Connect notifications for music, timers and calls.\n2. Enable the camera-area overlay for top-edge placement and taps.\n3. Add usage access for more reliable app-to-island transitions.", 15f, NothingStyle.muted), row(-2).apply { topMargin = dp(12) })
        column.addView(label("Display over other apps is a fallback when the camera-area overlay is unavailable. It uses a position below the status bar.", 14f, NothingStyle.muted), row(-2).apply { topMargin = dp(12) })
        column.addView(heading("CLOCK NOTIFICATION STATUS"), row(-2).apply { topMargin = dp(16) })
        clockDiagnostic = label(TimerDiagnostics.read(this), 13f, NothingStyle.muted)
        column.addView(clockDiagnostic, row(-2).apply { topMargin = dp(8) })


        column.addView(action("overlay", "Allow display over other apps") {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } else startIsland()
        }, row(-2).apply { topMargin = dp(16) })
        column.addView(action("notifications", "Allow notification access") {
            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        }, row(-2).apply { topMargin = dp(10) })
        column.addView(action("a11y", "Allow tapping over the status bar") {
            toast("Find Dynamic Droid under Installed apps. If it's greyed out: App info → ⋮ → Allow restricted settings.")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }, row(-2).apply { topMargin = dp(10) })
        column.addView(action("usage", "Allow usage access (optional)") {
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

    private data class InstalledApp(val packageName: String, val label: String)

    private fun priorityControl(mode: IslandMode, initial: Int): LinearLayout {
        val title = label("${mode.name.lowercase().replaceFirstChar { it.uppercase() }} · $initial", 16f, NothingStyle.foreground).apply {
            typeface = NothingStyle.body
        }
        val slider = SeekBar(this).apply {
            max = 100; progress = initial; contentDescription = "${mode.name.lowercase()} priority"
            progressTintList = ColorStateList.valueOf(NothingStyle.red)
            thumbTintList = ColorStateList.valueOf(NothingStyle.foreground)
        }
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) { title.text = "${mode.name.lowercase().replaceFirstChar { it.uppercase() }} · $value" }
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
            hint = "Search installed apps"; setSingleLine(); textSize = 15f
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
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle(if (allowed) "Apps allowed in the island" else "Hide while these apps are open")
            .setView(picker)
            .setNegativeButton("Cancel", null)
            .setNeutralButton(if (allowed) "Allow all" else "Clear rules") { _, _ ->
                if (allowed) IslandCustomization.setAllowedApps(this, emptySet(), filterEnabled = false)
                else IslandCustomization.setHiddenWhenOpen(this, emptySet())
                refreshCustomization()
            }
            .setPositiveButton("Save") { _, _ ->
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
            val options = listOf(IslandBus.MEDIA_PREVIOUS to "Previous track", IslandBus.MEDIA_TOGGLE to "Play / pause",
                IslandBus.MEDIA_NEXT to "Next track", IslandBus.MEDIA_SEEK to "Seek bar")
            val current = IslandCustomization.mediaSelection(this, activity.packageName) ?: options.map { it.first }.toSet()
            val selected = current.toMutableSet()
            android.app.AlertDialog.Builder(this).setTitle("Music controls · ${activity.appLabel}")
                .setMultiChoiceItems(options.map { it.second }.toTypedArray(), BooleanArray(options.size) { options[it].first in selected }) { _, index, checked ->
                    if (checked) selected += options[index].first else selected -= options[index].first
                }.setNeutralButton("Use defaults") { _, _ ->
                    IslandCustomization.setMediaSelection(this, activity.packageName, null); refreshCustomization()
                }.setNegativeButton("Cancel", null)
                .setPositiveButton("Save") { _, _ -> IslandCustomization.setMediaSelection(this, activity.packageName, selected); refreshCustomization() }
                .show()
            return
        }
        val actions = activity.actions
        if (actions.isEmpty()) { toast("This app has not provided any quick actions yet"); return }
        val current = IslandCustomization.actionSelection(this, activity.packageName)
        val selected = (current ?: actions.take(3).map { it.label }.toSet()).toMutableSet()
        android.app.AlertDialog.Builder(this).setTitle("Quick actions · ${activity.appLabel}")
            .setMultiChoiceItems(actions.map { it.label }.toTypedArray(), BooleanArray(actions.size) { actions[it].label in selected }) { _, index, checked ->
                val label = actions[index].label
                if (checked && selected.size >= 3) { toast("Choose up to three actions"); return@setMultiChoiceItems }
                if (checked) selected += label else selected -= label
            }
            .setNeutralButton("Use app defaults") { _, _ ->
                IslandCustomization.setActionSelection(this, activity.packageName, null); refreshCustomization()
            }
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ -> IslandCustomization.setActionSelection(this, activity.packageName, selected); refreshCustomization() }
            .show()
    }

    private fun chooseAppStyle() {
        val apps = installedApps(); if (apps.isEmpty()) return
        android.app.AlertDialog.Builder(this).setTitle("Choose an app to style")
            .setItems(apps.map { it.label }.toTypedArray()) { _, index -> editAppStyle(apps[index]) }.show()
    }

    private fun editAppStyle(app: InstalledApp) {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(4)) }
        column.addView(label("How it opens", 14f, NothingStyle.muted), row(-2))
        val layout = android.widget.RadioGroup(this).apply { orientation = android.widget.RadioGroup.VERTICAL }
        val modes = listOf("Use activity default", "Open compact", "Open expanded")
        val selectedMode = when (IslandCustomization.preferredExpanded(this, app.packageName)) { true -> 2; false -> 1; null -> 0 }
        modes.forEachIndexed { index, mode -> layout.addView(android.widget.RadioButton(this).apply {
            id = View.generateViewId(); text = mode; isChecked = index == selectedMode
            setTextColor(NothingStyle.foreground)
        }) }
        column.addView(layout, row(-2))
        column.addView(label("Animation speed", 14f, NothingStyle.muted).apply { setPadding(0, dp(8), 0, 0) }, row(-2))
        val speedLabel = label("${(IslandCustomization.motionSpeed(this, app.packageName) * 100).toInt()}%", 13f, NothingStyle.muted)
        val speed = SeekBar(this).apply { max = 85; progress = ((IslandCustomization.motionSpeed(this@MainActivity, app.packageName) - .65f) * 100).toInt() }
        speed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                speedLabel.text = "${value + 65}%"
            }
            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })
        column.addView(speedLabel, row(-2)); column.addView(speed, row(dp(48)))
        val accentButton = Button(this).apply {
            text = "Island color: ${IslandCustomization.accents[IslandCustomization.accentChoice(this@MainActivity, app.packageName)].first}"
            isAllCaps = false; background = controlBackground(); setTextColor(NothingStyle.foreground)
            setOnClickListener {
                android.app.AlertDialog.Builder(this@MainActivity).setTitle("${app.label} island color")
                    .setSingleChoiceItems(IslandCustomization.accents.map { it.first }.toTypedArray(),
                        IslandCustomization.accentChoice(this@MainActivity, app.packageName)) { dialog, choice ->
                        IslandCustomization.setAccentChoice(this@MainActivity, app.packageName, choice)
                        text = "Island color: ${IslandCustomization.accents[choice].first}"; dialog.dismiss()
                    }.show()
            }
        }
        column.addView(accentButton, row(-2).apply { topMargin = dp(8) })
        android.app.AlertDialog.Builder(this).setTitle(app.label).setView(column)
            .setNeutralButton("Use defaults") { _, _ ->
                IslandCustomization.setPreferredExpanded(this, app.packageName, null)
                IslandCustomization.setMotionSpeed(this, app.packageName, 1f)
                IslandCustomization.setAccentChoice(this, app.packageName, 0)
                refreshCustomization()
            }.setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val checked = layout.checkedRadioButtonId
                val mode = (0 until layout.childCount).firstOrNull { layout.getChildAt(it).id == checked } ?: 0
                IslandCustomization.setPreferredExpanded(this, app.packageName, when (mode) { 1 -> false; 2 -> true; else -> null })
                IslandCustomization.setMotionSpeed(this, app.packageName, (speed.progress + 65) / 100f)
                IslandCustomization.saveStyledApps(this, IslandCustomization.savedApps(this) + app.packageName)
                refreshCustomization(); toast("${app.label} style saved")
            }.show()
    }

    private fun startTimerPreset(minutes: Int) {
        val intent = Intent(android.provider.AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(android.provider.AlarmClock.EXTRA_LENGTH, minutes * 60)
            val name = timerLabelInput?.text?.toString()?.trim().orEmpty().ifBlank { "Timer" }
            putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, name)
            putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (intent.resolveActivity(packageManager) != null) runCatching { startActivity(intent) }
            .onFailure { toast("Couldn’t start that timer") }
        else {
            val label = timerLabelInput?.text?.toString()?.trim().orEmpty().ifBlank { "$minutes min" }
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
        val input = android.widget.EditText(this).apply { hint = "e.g. Samsung"; setSingleLine() }
        android.app.AlertDialog.Builder(this).setTitle("Save phone placement").setView(input)
            .setNegativeButton("Cancel", null).setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim().ifBlank { "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}" }
                IslandProfileStore.save(this, name, fit); refreshCustomization(); toast("Profile saved")
            }.show()
    }
    private fun loadProfile() {
        val names = IslandProfileStore.names(this).sorted()
        if (names.isEmpty()) { toast("Save a placement profile first"); return }
        android.app.AlertDialog.Builder(this).setTitle("Load placement profile").setItems(names.toTypedArray()) { _, index ->
            IslandProfileStore.load(this, names[index])?.let { next ->
                fit = next
                val values = listOf((fit.scale * 100).toInt(), (fit.pillWidth * 100).toInt(), fit.offsetX.toInt(), fit.offsetY.toInt())
                fitSliders.take(4).forEachIndexed { i, slider -> slider.progress = values[i] - listOf(70, 70, -180, -24)[i] }
                cameraSwitch?.isChecked = fit.alignCamera
                placementChoices?.let { group -> (0 until group.childCount).map { group.getChildAt(it) }
                    .firstOrNull { it.tag == fit.placement }?.let { group.check(it.id) } }
                updateFit(); toast("${names[index]} profile loaded")
            }
        }.setNegativeButton("Cancel", null).setNeutralButton("Manage") { _, _ -> manageProfiles(names) }.show()
    }
    private fun manageProfiles(names: List<String>) {
        if (names.isEmpty()) return
        android.app.AlertDialog.Builder(this).setTitle("Remove a placement profile").setItems(names.toTypedArray()) { _, index ->
            IslandProfileStore.remove(this, names[index]); toast("Profile removed")
        }.setNegativeButton("Done", null).show()
    }
    private fun refreshCustomization() {
        allowedAppsInfo?.text = when {
            !IslandCustomization.allowedFilterEnabled(this) -> "All supported apps are currently allowed"
            IslandCustomization.allowedApps(this).isEmpty() -> "No apps are currently allowed"
            else -> "${IslandCustomization.allowedApps(this).size} selected apps are allowed"
        }
        hiddenAppsInfo?.text = IslandCustomization.hiddenWhenOpen(this).let { if (it.isEmpty()) "No apps are hidden while open" else "${it.size} apps hide the island while open" }
        quietSwitch?.isChecked = IslandCustomization.quietEnabled(this)
        quietStartButton?.text = "From ${minuteLabel(IslandCustomization.quietStart(this))}"
        quietEndButton?.text = "Until ${minuteLabel(IslandCustomization.quietEnd(this))}"
        timerBubbleSwitch?.isChecked = IslandCustomization.timerAsBubble(this)
        if (Settings.canDrawOverlays(this) || IslandAccessibilityService.isEnabled(this))
            ContextCompat.startForegroundService(this, Intent(this, IslandOverlayService::class.java).setAction(IslandOverlayService.ACTION_CUSTOMIZATION))
    }

    private fun selectPage(name: String) {
        if (name == "Rules") refreshCustomization()
        pages.forEach { (key, view) -> view.visibility = if (key == name) View.VISIBLE else View.GONE }
        navigation.forEach { (key, button) ->
            val selected = key == name
            button.isSelected = selected
            button.setTextColor(if (selected) Color.BLACK else NothingStyle.foreground)
            button.background = controlBackground(if (selected) NothingStyle.foreground else NothingStyle.surface)
            ViewCompat.setStateDescription(button, if (selected) "Selected" else "")
        }
        pages[name]?.parent?.let { (it as? View)?.parent as? ScrollView }?.scrollTo(0, 0)
    }

    private fun placementExplanation(): String = when {
        fit.placement == IslandPlacement.BELOW_STATUS_BAR -> "Below status bar • avoids system clock and icon overlap"
        IslandAccessibilityService.instance != null -> "Camera-area overlay connected • Automatic follows the camera in portrait"
        else -> "Standard overlay • Automatic keeps the island below the status bar"
    }

    private fun addWallpaperControls(column: LinearLayout) {
        column.addView(heading("WALLPAPER GLASS"), row(-2).apply { topMargin = dp(28) })
        column.addView(label("Choose your wallpaper image. It stays still beneath the refracted glass.", 14f, NothingStyle.muted), row(-2).apply { topMargin = dp(12) })
        column.addView(action("wallpaper-pick", "Choose wallpaper image") { pickWallpaper.launch("image/*") }, row(-2).apply { topMargin = dp(12) })
        wallpaperSwitch = SwitchCompat(this).apply {
            text = "Wallpaper inside pill"; textSize = 16f; minHeight = dp(56)
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
            text = "Liquid Glass refraction"; minHeight = dp(56)
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
        control("Wallpaper horizontal position", (wallpaperStyle.x * 100).toInt()) {
            wallpaperStyle = wallpaperStyle.copy(x = it / 100f); applyWallpaperUpdate()
        }
        control("Wallpaper vertical position", (wallpaperStyle.y * 100).toInt()) {
            wallpaperStyle = wallpaperStyle.copy(y = it / 100f); applyWallpaperUpdate()
        }
        control("Wallpaper strength", (wallpaperStyle.strength * 100).toInt(), 15, 65) {
            wallpaperStyle = wallpaperStyle.copy(strength = it / 100f); applyWallpaperUpdate()
        }
        column.addView(action("wallpaper-remove", "Remove wallpaper image") {
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
            ContextCompat.startForegroundService(this, Intent(this, IslandOverlayService::class.java).setAction(IslandOverlayService.ACTION_WALLPAPER))
        }
    }

    private fun chip(text: String, click: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 16f
        minHeight = dp(56)
        setPadding(dp(12), dp(14), dp(12), dp(14))
        contentDescription = "$text preview"
        setTextColor(NothingStyle.foreground)
        typeface = NothingStyle.body
        background = controlBackground()
        setOnClickListener { click() }
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
        val spokenUnit = if (unit == "%") "percent" else "density-independent pixels"
        val title = label("$name  ·  $value$unit", 16f, NothingStyle.foreground).apply {
            typeface = NothingStyle.body
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val slider = SeekBar(this).apply {
            id = View.generateViewId()
            this.max = max - min
            progress = value - min
            contentDescription = name
            ViewCompat.setStateDescription(this, "$value $spokenUnit")
            progressTintList = ColorStateList.valueOf(NothingStyle.red)
            thumbTintList = ColorStateList.valueOf(NothingStyle.foreground)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    title.text = "$name  ·  ${progress + min}$unit"
                    seekBar?.let { ViewCompat.setStateDescription(it, "${progress + min} $spokenUnit") }
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
            ContextCompat.startForegroundService(this, Intent(this, IslandOverlayService::class.java).setAction(IslandOverlayService.ACTION_FIT))
        }
    }

    // ---------------------------------------------------------------- wallpaper theme

    private fun swatch() = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(10) }
        contentDescription = "Theme colour"
    }

    private fun applyWallpaperTheme(next: IslandTheme?) {
        if (next == null) {
            toast("Couldn't read colours from that")
            return
        }
        theme = next.copy(enabled = true).safe()
        themeSwitch?.isChecked = true
        applyThemeUpdate()
        toast("Island now matches your wallpaper")
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
            ContextCompat.startForegroundService(this, Intent(this, IslandOverlayService::class.java).setAction(IslandOverlayService.ACTION_THEME))
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    private fun row(height: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun startIsland() {
        ContextCompat.startForegroundService(this, Intent(this, IslandOverlayService::class.java))
    }

    companion object {
        private val BG = NothingStyle.background
        private val SURFACE = NothingStyle.surface
        private val MUTED = NothingStyle.muted
    }
}
