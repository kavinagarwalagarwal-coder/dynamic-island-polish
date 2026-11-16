package com.dynamicdroid.app

import android.annotation.TargetApi
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import kotlin.math.roundToInt

/**
 * Live frosted glass behind the open island card, done the way iOS does it: Android's own
 * compositor blurs whatever is on screen behind a small window, every frame, at almost no cost,
 * and nothing is ever captured or copied into the app. (Android 12+, window blurs.)
 *
 * The window sits exactly under the settled card, shaped by a rounded background drawable, and
 * one layer below the island (an app overlay under the island's accessibility overlay), so the
 * island draws its content over the blur. Android may switch blurs off (battery saver, some video,
 * weaker GPUs); [available] says when it can't, and the island then stays solid.
 */
@TargetApi(31)
class GlassBackdrop(private val context: Context) {
    private var dialog: Dialog? = null
    private val shape = GradientDrawable().apply { setColor(Color.argb(20, 0, 0, 0)) }
    private val wm = context.getSystemService(WindowManager::class.java)
    private var shown = false
    private var blurListener: java.util.function.Consumer<Boolean>? = null

    /** Called with false when Android turns window blurs off, so the island can go solid. */
    var onAvailability: ((Boolean) -> Unit)? = null

    fun available(): Boolean = Build.VERSION.SDK_INT >= 31 && Settings.canDrawOverlays(context) &&
        runCatching { wm?.isCrossWindowBlurEnabled == true }.getOrDefault(false)

    /** Places the blur under [card] (screen pixels) with the card's [corner] radius. */
    fun show(card: RectF, corner: Float) {
        if (!available() || card.width() < 2f || card.height() < 2f) { hide(); return }
        val d = dialog ?: create() ?: return
        val window = d.window ?: return
        shape.cornerRadius = corner
        window.setBackgroundDrawable(shape)
        window.setBackgroundBlurRadius((BLUR_DP * context.resources.displayMetrics.density).roundToInt())
        val lp = window.attributes
        lp.x = card.left.roundToInt(); lp.y = card.top.roundToInt()
        lp.width = card.width().roundToInt(); lp.height = card.height().roundToInt()
        window.attributes = lp
        if (!shown) runCatching { d.show(); shown = true }.onFailure { hide() }
    }

    fun hide() {
        if (shown) runCatching { dialog?.hide() }
        shown = false
    }

    fun release() {
        hide()
        blurListener?.let { l -> runCatching { wm?.removeCrossWindowBlurEnabledListener(l) } }
        blurListener = null
        runCatching { dialog?.dismiss() }
        dialog = null
    }

    private fun create(): Dialog? = runCatching {
        Dialog(context, android.R.style.Theme_Translucent_NoTitleBar).apply {
            setContentView(View(context))
            setCancelable(false)
            window?.apply {
                setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
                setGravity(Gravity.TOP or Gravity.START)
                setWindowAnimations(0)
                attributes = attributes.apply {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    title = "Dynamic Droid glass"
                }
            }
        }.also { d ->
            dialog = d
            val l = java.util.function.Consumer<Boolean> { on -> if (!on) hide(); onAvailability?.invoke(on) }
            runCatching { wm?.addCrossWindowBlurEnabledListener(l) }
            blurListener = l
        }
    }.getOrNull()

    companion object {
        const val BLUR_DP = 28f
    }
}
