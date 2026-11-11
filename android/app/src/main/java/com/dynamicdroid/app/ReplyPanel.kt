package com.dynamicdroid.app

import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A small focusable window under the expanded message card, so the keyboard can open without
 * making the island itself focusable. Text goes back through the app's own reply action.
 */
class ReplyPanel(
    private val context: Context,
    private val onSent: (String) -> Unit,
    private val onClosed: () -> Unit,
) {
    private var root: View? = null
    private var manager: WindowManager? = null

    val isShowing get() = root != null

    fun show(host: Context, accessibilityHost: Boolean, topPx: Int, reply: ReplyAction) {
        dismiss(notify = false)
        val density = context.resources.displayMetrics.density
        fun dp(v: Float) = (v * density).toInt()
        // App overlays accept the keyboard everywhere; fall back to the accessibility overlay.
        val useAppOverlay = Settings.canDrawOverlays(context) || !accessibilityHost
        val windowContext = if (useAppOverlay) context else host
        val input = EditText(windowContext).apply {
            hint = reply.remoteInput.label?.takeIf { it.isNotBlank() } ?: context.getString(R.string.island_reply_hint)
            setTextColor(NothingStyle.foreground)
            setHintTextColor(NothingStyle.muted)
            background = null
            textSize = 16f
            maxLines = 4
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = EditorInfo.IME_ACTION_SEND
            setPadding(dp(18f), dp(12f), dp(8f), dp(12f))
        }
        val send = TextView(windowContext).apply {
            text = context.getString(R.string.island_send)
            setTextColor(NothingStyle.background)
            textSize = 15f
            gravity = Gravity.CENTER
            minHeight = dp(44f); minWidth = dp(72f)
            setPadding(dp(16f), 0, dp(16f), 0)
            background = GradientDrawable().apply { setColor(NothingStyle.foreground); cornerRadius = dp(22f).toFloat() }
            contentDescription = context.getString(R.string.island_send)
            isClickable = true; isFocusable = true
        }
        fun submit() {
            val text = input.text?.toString()?.trim().orEmpty()
            if (text.isEmpty()) return
            if (sendReply(reply, text)) { onSent(text); dismiss(notify = false) }
        }
        send.setOnClickListener { submit() }
        input.setOnEditorActionListener { _, id, event ->
            if (id == EditorInfo.IME_ACTION_SEND || event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN) { submit(); true } else false
        }
        val panel = object : LinearLayout(windowContext) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) { dismiss(); return true }
                return super.dispatchKeyEvent(event)
            }
            // Android 16+ apps built for it get predictive back: the back key no longer reaches
            // views, so listen through the window's back dispatcher as well.
            private var backCallback: Any? = null
            override fun onAttachedToWindow() {
                super.onAttachedToWindow()
                if (Build.VERSION.SDK_INT >= 33) {
                    val cb = android.window.OnBackInvokedCallback { dismiss() }
                    findOnBackInvokedDispatcher()?.registerOnBackInvokedCallback(
                        android.window.OnBackInvokedDispatcher.PRIORITY_OVERLAY, cb)
                    backCallback = cb
                }
            }
            override fun onDetachedFromWindow() {
                if (Build.VERSION.SDK_INT >= 33) (backCallback as? android.window.OnBackInvokedCallback)?.let {
                    runCatching { findOnBackInvokedDispatcher()?.unregisterOnBackInvokedCallback(it) }
                }
                backCallback = null
                super.onDetachedFromWindow()
            }
        }.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4f), dp(6f), dp(4f))
            background = GradientDrawable().apply { setColor(android.graphics.Color.BLACK); cornerRadius = dp(28f).toFloat() }
            addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(send, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) { dismiss(); true } else false }
        }
        val type = if (useAppOverlay) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        val width = context.resources.displayMetrics.widthPixels - dp(16f)
        val lp = WindowManager.LayoutParams(width, WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = topPx + dp(8f)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            if (Build.VERSION.SDK_INT >= 30) { setFitInsetsTypes(0); setFitInsetsSides(0) }
        }
        val wm = windowContext.getSystemService(WindowManager::class.java) ?: return
        if (runCatching { wm.addView(panel, lp) }.isFailure) return
        root = panel; manager = wm
        input.requestFocus()
        input.postDelayed({
            windowContext.getSystemService(InputMethodManager::class.java)?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }, 120L)
    }

    fun dismiss(notify: Boolean = true) {
        val view = root ?: return
        root = null
        runCatching { manager?.removeView(view) }
        manager = null
        if (notify) onClosed()
    }

    private fun sendReply(reply: ReplyAction, text: String): Boolean = try {
        val results = Bundle().apply { putCharSequence(reply.remoteInput.resultKey, text) }
        val fill = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        RemoteInput.addResultsToIntent(arrayOf(reply.remoteInput), fill, results)
        reply.intent.send(context, 0, fill)
        true
    } catch (_: PendingIntent.CanceledException) { false } catch (_: RuntimeException) { false }
}
