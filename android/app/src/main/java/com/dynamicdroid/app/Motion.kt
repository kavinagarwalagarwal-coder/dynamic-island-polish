package com.dynamicdroid.app

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator

/** Small, shared UI motion: springy presses and staggered entrances that respect reduced motion. */
object Motion {
    private val settle = OvershootInterpolator(2.2f)
    private val glide = DecelerateInterpolator(2f)

    /** Instrumented test runs set this so screenshots are never taken mid-flourish. */
    val underTest: Boolean get() = System.getProperty(TEST_PROPERTY) == "true"
    const val TEST_PROPERTY = "dynamicdroid.test"

    fun reduced(context: Context): Boolean = underTest || runCatching {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }.getOrDefault(false)

    /** Squish on touch, spring back on release. Never consumes the touch. */
    @SuppressLint("ClickableViewAccessibility")
    fun springPress(view: View, pressed: Float = .96f) {
        view.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(pressed).scaleY(pressed).setDuration(90).setInterpolator(glide).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(settle).start()
            }
            false
        }
    }

    /** Children rise and fade in one after another. */
    fun staggerIn(views: List<View>, context: Context, startDelay: Long = 0L, step: Long = 35L, rise: Float = 14f) {
        if (reduced(context)) { views.forEach { it.alpha = 1f; it.translationY = 0f }; return }
        val density = context.resources.displayMetrics.density
        views.forEachIndexed { index, view ->
            view.animate().cancel()
            view.alpha = 0f
            view.translationY = rise * density
            view.animate().alpha(1f).translationY(0f)
                .setStartDelay(startDelay + index * step).setDuration(340).setInterpolator(glide).start()
        }
    }
}
