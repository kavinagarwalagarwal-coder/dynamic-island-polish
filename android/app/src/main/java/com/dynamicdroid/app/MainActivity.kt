package com.dynamicdroid.app

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
        buttons["a11y"]?.text = if (a11y) "Top-edge tapping: enabled" else "Allow tapping over the status bar"
        if (overlay || a11y) startIsland()
    }

    // ---------------------------------------------------------------- live preview

    private fun showDemo(a: LiveActivity, p: Presentation) {
        demo = a
        demoPresentation = p
        preview.show(a, p)
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

    private fun demoTimer() = LiveActivity(
        key = "demo-timer", packageName = "com.google.android.deskclock", mode = IslandMode.TIMER,
        title = "Pasta", subtitle = "Timer", appLabel = "Clock",
        appIcon = AppStyle.icon(this, "com.google.android.deskclock"), artwork = null,
        accent = AppStyle.accent(this, "com.google.android.deskclock"),
        actions = listOf(IslandAction("Pause", selfIntent()), IslandAction("Cancel", selfIntent())),
        chronometerBase = System.currentTimeMillis() + 9 * 60_000L, chronometerCountDown = true,
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

    private fun buildScreen(): ScrollView {
        val pad = dp(20)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, dp(20), pad, pad)
        }
        column.addView(heading("( 01 )  DROID / NOTHING INSPIRED"), row(-2))
        column.addView(DotHeading(this), row(dp(64)))
        preview = IslandView(this).apply {
            anchorToCutout = false
            callbacks = previewCallbacks
            applyFit(fit.copy(offsetY = 0f))
            applyTheme(theme)
            applyWallpaper(wallpaperStyle, WallpaperSkinStore.image(this@MainActivity))
        }
        column.addView(FrameLayout(this).apply { addView(preview, FrameLayout.LayoutParams(-1, -1)) }, row(dp(270)))
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

        column.addView(heading("( 02 )  PHONE FIT"), row(-2).apply { topMargin = dp(24) })
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
            updateFit()
        }, row(-2))

        column.addView(heading("( 03 )  WALLPAPER THEME"), row(-2).apply { topMargin = dp(28) })
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
        column.addView(action("pick", "Pick an image instead") {
            pickImage.launch("image/*")
        }, row(-2).apply { topMargin = dp(10) })

        addWallpaperControls(column)

        column.addView(heading("( 05 )  CONNECTIONS"), row(-2).apply { topMargin = dp(28) })
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

        return ScrollView(this).apply {
            setBackgroundColor(BG)
            addView(column)
        }
    }

    private fun addWallpaperControls(column: LinearLayout) {
        column.addView(heading("( 04 )  WALLPAPER GLASS"), row(-2).apply { topMargin = dp(28) })
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
        background = NothingStyle.panel()
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
        background = NothingStyle.panel()
        setOnClickListener { click() }
        buttons[key] = this
    }

    private fun label(text: String, size: Float, color: Int) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        typeface = NothingStyle.mono
    }

    private fun heading(text: String) = label(text, 14f, NothingStyle.foreground).apply {
        ViewCompat.setAccessibilityHeading(this, true)
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
