package com.dynamicdroid.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.view.RoundedCorner
import android.text.TextPaint
import android.text.TextUtils
import android.view.Choreographer
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import androidx.core.graphics.ColorUtils
import java.util.WeakHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin

enum class Presentation { IDLE, COMPACT, EXPANDED }

/**
 * The island. Shape and content are driven by springs stepped on every display frame, so
 * every change (new activity, expand, collapse, press, drag) morphs smoothly instead of
 * jumping. Gestures follow iOS:
 *  - compact/minimal: tap opens its app, hold or pull down expands that activity
 *  - expanded: controls work in place; swipe up or tap outside collapses
 */
class IslandView(context: Context) : View(context), Choreographer.FrameCallback {

    interface Callbacks {
        fun onOpen(activity: LiveActivity) {}
        fun onAction(activity: LiveActivity, action: IslandAction) {}
        fun onMedia(activity: LiveActivity, command: String, positionMs: Long? = null) {}
        fun onExpandRequest() {}
        fun onSecondaryExpand(activity: LiveActivity) {}
        fun onSwitchActivity() {}
        fun onCollapseRequest() {}
        fun onDismiss(activity: LiveActivity) {}
        fun onInteraction() {}
        fun onSettled() {}
        fun onAnchorChanged() {}
        /** Another app went fullscreen (video, photos, games) or came back out of it. */
        fun onFullscreenChanged(fullscreen: Boolean) {}
        /** The message's Reply pill was tapped. */
        fun onReply(activity: LiveActivity) {}
        /** The volume island's bar was dragged to [fraction] of the stream's range. */
        fun onVolumeChange(fraction: Float) {}
        /** The album art on the open music card was tapped: show the full-screen dot player. */
        fun onDotPlayer() {}
        /** A ring / vibrate / silent / Do Not Disturb button, or the ringer island, was tapped. */
        fun onRingerMode(state: RingerState) {}
    }

    var callbacks: Callbacks? = null
    var anchorToCutout = true
    var accessibilityHost = true
    private var statusBarHeight = 0f
    private var windowOriginY = 0f
    private var unsafeCutout = false
    private val windowLocation = IntArray(2)

    var activity: LiveActivity? = null
        private set
    var presentation = Presentation.IDLE
        private set

    private var secondary: LiveActivity? = null
    private var userAccent: Int? = null
    private var mediaActions = setOf(IslandBus.MEDIA_PREVIOUS, IslandBus.MEDIA_TOGGLE, IslandBus.MEDIA_NEXT, IslandBus.MEDIA_SEEK)
    private fun showsMediaAction(action: String) = action in mediaActions
    private var departingSecondary: LiveActivity? = null
    private var departingActivity: LiveActivity? = null
    private val presenceS = Spring(0f, 240f, 0.9f, 0.003f)
    private val bubbleCenterS = Spring(0f, 220f, 0.82f, 0.5f)
    private val dropS = Spring(0f, IslandMotion.DROP_K, IslandMotion.DROP_DAMPING_RATIO, 0.003f)
    private val dropGeometry = IslandDropGeometry()
    private val dropNeck = Path()
    private val secondaryRect = RectF()
    private var touchingSecondary = false
    private val secondaryHit = RectF()
    private val bubblePress = Spring(1f, 760f, 0.72f, 0.0008f)
    private val secondaryA = Spring(0f, 240f, 0.84f, 0.003f)
    private val timerFeedback = Spring(0f, 280f, 0.85f, 0.003f)

    fun timerDidComplete() {
        timerFeedback.snap(1f); timerFeedback.target = 0f
        haptic(HapticGesture.HOLD, HapticFeedbackConstants.LONG_PRESS)
        kick()
    }

    // ---------------------------------------------------------------- optional features

    private var features = IslandFeatureSet()
    private var secondaryAccent: Int? = null
    private var screenCorner = 0f
    private var lockDim = false
    private var eventOutlineUntil = 0L
    /** When the current ringer state first showed; drives its one-shot glyph animation. */
    private var ringerAnimAt = 0L
    private val moonCut = Path()
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val dimPaint = Paint().apply { colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }) }
    // Levels glide to new values: critically damped, so they settle without wobbling.
    private val levelS = Spring(0f, 340f, 1f, 0.0015f)
    /** Where the ringer highlight sits: 0 ring, 1 vibrate, 2 silent. It slides between buttons. */
    private val modeS = Spring(0f, 420f, 0.86f, 0.002f)
    /** Album-art backdrop: how much shows (fades with play/pause) and the crossfade between covers. */
    private val artA = Spring(0f, 120f, 1f, 0.003f)
    private val artMix = Spring(1f, 140f, 1f, 0.003f)
    /** Tiny pre-blurred covers; drawn scaled up with filtering, they read as a soft blur for free. */
    private var artBlur: Bitmap? = null
    private var artBlurPrev: Bitmap? = null
    private var artSource: Bitmap? = null
    private val artPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val artSrc = Rect()
    private val modeHits = ArrayList<Pair<RectF, RingerState>>()
    // ---------------------------------------------------------------- dot music
    private val compactDots = IslandDots(9)
    private val cardDots = IslandDots(15)
    private val dotField = IslandDotField()
    /** How strongly the dot field shows: full while playing, faint when paused. */
    private val fieldA = Spring(0f, 120f, 1f, 0.003f)
    /** Real band levels from the visualizer while the music card is open (null: steady pulse). */
    @Volatile var dotSpectrum: FloatArray? = null
    private var dotMono = false
    private var dotSong: String? = null
    private var dotsAt = 0L

    private fun usesDots(a: LiveActivity?) = features.dotMusic && a?.mode == IslandMode.MUSIC

    /** Once per frame: feed the cover to both records and move them along. */
    private fun advanceDots(a: LiveActivity) {
        val now = SystemClock.uptimeMillis()
        val song = a.key + "|" + a.title + "|" + a.subtitle
        val changed = dotSong != null && dotSong != song
        dotSong = song
        compactDots.prepare(a.artwork, dotMono, animate = changed)
        cardDots.prepare(a.artwork, dotMono, animate = changed)
        dotField.prepare(a.artwork, animate = changed)
        if (changed && !reduceMotion) { cardDots.ripple(1.3f); compactDots.ripple(1.3f) }
        if (now == dotsAt) return
        val dt = if (dotsAt == 0L) 1f / 60f else ((now - dotsAt) / 1000f).coerceIn(0f, 0.1f)
        dotsAt = now
        val spectrum = dotSpectrum
        compactDots.step(dt, a.isPlaying, spectrum, reduceMotion)
        cardDots.step(dt, a.isPlaying, spectrum, reduceMotion)
        dotField.step(dt)
    }

    /** The open music card's album art, which opens the dot player. */
    private val musicArtHit = RectF()
    private val bubbleLevelS = Spring(0f, 300f, 1f, 0.0015f)
    private val musicBarS = Spring(0f, 420f, 1f, 0.0008f)
    // A brief thickening of the volume bar on each key press.
    private val barPulse = Spring(0f, 260f, 0.85f, 0.004f)
    // Waveform energy: eases to still dots on pause instead of snapping.
    private val waveS = Spring(0f, 160f, 1f, 0.003f)
    private val burnInTick = Runnable { invalidate() }
    private val outlineTick = Runnable { invalidate() }

    fun applyFeatures(next: IslandFeatureSet) {
        features = next.safe()
        dotMono = DotPrefs.mono(context)
        if (!features.burnInCare) removeCallbacks(burnInTick)
        show(activity, presentation)
        invalidate()
    }

    /** Lock screen: grey content until the phone is unlocked. */
    fun setLockDim(dim: Boolean) {
        if (lockDim == dim) return
        lockDim = dim
        invalidate()
    }

    /** Separate haptics per gesture, silenced in Do Not Disturb when the user asks for that. */
    private fun haptic(gesture: HapticGesture, constant: Int) {
        if (!features.haptic(gesture)) return
        if (features.quietHapticsInDnd && IslandFeatures.dndActive(context)) return
        performHapticFeedback(constant)
    }

    private fun compactHeight() = dp(features.compactHeightDp)
    private fun bubbleDiameter() = compactHeight()

    fun setSecondary(next: LiveActivity?) {
        secondaryAccent = next?.let { IslandCustomization.customAccent(context, it.packageName) ?: it.accent }
        next?.progress?.let { if (next.key != secondary?.key) bubbleLevelS.snap(it) else bubbleLevelS.target = it }
        val fresh = next != null && secondary == null && dropS.value < 0.01f
        if (next != null) departingSecondary = next
        secondary = next
        if (fresh) bubbleCenterS.snap(compactCluster().bubbleCenter)
        dropS.target = if (next != null && presentation == Presentation.COMPACT) 1f else 0f
        secondaryA.target = if (next != null && presentation == Presentation.COMPACT) 1f else 0f
        refreshContentDescription()
        settled = false
        kick()
    }

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private var baseTop = dp(8f)
    private var fit = IslandFit()
    private var cameraX: Float? = null
    private var cameraTop: Float? = null
    private var cutoutCenterY: Float? = null
    private var cutoutDiameter: Float? = null

    private fun restingDiameter() = (cutoutDiameter ?: dp(14f)) / fit.scale
    private fun restingTop() = (cutoutCenterY?.let { it + dp(fit.offsetY) } ?: (baseTop * fit.scale + dp(18f))) / fit.scale - restingDiameter() / 2
    private fun targetTop(p: Presentation) = if (p == Presentation.IDLE && !features.idlePill) restingTop() else baseTop


    fun applyFit(next: IslandFit) {
        fit = next.safe()
        updateAnchor()
        show(activity, presentation)
        invalidate()
    }

    // Wallpaper theme: accents (timer ring, waves, progress, supporting buttons) take the
    // wallpaper's colours when enabled, and the pill body itself becomes a very dark
    // version of the wallpaper's main colour instead of plain black.
    private var accent = NothingStyle.red
    private var accentSoft = NothingStyle.muted
    private var waveColor = NothingStyle.foreground
    private var baseColor = NothingStyle.background
    private var wallpaperAccents = false
    private val timerColor: Int get() = userAccent ?: if (wallpaperAccents) accent else Color.rgb(255, 159, 10)

    fun applyTheme(theme: IslandTheme) {
        val t = theme.safe()
        wallpaperAccents = t.enabled
        accent = if (t.enabled) t.accent else NothingStyle.red
        accentSoft = if (t.enabled) t.secondary else NothingStyle.muted
        waveColor = if (t.enabled) t.accent else NothingStyle.foreground
        baseColor = Color.BLACK
        invalidate()
    }

    private val wallpaperSkin = WallpaperSkinRenderer()
    private val liquidGlass = WallpaperLiquidGlass(context)
    // Fixed area the wallpaper is laid out in, so it stays still while the island morphs over it.
    private val wallpaperStage = RectF()

    fun applyWallpaper(style: WallpaperStyle, image: Bitmap?) {
        wallpaperSkin.style = style
        wallpaperSkin.image = image
        liquidGlass.resetFailure()
        invalidate()
    }

    // Idle status bar data (time, network, battery). Redrawn only when it actually changes.
    private var status = StatusInfo()
    private val statusPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = NothingStyle.mono; textSize = v(15f); fontFeatureSettings = "tnum"
    }
    private fun v(x: Float) = x * resources.displayMetrics.density

    fun applyStatus(next: StatusInfo) {
        if (next == status) return
        status = next
        if (presentation == Presentation.IDLE || idleA.value > 0.01f) invalidate()
    }

    /** Hide (fullscreen media) or bring back the island with a shrink-and-fade. */
    var isConcealed = false
        private set

    fun setConcealed(hidden: Boolean) {
        if (hidden == isConcealed) return
        isConcealed = hidden
        if (hidden) {
            tracking = false; seeking = false
            removeCallbacks(longPress)
        }
        concealS.target = if (hidden) 0f else 1f
        settled = false
        kick()
    }

    private var sawStatusBar = false
    private var lastFullscreen: Boolean? = null
    private fun reportFullscreen(full: Boolean) {
        if (lastFullscreen == full) return
        lastFullscreen = full
        callbacks?.onFullscreenChanged(full)
    }

    private fun updateAnchor() {
        val physicalTop = if (anchorToCutout && fit.alignCamera) cameraTop ?: dp(8f) else dp(8f)
        val below = anchorToCutout && IslandPlacementPolicy.belowStatusBar(fit.placement,
            accessibilityHost, unsafeCutout, resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
        val statusBottom = max(0f, statusBarHeight - windowOriginY)
        baseTop = IslandPlacementPolicy.top(physicalTop, statusBottom, below, dp(fit.offsetY), dp(6f)) / fit.scale
        if (!isAbsorbing) {
            topS.target = targetTop(presentation)
            centerS.target = specFor(presentation, activity).cx
        }
    }

    // Shape: interpolating springs, tuned to flow like a liquid (see IslandMotion.FLUID_*).
    private val widthS = Spring(dp(104f), IslandMotion.SHAPE_K, IslandMotion.FLUID_WIDTH_DAMPING, 0.5f)
    private val heightS = Spring(dp(32f), IslandMotion.SHAPE_K, IslandMotion.FLUID_HEIGHT_DAMPING, 0.5f)
    private val radiusS = Spring(dp(16f), IslandMotion.SHAPE_K, IslandMotion.FLUID_RADIUS_DAMPING, 0.5f)
    private val topS = Spring(baseTop, IslandMotion.SHAPE_K, 0.86f, 0.5f)
    private val centerS = Spring(resources.displayMetrics.widthPixels / 2f / fit.scale, IslandMotion.SHAPE_K, IslandMotion.FLUID_CENTER_DAMPING, 0.5f)
    // Content and gesture feedback
    private val expandedA = Spring(0f, IslandMotion.CONTENT_IN_K, 1f, 0.004f)
    private val compactA = Spring(0f, IslandMotion.CONTENT_IN_K, 1f, 0.004f)
    // Stiffer press so the squish lands on the finger's first frame.
    private val pressS = Spring(1f, 760f, 0.76f, 0.0008f)
    private val stretchS = Spring(0f, 360f, 0.78f, 0.5f)
    private val absorbS = Spring(0f, 78f, 0.94f, 0.003f)
    private val appMorph = AppMorphGeometry()
    private var morphCard: Bitmap? = null
    private var softMorphCard: Bitmap? = null
    private val morphPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    // Wake/unlock intro: 0 = hidden dot, 1 = fully present
    private val introS = Spring(1f, 160f, 0.7f, 0.002f)
    // Status-bar content of the idle pill
    private val idleA = Spring(0f, IslandMotion.CONTENT_IN_K, 1f, 0.004f)
    // 1 = shown, 0 = tucked away (fullscreen media)
    private val concealS = Spring(1f, 230f, 0.9f, 0.003f)
    // Sheen that sweeps across the pill once the intro lands
    private var sheenStart = 0L
    // While the intro's first stage (dot -> circle) runs, show() must not widen the shape yet.
    private var introHoldUntil = 0L
    private val introWiden = Runnable { widenIntro() }
    private val shapeSprings = listOf(widthS, heightS, radiusS, topS, centerS)
    private val springs = listOf(widthS, heightS, radiusS, topS, centerS, expandedA, compactA, pressS, stretchS, absorbS, introS, idleA, concealS, secondaryA, timerFeedback, bubblePress, presenceS, bubbleCenterS, dropS, levelS, bubbleLevelS, musicBarS, barPulse, waveS, modeS, artA, artMix, fieldA)

    private val shapePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private fun blend(a: Int, b: Int, t: Float): Int {
        fun ch(x: Int, y: Int) = (x + (y - x) * t).toInt().coerceIn(0, 255)
        return Color.rgb(ch(Color.red(a), Color.red(b)), ch(Color.green(a), Color.green(b)), ch(Color.blue(a), Color.blue(b)))
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val medium = NothingStyle.mono
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = medium; textSize = dp(16f) }
    private val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(14f) }
    private val smallPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = medium; textSize = dp(11.5f) }
    private val bigPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = medium; textSize = dp(26f) }
    private val letterPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
    }
    // Timer digits: tabular figures so the numbers don't wobble as they tick.
    private val secondaryTimerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = medium; fontFeatureSettings = "tnum" }
    private val digitPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = medium; fontFeatureSettings = "tnum" }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val arcRect = RectF()

    // Timer live state: rolling digits and the ring's total duration per timer
    private var rollKey: String? = null
    private var rollText = ""
    private var rollPrev = ""
    private var rollAt = 0L
    private val timerTotals = HashMap<String, Long>()

    private val rect = RectF()
    private val hit = RectF()
    private val clip = Path()
    private val iconPath = Path()
    private val matrix = Matrix()
    private val shaders = WeakHashMap<Bitmap, BitmapShader>()
    private val phoneIcon = context.getDrawable(android.R.drawable.ic_menu_call)?.mutate()

    // Hit-test geometry captured while drawing
    private var barLeft = 0f
    private var barRight = 0f
    private var barY = -1000f
    private val actionHits = ArrayList<Pair<RectF, IslandAction>>()
    private val replyHit = RectF()
    private var volumeLeft = 0f
    private var volumeRight = 0f
    private var volumeDragging = false

    // Touch state
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var tracking = false
    private var dragging = false
    private var longFired = false
    private var seeking = false
    private var seekFraction = 0f
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private val longPress = Runnable {
        if (tracking && !dragging && presentation == Presentation.COMPACT) {
            longFired = true
            haptic(HapticGesture.HOLD, HapticFeedbackConstants.LONG_PRESS)
            pressS.target = 1f
            bubblePress.target = 1f
            if (touchingSecondary) secondary?.let { callbacks?.onSecondaryExpand(it) }
            else callbacks?.onExpandRequest()
        }
    }

    // Cached glow shader (rebuilding a RadialGradient every frame churns the GC)
    private var glowShader: RadialGradient? = null
    private var glowColor = 0
    private var glowRadius = 0f

    // Frame loop
    private var running = false
    private var delayedFrame = false
    private var lastFrame = 0L
    private var settled = true
    private var reduceMotion = false

    init {
        isClickable = true
        isFocusable = false
        refreshContentDescription()
        // Pre-Android 11 fallback: legacy flag still reports when the foreground app hides the status bar.
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT < 30) setOnSystemUiVisibilityChangeListener { flags ->
            if (anchorToCutout) reportFullscreen(flags and View.SYSTEM_UI_FLAG_FULLSCREEN != 0)
        }
    }

    // ---------------------------------------------------------------- public API

    fun show(next: LiveActivity?, nextPresentation: Presentation, absorb: Boolean = false) {
        reduceMotion = android.provider.Settings.Global.getFloat(context.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        val p = if (next == null) Presentation.IDLE else nextPresentation
        val old = activity
        if (old != null && next != null && old.key == next.key && next.mode == IslandMode.TIMER &&
            ((old.pausedTimerMs == null) != (next.pausedTimerMs == null) ||
                (old.clockMs() ?: 0L) > 0L && next.clockMs() == 0L)) {
            timerFeedback.snap(1f); timerFeedback.target = 0f
        }
        val playing = next?.mode == IslandMode.MUSIC && next.isPlaying
        if (next?.key != old?.key) waveS.snap(if (playing) 1f else 0f) else waveS.target = if (playing) 1f else 0f
        if (next == null && activity != null) departingActivity = activity
        if (next != null) departingActivity = null
        if (next?.mode == IslandMode.RINGER && (old?.mode != IslandMode.RINGER || old.ringer != next.ringer)) {
            ringerAnimAt = SystemClock.uptimeMillis()
            eventOutlineUntil = ringerAnimAt + OUTLINE_EVENT_MS
            removeCallbacks(outlineTick); postDelayed(outlineTick, OUTLINE_EVENT_MS + 32L)
        }
        if (next != null && next.key != old?.key) {
            eventOutlineUntil = SystemClock.uptimeMillis() + OUTLINE_EVENT_MS
            removeCallbacks(outlineTick); postDelayed(outlineTick, OUTLINE_EVENT_MS + 32L)
            next.progress?.let { levelS.snap(it) }
            ringerIndex(next.ringer)?.let { modeS.snap(it) }
        }
        next?.progress?.let { level ->
            if (!volumeDragging) levelS.target = level
            if (next.mode == IslandMode.VOLUME && old?.key == next.key && old.progress != level && !reduceMotion) {
                barPulse.snap(1f); barPulse.target = 0f
            }
        }
        activity = next
        userAccent = next?.let { IslandCustomization.customAccent(context, it.packageName) }
        mediaActions = next?.let { activity -> IslandCustomization.mediaSelection(context, activity.packageName)
            ?: setOf(IslandBus.MEDIA_PREVIOUS, IslandBus.MEDIA_TOGGLE, IslandBus.MEDIA_NEXT, IslandBus.MEDIA_SEEK) }
            ?: setOf(IslandBus.MEDIA_PREVIOUS, IslandBus.MEDIA_TOGGLE, IslandBus.MEDIA_NEXT, IslandBus.MEDIA_SEEK)
        // Appearing out of nothing (no resting pill on screen): grow out of the camera instead of
        // fading in a shape left over from whatever was shown last.
        val fromNothing = next != null && presenceS.value < 0.05f
        presenceS.target = if (next != null || features.idlePill) 1f else 0f
        updateArtBackdrop(next)
        presentation = p
        secondaryA.target = if (secondary != null && p == Presentation.COMPACT) 1f else 0f
        dropS.target = secondaryA.target
        val cluster = compactCluster()
        if (secondary != null && p == Presentation.COMPACT) bubbleCenterS.target = cluster.bubbleCenter
        refreshContentDescription()
        // Mid-intro: the widen stage picks up the latest state itself.
        if (!absorb && SystemClock.uptimeMillis() < introHoldUntil) return
        if (introS.value < 0.001f && introS.target == 0f) return
        if (introHoldUntil == 0L && !sheenActive() && !isAbsorbing) restoreShapeSprings()
        presenceS.stiffness = 240f
        next?.let { activity ->
            val speed = IslandCustomization.motionSpeed(context, activity.packageName)
            shapeSprings.forEach { it.stiffness = IslandMotion.SHAPE_K / (speed * speed) }
        }
        val spec = specFor(p, next)

        if (isAbsorbing && (next?.key != old?.key || p != Presentation.COMPACT)) {
            absorbS.snap(0f); morphCard = null; softMorphCard = null
        }
        if ((absorb || fromNothing) && next != null && !reduceMotion) {
            // Back from an app, or something new with nothing on screen: the island grows out of
            // the camera as one smooth motion, a dot that pops round and stretches into the pill
            // with its content fading in once there's room. No stand-in shape, no swap midway.
            playReturn(spec, p)
            return
        }
        if (next == null && !features.idlePill && !reduceMotion && old != null) {
            // Leaving: shrink back into the camera while fading, the reverse of appearing.
            val d = dp(12f)
            widthS.target = d; heightS.target = d; radiusS.target = d / 2f
            centerS.target = cameraCenter(); topS.target = baseTop + dp(8f)
            absorbS.target = 0f
            setContentTargets(p)
            softExit(old, next)
            settled = false
            kick()
            return
        }
        widthS.target = spec.w
        heightS.target = spec.h
        radiusS.target = spec.r
        centerS.target = spec.cx
        topS.target = targetTop(p)
        absorbS.target = 0f
        setContentTargets(p)
        softExit(old, next)
        settled = false
        kick()
    }

    /**
     * The volume, ringer and device islands slide away at the same unhurried pace they arrive
     * with, instead of snapping shut: content fades at the entrance speed and the shape settles
     * on softer springs.
     */
    private fun softExit(old: LiveActivity?, next: LiveActivity?) {
        if (old == null || next?.key == old.key || reduceMotion) return
        if (old.mode != IslandMode.VOLUME && old.mode != IslandMode.RINGER && old.mode != IslandMode.DEVICE) return
        expandedA.stiffness = IslandMotion.CONTENT_IN_K
        shapeSprings.forEach { it.stiffness = IslandMotion.SHAPE_K * 0.55f }
        presenceS.stiffness = 110f
    }

    fun cancelAppMorph() {
        if (!isAbsorbing) return
        absorbS.snap(0f); morphCard = null; softMorphCard = null
        settled = false; kick(); invalidate()
    }

    /** Content fades in slowly once the shape has room, and fades out fast so it never squashes. */
    private fun setContentTargets(p: Presentation) {
        expandedA.target = if (p == Presentation.EXPANDED) 1f else 0f
        expandedA.stiffness = if (p == Presentation.EXPANDED) IslandMotion.CONTENT_IN_K else IslandMotion.CONTENT_OUT_K
        compactA.target = if (p == Presentation.COMPACT) 1f else 0f
        compactA.stiffness = if (p == Presentation.COMPACT) IslandMotion.CONTENT_IN_K else IslandMotion.CONTENT_OUT_K
        idleA.target = 0f
        idleA.stiffness = if (p == Presentation.IDLE) IslandMotion.CONTENT_IN_K else IslandMotion.CONTENT_OUT_K
    }

    /** Screen went off: collapse into an invisible dot so the next wake can grow out of it. */
    fun hideForIntro() {
        // The window's GPU resources may be dropped while the screen is off; re-record on wake.
        liquidGlass.invalidateContent()
        Choreographer.getInstance().removeFrameCallback(this)
        running = false; delayedFrame = false; lastFrame = 0L
        tracking = false; seeking = false
        removeCallbacks(longPress)
        pressS.snap(1f); stretchS.snap(0f); absorbS.snap(0f)
        morphCard = null; softMorphCard = null
        removeCallbacks(introWiden)
        introHoldUntil = 0L
        introS.snap(0f)
        presenceS.snap(0f)
        secondaryA.snap(0f)
        dropS.snap(0f)
        widthS.snap(dp(10f)); heightS.snap(dp(10f)); radiusS.snap(dp(5f))
        centerS.snap(cameraCenter())
        topS.snap(baseTop + dp(12f))
        compactA.snap(0f); expandedA.snap(0f); idleA.snap(0f)
        sheenStart = 0L
        invalidate()
    }

    /**
     * Wake / unlock animation, in two stages:
     *  1. a dot appears in the camera hole and pops into a circle,
     *  2. the circle stretches sideways into the pill with a soft overshoot, content fades in
     *     and a sheen sweeps across.
     */
    fun playIntro() {
        if (isAbsorbing) return
        if (activity == null) {
            hideForIntro()
            introS.snap(1f)
            val resting = specFor(Presentation.IDLE, null)
            widthS.snap(resting.w); heightS.snap(resting.h); radiusS.snap(resting.r)
            centerS.snap(cameraCenter()); topS.snap(targetTop(Presentation.IDLE))
            if (features.idlePill) presenceS.snap(1f)
            callbacks?.onSettled()
            return
        }
        hideForIntro()
        presenceS.target = 1f
        val spec = specFor(presentation, activity)
        val d = min(spec.h, dp(34f))
        shapeSprings.forEach { it.stiffness = 260f; it.dampingRatio = 0.55f }
        widthS.target = d; heightS.target = d; radiusS.target = d / 2f
        centerS.target = cameraCenter()
        topS.target = baseTop
        introS.target = 1f
        introHoldUntil = SystemClock.uptimeMillis() + INTRO_HOLD_MS
        postDelayed(introWiden, INTRO_HOLD_MS)
        settled = false
        kick()
    }

    private fun landscape() = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    private fun seeThrough() = features.landscapeSeeThrough && landscape()

    /** True when the island sits on a real camera hole we know the position of. */
    private fun onCamera() = anchorToCutout && fit.alignCamera && cameraX != null && !landscape()

    private val fadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var fadeShader: RadialGradient? = null
    private var fadeShaderR = 0f

    /**
     * Content, cover art and dots darken smoothly into black around the camera hole, so nothing
     * ever sits half-hidden behind it and the hole reads as part of the island.
     */
    private fun drawCameraFade(canvas: Canvas, bodyAlpha: Int) {
        if (!features.cameraFade || !onCamera() || bodyAlpha < 4) return
        val r = restingDiameter() / 2f
        val cx = cameraCenter()
        val cy = restingTop() + r
        val outer = r * 2.6f
        if (!(outer > 1f)) return
        if (cx + outer < rect.left || cx - outer > rect.right || cy + outer < rect.top || cy - outer > rect.bottom) return
        var shader = fadeShader
        if (shader == null || fadeShaderR != outer) {
            shader = RadialGradient(0f, 0f, outer,
                intArrayOf(Color.BLACK, Color.BLACK, Color.argb(150, 0, 0, 0), Color.TRANSPARENT),
                floatArrayOf(0f, 0.46f, 0.7f, 1f), Shader.TileMode.CLAMP)
            fadeShader = shader; fadeShaderR = outer
        }
        matrix.reset(); matrix.setTranslate(cx, cy); shader.setLocalMatrix(matrix)
        fadePaint.shader = shader
        fadePaint.alpha = bodyAlpha
        canvas.drawCircle(cx, cy, outer, fadePaint)
        fadePaint.shader = null
    }

    /** How far right content left of the camera may reach, and where content right of it may start. */
    private fun cameraKeepOut(): Pair<Float, Float>? {
        if (!onCamera()) return null
        val r = restingDiameter() / 2f + dp(5f)
        val cx = cameraCenter()
        return (cx - r) to (cx + r)
    }

    private val body = Path()
    private val corner = RectF()

    /**
     * The island's outline: a rounded rectangle whose top and bottom edges bow by [bendY] and
     * whose sides bow by [bendX] (positive = outward). Plain rounded rect when both are tiny.
     */
    private fun buildBody(path: Path, box: RectF, radius: Float, bendY: Float, bendX: Float) {
        path.reset()
        if (!(box.width() > 0f && box.height() > 0f)) return
        val rad = min(radius, min(box.width(), box.height()) / 2f).coerceAtLeast(0f)
        if (abs(bendY) < 0.3f && abs(bendX) < 0.3f) { path.addRoundRect(box, rad, rad, Path.Direction.CW); return }
        val l = box.left; val t = box.top; val rr = box.right; val b = box.bottom
        val cx = box.centerX(); val cy = box.centerY()
        val d = rad * 2f
        // A quadratic's control point sits twice as far out as the curve's peak.
        val by = bendY.coerceIn(-box.height() * 0.2f, box.height() * 0.2f) * 2f
        val bx = bendX.coerceIn(-box.width() * 0.1f, box.width() * 0.1f) * 2f
        path.moveTo(l + rad, t)
        path.quadTo(cx, t - by, rr - rad, t)
        corner.set(rr - d, t, rr, t + d); path.arcTo(corner, -90f, 90f, false)
        path.quadTo(rr + bx, cy, rr, b - rad)
        corner.set(rr - d, b - d, rr, b); path.arcTo(corner, 0f, 90f, false)
        path.quadTo(cx, b + by, l + rad, b)
        corner.set(l, b - d, l + d, b); path.arcTo(corner, 90f, 90f, false)
        path.quadTo(l - bx, cy, l, t + rad)
        corner.set(l, t, l + d, t + d); path.arcTo(corner, 180f, 90f, false)
        path.close()
    }

    /** 0 while the shape is under ~70% of where it's heading, easing to 1 by ~92%. */
    private fun roomFor(now: Float, target: Float): Float {
        if (target <= 1f) return 1f
        val k = ((now / target - 0.7f) / 0.22f).coerceIn(0f, 1f)
        return k * k * (3f - 2f * k)
    }

    private fun playReturn(spec: Spec, p: Presentation) {
        removeCallbacks(introWiden)
        absorbS.snap(0f); morphCard = null; softMorphCard = null
        alpha = 1f
        concealS.snap(1f) // Home is visible; avoid a full-screen alpha layer left over from fullscreen media.
        introS.snap(1f); presenceS.snap(1f)
        compactA.snap(0f); expandedA.snap(0f); idleA.snap(0f)
        secondaryA.snap(0f); dropS.snap(0f)
        val d = min(spec.h, dp(34f))
        // Start from a small dot in the camera hole, already on screen (never a flash of black).
        widthS.snap(d * 0.45f); heightS.snap(d * 0.45f); radiusS.snap(d * 0.225f)
        centerS.snap(cameraCenter()); topS.snap(baseTop + d * 0.275f)
        shapeSprings.forEach { it.stiffness = 320f; it.dampingRatio = 0.6f }
        widthS.target = d; heightS.target = d; radiusS.target = d / 2f
        topS.target = baseTop
        introHoldUntil = SystemClock.uptimeMillis() + RETURN_HOLD_MS
        postDelayed(introWiden, RETURN_HOLD_MS)
        settled = false
        kick()
    }

    private fun widenIntro() {
        introHoldUntil = 0L
        val spec = specFor(presentation, activity)
        widthS.stiffness = 150f; widthS.dampingRatio = 0.6f
        heightS.stiffness = 170f; heightS.dampingRatio = 0.72f
        radiusS.stiffness = 170f; radiusS.dampingRatio = 0.85f
        centerS.stiffness = IslandMotion.SHAPE_K; centerS.dampingRatio = IslandMotion.CENTER_DAMPING_RATIO
        widthS.target = spec.w; heightS.target = spec.h; radiusS.target = spec.r
        centerS.target = spec.cx
        topS.target = targetTop(presentation)
        setContentTargets(presentation)
        secondaryA.target = if (secondary != null && presentation == Presentation.COMPACT) 1f else 0f
        dropS.target = secondaryA.target
        if (secondary != null) bubbleCenterS.target = compactCluster().bubbleCenter
        sheenStart = SystemClock.uptimeMillis() + 300L
        settled = false
        kick()
    }

    private fun restoreShapeSprings() {
        shapeSprings.forEach { it.stiffness = IslandMotion.SHAPE_K }
        widthS.dampingRatio = IslandMotion.FLUID_WIDTH_DAMPING
        heightS.dampingRatio = IslandMotion.FLUID_HEIGHT_DAMPING
        radiusS.dampingRatio = IslandMotion.FLUID_RADIUS_DAMPING
        centerS.dampingRatio = IslandMotion.FLUID_CENTER_DAMPING
        topS.dampingRatio = 0.86f
    }

    /** Pixel size the overlay window needs for the current target state. */
    fun windowSizeFor(next: LiveActivity?, p: Presentation): Pair<Int, Int> {
        val effective = if (next == null) Presentation.IDLE else p
        val spec = specFor(effective, next)
        // A fixed horizontal origin avoids one-frame jumps when WindowManager relayouts.
        // Keep the bottom slack tiny: the window eats touches wherever it covers, so a tall
        // idle window would deaden a strip of the screen under the island.
        return Pair(resources.displayMetrics.widthPixels, ((baseTop + spec.h + dp(14f)) * fit.scale).toInt())
    }

    val isAbsorbing: Boolean get() = absorbS.value > 0.002f

    // ---------------------------------------------------------------- layout specs

    private data class Spec(val w: Float, val h: Float, val r: Float, val cx: Float)

    /** Idle bar is edge-to-edge; expanded cards keep a slightly larger inset. */
    private fun idleWidth() = IslandMotion.idleWidth(resources.displayMetrics.widthPixels.toFloat(), density) / fit.scale

    private fun layoutWidth() = (if (width > 0) width else resources.displayMetrics.widthPixels).toFloat()
    private fun expandedWidth() = (layoutWidth() - dp(16f)) / fit.scale

    private fun screenCenter() = layoutWidth() / 2f / fit.scale

    /**
     * Opened islands span the screen, edge to edge with a small margin, so they never look
     * lopsided when the small pill has been nudged to sit on an off-centre camera.
     */
    private fun openWidth(small: Float) = if (features.fullWidthOpen) expandedWidth() else min(expandedWidth(), small)
    private fun openCenter() = if (features.fullWidthOpen) screenCenter() else cameraCenter()

    private fun cameraCenter(): Float {
        val viewX = if (anchorToCutout && fit.alignCamera) cameraX ?: (resources.displayMetrics.widthPixels / 2f)
        else layoutWidth() / 2f
        return (viewX + dp(fit.offsetX)) / fit.scale
    }

    private fun compactCluster() = IslandCluster.layout(layoutWidth() / fit.scale,
        dp(when {
            features.shortCompact && secondary == null -> SHORT_COMPACT_WIDTH_DP
            features.shortCompact -> SHORT_SPLIT_WIDTH_DP
            secondary == null -> IslandMotion.COMPACT_WIDTH_DP
            else -> IslandMotion.SPLIT_WIDTH_DP
        }) * fit.pillWidth,
        cameraCenter(), secondary != null, bubbleDiameter(), dp(8f), dp(4f))

    /** Full-width cards follow the phone's own screen corners, inset by the card margin. */
    private fun cardRadius(fallback: Float): Float {
        if (!features.phoneCorners || screenCorner <= 0f) return fallback
        return max(dp(22f), screenCorner / fit.scale - dp(8f))
    }

    private fun hasMessageButtons(a: LiveActivity) = visibleActions(a).isNotEmpty() || a.reply != null

    private fun visibleActions(a: LiveActivity) = a.actions.take(3)

    private fun specFor(p: Presentation, a: LiveActivity?): Spec {
        if (p == Presentation.IDLE || a == null) {
            if (features.idlePill) {
                // A small resting pill around the camera, the same height as the live one.
                val h = compactHeight()
                return Spec(max(h * 2.4f, dp(IDLE_PILL_WIDTH_DP) * fit.pillWidth), h, h / 2f, cameraCenter())
            }
            val d = restingDiameter()
            return Spec(d, d, d / 2f, cameraCenter())
        }
        if (p == Presentation.COMPACT) {
            // A new message first arrives as a medium island; holding it grows the full card.
            if (a.mode == IslandMode.MESSAGE) {
                val h = dp(MEDIUM_HEIGHT_DP)
                return Spec(openWidth(dp(MEDIUM_WIDTH_DP)), h, h / 2f, openCenter())
            }
            val h = compactHeight()
            val cluster = compactCluster()
            return Spec(cluster.width, h, h / 2f, cluster.center)
        }
        val w = expandedWidth()
        return when (a.mode) {
            IslandMode.MUSIC -> Spec(w, dp(178f), cardRadius(dp(46f)), screenCenter())
            IslandMode.CALL -> Spec(w, dp(88f), cardRadius(dp(44f)), screenCenter())
            IslandMode.MESSAGE -> Spec(w, dp(if (hasMessageButtons(a)) 134f else 84f), cardRadius(dp(40f)), screenCenter())
            IslandMode.CHARGING -> Spec(w, dp(64f), cardRadius(dp(32f)), screenCenter())
            IslandMode.TIMER -> Spec(w, dp(if (visibleActions(a).isEmpty()) 88f else 138f), cardRadius(dp(44f)), screenCenter())
            IslandMode.ALARM -> Spec(w, dp(88f), cardRadius(dp(44f)), screenCenter())
            IslandMode.DOWNLOAD -> Spec(w, dp(100f), cardRadius(dp(40f)), screenCenter())
            IslandMode.VOLUME -> {
                val h = dp(VOLUME_HEIGHT_DP) + if (a.ringer != null) dp(RINGER_ROW_DP) else 0f
                Spec(openWidth(dp(VOLUME_WIDTH_DP)), h, min(h / 2f, dp(VOLUME_HEIGHT_DP) / 2f), openCenter())
            }
            IslandMode.DEVICE -> Spec(openWidth(dp(MEDIUM_WIDTH_DP)), dp(MEDIUM_HEIGHT_DP), dp(MEDIUM_HEIGHT_DP) / 2f, openCenter())
            IslandMode.RINGER -> {
                // Just wide enough for the glyph and its word, like a small switch label.
                val h = dp(RINGER_HEIGHT_DP)
                val want = h + dp(14f) + titlePaint.measureText(a.title) + dp(22f)
                if (features.fullWidthOpen) Spec(w, h, h / 2f, screenCenter())
                else Spec(want.coerceIn(dp(136f), w), h, h / 2f, cameraCenter())
            }
            else -> Spec(w, dp(84f), cardRadius(dp(42f)), screenCenter())
        }
    }

    // ---------------------------------------------------------------- frame loop

    private fun kick() {
        if (delayedFrame) {
            Choreographer.getInstance().removeFrameCallback(this)
            delayedFrame = false
            running = false
        }
        if (running) return
        running = true
        lastFrame = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        try { frame(frameTimeNanos) } catch (t: Throwable) {
            // Never let one bad frame stop the island: record it, settle, and keep going.
            CrashLog.caught(context, "frame", t)
            springs.forEach { if (!it.value.isFinite()) it.snap(if (it.target.isFinite()) it.target else 0f) }
            running = false; lastFrame = 0L; delayedFrame = false
            postDelayed({ kick() }, 32L)
        }
    }

    private fun frame(frameTimeNanos: Long) {
        delayedFrame = false
        if (!isAttachedToWindow || !isShown) { running = false; lastFrame = 0L; return }
        val dt = if (lastFrame == 0L) 1f / 60f else (frameTimeNanos - lastFrame) / 1_000_000_000f
        lastFrame = frameTimeNanos
        var rest = true
        for (s in springs) {
            if (reduceMotion) s.snap(s.target) else s.step(dt)
            if (!s.isAtRest) rest = false
        }
        invalidate()
        if (rest && !settled && !tracking) {
            settled = true
            if (activity == null) departingActivity = null
            if (!isAbsorbing) { morphCard = null; softMorphCard = null }
            restoreShapeSprings()
            callbacks?.onSettled()
        }
        val continuous = continuousDelay()
        when {
            !rest || tracking || sheenActive() -> Choreographer.getInstance().postFrameCallback(this)
            continuous != null -> {
                lastFrame = 0L; delayedFrame = true
                Choreographer.getInstance().postFrameCallbackDelayed(this, continuous)
            }
            else -> running = false
        }
    }

    private fun sheenActive(): Boolean {
        if (sheenStart == 0L) return false
        val t = SystemClock.uptimeMillis() - sheenStart
        if (t > SHEEN_MS) { sheenStart = 0L; return false }
        return true
    }

    /** Waveforms and clocks keep redrawing even when the shape is at rest. */
    private fun continuousDelay(): Long? {
        if (SystemClock.uptimeMillis() < rollingUntil) return 16L
        val a = activity ?: return null
        if (presentation == Presentation.IDLE) return null
        if (isConcealed || introS.value < 0.01f || !isShown) return null
        val other = secondary
        if (other != null && secondaryA.value > 0.01f &&
            (other.mode == IslandMode.MUSIC && other.isPlaying ||
                other.mode == IslandMode.TIMER && other.pausedTimerMs == null && (other.clockMs() ?: 0L) > 0L)) return 33L
        // Scheduling runs before onDraw updates rollAt. Include a pending text change so the
        // last 0:01 → 0:00 roll (and paused updates) gets frames all the way to completion.
        if (isLiveTimer(a) && (a.chronometerText() != rollText ||
                rollAt != 0L && SystemClock.uptimeMillis() - rollAt < ROLL_MS)) return 16L
        if (a.mode == IslandMode.RINGER && SystemClock.uptimeMillis() - ringerAnimAt < RINGER_ANIM_MS + 50L) return 16L
        return when {
            // The small island's waveform reads fine at 30 fps; the open card's progress bar gets 60.
            a.mode == IslandMode.MUSIC && a.isPlaying -> if (presentation == Presentation.EXPANDED) 16L else 33L
            // A tap's ripple (or a burst between songs) finishes even while paused.
            usesDots(a) && (cardDots.beatGlow > 0.01f || cardDots.rippling) -> 16L
            a.mode == IslandMode.CALL -> 33L
            a.mode == IslandMode.ALARM -> 33L
            a.clockBaseElapsed != null && a.pausedTimerMs == null -> {
                val ms = a.clockMs() ?: return null
                if (a.chronometerCountDown && ms == 0L) return null
                // Keep the progress ring continuous; digits still change only at second boundaries.
                33L
            }
            else -> null
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        liquidGlass.invalidateContent()
        kick()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) { liquidGlass.invalidateContent(); kick() }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        // Screen-off and lock hide the overlay window; HWUI may free its display lists meanwhile.
        if (visibility == VISIBLE) { liquidGlass.invalidateContent(); invalidate() }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(introWiden); introHoldUntil = 0L
        Choreographer.getInstance().removeFrameCallback(this)
        running = false
        liquidGlass.release()
        morphCard = null; softMorphCard = null
        super.onDetachedFromWindow()
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        if (anchorToCutout) {
            getLocationOnScreen(windowLocation)
            windowOriginY = windowLocation[1].toFloat()
            val fallbackId = resources.getIdentifier("status_bar_height", "dimen", "android")
            val fallback = if (fallbackId != 0) resources.getDimensionPixelSize(fallbackId).toFloat() else dp(24f)
            statusBarHeight = if (Build.VERSION.SDK_INT >= 30)
                max(fallback, insets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top.toFloat())
                else max(fallback, insets.systemWindowInsetTop.toFloat())
            cameraX = null; cameraTop = null; cutoutCenterY = null; cutoutDiameter = null; unsafeCutout = false
            if (Build.VERSION.SDK_INT >= 28) {
                // Ignore side and bottom cutouts after rotation. Rects are relative to this window.
                val cut = insets.displayCutout?.boundingRects?.filter { it.top <= dp(80f) && it.height() <= dp(80f) && it.width() > 0 && it.height() > 0 }
                    ?.minByOrNull { abs(it.exactCenterX() - resources.displayMetrics.widthPixels / 2f) }
                if (cut != null) {
                    unsafeCutout = cut.width() > dp(96f) ||
                        abs(cut.exactCenterX() - resources.displayMetrics.widthPixels / 2f) > resources.displayMetrics.widthPixels * .20f
                    cutoutCenterY = cut.exactCenterY()
                    cutoutDiameter = min(cut.width(), cut.height()).toFloat().coerceIn(dp(8f), dp(40f))
                    cameraTop = max(dp(4f), cut.exactCenterY() - dp(18f))
                    cameraX = cut.exactCenterX()
                    statusBarHeight = max(statusBarHeight, cut.bottom + windowOriginY)
                }
            }
            if (Build.VERSION.SDK_INT >= 31) {
                screenCorner = listOfNotNull(insets.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT),
                    insets.getRoundedCorner(RoundedCorner.POSITION_TOP_RIGHT)).maxOfOrNull { it.radius }?.toFloat() ?: 0f
            }
            val previousTop = baseTop
            updateAnchor()
            // Reserve space before moving downward; otherwise a short idle window clips on OEMs.
            if (abs(previousTop - baseTop) > .5f) callbacks?.onAnchorChanged()
            settled = false; kick()
        }
        // Videos, photos and games hide the system status bar; overlays see that in their insets.
        if (anchorToCutout && Build.VERSION.SDK_INT >= 30) {
            // Some windows (accessibility overlays, no-limits overlays) never see the status bar
            // at all. Only trust "hidden" after this window has seen it visible, otherwise the
            // island would stay tucked away forever.
            val visible = insets.isVisible(WindowInsets.Type.statusBars())
            if (visible) sawStatusBar = true
            reportFullscreen(sawStatusBar && !visible)
        }
        return super.onApplyWindowInsets(insets)
    }

    // ---------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        val saves = canvas.saveCount
        try { drawIsland(canvas) } catch (t: Throwable) {
            // A drawing error loses one frame, not the island.
            canvas.restoreToCount(saves)
            CrashLog.caught(context, "draw ${activity?.mode} ${presentation}", t)
        }
    }

    private fun drawIsland(canvas: Canvas) {
        // Fully hidden (fullscreen video/photos): draw nothing at all.
        val conceal = concealS.value.coerceIn(0f, 1f)
        if (conceal < 0.01f) return
        val layered = conceal < 0.995f
        if (layered) {
            canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), (conceal * 255).toInt())
            // Hiding for a video or photo and coming back: the island sinks into the camera and
            // rises out of it, rather than only fading.
            if (!reduceMotion) {
                val k = 0.8f + 0.2f * conceal
                canvas.scale(k, k, cameraCenter() * fit.scale, rect.top * fit.scale)
            }
        }
        // Lock screen: the whole island renders in grey until unlock.
        val dimmed = lockDim
        if (dimmed) canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
        canvas.save()
        canvas.scale(fit.scale, fit.scale)
        val a = activity ?: departingActivity
        // Center is a spring, same as width/height, so idle (full-width) → compact (camera)
        // does not jump. Clamp so a wide pill cannot sit off-screen.
        val cx = IslandFit.center(
            width.toFloat() / fit.scale,
            widthS.value,
            centerS.value,
            dp(4f),
        )
        val intro = introS.value.coerceIn(0f, 1f)
        val press = pressS.value
        // Hiding shrinks the island back into the camera as it fades.
        val shrink = 0.35f + 0.65f * conceal
        var w = widthS.value * press * shrink
        var h = max(dp(1f), heightS.value + stretchS.value) * press * (0.6f + 0.4f * conceal)
        // Like a drop of liquid keeping its volume: while the island widens fast it thins a
        // little, while it grows taller it narrows, and it fills back out as it settles.
        val flowX = if (reduceMotion) 0f else (widthS.speed / dp(1500f)).coerceIn(-1f, 1f)
        val flowY = if (reduceMotion) 0f else (heightS.speed / dp(1500f)).coerceIn(-1f, 1f)
        h = max(dp(1f), h * (1f - 0.11f * flowX + 0.03f * flowY))
        w *= 1f - 0.05f * flowY + 0.015f * flowX
        val top = topS.value
        rect.set(cx - w / 2f, top, cx + w / 2f, top + h)
        var r = min(radiusS.value * press, h / 2f)
        val absorb = absorbS.value.coerceIn(0f, 1f)
        if (isAbsorbing) {
            val sceneHeight = (if (anchorToCutout) resources.displayMetrics.heightPixels else height).toFloat() / fit.scale
            appMorph.update(1f - absorb, width / fit.scale, sceneHeight, cx, top, w, h, density)
            rect.set(appMorph.left, appMorph.top, appMorph.left + appMorph.width, appMorph.top + appMorph.height)
            r = appMorph.radius
        }
        // Content waits for the shape to make room, so it never shows squashed or clipped while
        // the island grows (appearing, widening, opening) and leaves before it shrinks.
        val room = if (isAbsorbing) 1f else min(roomFor(rect.width(), widthS.target), roomFor(rect.height(), heightS.target))
        val reveal = (if (isAbsorbing) appMorph.reveal else 1f) * room

        val bodyAlpha = (255 * presenceS.value.coerceIn(0f, 1f) * min(1f, intro * 2.5f)).toInt()
        drawSecondary(canvas, intro * reveal)
        if (a != null) {
            val glowAlpha = if (features.glowOpenOnly) (bodyAlpha * expandedA.value.coerceIn(0f, 1f)).toInt() else bodyAlpha
            drawGlow(canvas, a, r, glowAlpha)
        }
        shapePaint.color = Color.BLACK
        // In landscape there's no camera to cover, so the body lets the game or video show through.
        shapePaint.alpha = if (seeThrough()) (bodyAlpha * LANDSCAPE_BODY).toInt() else bodyAlpha
        // The edges flow too: they pinch in while the island stretches and bow out as it
        // shrinks, so a morph reads as liquid rather than a rubber rectangle.
        buildBody(body, rect, r, -flowX * dp(2.4f), -flowY * dp(3f))
        canvas.drawPath(body, shapePaint)
        if (a == null) { canvas.restore(); if (dimmed) canvas.restore(); if (layered) canvas.restore(); return }
        drawOutline(canvas, a, r, bodyAlpha)

        clip.set(body)
        canvas.save()
        canvas.clipPath(clip)
        if (absorb < .06f) {
            wallpaperStage.set(0f, 0f, width / fit.scale, max(baseTop + dp(WALLPAPER_STAGE_DP), rect.bottom))
            liquidGlass.draw(canvas, wallpaperStage, rect, r, bodyAlpha, wallpaperSkin,
                resizing = shapeSprings.any { !it.isAtRest } || !pressS.isAtRest || !stretchS.isAtRest)
        }
        val backdrop = bodyAlpha * (1f - absorb) * (0.25f + 0.75f * room)
        if (usesDots(a)) drawDotField(canvas, a, backdrop) else drawArtBackdrop(canvas, backdrop)

        val ea = expandedA.value.coerceIn(0f, 1f) * reveal
        val ca = compactA.value.coerceIn(0f, 1f) * reveal
        val liveTimer = isLiveTimer(a)
        actionHits.clear()
        musicArtHit.setEmpty()
        modeHits.clear()
        if (ea > 0.01f) {
            // Nothing-inspired island stays monochrome; no coloured content glow.
            val alpha = (((ea - 0.45f) / 0.55f).coerceIn(0f, 1f) * 255 * intro).toInt()
            // Content rises and scales in slightly as the shape opens, like iOS.
            val lift = (1f - ea) * dp(7f)
            val sc = 0.97f + 0.03f * ea
            canvas.save()
            canvas.translate(0f, -lift)
            canvas.scale(sc, sc, rect.centerX(), rect.top)
            if (alpha > 2) when (a.mode) {
                // Secondary elements (progress, buttons) trail the header by a beat.
                IslandMode.MUSIC -> drawMusicExpanded(canvas, a, alpha, stagger(ea, 0.3f))
                IslandMode.MESSAGE -> drawMessageExpanded(canvas, a, alpha, stagger(ea, 0.22f))
                IslandMode.CALL -> drawCallExpanded(canvas, a, alpha)
                IslandMode.CHARGING -> drawChargingExpanded(canvas, a, alpha)
                IslandMode.TIMER -> if (liveTimer) drawTimerExpanded(canvas, a, alpha, stagger(ea, 0.22f)) else drawGenericExpanded(canvas, a, alpha)
                IslandMode.VOLUME -> drawVolumeExpanded(canvas, a, alpha)
                IslandMode.DEVICE -> drawDeviceExpanded(canvas, a, alpha)
                IslandMode.RINGER -> drawRingerExpanded(canvas, a, alpha)
                IslandMode.DOWNLOAD -> drawDownloadExpanded(canvas, a, alpha, stagger(ea, 0.22f))
                IslandMode.ALARM -> drawAlarmExpanded(canvas, a, alpha)
                else -> drawGenericExpanded(canvas, a, alpha)
            }
            canvas.restore()
        } else {
            barY = -1000f
        }
        if (ca > 0.01f && !liveTimer) {
            val alpha = (((ca - 0.42f) / 0.58f).coerceIn(0f, 1f) * 255).toInt()
            if (alpha > 2) {
                // Compact glyphs slide in from the centre as the pill widens.
                canvas.save()
                canvas.scale(1f - (1f - ca) * 0.08f, 1f, rect.centerX(), rect.centerY())
                burnInShift(canvas)
                if (a.mode == IslandMode.MESSAGE) drawMessageMedium(canvas, a, (alpha * intro).toInt())
                else drawCompact(canvas, a, (alpha * intro).toInt())
                canvas.restore()
            }
        }
        // The timer's ring and digits are shared between compact and expanded and morph between them.
        if (liveTimer) drawTimerMorph(canvas, a, ea, ca, intro)
        drawCameraFade(canvas, bodyAlpha)
        if (absorb > 0.01f) drawAbsorb(canvas, absorb)
        canvas.restore()
        canvas.restore()
        if (dimmed) canvas.restore()
        if (layered) canvas.restore()
    }

    /** Thin rim: always, or only for a few seconds when something new arrives. */
    private fun drawOutline(canvas: Canvas, a: LiveActivity, r: Float, alpha: Int) {
        val on = when (features.outline) {
            OutlineMode.OFF -> false
            OutlineMode.ALWAYS -> true
            OutlineMode.EVENTS -> a.isTransient || SystemClock.uptimeMillis() < eventOutlineUntil
        }
        if (!on || isAbsorbing) return
        outlinePaint.strokeWidth = dp(1f)
        outlinePaint.color = withAlpha(userAccent ?: lighten(NothingStyle.line), alpha * 150 / 255)
        val inset = outlinePaint.strokeWidth / 2f
        canvas.drawRoundRect(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset,
            max(0f, r - inset), max(0f, r - inset), outlinePaint)
    }

    /**
     * Burn-in care: static compact glyphs drift by up to a pixel or two each minute, so the same
     * pixels are not lit for hours. The island body itself is black and lights no pixels.
     */
    private fun burnInShift(canvas: Canvas) {
        if (!features.burnInCare || presentation != Presentation.COMPACT) return
        val minute = System.currentTimeMillis() / 60_000L
        val dx = ((minute % 3) - 1) * dp(1f)
        val dy = (((minute / 3) % 3) - 1) * dp(0.5f)
        canvas.translate(dx, dy)
        removeCallbacks(burnInTick)
        postDelayed(burnInTick, 60_000L - System.currentTimeMillis() % 60_000L + 50L)
    }

    private fun drawSecondary(canvas: Canvas, intro: Float) {
        secondaryRect.setEmpty()
        dropNeck.reset()
        val a = secondary ?: departingSecondary ?: return
        if (dropS.value < 0.01f && dropS.target == 0f) {
            if (secondary == null) departingSecondary = null
            return
        }
        val diameter = bubbleDiameter()
        dropGeometry.update(dropS.value, rect.right - diameter * .42f, bubbleCenterS.value,
            baseTop + compactHeight() / 2f, diameter, width / fit.scale, dp(4f), bubblePress.value)
        val center = dropGeometry.centerX
        val cy = dropGeometry.centerY
        secondaryRect.set(center - dropGeometry.radiusX, cy - dropGeometry.radiusY,
            center + dropGeometry.radiusX, cy + dropGeometry.radiusY)
        val surfaceAlpha = (255 * intro * presenceS.value.coerceIn(0f, 1f)).toInt()
        fillPaint.color = withAlpha(Color.BLACK, surfaceAlpha)
        drawDropNeck(canvas, surfaceAlpha)
        canvas.drawOval(secondaryRect, fillPaint)
        val alpha = (surfaceAlpha * dropGeometry.content).toInt()
        if (features.colouredBubbles && alpha > 2) drawBubbleRing(canvas, a, alpha)
        when (a.mode) {
            IslandMode.TIMER -> {
                val timerAlpha = if (a.pausedTimerMs != null) alpha / 2 else alpha
                val digits = a.chronometerText() ?: "–:–"
                val paint = secondaryTimerPaint
                paint.textSize = dp(10f)
                val textWidth = paint.measureText(digits)
                if (textWidth > dp(29f)) paint.textSize *= dp(29f) / textWidth
                rolling(canvas, "bubble", digits, center - paint.measureText(digits) / 2,
                    cy - (paint.ascent() + paint.descent()) / 2, paint, timerColor, timerAlpha, travel = .7f)
            }
            IslandMode.MUSIC -> if (usesDots(a)) {
                advanceDots(a)
                compactDots.drawMeter(canvas, fillPaint, center - dp(9f), cy, dp(18f), dp(14f), 3, 7,
                    userAccent ?: waveColor, if (a.isPlaying) 1f else 0f, alpha)
            } else drawWave(canvas, center - dp(9f), cy, dp(18f), dp(14f), userAccent ?: waveColor, a.isPlaying, alpha)
            IslandMode.CALL -> drawPhone(canvas, center, cy, dp(18f), alpha, false)
            IslandMode.ALARM -> drawAlarmGlyph(canvas, center, cy, dp(15f), withAlpha(ALARM, alpha))
            IslandMode.DOWNLOAD -> drawMiniProgress(canvas, center, cy, dp(10f), bubbleLevelS.value, alpha)
            else -> text(canvas, a.appLabel.take(1), center - dp(4f), cy + dp(5f), dp(15f), bodyPaint, Color.WHITE, alpha)
        }
    }

    /** Each bubble wears its activity's colour; a timer's ring shows the time left. */
    private fun drawBubbleRing(canvas: Canvas, a: LiveActivity, alpha: Int) {
        val stroke = dp(1.6f)
        arcRect.set(secondaryRect)
        arcRect.inset(stroke / 2f, stroke / 2f)
        ringPaint.strokeWidth = stroke
        if (a.mode == IslandMode.TIMER && a.clockMs() != null) {
            ringPaint.color = withAlpha(timerColor, alpha * 60 / 255)
            canvas.drawOval(arcRect, ringPaint)
            val fraction = timerFraction(a)
            ringPaint.color = withAlpha(timerColor, if (a.pausedTimerMs != null) alpha / 2 else alpha)
            if (fraction > 0.001f) canvas.drawArc(arcRect, -90f, 360f * fraction, false, ringPaint)
        } else {
            ringPaint.color = withAlpha(secondaryAccent ?: a.accent, alpha * 200 / 255)
            canvas.drawOval(arcRect, ringPaint)
        }
    }

    /** Two cubic edges pinch down before the droplet detaches. Draw behind both surfaces. */
    private fun drawDropNeck(canvas: Canvas, alpha: Int) {
        dropNeck.reset()
        val gap = secondaryRect.left - rect.right
        if (reduceMotion || dropGeometry.neck < .01f || gap < -dp(8f) || gap > dp(36f) || rect.height() > dp(64f)) return
        val half = min(rect.height() * .32f, dropGeometry.radiusY * .65f) * dropGeometry.neck
        val x1 = rect.right - dp(5f)
        val x2 = secondaryRect.left + dropGeometry.radiusX * .18f
        val y1 = rect.centerY()
        val y2 = dropGeometry.centerY
        val middle = (x1 + x2) / 2f
        dropNeck.moveTo(x1, y1 - half)
        dropNeck.cubicTo(middle, y1 - half * .12f, middle, y2 - half * .12f, x2, y2 - half)
        dropNeck.lineTo(x2, y2 + half)
        dropNeck.cubicTo(middle, y2 + half * .12f, middle, y1 + half * .12f, x1, y1 + half)
        dropNeck.close()
        fillPaint.color = withAlpha(Color.BLACK, alpha)
        canvas.drawPath(dropNeck, fillPaint)
    }

    /** Idle island as a status bar: time on the left, network and battery on the right. */
    private fun drawStatus(canvas: Canvas, alpha: Int, t: Float) {
        val s = status
        val cy = rect.centerY()
        // Glyphs drift in from the centre (where the camera is) as the pill widens.
        val inset = dp(13f) + (1f - t) * dp(10f)
        if (s.time.isNotEmpty()) {
            statusPaint.color = withAlpha(NothingStyle.foreground, alpha)
            canvas.drawText(s.time, rect.left + inset, cy + statusPaint.textSize * 0.36f, statusPaint)
        }
        drawStatusCluster(canvas, rect.right - inset, cy, alpha)
    }

    /**
     * Battery + network (and optionally the time), right-aligned at [rightEdge].
     * Shared by the idle pill and the compact music pill, which shortens its music
     * content to make room for this readout. Returns the left edge of what was drawn.
     */
    private fun drawStatusCluster(canvas: Canvas, rightEdge: Float, cy: Float, alpha: Int, withTime: Boolean = false): Float {
        val s = status
        // Battery: outline, cap, level fill.
        var right = rightEdge
        if (s.battery >= 0) {
            val bw = dp(21f); val bh = dp(10.5f)
            val l = right - bw - dp(2f)
            arcRect.set(l, cy - bh / 2f, l + bw, cy + bh / 2f)
            fillPaint.style = Paint.Style.STROKE
            fillPaint.strokeWidth = dp(1.2f)
            fillPaint.color = withAlpha(NothingStyle.muted, alpha)
            canvas.drawRoundRect(arcRect, dp(3f), dp(3f), fillPaint)
            fillPaint.style = Paint.Style.FILL
            canvas.drawRoundRect(l + bw + dp(0.6f), cy - dp(2.2f), l + bw + dp(2f), cy + dp(2.2f), dp(1f), dp(1f), fillPaint)
            val level = s.battery.coerceIn(0, 100) / 100f
            val color = when {
                s.charging -> accentSoft
                s.battery <= 15 -> NothingStyle.red
                else -> NothingStyle.foreground
            }
            fillPaint.color = withAlpha(color, alpha)
            val pad = dp(2.1f)
            canvas.drawRoundRect(l + pad, cy - bh / 2f + pad, l + pad + (bw - pad * 2) * max(0.08f, level), cy + bh / 2f - pad, dp(1.5f), dp(1.5f), fillPaint)
            if (s.charging) drawBolt(canvas, l + bw / 2f, cy, dp(8f), withAlpha(baseColor, alpha))
            right = l - dp(7f)
        }
        // Network: wifi fan or four signal bars; a dim "x" style empty bars when offline.
        if (s.wifi) {
            ringPaint.strokeWidth = dp(1.6f)
            val ox = right - dp(7f); val oy = cy + dp(4.5f)
            for (i in 1..3) {
                val rr = dp(3.2f) * i
                val lit = s.bars >= i + 1 || (i == 1 && s.bars >= 0)
                ringPaint.color = withAlpha(if (lit) NothingStyle.foreground else NothingStyle.line, alpha)
                arcRect.set(ox - rr, oy - rr, ox + rr, oy + rr)
                canvas.drawArc(arcRect, 225f, 90f, false, ringPaint)
            }
            fillPaint.color = withAlpha(NothingStyle.foreground, alpha)
            canvas.drawCircle(ox, oy - dp(0.4f), dp(1.3f), fillPaint)
            right -= dp(24f)
        } else {
            val bw = dp(2.6f); val gap = dp(1.5f)
            for (i in 0 until 4) {
                val l = right - (4 - i) * (bw + gap)
                val bh = dp(3.5f) + i * dp(2.2f)
                fillPaint.color = withAlpha(if (s.bars > i) NothingStyle.foreground else NothingStyle.line, alpha)
                canvas.drawRoundRect(l, cy + dp(5f) - bh, l + bw, cy + dp(5f), dp(1f), dp(1f), fillPaint)
            }
            right -= 4 * (bw + gap) + dp(7f)
        }
        if (withTime && s.time.isNotEmpty()) {
            statusPaint.color = withAlpha(NothingStyle.foreground, alpha)
            val w = statusPaint.measureText(s.time)
            canvas.drawText(s.time, right - w, cy + statusPaint.textSize * 0.36f, statusPaint)
            right -= w + dp(10f)
        }
        return right
    }

    /** Soft wash of the app's colour (WhatsApp green, Spotify green, YouTube red...). */
    private fun drawGlow(canvas: Canvas, a: LiveActivity, ea: Float) {
        val color = NothingStyle.foreground
        val radius = max(expandedWidth() * 0.75f, 1f)
        var shader = glowShader
        if (shader == null || glowColor != color || glowRadius != radius) {
            shader = RadialGradient(
                0f, 0f, radius,
                intArrayOf(withAlpha(color, 0x66), withAlpha(color, 0x14), Color.TRANSPARENT),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
            )
            glowShader = shader; glowColor = color; glowRadius = radius
        }
        matrix.reset()
        matrix.setTranslate(rect.left + dp(48f), rect.top + dp(44f))
        shader.setLocalMatrix(matrix)
        haloPaint.shader = shader
        haloPaint.alpha = (255 * ea).toInt()
        canvas.drawRect(rect, haloPaint)
        haloPaint.shader = null
        haloPaint.alpha = 255
    }

    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var sheenShader: LinearGradient? = null

    private fun drawSheen(canvas: Canvas, r: Float) {
        if (sheenStart == 0L) return
        val t = (SystemClock.uptimeMillis() - sheenStart).toFloat() / SHEEN_MS
        if (t < 0f || t > 1f) return
        val band = dp(60f)
        val shader = sheenShader ?: LinearGradient(
            0f, 0f, band, 0f,
            intArrayOf(Color.TRANSPARENT, Color.argb(46, 255, 255, 255), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP
        ).also { sheenShader = it }
        val eased = 1f - (1f - t) * (1f - t) * (1f - t)
        val x = rect.left - band + (rect.width() + band) * eased
        matrix.reset(); matrix.setTranslate(x, 0f)
        shader.setLocalMatrix(matrix)
        sheenPaint.shader = shader
        sheenPaint.alpha = (255 * sin(Math.PI * t).toFloat()).toInt()
        canvas.drawRoundRect(rect, r, r, sheenPaint)
    }

    private fun drawCompact(canvas: Canvas, a: LiveActivity, alpha: Int) {
        val cy = rect.centerY()
        val s = dp(22f)
        val l = rect.left + dp(7f)
        when (a.mode) {
            IslandMode.MUSIC -> if (usesDots(a)) {
                advanceDots(a)
                compactDots.drawRecord(canvas, fillPaint, l + s / 2, cy, s / 2 + dp(1.5f), a.isPlaying, alpha)
            } else drawArtwork(canvas, a, l, cy - s / 2, s, dp(6f), alpha)
            IslandMode.CALL -> {
                fillPaint.color = withAlpha(NothingStyle.muted, alpha)
                canvas.drawCircle(l + s / 2, cy, s / 2, fillPaint)
                drawPhone(canvas, l + s / 2, cy, dp(13f), alpha, false)
            }
            IslandMode.CHARGING -> drawBolt(canvas, l + s / 2, cy, dp(14f), withAlpha(accentSoft, alpha))
            IslandMode.ALARM -> drawAlarmGlyph(canvas, l + s / 2, cy, dp(16f), withAlpha(ALARM, alpha))
            else -> {
                val img = a.artwork ?: a.appIcon
                if (img != null) drawImage(canvas, img, l, cy - s / 2, s, s / 2, alpha)
                else drawLetterTile(canvas, a, l, cy - s / 2, s, s / 2, alpha)
            }
        }
        val right = rect.right - dp(12f)
        // Text on the right never runs in behind the camera (narrow "small island" pills too).
        val roomRight = cameraKeepOut()?.let { right - it.second }?.takeIf { it < rect.width() } ?: rect.width() / 2f - dp(24f)
        when (a.mode) {
            IslandMode.MUSIC -> {
                // Artwork and waveform flank the camera, matching the SwiftUI compact layout.
                if (usesDots(a)) compactDots.drawMeter(canvas, fillPaint, right - dp(22f), cy, dp(22f), dp(17f), 4, 7,
                    userAccent ?: waveColor, waveS.value, alpha)
                else drawWave(canvas, right - dp(20f), cy, dp(20f), dp(14f), userAccent ?: waveColor, waveS.value, alpha)
            }
            IslandMode.CALL -> {
                val t = a.chronometerText()
                when {
                    a.callState == CallState.CALLING ->
                        text(canvas, callingLabel(), right, cy + dp(4.5f), min(dp(90f), roomRight), smallPaint, NothingStyle.muted, pulse(alpha), alignRight = true)
                    t != null && smallPaint.measureText(t) <= roomRight ->
                        rolling(canvas, "call", t, right, cy + dp(4.5f), smallPaint, NothingStyle.muted, alpha, alignRight = true)
                    t != null -> drawWave(canvas, right - dp(20f), cy, dp(20f), dp(12f), NothingStyle.muted, true, alpha)
                    else -> drawWave(canvas, right - dp(20f), cy, dp(20f), dp(12f), NothingStyle.muted, true, alpha)
                }
            }
            IslandMode.DOWNLOAD -> {
                val fraction = levelS.value
                drawMiniProgress(canvas, right - dp(9f), cy, dp(8.5f), fraction, alpha)
                // The number only shows when it fits beside the camera; the ring alone says enough.
                val percent = "${IslandSignals.percent(a.progress ?: 0f)}%"
                if (smallPaint.measureText("100%") <= roomRight - dp(24f))
                    rolling(canvas, "download", percent, right - dp(24f), cy + dp(4.5f),
                        smallPaint, NothingStyle.foreground, alpha, alignRight = true)
            }
            IslandMode.ALARM -> text(canvas, a.title, right, cy + dp(4.5f), roomRight, smallPaint, ALARM, alpha, alignRight = true)
            else -> {
                val label = a.chronometerText() ?: a.title
                text(canvas, label, right, cy + dp(4.5f), roomRight, smallPaint, NothingStyle.foreground, alpha, alignRight = true)
            }
        }
    }

    private fun drawMusicExpanded(canvas: Canvas, a: LiveActivity, alpha: Int, late: Int) {
        val pad = dp(22f)
        val l = rect.left + pad
        val t = rect.top + pad
        val rr = rect.right - pad
        val art = dp(58f)
        if (usesDots(a)) {
            // The record spins inside its ring of bouncing dots.
            advanceDots(a)
            val acx = l + art / 2f
            val acy = t + art / 2f
            cardDots.drawRing(canvas, fillPaint, acx, acy, dp(23.5f), dp(2.6f), dp(1.05f), 3, userAccent ?: a.accent, a.isPlaying, alpha)
            cardDots.drawRecord(canvas, fillPaint, acx, acy, dp(21.5f), a.isPlaying, alpha)
        } else drawArtwork(canvas, a, l, t, art, dp(14f), alpha)
        musicArtHit.set(l - dp(6f), t - dp(6f), l + art + dp(6f), t + art + dp(6f))
        val tx = l + art + dp(14f)
        val waveW = dp(24f)
        if (usesDots(a)) cardDots.drawMeter(canvas, fillPaint, rr - waveW, t + dp(20f), waveW, dp(20f), 5, 7,
            userAccent ?: waveColor, waveS.value, alpha)
        else drawWave(canvas, rr - waveW, t + dp(20f), waveW, dp(18f), userAccent ?: waveColor, waveS.value, alpha)
        text(canvas, a.title, tx, t + dp(25f), rr - waveW - dp(12f) - tx, titlePaint, Color.WHITE, alpha)
        text(canvas, a.subtitle, tx, t + dp(46f), rr - tx, bodyPaint, GREY, alpha)

        // Progress (fades in a beat after the header, so the open doesn't pop all at once)
        barY = rect.top + dp(108f)
        val pos = if (seeking) (a.durationMs * seekFraction).toLong() else a.livePositionMs()
        val elapsed = formatDuration(pos)
        val remaining = "-" + formatDuration((a.durationMs - pos).coerceAtLeast(0L))
        barLeft = l + dp(44f)
        barRight = rr - dp(48f)
        if (showsMediaAction(IslandBus.MEDIA_SEEK)) {
            rolling(canvas, "elapsed", elapsed, l, barY + dp(4f), smallPaint, GREY, late, travel = .4f)
            rolling(canvas, "remaining", remaining, rr, barY + dp(4f), smallPaint, GREY, late, alignRight = true, travel = .4f)
            val fraction = if (a.durationMs > 0L) (pos.toFloat() / a.durationMs).coerceIn(0f, 1f) else 0f
            // Steady playback is followed exactly; jumps (seek, next track) glide instead of snapping.
            if (seeking || abs(musicBarS.value - fraction) < .004f || reduceMotion) musicBarS.snap(fraction)
            else { musicBarS.target = fraction; kick() }
            drawBar(canvas, barLeft, barRight, barY, musicBarS.value, userAccent ?: waveColor, late,
                if (seeking) dp(7f) else dp(4f))
        }

        // Controls
        val cy = rect.top + dp(146f)
        val cx = rect.centerX()
        if (showsMediaAction(IslandBus.MEDIA_PREVIOUS))
            drawSkip(canvas, cx - dp(72f), cy, false, late)
        if (showsMediaAction(IslandBus.MEDIA_TOGGLE))
            drawPlayPause(canvas, cx, cy, a.isPlaying, late)
        if (showsMediaAction(IslandBus.MEDIA_NEXT))
            drawSkip(canvas, cx + dp(72f), cy, true, late)
    }

    private fun drawMessageExpanded(canvas: Canvas, a: LiveActivity, alpha: Int, late: Int) {
        val pad = dp(20f)
        val l = rect.left + pad
        val t = rect.top + dp(20f)
        val rr = rect.right - pad
        val av = dp(44f)
        drawAvatar(canvas, a, l, t, av, alpha)
        val tx = l + av + dp(12f)
        text(canvas, context.getString(R.string.island_now), rr, t + dp(11f), dp(40f), smallPaint, GREY, alpha, alignRight = true)
        text(canvas, a.appLabel, tx, t + dp(11f), rr - dp(40f) - tx, smallPaint, NothingStyle.foreground, alpha)
        text(canvas, a.title, tx, t + dp(30f), rr - tx, titlePaint, Color.WHITE, alpha)
        text(canvas, a.subtitle, tx, t + dp(48f), rr - tx, bodyPaint, GREY, alpha)
        drawActions(canvas, a, l, rect.top + dp(84f), rr, late)
    }

    /** Medium island: who and what, on one line each. Hold to open the full card with Reply. */
    private fun drawMessageMedium(canvas: Canvas, a: LiveActivity, alpha: Int) {
        val av = rect.height() - dp(20f)
        val l = rect.left + dp(10f)
        val cy = rect.centerY()
        drawAvatar(canvas, a, l, cy - av / 2, av, alpha)
        val tx = l + av + dp(10f)
        val rr = rect.right - dp(18f)
        // The short app name goes on top, where the camera is, and stops before it; the message
        // itself (who, then what) runs along the bottom line, clear of the camera.
        val keep = cameraKeepOut()
        val topRoom = if (keep != null && tx < keep.first) keep.first - tx else rr - tx
        text(canvas, a.appLabel.ifBlank { a.title }, tx, cy - dp(5f), topRoom, smallPaint, GREY, alpha)
        val who = if (a.appLabel.isBlank()) "" else a.title
        val what = a.subtitle
        val y = cy + dp(14f)
        when {
            who.isNotBlank() && what.isNotBlank() -> {
                val whoW = min(titlePaint.measureText(who), (rr - tx) * 0.45f)
                text(canvas, who, tx, y, whoW, titlePaint, Color.WHITE, alpha)
                val gap = dp(6f)
                text(canvas, what, tx + whoW + gap, y, rr - tx - whoW - gap, bodyPaint, GREY, alpha)
            }
            who.isNotBlank() -> text(canvas, who, tx, y, rr - tx, titlePaint, Color.WHITE, alpha)
            else -> text(canvas, what, tx, y, rr - tx, bodyPaint, Color.WHITE, alpha)
        }
    }

    private fun drawCallExpanded(canvas: Canvas, a: LiveActivity, alpha: Int) {
        val pad = dp(20f)
        val l = rect.left + pad
        val rr = rect.right - pad
        val av = dp(48f)
        val cy = rect.centerY()
        drawAvatar(canvas, a, l, cy - av / 2, av, alpha)
        val decline = findAction(a, "decline", "reject", "hang up", "end", "dismiss")
        val accept = findAction(a, "answer", "accept")
        val btn = dp(44f)
        var buttonsLeft = rr
        if (decline != null) {
            val x = buttonsLeft - btn
            drawCallButton(canvas, x, cy - btn / 2, btn, NothingStyle.red, true, alpha)
            actionHits.add(RectF(x, cy - btn / 2, x + btn, cy + btn / 2) to decline)
            buttonsLeft = x - dp(12f)
        }
        if (accept != null) {
            val x = buttonsLeft - btn
            drawCallButton(canvas, x, cy - btn / 2, btn, accentSoft, false, alpha)
            actionHits.add(RectF(x, cy - btn / 2, x + btn, cy + btn / 2) to accept)
            buttonsLeft = x - dp(12f)
        }
        val tx = l + av + dp(12f)
        val clock = a.chronometerText()
        if (a.callState != CallState.CALLING && clock != null) {
            // App name, then a timer whose digits roll as the seconds tick.
            val prefix = "${a.appLabel} · "
            val prefixWidth = min(smallPaint.measureText(prefix), (buttonsLeft - tx) * .6f)
            text(canvas, prefix, tx, cy - dp(6f), prefixWidth, smallPaint, NothingStyle.muted, alpha)
            rolling(canvas, "call-x", clock, tx + prefixWidth, cy - dp(6f), smallPaint, NothingStyle.foreground, alpha)
        } else {
            val sub = if (a.callState == CallState.CALLING) "${a.appLabel} · ${callingLabel()}" else a.subtitle.ifBlank { a.appLabel }
            val subAlpha = if (a.callState == CallState.CALLING) pulse(alpha) else alpha
            text(canvas, sub, tx, cy - dp(6f), buttonsLeft - tx, smallPaint, NothingStyle.foreground, subAlpha)
        }
        text(canvas, a.title, tx, cy + dp(14f), buttonsLeft - tx, titlePaint, Color.WHITE, alpha)
    }

    private fun drawGenericExpanded(canvas: Canvas, a: LiveActivity, alpha: Int) {
        val pad = dp(20f)
        val l = rect.left + pad
        val rr = rect.right - pad
        val s = dp(44f)
        val cy = rect.centerY()
        val img = a.artwork ?: a.appIcon
        if (img != null) drawImage(canvas, img, l, cy - s / 2, s, dp(12f), alpha)
        else drawLetterTile(canvas, a, l, cy - s / 2, s, dp(12f), alpha)
        val tx = l + s + dp(14f)
        val clock = a.chronometerText()
        if (clock != null) {
            text(canvas, clock, rr, cy + dp(10f), dp(140f), bigPaint, NothingStyle.foreground, alpha, alignRight = true)
            text(canvas, a.title, tx, cy - dp(3f), rr - dp(120f) - tx, titlePaint, Color.WHITE, alpha)
            text(canvas, a.appLabel, tx, cy + dp(16f), rr - dp(120f) - tx, bodyPaint, GREY, alpha)
        } else {
            text(canvas, a.title, tx, cy - dp(3f), rr - tx, titlePaint, Color.WHITE, alpha)
            text(canvas, a.subtitle.ifBlank { a.appLabel }, tx, cy + dp(16f), rr - tx, bodyPaint, GREY, alpha)
        }
    }

    private fun drawChargingExpanded(canvas: Canvas, a: LiveActivity, alpha: Int) {
        val cy = rect.centerY()
        val l = rect.left + dp(22f)
        val rr = rect.right - dp(22f)
        drawBolt(canvas, l + dp(9f), cy, dp(18f), withAlpha(accentSoft, alpha))
        text(canvas, context.getString(R.string.island_charging), l + dp(26f), cy + dp(6f), dp(140f), titlePaint, Color.WHITE, alpha)
        // Battery pill
        val bw = dp(34f)
        val bh = dp(16f)
        val bl = rr - bw
        fillPaint.color = withAlpha(Color.WHITE, alpha * 60 / 255)
        canvas.drawRoundRect(bl, cy - bh / 2, rr, cy + bh / 2, dp(5f), dp(5f), fillPaint)
        val level = a.title.filter { it.isDigit() }.toIntOrNull()?.coerceIn(0, 100) ?: 50
        fillPaint.color = withAlpha(NothingStyle.muted, alpha)
        canvas.drawRoundRect(bl + dp(2f), cy - bh / 2 + dp(2f), bl + dp(2f) + (bw - dp(4f)) * level / 100f, cy + bh / 2 - dp(2f), dp(3.5f), dp(3.5f), fillPaint)
        rolling(canvas, "charging", a.title, bl - dp(10f), cy + dp(6f), titlePaint, NothingStyle.muted, alpha, alignRight = true)
    }

    // ---------------------------------------------------------------- volume, devices, downloads, alarms

    private fun callingLabel() = context.getString(R.string.island_calling)

    /** Slow breathing alpha for states that are waiting on something (Calling…). */
    private fun pulse(alpha: Int): Int {
        val t = (SystemClock.uptimeMillis() % 1600L) / 1600f
        return (alpha * (0.55f + 0.45f * (0.5f + 0.5f * sin(t * 2f * Math.PI.toFloat())))).toInt()
    }

    /**
     * Volume: icon and percent sit either side of the camera, the level bar runs underneath.
     * Drag the bar to set the level directly.
     */
    private fun drawVolumeExpanded(canvas: Canvas, a: LiveActivity, alpha: Int) {
        val level = levelS.value.coerceIn(0f, 1f)
        val rowY = rect.top + compactHeight() / 2f
        drawSpeaker(canvas, rect.left + dp(24f), rowY, dp(15f), level, alpha)
        // The shown number follows the stream's real step, so it never lags the key press.
        rolling(canvas, "volume", "${IslandSignals.percent(a.progress ?: level)}", rect.right - dp(20f), rowY + dp(4.5f),
            smallPaint, NothingStyle.foreground, alpha, alignRight = true)
        volumeLeft = rect.left + dp(18f)
        volumeRight = rect.right - dp(18f)
        val pulse = barPulse.value.coerceIn(0f, 1f)
        val thick = dp(4f) + (if (volumeDragging) dp(3f) else dp(2f) * pulse)
        drawBar(canvas, volumeLeft, volumeRight, rect.top + dp(VOLUME_HEIGHT_DP) - dp(14f), level, userAccent ?: waveColor, alpha, thick)
        if (a.ringer != null) drawRingerControls(canvas, a, alpha)
    }

    private fun ringerIndex(state: RingerState?) = when (state) {
        RingerState.RING -> 0f; RingerState.VIBRATE -> 1f; RingerState.SILENT -> 2f; else -> null
    }

    private fun ringerTint(state: RingerState) = when (state) {
        RingerState.SILENT -> SILENT_RED
        RingerState.DND_ON, RingerState.DND_OFF -> DND_PURPLE
        else -> Color.WHITE
    }

    /**
     * Ring / Vibrate / Silent share one highlight that slides between them; Do Not Disturb is its
     * own switch at the end, since it can be on with any ringer mode.
     */
    private fun drawRingerControls(canvas: Canvas, a: LiveActivity, alpha: Int) {
        val current = a.ringer ?: return
        val top = rect.top + dp(VOLUME_HEIGHT_DP) + dp(2f)
        val h = dp(40f)
        val gap = dp(8f)
        val l = rect.left + dp(14f)
        val r = rect.right - dp(14f)
        val bw = (r - l - gap * 3) / 4f
        if (bw <= h * 0.8f || rect.bottom < top + h) return
        val cy = top + h / 2f
        val off = Color.rgb(30, 30, 30)
        ringerIndex(current)?.let { modeS.target = it }
        val pos = modeS.value.coerceIn(0f, 2f)
        val hx = l + pos * (bw + gap)
        val from = floor(pos).toInt().coerceIn(0, 2)
        val to = ceil(pos).toInt().coerceIn(0, 2)
        val highlight = ColorUtils.blendARGB(ringerTint(RINGER_ORDER[from]), ringerTint(RINGER_ORDER[to]), pos - from)
        for (i in 0 until 4) {
            val x = l + i * (bw + gap)
            fillPaint.color = withAlpha(off, alpha)
            canvas.drawRoundRect(x, top, x + bw, top + h, h / 2f, h / 2f, fillPaint)
        }
        // One highlight slides between ring, vibrate and silent.
        fillPaint.color = withAlpha(highlight, alpha)
        canvas.drawRoundRect(hx, top, hx + bw, top + h, h / 2f, h / 2f, fillPaint)
        if (a.dndOn) {
            val x = l + 3 * (bw + gap)
            fillPaint.color = withAlpha(DND_PURPLE, alpha)
            canvas.drawRoundRect(x, top, x + bw, top + h, h / 2f, h / 2f, fillPaint)
        }
        val g = dp(20f)
        for (i in 0 until 4) {
            val x = l + i * (bw + gap)
            val gx = x + bw / 2f
            // A glyph turns dark as the highlight slides under it.
            val covered = if (i < 3) 1f - abs(pos - i).coerceIn(0f, 1f) else if (a.dndOn) 1f else 0f
            val glyph = withAlpha(ColorUtils.blendARGB(Color.rgb(150, 150, 150), Color.BLACK, covered), alpha)
            val under = if (covered > 0.5f) (if (i < 3) highlight else DND_PURPLE) else off
            when (i) {
                0 -> drawBell(canvas, gx, cy, g, glyph)
                1 -> drawVibrate(canvas, gx, cy, g, glyph, 0f)
                2 -> { drawBell(canvas, gx, cy, g, glyph); drawSlash(canvas, gx, cy, g, glyph, 1f, under) }
                else -> drawCrescent(canvas, gx, cy, g, glyph)
            }
            val state = when (i) {
                0 -> RingerState.RING; 1 -> RingerState.VIBRATE; 2 -> RingerState.SILENT
                else -> if (a.dndOn) RingerState.DND_OFF else RingerState.DND_ON
            }
            modeHits.add(RectF(x, top - gap / 2f, x + bw, top + h + gap / 2f) to state)
        }
    }

    /** Headphones arriving, or any Bluetooth audio device dropping away. */
    private fun drawDeviceExpanded(canvas: Canvas, a: LiveActivity, alpha: Int) {
        val cy = rect.centerY()
        val icon = rect.height() - dp(20f)
        val l = rect.left + dp(10f)
        val tint = if (a.lost) NothingStyle.red else (userAccent ?: AppStyle.GREEN)
        fillPaint.color = withAlpha(tint, alpha * 45 / 255)
        canvas.drawCircle(l + icon / 2, cy, icon / 2, fillPaint)
        if (a.lost) drawBluetoothGlyph(canvas, l + icon / 2, cy, icon * .48f, withAlpha(tint, alpha))
        else drawHeadphones(canvas, l + icon / 2, cy, icon * .5f, withAlpha(tint, alpha))
        val tx = l + icon + dp(10f)
        var right = rect.right - dp(18f)
        val battery = a.progress
        if (battery != null && !a.lost) {
            val label = "${IslandSignals.percent(battery)}%"
            right = rolling(canvas, "battery", label, right, cy + dp(5f), titlePaint, tint, alpha, alignRight = true) - dp(10f)
        }
        text(canvas, a.title, tx, cy - dp(2f), right - tx, titlePaint, Color.WHITE, alpha)
        text(canvas, a.subtitle, tx, cy + dp(16f), right - tx, smallPaint, if (a.lost) NothingStyle.red else GREY, alpha)
    }

    /**
     * The cover shows only while music is actually playing; pausing fades it back to black. A new
     * cover crossfades from the old one. All the blurring happens once per cover, off the frame path.
     */
    private fun updateArtBackdrop(next: LiveActivity?) {
        val art = next?.takeIf { it.mode == IslandMode.MUSIC }?.artwork
        val on = features.artworkBackdrop && art != null && next?.isPlaying == true
        if (art != null && art !== artSource && features.artworkBackdrop) {
            artSource = art
            val blurred = blurredCover(art)
            if (blurred != null && artBlur?.sameAs(blurred) != true) {
                if (artA.value > 0.02f && artBlur != null) {
                    artBlurPrev = artBlur
                    artMix.snap(0f); artMix.target = 1f
                } else {
                    artBlurPrev = null
                    artMix.snap(1f)
                }
                artBlur = blurred
            }
        }
        artA.target = if (on) 1f else 0f
        if (!on && artA.value < 0.01f && !features.artworkBackdrop) { artBlur = null; artBlurPrev = null; artSource = null }
    }

    /** Two small downscales: a cheap, smooth blur that costs nothing per frame. */
    private fun blurredCover(src: Bitmap): Bitmap? = runCatching {
        if (src.width < 2 || src.height < 2 || src.isRecycled) return null
        val tiny = Bitmap.createScaledBitmap(src, ART_BLUR_TINY, ART_BLUR_TINY, true)
        Bitmap.createScaledBitmap(tiny, ART_BLUR_SIZE, ART_BLUR_SIZE, true).also { if (it !== tiny) tiny.recycle() }
    }.getOrNull()

    /**
     * Dot music: the whole island in white dots with the cover carved into them, breathing and
     * rippling with the record. Kept faint enough that the title and controls read on top.
     */
    private fun drawDotField(canvas: Canvas, a: LiveActivity, bodyAlpha: Float) {
        fieldA.target = if (a.isPlaying) 1f else 0.4f
        val show = fieldA.value.coerceIn(0f, 1f)
        if (show < 0.01f || bodyAlpha < 2f) return
        advanceDots(a)
        val open = expandedA.value.coerceIn(0f, 1f)
        val strength = (0.6f - 0.24f * open) * show
        val pitch = dp(4.5f + 1.8f * open)
        // Ripples start at the record (left of the small island, top-left of the open card), and
        // the field is carved away around it so the record sits in its own clearing.
        val compactX = rect.left + dp(7f) + dp(11f)
        val compactY = rect.centerY()
        val cardX = rect.left + dp(22f) + dp(29f)
        val cardY = rect.top + dp(22f) + dp(29f)
        val ox = compactX + (cardX - compactX) * open
        val oy = compactY + (cardY - compactY) * open
        val hole = dp(14.5f + 18.5f * open)
        dotField.draw(canvas, fillPaint, rect.left, rect.top, rect.right, rect.bottom, pitch, cardDots, a.isPlaying,
            (bodyAlpha * strength).toInt(), peak = (bodyAlpha * 0.95f * show).toInt(), ox = ox, oy = oy, hole = hole)
    }

    private fun drawArtBackdrop(canvas: Canvas, bodyAlpha: Float) {
        val show = artA.value.coerceIn(0f, 1f)
        val cover = artBlur ?: return
        if (show < 0.01f || bodyAlpha < 2f) return
        // Centre-crop the square cover to the island's shape.
        val aspect = rect.width() / max(1f, rect.height())
        val n = cover.width
        if (aspect >= 1f) {
            val h = (n / aspect).toInt().coerceIn(1, n)
            artSrc.set(0, (n - h) / 2, n, (n - h) / 2 + h)
        } else {
            val w = (n * aspect).toInt().coerceIn(1, n)
            artSrc.set((n - w) / 2, 0, (n - w) / 2 + w, n)
        }
        val base = bodyAlpha * show
        val mix = artMix.value.coerceIn(0f, 1f)
        artBlurPrev?.takeIf { mix < 0.99f }?.let {
            artPaint.alpha = (base * (1f - mix)).toInt()
            canvas.drawBitmap(it, artSrc, rect, artPaint)
        }
        artPaint.alpha = (base * if (artBlurPrev != null) mix else 1f).toInt()
        canvas.drawBitmap(cover, artSrc, rect, artPaint)
        if (mix >= 0.99f) artBlurPrev = null
        // Dim it so white text and controls stay easy to read; a touch more when open.
        fillPaint.color = Color.BLACK
        fillPaint.alpha = (base * (ART_DIM + 0.12f * expandedA.value.coerceIn(0f, 1f))).toInt()
        canvas.drawRect(rect, fillPaint)
    }

    /**
     * Soft halo: a few rings stepping outward and fading, so it costs no blur and looks the same on
     * every Android version. It brightens for a few seconds when something new arrives.
     */
    private fun drawGlow(canvas: Canvas, a: LiveActivity, r: Float, alpha: Int) {
        if (!features.edgeGlow || isAbsorbing || alpha < 8) return
        val base = ColorUtils.blendARGB(userAccent ?: a.accent.takeIf { ColorUtils.calculateLuminance(it) > .08 } ?: Color.WHITE,
            Color.WHITE, 0.3f)
        val now = SystemClock.uptimeMillis()
        val flare = if (now < eventOutlineUntil) 1f + 0.7f * ((eventOutlineUntil - now) / OUTLINE_EVENT_MS.toFloat()) else 1f
        // Many hairline rings with a smooth falloff read as one soft halo, not separate lines.
        val step = dp(GLOW_REACH_DP) / GLOW_STEPS
        haloPaint.strokeWidth = step * 1.25f
        for (i in 0 until GLOW_STEPS) {
            val d = (i + 0.5f) / GLOW_STEPS
            val o = (i + 0.5f) * step
            val f = exp(-d * d * 4.2f)
            haloPaint.color = withAlpha(base, (alpha * 0.2f * flare * f).toInt())
            canvas.drawRoundRect(rect.left - o, rect.top - o, rect.right + o, rect.bottom + o, r + o, r + o, haloPaint)
        }
    }

    /** Ring / vibrate / silent / Do Not Disturb: a glyph that moves once, and the state's name. */
    private fun drawRingerExpanded(canvas: Canvas, a: LiveActivity, alpha: Int) {
        val state = a.ringer ?: return
        val t = ((SystemClock.uptimeMillis() - ringerAnimAt) / RINGER_ANIM_MS.toFloat()).coerceIn(0f, 1f)
        val settle = 1f - t
        val tint = when (state) {
            RingerState.SILENT -> SILENT_RED
            RingerState.DND_ON -> DND_PURPLE
            else -> Color.WHITE
        }
        val color = withAlpha(tint, alpha)
        val cy = rect.centerY()
        val icon = rect.height() - dp(14f)
        val gx = rect.left + dp(7f) + icon / 2f
        fillPaint.color = withAlpha(tint, alpha * 40 / 255)
        canvas.drawCircle(gx, cy, icon / 2f, fillPaint)
        val g = icon * 0.56f
        when (state) {
            RingerState.RING -> {
                // A few swings that die away, hinged at the top of the bell.
                canvas.save()
                canvas.rotate(sin(t * PI.toFloat() * 6f) * 22f * settle, gx, cy - g * .5f)
                drawBell(canvas, gx, cy, g, color)
                canvas.restore()
            }
            RingerState.VIBRATE -> drawVibrate(canvas, gx + sin(t * PI.toFloat() * 14f) * dp(1.6f) * settle, cy, g, color, settle)
            RingerState.SILENT -> {
                drawBell(canvas, gx, cy, g, color)
                drawSlash(canvas, gx, cy, g, color, easeOut(t * 1.6f))
            }
            RingerState.DND_ON, RingerState.DND_OFF -> {
                val m = if (state == RingerState.DND_ON) 1f + 0.12f * sin(t * PI.toFloat()) else 1f
                drawCrescent(canvas, gx, cy, g * m, color)
                if (state == RingerState.DND_OFF) drawSlash(canvas, gx, cy, g, color, easeOut(t * 1.6f))
            }
        }
        val right = rect.right - dp(18f)
        val tx = gx + icon / 2f + dp(12f)
        // The word slides in a touch behind the glyph.
        val lag = easeOut(((t - 0.05f) * 2.5f).coerceIn(0f, 1f))
        // Centred in the space beside the glyph, so short words don't float off to one side.
        val room = right - tx
        val textW = min(titlePaint.measureText(a.title), room)
        text(canvas, a.title, tx + (room - textW) / 2f + (1f - lag) * dp(6f), cy + dp(5.5f), room, titlePaint, tint,
            (alpha * lag).toInt())
    }

    private fun easeOut(x: Float): Float { val c = x.coerceIn(0f, 1f); return 1f - (1f - c) * (1f - c) * (1f - c) }

    private fun drawBell(canvas: Canvas, cx: Float, cy: Float, size: Float, color: Int) {
        val s = size / 2f
        val top = cy - s * .95f
        val rim = cy + s * .55f
        iconPath.reset()
        iconPath.moveTo(cx - s * .78f, rim)
        iconPath.cubicTo(cx - s * .62f, rim - s * .3f, cx - s * .72f, top + s * .1f, cx, top)
        iconPath.cubicTo(cx + s * .72f, top + s * .1f, cx + s * .62f, rim - s * .3f, cx + s * .78f, rim)
        iconPath.close()
        fillPaint.color = color
        canvas.drawPath(iconPath, fillPaint)
        canvas.drawRoundRect(cx - s * .92f, rim - dp(.6f), cx + s * .92f, rim + dp(1.6f), dp(1f), dp(1f), fillPaint)
        canvas.drawCircle(cx, rim + s * .32f, s * .17f, fillPaint)
    }

    private fun drawVibrate(canvas: Canvas, cx: Float, cy: Float, size: Float, color: Int, energy: Float) {
        val s = size / 2f
        ringPaint.color = color
        ringPaint.strokeWidth = dp(1.7f)
        canvas.drawRoundRect(cx - s * .42f, cy - s * .78f, cx + s * .42f, cy + s * .78f, dp(2.5f), dp(2.5f), ringPaint)
        // Buzz marks either side, stronger while it shakes.
        ringPaint.color = withAlpha(color, (Color.alpha(color) * (0.55f + 0.45f * energy)).toInt())
        ringPaint.strokeWidth = dp(1.4f)
        for (k in 0..1) {
            val side = if (k == 0) -1f else 1f
            val x = cx + side * s * .72f
            val w = side * s * .16f
            iconPath.reset()
            iconPath.moveTo(x, cy - s * .48f)
            iconPath.lineTo(x + w, cy - s * .24f)
            iconPath.lineTo(x, cy)
            iconPath.lineTo(x + w, cy + s * .24f)
            iconPath.lineTo(x, cy + s * .48f)
            canvas.drawPath(iconPath, ringPaint)
        }
    }

    private fun drawCrescent(canvas: Canvas, cx: Float, cy: Float, size: Float, color: Int) {
        val s = size / 2f
        iconPath.reset()
        iconPath.addCircle(cx, cy, s * .82f, Path.Direction.CW)
        moonCut.reset()
        moonCut.addCircle(cx + s * .48f, cy - s * .36f, s * .68f, Path.Direction.CW)
        iconPath.op(moonCut, Path.Op.DIFFERENCE)
        fillPaint.color = color
        canvas.drawPath(iconPath, fillPaint)
    }

    /** A diagonal strike drawn in over [progress], with a dark gap so it reads over the glyph. */
    private fun drawSlash(canvas: Canvas, cx: Float, cy: Float, size: Float, color: Int, progress: Float, gap: Int = Color.BLACK) {
        if (progress <= 0f) return
        val s = size / 2f
        val x0 = cx - s * .85f; val y0 = cy - s * .85f
        val x1 = x0 + (s * 1.7f) * progress; val y1 = y0 + (s * 1.7f) * progress
        ringPaint.strokeWidth = dp(4.2f)
        ringPaint.color = withAlpha(gap, Color.alpha(color))
        canvas.drawLine(x0, y0, x1, y1, ringPaint)
        ringPaint.strokeWidth = dp(1.8f)
        ringPaint.color = color
        canvas.drawLine(x0, y0, x1, y1, ringPaint)
    }

    private fun drawDownloadExpanded(canvas: Canvas, a: LiveActivity, alpha: Int, late: Int) {
        val l = rect.left + dp(20f)
        val rr = rect.right - dp(20f)
        val s = dp(40f)
        val top = rect.top + dp(16f)
        val img = a.appIcon ?: a.artwork
        if (img != null) drawImage(canvas, img, l, top, s, dp(11f), alpha) else drawLetterTile(canvas, a, l, top, s, dp(11f), alpha)
        val tx = l + s + dp(12f)
        val percentLeft = rolling(canvas, "download-x", "${IslandSignals.percent(a.progress ?: 0f)}%", rr, top + dp(16f),
            titlePaint, NothingStyle.foreground, alpha, alignRight = true)
        val textRight = percentLeft - dp(12f)
        text(canvas, a.title, tx, top + dp(16f), textRight - tx, titlePaint, Color.WHITE, alpha)
        text(canvas, a.subtitle.ifBlank { a.appLabel }, tx, top + dp(35f), rr - tx, bodyPaint, GREY, alpha)
        drawBar(canvas, l, rr, rect.bottom - dp(20f), levelS.value, userAccent ?: waveColor, late)
    }

    /** Ringing alarm: Snooze and Dismiss come from the clock app's own notification buttons. */
    private fun drawAlarmExpanded(canvas: Canvas, a: LiveActivity, alpha: Int) {
        val cy = rect.centerY()
        val l = rect.left + dp(20f)
        val rr = rect.right - dp(20f)
        val av = dp(48f)
        fillPaint.color = withAlpha(ALARM, alpha * 40 / 255)
        canvas.drawCircle(l + av / 2, cy, av / 2, fillPaint)
        drawAlarmGlyph(canvas, l + av / 2, cy, dp(24f), withAlpha(ALARM, pulse(alpha)))
        val labels = a.actions.map { it.label }
        val dismiss = IslandSignals.alarmDismissIndex(labels).takeIf { it >= 0 }?.let { a.actions[it] }
        val snooze = IslandSignals.alarmSnoozeIndex(labels).takeIf { it >= 0 }?.let { a.actions[it] }
        val btn = dp(44f)
        var buttonsLeft = rr
        if (dismiss != null) {
            val x = buttonsLeft - btn
            fillPaint.color = withAlpha(ALARM, alpha)
            canvas.drawCircle(x + btn / 2, cy, btn / 2, fillPaint)
            ringPaint.color = withAlpha(Color.BLACK, alpha); ringPaint.strokeWidth = dp(2.4f)
            canvas.drawLine(x + btn / 2 - dp(6f), cy - dp(6f), x + btn / 2 + dp(6f), cy + dp(6f), ringPaint)
            canvas.drawLine(x + btn / 2 - dp(6f), cy + dp(6f), x + btn / 2 + dp(6f), cy - dp(6f), ringPaint)
            actionHits.add(RectF(x, cy - btn / 2, x + btn, cy + btn / 2) to dismiss)
            buttonsLeft = x - dp(12f)
        }
        if (snooze != null) {
            val x = buttonsLeft - btn
            fillPaint.color = withAlpha(NothingStyle.foreground, alpha * 45 / 255)
            canvas.drawCircle(x + btn / 2, cy, btn / 2, fillPaint)
            drawMoon(canvas, x + btn / 2, cy, dp(9f), withAlpha(Color.WHITE, alpha))
            actionHits.add(RectF(x, cy - btn / 2, x + btn, cy + btn / 2) to snooze)
            buttonsLeft = x - dp(12f)
        }
        val tx = l + av + dp(12f)
        text(canvas, a.appLabel.ifBlank { context.getString(R.string.island_alarm) }, tx, cy - dp(6f), buttonsLeft - tx, smallPaint, ALARM, alpha)
        text(canvas, a.title, tx, cy + dp(14f), buttonsLeft - tx, titlePaint, Color.WHITE, alpha)
    }

    private fun drawMiniProgress(canvas: Canvas, cx: Float, cy: Float, r: Float, fraction: Float, alpha: Int) {
        ringPaint.strokeWidth = dp(2.2f)
        arcRect.set(cx - r, cy - r, cx + r, cy + r)
        ringPaint.color = withAlpha(NothingStyle.foreground, alpha * 60 / 255)
        canvas.drawOval(arcRect, ringPaint)
        if (fraction > 0.001f) {
            ringPaint.color = withAlpha(userAccent ?: waveColor, alpha)
            canvas.drawArc(arcRect, -90f, 360f * fraction.coerceIn(0f, 1f), false, ringPaint)
        }
    }

    /** Speaker with 0–2 sound waves; a cross when muted. */
    private fun drawSpeaker(canvas: Canvas, cx: Float, cy: Float, size: Float, level: Float, alpha: Int) {
        val s = size / 2
        fillPaint.color = withAlpha(Color.WHITE, alpha)
        iconPath.reset()
        iconPath.moveTo(cx - s, cy - s * .35f)
        iconPath.lineTo(cx - s * .45f, cy - s * .35f)
        iconPath.lineTo(cx + s * .1f, cy - s * .9f)
        iconPath.lineTo(cx + s * .1f, cy + s * .9f)
        iconPath.lineTo(cx - s * .45f, cy + s * .35f)
        iconPath.lineTo(cx - s, cy + s * .35f)
        iconPath.close()
        canvas.drawPath(iconPath, fillPaint)
        ringPaint.color = withAlpha(Color.WHITE, alpha)
        ringPaint.strokeWidth = dp(1.6f)
        if (level <= 0.001f) {
            canvas.drawLine(cx + s * .45f, cy - s * .35f, cx + s * 1.1f, cy + s * .35f, ringPaint)
            canvas.drawLine(cx + s * .45f, cy + s * .35f, cx + s * 1.1f, cy - s * .35f, ringPaint)
            return
        }
        val waves = if (level > .5f) 2 else 1
        for (i in 1..waves) {
            val rr = s * (.35f + .4f * i)
            arcRect.set(cx + s * .1f - rr, cy - rr, cx + s * .1f + rr, cy + rr)
            canvas.drawArc(arcRect, -45f, 90f, false, ringPaint)
        }
    }

    private fun drawHeadphones(canvas: Canvas, cx: Float, cy: Float, size: Float, color: Int) {
        val s = size / 2
        ringPaint.color = color
        ringPaint.strokeWidth = dp(2f)
        arcRect.set(cx - s, cy - s, cx + s, cy + s)
        canvas.drawArc(arcRect, 180f, 180f, false, ringPaint)
        fillPaint.color = color
        canvas.drawRoundRect(cx - s - dp(1.5f), cy - dp(1f), cx - s + dp(4f), cy + s, dp(2f), dp(2f), fillPaint)
        canvas.drawRoundRect(cx + s - dp(4f), cy - dp(1f), cx + s + dp(1.5f), cy + s, dp(2f), dp(2f), fillPaint)
    }

    private fun drawBluetoothGlyph(canvas: Canvas, cx: Float, cy: Float, size: Float, color: Int) {
        val s = size / 2
        ringPaint.color = color
        ringPaint.strokeWidth = dp(1.8f)
        iconPath.reset()
        iconPath.moveTo(cx - s * .55f, cy - s * .5f)
        iconPath.lineTo(cx + s * .5f, cy + s * .45f)
        iconPath.lineTo(cx, cy + s)
        iconPath.lineTo(cx, cy - s)
        iconPath.lineTo(cx + s * .5f, cy - s * .45f)
        iconPath.lineTo(cx - s * .55f, cy + s * .5f)
        val style = ringPaint.style
        ringPaint.style = Paint.Style.STROKE
        canvas.drawPath(iconPath, ringPaint)
        ringPaint.style = style
    }

    /** Clock face with two bells. */
    private fun drawAlarmGlyph(canvas: Canvas, cx: Float, cy: Float, size: Float, color: Int) {
        val r = size * .38f
        ringPaint.color = color
        ringPaint.strokeWidth = max(dp(1.4f), size * .09f)
        canvas.drawCircle(cx, cy + size * .04f, r, ringPaint)
        canvas.drawLine(cx, cy + size * .04f, cx, cy - r * .5f, ringPaint)
        canvas.drawLine(cx, cy + size * .04f, cx + r * .45f, cy + size * .04f + r * .3f, ringPaint)
        arcRect.set(cx - size * .55f, cy - size * .6f, cx - size * .1f, cy - size * .15f)
        canvas.drawArc(arcRect, 200f, 90f, false, ringPaint)
        arcRect.set(cx + size * .1f, cy - size * .6f, cx + size * .55f, cy - size * .15f)
        canvas.drawArc(arcRect, 250f, 90f, false, ringPaint)
    }

    private fun drawMoon(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        iconPath.reset()
        iconPath.addCircle(cx, cy, r, Path.Direction.CW)
        moonCut.reset()
        moonCut.addCircle(cx + r * .55f, cy - r * .45f, r * .85f, Path.Direction.CW)
        iconPath.op(moonCut, Path.Op.DIFFERENCE)
        fillPaint.color = color
        canvas.drawPath(iconPath, fillPaint)
    }

    // ---------------------------------------------------------------- timer

    private fun isLiveTimer(a: LiveActivity) = a.mode == IslandMode.TIMER && a.clockMs() != null

    /** Title and buttons; the ring and digits are drawn by drawTimerMorph. */
    private fun drawTimerExpanded(canvas: Canvas, a: LiveActivity, alpha: Int, late: Int) {
        val l = rect.left + dp(20f)
        val rr = rect.right - dp(20f)
        text(canvas, a.title, l, rect.top + dp(27f), rr - l, titlePaint, Color.WHITE, alpha)
        val status = when {
            a.pausedTimerMs != null -> context.getString(R.string.timer_paused)
            a.chronometerCountDown && a.clockMs() == 0L -> context.getString(R.string.timer_done)
            a.chronometerCountDown -> context.getString(R.string.timer_label)
            else -> context.getString(R.string.timer_stopwatch)
        }
        text(canvas, status, l + dp(34f), rect.top + dp(64f), dp(76f), smallPaint, GREY, alpha)
        drawTimerActions(canvas, a, l, rect.top + dp(86f), rr, late)
    }

    /** Real notification actions only; no local fake pause or guessed PendingIntent. */
    private fun drawTimerActions(canvas: Canvas, a: LiveActivity, left: Float, top: Float, right: Float, alpha: Int) {
        val toggle = findAction(a, "pause", "resume", "start", "continue")
        val stop = findAction(a, "cancel", "stop", "delete", "dismiss", "reset")
        if (toggle == null && stop == null) { drawActions(canvas, a, left, top, right, alpha); return }
        val items = listOfNotNull(stop, toggle).distinct()
        val size = dp(44f)
        items.forEachIndexed { index, action ->
            val cx = if (index == 0) left + size / 2 else right - size / 2
            val cy = top + size / 2
            fillPaint.color = withAlpha(if (action == toggle) timerColor else Color.DKGRAY, alpha * 55 / 255)
            canvas.drawCircle(cx, cy, size / 2, fillPaint)
            if (action == toggle) {
                val paused = a.pausedTimerMs != null || action.label.contains("resume", true) || action.label.contains("start", true)
                // drawPlayPause takes a playing flag.
                drawPlayPause(canvas, cx, cy, !paused, alpha)
            } else {
                ringPaint.color = withAlpha(Color.WHITE, alpha)
                ringPaint.strokeWidth = dp(2f)
                canvas.drawLine(cx - dp(5f), cy - dp(5f), cx + dp(5f), cy + dp(5f), ringPaint)
                canvas.drawLine(cx - dp(5f), cy + dp(5f), cx + dp(5f), cy - dp(5f), ringPaint)
            }
            actionHits.add(RectF(cx - size / 2, top, cx + size / 2, top + size) to action)
        }
    }

    private fun drawTimerMorph(canvas: Canvas, a: LiveActivity, ea: Float, ca: Float, intro: Float) {
        val vis = (ea + ca).coerceIn(0f, 1f)
        val alpha = (((vis - 0.2f) / 0.8f).coerceIn(0f, 1f) * 255 * intro).toInt()
        if (alpha < 3) return
        val expandedHeight = specFor(Presentation.EXPANDED, a).h
        val m = ((heightS.value - compactHeight()) / (expandedHeight - compactHeight())).coerceIn(0f, 1f)
        val ringR = lerp(dp(8.5f), dp(11f), m)
        val ringX = lerp(rect.left + dp(18f), rect.left + dp(31f), m)
        val ringY = lerp(rect.centerY(), rect.top + dp(60f), m)
        val feedback = timerFeedback.value.coerceIn(0f, 1f)
        val timerAlpha = if (a.pausedTimerMs != null) (alpha * 0.55f).toInt() else alpha
        drawRing(canvas, ringX, ringY, ringR * (1f + feedback * 0.08f), dp(2.6f), timerFraction(a), timerAlpha)
        updateRoll(a)
        val right = lerp(rect.right - dp(13f), rect.right - dp(20f), m)
        digitPaint.textSize = lerp(dp(14f), dp(36f), m)
        // Fit hours without clipping the label or the camera cutout in the compact layout.
        val available = lerp(max(dp(64f), rect.width() / 2f - dp(20f)), max(dp(70f), rect.width() - dp(150f)), m)
        val measured = digitPaint.measureText(rollText)
        if (measured > available) digitPaint.textSize *= available / measured
        val baseline = lerp(rect.centerY() + dp(4.8f), rect.top + dp(72f), m)
        canvas.save()
        canvas.clipRect(right - available, baseline + digitPaint.ascent(), right + dp(1f), baseline + digitPaint.descent())
        drawRolling(canvas, right, baseline, timerAlpha, a.chronometerCountDown)
        canvas.restore()
    }

    private fun drawRing(canvas: Canvas, cx: Float, cy: Float, r: Float, stroke: Float, fraction: Float, alpha: Int) {
        ringPaint.strokeWidth = stroke
        arcRect.set(cx - r, cy - r, cx + r, cy + r)
        ringPaint.color = withAlpha(timerColor, alpha * 70 / 255)
        canvas.drawOval(arcRect, ringPaint)
        if (fraction > 0.001f) {
            ringPaint.color = withAlpha(timerColor, alpha)
            canvas.drawArc(arcRect, -90f, 360f * fraction, false, ringPaint)
        }
    }

    /** Countdown: remaining share of the longest duration seen. Stopwatch: sweeps once a minute. */
    private fun timerFraction(a: LiveActivity): Float {
        val remaining = a.clockMs() ?: return 0f
        if (!a.chronometerCountDown) return remaining.mod(60_000L) / 60_000f
        if (timerTotals.size > 16 && a.key !in timerTotals) timerTotals.clear()
        val total = max(timerTotals[a.key] ?: 0L, remaining)
        timerTotals[a.key] = total
        return if (total <= 0L) 0f else remaining.toFloat() / total
    }

    private fun updateRoll(a: LiveActivity) {
        val t = a.chronometerText().orEmpty()
        if (a.key != rollKey) {
            rollKey = a.key; rollText = t; rollPrev = t; rollAt = 0L
        } else if (t != rollText) {
            rollPrev = rollText; rollText = t; rollAt = SystemClock.uptimeMillis()
        }
    }

    /** Digits that change roll in (from above when counting down, from below when counting up). */
    private fun drawRolling(canvas: Canvas, right: Float, baseline: Float, alpha: Int, countDown: Boolean) {
        val p = digitPaint
        val cur = rollText
        val prev = rollPrev
        val progress = if (rollAt == 0L) 1f
        else ((SystemClock.uptimeMillis() - rollAt).toFloat() / ROLL_MS).coerceIn(0f, 1f)
        val e = 1f - (1f - progress) * (1f - progress) * (1f - progress)
        val shift = p.textSize * 0.75f
        val dir = if (countDown) 1f else -1f
        val sameShape = prev.length == cur.length
        var x = right - p.measureText(cur)
        for (i in cur.indices) {
            val ch = cur[i]
            val s = ch.toString()
            val w = p.measureText(s)
            val old = if (sameShape) prev[i] else ch
            if (old != ch && e < 1f) {
                p.color = withAlpha(timerColor, (alpha * (1f - e)).toInt())
                canvas.drawText(old.toString(), x, baseline + dir * e * shift, p)
                p.color = withAlpha(timerColor, (alpha * e).toInt())
                canvas.drawText(s, x, baseline - dir * (1f - e) * shift, p)
            } else {
                p.color = withAlpha(timerColor, alpha)
                canvas.drawText(s, x, baseline, p)
            }
            x += w
        }
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    /** Alpha that starts rising only after [at] of the open progress, for staggered content. */
    private fun stagger(ea: Float, at: Float) = (((ea - at) / (1f - at)).coerceIn(0f, 1f) * 255).toInt()



    /** Small notification-derived replica, not a screenshot of the third-party app. */
    private fun makeMorphCard(a: LiveActivity): Bitmap? = try {
        val bitmap = Bitmap.createBitmap(320, 560, Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap)
        c.drawColor(Color.rgb(20, 20, 22))
        val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = NothingStyle.body; color = Color.WHITE; textSize = 22f; textAlign = Paint.Align.CENTER
        }
        val small = TextPaint(title).apply { textSize = 14f; color = Color.LTGRAY }
        fun label(value: String, y: Float, paint: TextPaint) {
            c.drawText(TextUtils.ellipsize(value, paint, 280f, TextUtils.TruncateAt.END).toString(), 160f, y, paint)
        }
        label(a.appLabel, 42f, small)
        val image = a.artwork ?: a.appIcon
        if (image != null) drawImage(c, image, 48f, 100f, 224f, if (a.mode == IslandMode.CALL) 112f else 16f, 255)
        else {
            val tile = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY }
            c.drawRoundRect(48f, 100f, 272f, 324f, 24f, 24f, tile)
            label(a.appLabel.take(1), 222f, title)
        }
        label(a.title, 374f, title)
        label(a.subtitle.ifBlank { a.appLabel }, 404f, small)
        c.save(); c.scale(1f / density, 1f / density)
        if (a.mode == IslandMode.MUSIC) {
            drawSkip(c, 84f * density, 493f * density, false, 255)
            drawPlayPause(c, 160f * density, 493f * density, a.isPlaying, 255)
            drawSkip(c, 236f * density, 493f * density, true, 255)
        } else drawPhone(c, 160f * density, 493f * density, dp(28f), 255, false)
        c.restore()
        bitmap
    } catch (_: OutOfMemoryError) { null } catch (_: RuntimeException) { null }

    private fun drawAbsorb(canvas: Canvas, absorb: Float) {
        val image = morphCard ?: return
        val fade = ((1f - absorb - .28f) / .44f).coerceIn(0f, 1f)
        val cardVisibility = 1f - fade * fade * (3f - 2f * fade)
        val alpha = ((1f - appMorph.reveal) * cardVisibility * 255).toInt().coerceIn(0, 255)
        val blur = (sin((1f - absorb) * Math.PI).toFloat() * .85f).coerceIn(0f, 1f)
        morphPaint.alpha = alpha
        canvas.drawBitmap(image, null, rect, morphPaint)
        softMorphCard?.let {
            morphPaint.alpha = (alpha * blur).toInt()
            canvas.drawBitmap(it, null, rect, morphPaint)
        }
    }

    private fun drawActions(canvas: Canvas, a: LiveActivity, l: Float, t: Float, r: Float, alpha: Int) {
        val reply = a.reply
        val actions = if (reply != null) visibleActions(a).take(2) else visibleActions(a)
        replyHit.setEmpty()
        val count = actions.size + if (reply != null) 1 else 0
        if (count == 0) return
        val gap = dp(8f)
        val h = dp(36f)
        val w = (r - l - gap * (count - 1)) / count
        var first = 0
        if (reply != null) {
            // The reply pill is filled white so it reads as the main action.
            fillPaint.color = withAlpha(NothingStyle.foreground, alpha)
            canvas.drawRoundRect(l, t, l + w, t + h, h / 2, h / 2, fillPaint)
            smallPaint.textAlign = Paint.Align.CENTER
            text(canvas, reply.label, l + w / 2, t + h / 2 + dp(4.5f), w - dp(16f), smallPaint, NothingStyle.background, alpha, center = true)
            smallPaint.textAlign = Paint.Align.LEFT
            replyHit.set(l, t, l + w, t + h)
            first = 1
        }
        actions.forEachIndexed { index, action ->
            val i = index + first
            val x = l + i * (w + gap)
            fillPaint.color = withAlpha(NothingStyle.foreground, alpha * 60 / 255)
            canvas.drawRoundRect(x, t, x + w, t + h, h / 2, h / 2, fillPaint)
            smallPaint.textAlign = Paint.Align.CENTER
            text(canvas, action.label, x + w / 2, t + h / 2 + dp(4.5f), w - dp(16f), smallPaint, lighten(NothingStyle.foreground), alpha, center = true)
            smallPaint.textAlign = Paint.Align.LEFT
            actionHits.add(RectF(x, t, x + w, t + h) to action)
        }
    }

    // ---------------------------------------------------------------- drawing helpers

    private val artworkPlaceholder = LinearGradient(0f, 0f, 1f, 1f, NothingStyle.foreground,
        ColorUtils.blendARGB(NothingStyle.foreground, Color.BLACK, 0.6f), Shader.TileMode.CLAMP)

    private fun drawArtwork(canvas: Canvas, a: LiveActivity, l: Float, t: Float, size: Float, radius: Float, alpha: Int) {
        val art = a.artwork
        if (art != null) { drawImage(canvas, art, l, t, size, radius, alpha); return }
        // Unit gradient stretched per call: music placeholders redraw every frame without allocating.
        matrix.setScale(size, size); matrix.postTranslate(l, t)
        artworkPlaceholder.setLocalMatrix(matrix)
        fillPaint.shader = artworkPlaceholder
        fillPaint.alpha = alpha
        canvas.drawRoundRect(l, t, l + size, t + size, radius, radius, fillPaint)
        fillPaint.shader = null
        a.appIcon?.let { drawImage(canvas, it, l + size * 0.22f, t + size * 0.22f, size * 0.56f, size * 0.16f, alpha) }
    }

    /** Sender / caller photo with the app's icon as a badge, like iOS communication notifications. */
    private fun drawAvatar(canvas: Canvas, a: LiveActivity, l: Float, t: Float, size: Float, alpha: Int) {
        val photo = a.artwork
        if (photo != null) {
            drawImage(canvas, photo, l, t, size, size / 2, alpha)
            val icon = a.appIcon ?: return
            val b = size * 0.42f
            val bx = l + size - b * 0.8f
            val by = t + size - b * 0.8f
            fillPaint.color = withAlpha(baseColor, alpha)
            canvas.drawCircle(bx + b / 2, by + b / 2, b / 2 + dp(2f), fillPaint)
            drawImage(canvas, icon, bx, by, b, b / 2, alpha)
        } else {
            val icon = a.appIcon
            if (icon != null) drawImage(canvas, icon, l, t, size, size * 0.24f, alpha)
            else drawLetterTile(canvas, a, l, t, size, size * 0.24f, alpha)
        }
    }

    private fun drawLetterTile(canvas: Canvas, a: LiveActivity, l: Float, t: Float, size: Float, radius: Float, alpha: Int) {
        fillPaint.color = withAlpha(NothingStyle.foreground, alpha)
        canvas.drawRoundRect(l, t, l + size, t + size, radius, radius, fillPaint)
        letterPaint.textSize = size * 0.46f
        // Dark letter on the light tile; white-on-white was unreadable.
        letterPaint.color = withAlpha(NothingStyle.background, alpha)
        val letter = (a.title.firstOrNull { it.isLetterOrDigit() } ?: a.appLabel.firstOrNull() ?: '•').uppercase()
        canvas.drawText(letter, l + size / 2, t + size / 2 + size * 0.16f, letterPaint)
    }

    private fun drawImage(canvas: Canvas, bmp: Bitmap, l: Float, t: Float, size: Float, radius: Float, alpha: Int) {
        if (bmp.isRecycled || bmp.width == 0 || bmp.height == 0) return
        val shader = shaders.getOrPut(bmp) { BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        val scale = size / min(bmp.width, bmp.height).toFloat()
        matrix.reset()
        matrix.setScale(scale, scale)
        matrix.postTranslate(l - (bmp.width * scale - size) / 2f, t - (bmp.height * scale - size) / 2f)
        shader.setLocalMatrix(matrix)
        imagePaint.shader = shader
        imagePaint.alpha = alpha
        canvas.drawRoundRect(l, t, l + size, t + size, radius, radius, imagePaint)
        imagePaint.shader = null
    }

    private fun drawWave(canvas: Canvas, left: Float, cy: Float, w: Float, maxH: Float, color: Int, active: Boolean, alpha: Int) =
        drawWave(canvas, left, cy, w, maxH, color, if (active) 1f else 0f, alpha)

    /**
     * Five bars, mirrored round [cy], tallest in the middle. Each follows two slow, offset sines
     * plus a soft pulse, so they sway together like a voice rather than jitter. [energy] eases
     * them between still dots (0) and full motion (1), so play and pause fade rather than snap.
     */
    private fun drawWave(canvas: Canvas, left: Float, cy: Float, w: Float, maxH: Float, color: Int, energy: Float, alpha: Int) {
        val bars = 5
        val bw = w / (bars * 2 - 1)
        fillPaint.color = withAlpha(color, alpha)
        val t = SystemClock.uptimeMillis() / 1000f
        val e = energy.coerceIn(0f, 1f)
        val pulse = .5f + .5f * sin(t * 6.1f)
        for (i in 0 until bars) {
            val shape = .55f + .45f * sin(PI.toFloat() * (i + .5f) / bars)
            val sway = .5f + .28f * sin(t * (3.1f + i * .37f) + i * 1.3f) + .22f * sin(t * (1.7f + i * .23f) - i * .8f)
            val level = (shape * (.35f + .5f * sway + .15f * pulse)).coerceIn(0f, 1f)
            val h = bw + (maxH * level - bw) * e
            val x = left + i * bw * 2
            canvas.drawRoundRect(x, cy - h / 2, x + bw, cy + h / 2, bw / 2, bw / 2, fillPaint)
        }
    }

    private fun drawPlayPause(canvas: Canvas, cx: Float, cy: Float, playing: Boolean, alpha: Int) {
        fillPaint.color = withAlpha(Color.WHITE, alpha)
        val s = dp(15f)
        if (playing) {
            val bw = dp(6f)
            canvas.drawRoundRect(cx - dp(9f), cy - s, cx - dp(9f) + bw, cy + s, dp(2f), dp(2f), fillPaint)
            canvas.drawRoundRect(cx + dp(3f), cy - s, cx + dp(3f) + bw, cy + s, dp(2f), dp(2f), fillPaint)
        } else {
            iconPath.reset()
            iconPath.moveTo(cx - dp(9f), cy - s)
            iconPath.lineTo(cx + dp(13f), cy)
            iconPath.lineTo(cx - dp(9f), cy + s)
            iconPath.close()
            canvas.drawPath(iconPath, fillPaint)
        }
    }

    /** iOS-style double triangle. */
    private fun drawSkip(canvas: Canvas, cx: Float, cy: Float, forward: Boolean, alpha: Int) {
        fillPaint.color = withAlpha(Color.WHITE, alpha)
        val d = if (forward) 1f else -1f
        val s = dp(10f)
        iconPath.reset()
        for (offset in floatArrayOf(-s, 0f)) {
            val base = cx + d * offset
            iconPath.moveTo(base, cy - s)
            iconPath.lineTo(base + d * s, cy)
            iconPath.lineTo(base, cy + s)
            iconPath.close()
        }
        canvas.drawPath(iconPath, fillPaint)
    }

    private fun drawCallButton(canvas: Canvas, l: Float, t: Float, size: Float, color: Int, decline: Boolean, alpha: Int) {
        fillPaint.color = withAlpha(color, alpha)
        canvas.drawCircle(l + size / 2, t + size / 2, size / 2, fillPaint)
        drawPhone(canvas, l + size / 2, t + size / 2, size * 0.55f, alpha, decline)
    }

    private fun drawPhone(canvas: Canvas, cx: Float, cy: Float, size: Float, alpha: Int, rotated: Boolean) {
        val d = phoneIcon ?: return
        d.setTint(Color.WHITE)
        d.alpha = alpha
        d.setBounds((cx - size / 2).toInt(), (cy - size / 2).toInt(), (cx + size / 2).toInt(), (cy + size / 2).toInt())
        canvas.save()
        if (rotated) canvas.rotate(135f, cx, cy)
        d.draw(canvas)
        canvas.restore()
    }

    private fun drawBolt(canvas: Canvas, cx: Float, cy: Float, size: Float, color: Int) {
        fillPaint.color = color
        val s = size / 2
        iconPath.reset()
        iconPath.moveTo(cx + s * 0.15f, cy - s)
        iconPath.lineTo(cx - s * 0.6f, cy + s * 0.12f)
        iconPath.lineTo(cx - s * 0.02f, cy + s * 0.12f)
        iconPath.lineTo(cx - s * 0.15f, cy + s)
        iconPath.lineTo(cx + s * 0.6f, cy - s * 0.12f)
        iconPath.lineTo(cx + s * 0.02f, cy - s * 0.12f)
        iconPath.close()
        canvas.drawPath(iconPath, fillPaint)
    }

    private fun text(
        canvas: Canvas, s: String, x: Float, y: Float, maxW: Float, p: TextPaint, color: Int, alpha: Int,
        alignRight: Boolean = false, center: Boolean = false,
    ) {
        if (maxW <= 0f || s.isBlank()) return
        val out = fitted(s, p, maxW)
        p.color = withAlpha(color, alpha * Color.alpha(color) / 255)
        val dx = if (alignRight && !center) x - p.measureText(out) else x
        canvas.drawText(out, dx, y, p)
    }

    // Ellipsizing allocates; the same labels are drawn every frame, so remember the last results.
    private val fitSource = arrayOfNulls<String>(FIT_SLOTS)
    private val fitResult = arrayOfNulls<String>(FIT_SLOTS)
    private val fitWidth = FloatArray(FIT_SLOTS)
    private val fitSize = FloatArray(FIT_SLOTS)
    private val fitFace = arrayOfNulls<Typeface>(FIT_SLOTS)

    private fun fitted(s: String, p: TextPaint, maxW: Float): String {
        val width = kotlin.math.floor(maxW)
        val slot = ((s.hashCode() * 31 + width.toInt()) and Int.MAX_VALUE) % FIT_SLOTS
        if (fitSource[slot] === s || fitSource[slot] == s) {
            if (fitWidth[slot] == width && fitSize[slot] == p.textSize && fitFace[slot] === p.typeface) return fitResult[slot]!!
        }
        val out = TextUtils.ellipsize(s.replace('\n', ' '), p, width, TextUtils.TruncateAt.END).toString()
        fitSource[slot] = s; fitResult[slot] = out; fitWidth[slot] = width; fitSize[slot] = p.textSize; fitFace[slot] = p.typeface
        return out
    }

    // ---------------------------------------------------------------- rolling numbers

    /**
     * Any number on the island rolls when it changes: only the digits that changed move, upward
     * when the value grows and downward when it shrinks, with a short fade. Tabular figures keep
     * every digit in place.
     */
    private class Roll {
        var text = ""
        var prev = ""
        var at = 0L
        var up = true
    }
    private val rolls = HashMap<String, Roll>()
    private var rollingUntil = 0L

    private fun numberOf(s: String): Long? {
        var n = 0L; var any = false
        for (c in s) if (c in '0'..'9') { n = n * 10 + (c - '0'); any = true }
        return if (any) n else null
    }

    private fun rolling(
        canvas: Canvas, slot: String, value: String, x: Float, baseline: Float, p: TextPaint, color: Int, alpha: Int,
        alignRight: Boolean = false, travel: Float = .62f,
    ): Float {
        val r = rolls.getOrPut(slot) { Roll() }
        val now = SystemClock.uptimeMillis()
        if (value != r.text) {
            if (r.text.isNotEmpty() && !reduceMotion) {
                r.prev = r.text; r.at = now
                val a = numberOf(value); val b = numberOf(r.text)
                r.up = if (a != null && b != null) a >= b else true
                rollingUntil = max(rollingUntil, now + NUMBER_ROLL_MS)
                kick()
            }
            r.text = value
        }
        val width = p.measureText(value)
        val left = if (alignRight) x - width else x
        val t = if (r.at == 0L) 1f else ((now - r.at).toFloat() / NUMBER_ROLL_MS).coerceIn(0f, 1f)
        val e = 1f - (1f - t) * (1f - t) * (1f - t)
        if (t >= 1f || r.prev.isEmpty()) {
            p.color = withAlpha(color, alpha)
            canvas.drawText(value, left, baseline, p)
            return left
        }
        val shift = p.textSize * travel
        val dir = if (r.up) 1f else -1f
        canvas.save()
        canvas.clipRect(min(left, x - (if (alignRight) p.measureText(r.prev) else 0f)) - dp(2f), baseline + p.ascent() - dp(1f),
            max(left + width, if (alignRight) x else x + p.measureText(r.prev)) + dp(2f), baseline + p.descent() + dp(1f))
        if (r.prev.length == value.length) {
            // Same shape: unchanged characters stay put, changed ones roll.
            for (i in value.indices) {
                val cx = left + p.measureText(value, 0, i)
                if (r.prev[i] == value[i]) {
                    p.color = withAlpha(color, alpha)
                    canvas.drawText(value, i, i + 1, cx, baseline, p)
                } else {
                    p.color = withAlpha(color, (alpha * (1f - e)).toInt())
                    canvas.drawText(r.prev, i, i + 1, cx, baseline - dir * e * shift, p)
                    p.color = withAlpha(color, (alpha * e).toInt())
                    canvas.drawText(value, i, i + 1, cx, baseline + dir * (1f - e) * shift, p)
                }
            }
        } else {
            // A digit was added or dropped (9 → 10): the whole number rolls.
            val oldLeft = if (alignRight) x - p.measureText(r.prev) else x
            p.color = withAlpha(color, (alpha * (1f - e)).toInt())
            canvas.drawText(r.prev, oldLeft, baseline - dir * e * shift, p)
            p.color = withAlpha(color, (alpha * e).toInt())
            canvas.drawText(value, left, baseline + dir * (1f - e) * shift, p)
        }
        canvas.restore()
        return left
    }

    /** One quiet bar style for every level and progress on the island. */
    private fun drawBar(canvas: Canvas, left: Float, right: Float, cy: Float, fraction: Float, color: Int, alpha: Int, thick: Float = dp(4f)) {
        if (right <= left) return
        fillPaint.color = withAlpha(NothingStyle.foreground, alpha * 38 / 255)
        canvas.drawRoundRect(left, cy - thick / 2, right, cy + thick / 2, thick / 2, thick / 2, fillPaint)
        val f = fraction.coerceIn(0f, 1f)
        if (f <= 0f) return
        // Never shorter than its own height, so a low level still reads as a rounded dot.
        val end = max(left + thick, left + (right - left) * f)
        fillPaint.color = withAlpha(color, alpha)
        canvas.drawRoundRect(left, cy - thick / 2, end, cy + thick / 2, thick / 2, thick / 2, fillPaint)
    }

    private fun withAlpha(color: Int, alpha: Int) = ColorUtils.setAlphaComponent(color, alpha.coerceIn(0, 255))
    private fun lighten(color: Int) = ColorUtils.blendARGB(color, Color.WHITE, 0.25f)

    private fun findAction(a: LiveActivity, vararg words: String) =
        visibleActions(a).firstOrNull { act -> words.any { act.label.contains(it, ignoreCase = true) } }

    // ---------------------------------------------------------------- touch

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val local = MotionEvent.obtain(e)
        local.setLocation(e.x / fit.scale, e.y / fit.scale)
        return try { handleTouch(local) } catch (t: Throwable) {
            CrashLog.caught(context, "touch ${activity?.mode} ${presentation}", t)
            true
        } finally { local.recycle() }
    }

    private fun handleTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_OUTSIDE -> {
                if (presentation == Presentation.EXPANDED) callbacks?.onCollapseRequest()
                return false
            }
            MotionEvent.ACTION_DOWN -> {
                hit.set(rect)
                hit.inset(-dp(10f), -dp(10f))
                secondaryHit.set(secondaryRect)
                secondaryHit.inset(-dp(4f), -dp(4f))
                touchingSecondary = secondary != null && presentation == Presentation.COMPACT && secondaryA.value > 0.5f && dropS.value > .92f && secondaryHit.contains(e.x, e.y)
                if (isConcealed || (!touchingSecondary && !hit.contains(e.x, e.y))) return false
                tracking = true
                dragging = false
                longFired = false
                downX = e.x
                downY = e.y
                downAt = e.eventTime
                val a = activity
                seeking = presentation == Presentation.EXPANDED && a?.mode == IslandMode.MUSIC &&
                    showsMediaAction(IslandBus.MEDIA_SEEK) &&
                    a.durationMs > 0L && abs(e.y - barY) < dp(16f) &&
                    e.x >= barLeft - dp(12f) && e.x <= barRight + dp(12f)
                if (seeking) seekFraction = fractionAt(e.x)
                // Touching the dots ripples them out from your finger, as in the full player.
                if (a != null && usesDots(a) && !reduceMotion && !touchingSecondary) { cardDots.ripple(0.9f, e.x, e.y); kick() }
                // Only the slider row drags; the ringer buttons below it are taps.
                volumeDragging = presentation == Presentation.EXPANDED && a?.mode == IslandMode.VOLUME &&
                    e.y < rect.top + dp(VOLUME_HEIGHT_DP) + dp(2f)
                if (volumeDragging) setVolumeFromTouch(e.x)
                if (touchingSecondary) bubblePress.target = if (reduceMotion) 1f else 0.94f
                else pressS.target = if (reduceMotion) 1f else if (presentation == Presentation.EXPANDED) 0.985f else 0.96f
                if (presentation == Presentation.COMPACT) postDelayed(longPress, LONG_PRESS_MS)
                callbacks?.onInteraction()
                settled = false
                kick()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return false
                val dx = e.x - downX
                val dy = e.y - downY
                if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                    dragging = true
                    removeCallbacks(longPress)
                }
                if (volumeDragging) {
                    setVolumeFromTouch(e.x)
                } else if (seeking) {
                    seekFraction = fractionAt(e.x)
                    invalidate()
                } else if (dragging) {
                    stretchS.snap(if (reduceMotion) 0f else rubber(dy))
                    kick()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!tracking) return false
                finishTouch()
                val dy = e.y - downY
                val dx = e.x - downX
                val velocityY = dy * 1000f / max(1L, e.eventTime - downAt)
                val swipe = abs(dy) > abs(dx) && (abs(dy) > dp(26f) || abs(dy) > slop && abs(velocityY) > dp(500f))
                val a = activity
                when {
                    longFired -> Unit
                    volumeDragging -> { volumeDragging = false; haptic(HapticGesture.SWIPE, HapticFeedbackConstants.CLOCK_TICK) }
                    seeking && a != null -> {
                        val pos = (a.durationMs * seekFraction).toLong()
                        activity = a.copy(positionMs = pos, positionUpdatedAt = SystemClock.elapsedRealtime())
                        callbacks?.onMedia(a, IslandBus.MEDIA_SEEK, pos)
                        haptic(HapticGesture.SWIPE, HapticFeedbackConstants.CLOCK_TICK)
                    }
                    dragging && abs(dx) > dp(28f) && abs(dx) > abs(dy) && secondary != null -> {
                        haptic(HapticGesture.SWIPE, HapticFeedbackConstants.CLOCK_TICK)
                        callbacks?.onSwitchActivity()
                    }
                    dragging && swipe && dy < 0f -> swipeUp()
                    dragging && swipe && dy > 0f && presentation == Presentation.COMPACT -> {
                        if (touchingSecondary) secondary?.let { callbacks?.onSecondaryExpand(it) }
                        else callbacks?.onExpandRequest()
                    }
                    !dragging -> {
                        performClick()
                        onTapUp(e.x, e.y)
                    }
                }
                seeking = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                finishTouch()
                seeking = false
                volumeDragging = false
                return true
            }
        }
        return tracking
    }

    private fun finishTouch() {
        tracking = false
        removeCallbacks(longPress)
        pressS.target = 1f
        bubblePress.target = 1f
        stretchS.target = 0f
        settled = false
        kick()
    }

    private fun rubber(dy: Float): Float {
        val limit = dp(34f)
        return sign(dy) * limit * (1f - exp(-abs(dy) / dp(140f)))
    }

    private fun fractionAt(x: Float) = ((x - barLeft) / max(1f, barRight - barLeft)).coerceIn(0f, 1f)

    private fun swipeUp() {
        val a = activity ?: return
        haptic(HapticGesture.UP, HapticFeedbackConstants.KEYBOARD_TAP)
        if (presentation == Presentation.EXPANDED && !a.isTransient) callbacks?.onCollapseRequest()
        else callbacks?.onDismiss(a)
    }

    private fun onTapUp(x: Float, y: Float) {
        if (touchingSecondary) secondary?.let { callbacks?.onOpen(it) }
        else handleTap(x, y)
    }

    private fun handleTap(x: Float, y: Float) {
        val a = activity ?: return
        when (presentation) {
            Presentation.IDLE -> return
            Presentation.COMPACT -> callbacks?.onOpen(a)
            Presentation.EXPANDED -> {
                // System islands have no app to open; a tap just tucks them away.
                modeHits.firstOrNull { it.first.contains(x, y) }?.let {
                    haptic(HapticGesture.TAP, HapticFeedbackConstants.KEYBOARD_TAP)
                    callbacks?.onRingerMode(it.second)
                    return
                }
                // The ringer island steps to the next mode: ring → vibrate → silent → ring.
                if (a.mode == IslandMode.RINGER && features.ringerControls) a.ringer?.let { state ->
                    haptic(HapticGesture.TAP, HapticFeedbackConstants.KEYBOARD_TAP)
                    callbacks?.onRingerMode(when (state) {
                        RingerState.RING -> RingerState.VIBRATE
                        RingerState.VIBRATE -> RingerState.SILENT
                        RingerState.SILENT -> RingerState.RING
                        RingerState.DND_ON -> RingerState.DND_OFF
                        RingerState.DND_OFF -> RingerState.DND_ON
                    })
                    return
                }
                if (a.mode == IslandMode.VOLUME || a.mode == IslandMode.DEVICE || a.mode == IslandMode.RINGER) { callbacks?.onDismiss(a); return }
                if (!replyHit.isEmpty && replyHit.contains(x, y)) {
                    haptic(HapticGesture.TAP, HapticFeedbackConstants.KEYBOARD_TAP)
                    callbacks?.onReply(a)
                    return
                }
                actionHits.firstOrNull { it.first.contains(x, y) }?.let {
                    callbacks?.onAction(a, it.second)
                    haptic(HapticGesture.TAP, HapticFeedbackConstants.KEYBOARD_TAP)
                    return
                }
                if (a.mode == IslandMode.MUSIC && musicArtHit.contains(x, y)) {
                    callbacks?.onDotPlayer()
                } else if (a.mode == IslandMode.MUSIC && y > barY + dp(16f)) {
                    val cx = rect.centerX()
                    when {
                        x < cx - dp(36f) && showsMediaAction(IslandBus.MEDIA_PREVIOUS) -> callbacks?.onMedia(a, IslandBus.MEDIA_PREVIOUS)
                        x > cx + dp(36f) && showsMediaAction(IslandBus.MEDIA_NEXT) -> callbacks?.onMedia(a, IslandBus.MEDIA_NEXT)
                        x in (cx - dp(36f))..(cx + dp(36f)) && showsMediaAction(IslandBus.MEDIA_TOGGLE) -> {
                            // Flip instantly so the button feels responsive; the real state follows.
                            activity = a.copy(
                                isPlaying = !a.isPlaying,
                                positionMs = a.livePositionMs(),
                                positionUpdatedAt = SystemClock.elapsedRealtime(),
                            )
                            callbacks?.onMedia(a, IslandBus.MEDIA_TOGGLE)
                            if (usesDots(a)) { cardDots.ripple(1f); compactDots.ripple(1f) }
                            kick()
                        }
                        else -> callbacks?.onOpen(a)
                    }
                } else {
                    callbacks?.onOpen(a)
                }
            }
        }
        haptic(HapticGesture.TAP, HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun setVolumeFromTouch(x: Float) {
        val fraction = ((x - volumeLeft) / max(1f, volumeRight - volumeLeft)).coerceIn(0f, 1f)
        levelS.target = fraction
        callbacks?.onVolumeChange(fraction)
        settled = false
        kick()
    }

    /** The island's current shape in view pixels (after phone-fit scaling), for guided demos. */
    /**
     * Where the island takes touches, in view pixels: where it is now plus where it is heading,
     * with its bubble and a small margin. Empty when there is nothing to touch.
     */
    fun touchBounds(out: RectF) {
        val a = activity
        if (a == null || isConcealed) { out.setEmpty(); return }
        val spec = specFor(presentation, a)
        val cx = IslandFit.center(layoutWidth() / fit.scale, spec.w, spec.cx, dp(4f))
        val top = targetTop(presentation)
        out.set(cx - spec.w / 2f, top, cx + spec.w / 2f, top + spec.h)
        if (rect.width() > 1f) out.union(rect)
        if (secondary != null && presentation == Presentation.COMPACT) {
            val c = compactCluster()
            val d = bubbleDiameter()
            out.union(c.bubbleCenter - d / 2f, top, c.bubbleCenter + d / 2f, top + d)
            if (!secondaryRect.isEmpty) out.union(secondaryRect)
        }
        val m = dp(TOUCH_MARGIN_DP)
        out.set((out.left - m) * fit.scale, max(0f, out.top - m) * fit.scale, (out.right + m) * fit.scale, (out.bottom + m) * fit.scale)
    }

    fun islandBounds(out: RectF) {
        out.set(rect.left * fit.scale, rect.top * fit.scale, rect.right * fit.scale, rect.bottom * fit.scale)
    }

    /** The detached bubble in view pixels; empty when there is none. */
    fun bubbleBounds(out: RectF) {
        out.set(secondaryRect.left * fit.scale, secondaryRect.top * fit.scale,
            secondaryRect.right * fit.scale, secondaryRect.bottom * fit.scale)
    }

    /** Where the expanded card ends, in window pixels: the reply field sits just below it. */
    fun expandedBottomPx(a: LiveActivity): Int = ((baseTop + specFor(Presentation.EXPANDED, a).h) * fit.scale).toInt()

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun refreshContentDescription() {
        val a = activity
        contentDescription = when {
            a == null -> context.getString(R.string.cd_island)
            a.mode == IslandMode.MUSIC -> context.getString(R.string.cd_now_playing, a.title, a.subtitle)
            a.mode == IslandMode.VOLUME -> "${a.title} ${IslandSignals.percent(a.progress ?: 0f)}%"
            else -> "${a.appLabel}: ${a.title}"
        } + (secondary?.let { context.getString(R.string.cd_also, it.appLabel, it.title) } ?: "")
    }

    companion object {
        private val GREY = NothingStyle.muted
        private const val LONG_PRESS_MS = 350L
        /** Wallpaper stage height below the island's top; taller than any expanded card. */
        private const val WALLPAPER_STAGE_DP = 240f
        private const val SHEEN_MS = 800L
        private const val INTRO_HOLD_MS = 190L
        private const val RETURN_HOLD_MS = 110L
        private const val LANDSCAPE_BODY = 0.55f
        private const val ROLL_MS = 240L
        private const val OUTLINE_EVENT_MS = 4_000L
        private const val RINGER_HEIGHT_DP = 46f
        private const val RINGER_ROW_DP = 52f
        private const val IDLE_PILL_WIDTH_DP = 96f
        private const val TOUCH_MARGIN_DP = 10f
        private val RINGER_ORDER = arrayOf(RingerState.RING, RingerState.VIBRATE, RingerState.SILENT)
        private const val ART_BLUR_TINY = 6
        private const val ART_BLUR_SIZE = 24
        private const val ART_DIM = 0.42f
        private const val RINGER_ANIM_MS = 900L
        private const val GLOW_STEPS = 16
        private const val GLOW_REACH_DP = 10f
        private val SILENT_RED = Color.rgb(255, 92, 72)
        private val DND_PURPLE = Color.rgb(150, 132, 255)
        private const val NUMBER_ROLL_MS = 260L
        private const val FIT_SLOTS = 48
        private const val SHORT_COMPACT_WIDTH_DP = 168f
        private const val SHORT_SPLIT_WIDTH_DP = 140f
        private const val MEDIUM_WIDTH_DP = 360f
        private const val MEDIUM_HEIGHT_DP = 60f
        private const val VOLUME_WIDTH_DP = 300f
        private const val VOLUME_HEIGHT_DP = 54f
        private val ALARM = Color.rgb(255, 184, 28)
    }
}
