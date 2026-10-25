package com.dynamicdroid.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.animation.PathInterpolator
import kotlin.math.min
import kotlin.math.sin

enum class IslandMode { IDLE, MUSIC, CALL, TIMER, CHARGING, NAVIGATION, MESSAGE }

class IslandView(context: android.content.Context) : View(context) {
    var mode = IslandMode.IDLE
        set(value) {
            field = value
            refreshContentDescription()
            invalidate()
        }
    var title = "Midnight City"
        set(value) { field = value; refreshContentDescription(); invalidate() }
    var subtitle = "M83"
        set(value) { field = value; refreshContentDescription(); invalidate() }
    var artwork: Bitmap? = null
        set(value) { field = value; invalidate() }
    var isPlaying = false
        set(value) {
            field = value
            playbackUpdatedAt = SystemClock.elapsedRealtime()
            invalidate()
        }
    var playbackPositionMs = 0L
        set(value) {
            field = value.coerceAtLeast(0L)
            playbackUpdatedAt = SystemClock.elapsedRealtime()
            invalidate()
        }
    var durationMs = 0L
        set(value) { field = value.coerceAtLeast(0L); invalidate() }
    var primaryActionLabel = "OPEN"
    var onOpen: (() -> Unit)? = null
    var onPrimaryAction: (() -> Unit)? = null
    var onPreviousAction: (() -> Unit)? = null
    var onTogglePlayback: (() -> Unit)? = null
    var onNextAction: (() -> Unit)? = null
    var onSeekAction: ((Long) -> Unit)? = null
    var onBoundsChanged: ((Boolean) -> Unit)? = null
    var expanded = false
        set(value) {
            if (field == value) return
            field = value
            if (value) onBoundsChanged?.invoke(true)
            animateTo(if (value) 1f else 0f)
        }

    private var progress = 0f
    private var playbackUpdatedAt = SystemClock.elapsedRealtime()
    private var animator: ValueAnimator? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val artworkRect = RectF()
    private val artworkPath = Path()
    private val iconPath = Path()

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        isClickable = true
        isFocusable = true
        refreshContentDescription()
    }

    private fun refreshContentDescription() {
        contentDescription = when (mode) {
            IslandMode.IDLE -> "Dynamic Droid"
            IslandMode.MUSIC -> "Now playing: $title by $subtitle"
            else -> "${mode.name.lowercase().replaceFirstChar { it.uppercase() }}: $title"
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(resolveSize(dp(310), widthMeasureSpec), resolveSize(dp(154), heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val visualProgress = progress.coerceIn(0f, 1f)
        val compactW = dp(108f)
        val wide = dp(292f)
        val width = compactW + (wide - compactW) * visualProgress
        val height = dp(34f) + (dp(136f) - dp(34f)) * visualProgress
        rect.set((measuredWidth - width) / 2, dp(8f), (measuredWidth + width) / 2, dp(8f) + height)
        paint.color = Color.BLACK
        paint.setShadowLayer(dp(14f), 0f, dp(4f), 0x66000000)
        canvas.drawRoundRect(rect, dp(18f) + dp(10f) * visualProgress, dp(18f) + dp(10f) * visualProgress, paint)
        paint.clearShadowLayer()

        if (visualProgress < .12f) {
            paint.color = accentColor()
            canvas.drawCircle(rect.left + dp(17f), rect.top + dp(17f), dp(4f), paint)
            paint.color = Color.rgb(11, 16, 23)
            canvas.drawCircle(rect.right - dp(20f), rect.top + dp(17f), dp(5f), paint)
            return
        }
        paint.alpha = (255 * min(1f, visualProgress * 1.4f)).toInt()
        if (mode == IslandMode.MUSIC) drawMusic(canvas, rect.left + dp(15f), rect.top + dp(15f))
        else drawActivity(canvas, rect.left + dp(15f), rect.top + dp(15f))
        paint.alpha = 255

        if (mode == IslandMode.MUSIC && isPlaying) postInvalidateDelayed(120L)
    }

    private fun drawActivity(canvas: Canvas, left: Float, top: Float) {
        val accent = accentColor()
        paint.color = accent
        canvas.drawRoundRect(left, top, left + dp(45f), top + dp(45f), dp(12f), dp(12f), paint)
        paint.color = Color.WHITE
        paint.textSize = dp(17f)
        paint.typeface = Typeface.DEFAULT_BOLD
        canvas.drawText(symbol(), left + dp(13f), top + dp(29f), paint)

        paint.color = Color.rgb(135, 136, 144)
        paint.textSize = dp(8f)
        paint.typeface = Typeface.DEFAULT
        canvas.drawText(mode.name, left + dp(57f), top + dp(10f), paint)
        paint.color = Color.WHITE
        paint.textSize = dp(14f)
        paint.typeface = Typeface.DEFAULT_BOLD
        canvas.drawText(title.take(22), left + dp(57f), top + dp(28f), paint)
        paint.color = Color.rgb(151, 152, 159)
        paint.textSize = dp(10f)
        paint.typeface = Typeface.DEFAULT
        canvas.drawText(subtitle.take(28), left + dp(57f), top + dp(43f), paint)

        drawActions(canvas, left, top, accent)
    }

    private fun drawMusic(canvas: Canvas, left: Float, top: Float) {
        artworkRect.set(left, top, left + dp(45f), top + dp(45f))
        artworkPath.reset()
        artworkPath.addRoundRect(artworkRect, dp(10f), dp(10f), Path.Direction.CW)
        val cover = artwork
        if (cover != null && !cover.isRecycled) {
            canvas.save()
            canvas.clipPath(artworkPath)
            paint.color = Color.WHITE
            canvas.drawBitmap(cover, null, artworkRect, paint)
            canvas.restore()
        } else {
            paint.shader = LinearGradient(
                left,
                top,
                left + dp(45f),
                top + dp(45f),
                intArrayOf(Color.rgb(255, 93, 119), Color.rgb(119, 55, 157), Color.rgb(35, 69, 143)),
                null,
                Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(artworkRect, dp(10f), dp(10f), paint)
            paint.shader = null
            paint.color = Color.WHITE
            paint.textSize = dp(22f)
            paint.typeface = Typeface.DEFAULT_BOLD
            canvas.drawText("♪", left + dp(12f), top + dp(31f), paint)
        }

        paint.color = Color.rgb(151, 152, 159)
        paint.textSize = dp(8f)
        paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        canvas.drawText(if (isPlaying) "NOW PLAYING" else "PAUSED", left + dp(57f), top + dp(9f), paint)
        paint.color = Color.WHITE
        paint.textSize = dp(13f)
        paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        canvas.drawText(title.take(24), left + dp(57f), top + dp(27f), paint)
        paint.color = Color.rgb(151, 152, 159)
        paint.textSize = dp(10f)
        paint.typeface = Typeface.DEFAULT
        canvas.drawText(subtitle.take(30), left + dp(57f), top + dp(43f), paint)
        drawPlaybackWave(canvas, top + dp(23f))

        val centerX = rect.centerX()
        val controlsY = top + dp(77f)
        drawSkip(canvas, centerX - dp(54f), controlsY, false)
        drawPlaybackButton(canvas, centerX, controlsY)
        drawSkip(canvas, centerX + dp(54f), controlsY, true)

        val barLeft = rect.left + dp(15f)
        val barRight = rect.right - dp(15f)
        val barTop = top + dp(103f)
        val barHeight = dp(2f)
        paint.color = Color.rgb(47, 48, 53)
        canvas.drawRoundRect(barLeft, barTop, barRight, barTop + barHeight, dp(2f), dp(2f), paint)
        val shownPosition = playbackPositionMs +
            if (isPlaying) (SystemClock.elapsedRealtime() - playbackUpdatedAt).coerceAtLeast(0L) else 0L
        val fraction = if (durationMs > 0L) (shownPosition.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
        paint.color = Color.WHITE
        canvas.drawRoundRect(
            barLeft,
            barTop,
            barLeft + (barRight - barLeft) * fraction,
            barTop + barHeight,
            dp(2f),
            dp(2f),
            paint
        )
        paint.color = Color.rgb(133, 134, 141)
        paint.textSize = dp(7f)
        paint.typeface = Typeface.DEFAULT
        canvas.drawText(formatTime(shownPosition), barLeft, top + dp(116f), paint)
        val remaining = if (durationMs > 0L) (durationMs - shownPosition).coerceAtLeast(0L) else 0L
        val remainingText = "-${formatTime(remaining)}"
        canvas.drawText(remainingText, barRight - paint.measureText(remainingText), top + dp(116f), paint)
    }

    private fun drawPlaybackWave(canvas: Canvas, centerY: Float) {
        val now = SystemClock.elapsedRealtime()
        val barWidth = dp(2f)
        val gap = dp(2f)
        val startX = rect.right - dp(15f) - (barWidth * 5) - (gap * 4)
        val levels = floatArrayOf(5f, 10f, 7f, 12f, 6f)
        paint.color = accentColor()
        levels.forEachIndexed { index, baseHeight ->
            val height = if (isPlaying) {
                val wave = sin((now.toDouble() / 105.0) + index * 1.15).toFloat()
                dp(baseHeight + ((wave + 1f) * 4f))
            } else {
                dp(3f)
            }
            val left = startX + index * (barWidth + gap)
            canvas.drawRoundRect(
                left,
                centerY - height / 2,
                left + barWidth,
                centerY + height / 2,
                dp(2f),
                dp(2f),
                paint
            )
        }
    }

    private fun drawPlaybackButton(canvas: Canvas, centerX: Float, centerY: Float) {
        paint.color = Color.rgb(35, 36, 42)
        canvas.drawCircle(centerX, centerY, dp(17f), paint)
        paint.color = Color.WHITE
        if (isPlaying) {
            canvas.drawRoundRect(
                centerX - dp(5f), centerY - dp(7f), centerX - dp(1f), centerY + dp(7f),
                dp(1.5f), dp(1.5f), paint
            )
            canvas.drawRoundRect(
                centerX + dp(1f), centerY - dp(7f), centerX + dp(5f), centerY + dp(7f),
                dp(1.5f), dp(1.5f), paint
            )
        } else {
            iconPath.reset()
            iconPath.moveTo(centerX - dp(4f), centerY - dp(7f))
            iconPath.lineTo(centerX + dp(7f), centerY)
            iconPath.lineTo(centerX - dp(4f), centerY + dp(7f))
            iconPath.close()
            canvas.drawPath(iconPath, paint)
        }
    }

    private fun drawSkip(canvas: Canvas, centerX: Float, centerY: Float, forward: Boolean) {
        paint.color = Color.WHITE
        val direction = if (forward) 1f else -1f
        iconPath.reset()
        iconPath.moveTo(centerX - direction * dp(5f), centerY - dp(7f))
        iconPath.lineTo(centerX + direction * dp(3f), centerY)
        iconPath.lineTo(centerX - direction * dp(5f), centerY + dp(7f))
        iconPath.close()
        canvas.drawPath(iconPath, paint)
        if (forward) {
            canvas.drawRect(centerX + dp(4f), centerY - dp(7f), centerX + dp(6f), centerY + dp(7f), paint)
        } else {
            canvas.drawRect(centerX - dp(6f), centerY - dp(7f), centerX - dp(4f), centerY + dp(7f), paint)
        }
    }

    private fun drawActions(canvas: Canvas, left: Float, top: Float, accent: Int) {
        paint.color = Color.rgb(42, 43, 48)
        canvas.drawRoundRect(left, top + dp(73f), left + dp(116f), top + dp(106f), dp(18f), dp(18f), paint)
        paint.color = accent
        canvas.drawRoundRect(left + dp(126f), top + dp(73f), left + dp(242f), top + dp(106f), dp(18f), dp(18f), paint)
        paint.color = Color.WHITE
        paint.textSize = dp(10f)
        canvas.drawText("DISMISS", left + dp(32f), top + dp(94f), paint)
        canvas.drawText(primaryActionLabel.take(12), left + dp(163f), top + dp(94f), paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            performClick()
            if (!expanded) {
                if (mode != IslandMode.IDLE) expanded = true
                return true
            }

            val localY = event.y - rect.top
            val localX = event.x - rect.left
            if (localY < dp(66f)) {
                onOpen?.invoke()
                return true
            }

            if (mode == IslandMode.MUSIC) {
                when {
                    localY <= dp(94f) -> {
                        val center = rect.width() / 2f
                        when {
                            localX < center - dp(25f) -> onPreviousAction?.invoke()
                            localX > center + dp(25f) -> onNextAction?.invoke()
                            else -> onTogglePlayback?.invoke()
                        }
                    }
                    localY <= dp(110f) -> onTogglePlayback?.invoke()
                    durationMs > 0L -> {
                        val barStart = dp(15f)
                        val barWidth = rect.width() - dp(30f)
                        val fraction = ((localX - barStart) / barWidth).coerceIn(0f, 1f)
                        onSeekAction?.invoke((durationMs * fraction).toLong())
                    }
                }
            } else if (localX < rect.width() / 2f) {
                expanded = false
            } else {
                (onPrimaryAction ?: onOpen)?.invoke()
            }
            performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun animateTo(target: Float) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(progress, target).apply {
            duration = if (target == 1f) 480 else 360
            interpolator = PathInterpolator(.18f, .88f, .2f, 1f)
            addUpdateListener { progress = it.animatedValue as Float; invalidate() }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) {
                    if (!cancelled && target == 0f) onBoundsChanged?.invoke(false)
                }
            })
            start()
        }
    }

    private fun formatTime(milliseconds: Long): String {
        val seconds = (milliseconds / 1000L).coerceAtLeast(0L)
        return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
    }

    private fun accentColor() = when (mode) {
        IslandMode.MUSIC -> Color.rgb(255, 55, 95)
        IslandMode.CALL, IslandMode.CHARGING -> Color.rgb(48, 209, 88)
        IslandMode.TIMER -> Color.rgb(255, 159, 10)
        IslandMode.NAVIGATION, IslandMode.MESSAGE -> Color.rgb(10, 132, 255)
        else -> Color.DKGRAY
    }

    private fun symbol() = when (mode) {
        IslandMode.MUSIC -> "♪"
        IslandMode.CALL -> "☎"
        IslandMode.TIMER -> "◷"
        IslandMode.CHARGING -> "ϟ"
        IslandMode.NAVIGATION -> "↱"
        IslandMode.MESSAGE -> "●"
        else -> ""
    }

    private fun dp(value: Int) = dp(value.toFloat()).toInt()
    private fun dp(value: Float) = value * resources.displayMetrics.density
}
