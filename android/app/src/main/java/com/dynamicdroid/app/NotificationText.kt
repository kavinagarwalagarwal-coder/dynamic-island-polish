package com.dynamicdroid.app

import android.app.Notification
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView

/**
 * The words in a notification's own custom layout (score apps draw their scoreboard as a custom
 * view with no title or text). The layout is built off screen from the notification itself;
 * nothing on the screen is read.
 */
object NotificationText {
    @Suppress("DEPRECATION")
    fun read(context: Context, n: Notification): List<String> {
        val out = ArrayList<String>()
        for (layout in listOfNotNull(n.bigContentView, n.contentView).distinct()) {
            val root = runCatching { layout.apply(context, FrameLayout(context)) }.getOrNull() ?: continue
            var visited = 0
            fun visit(view: View, depth: Int) {
                if (++visited > 256 || depth > 16 || view.visibility != View.VISIBLE) return
                if (view is TextView) view.text?.toString()?.trim()?.takeIf { it.isNotEmpty() && it !in out }?.let(out::add)
                if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i), depth + 1)
            }
            visit(root, 0)
            if (out.isNotEmpty()) break
        }
        return out
    }
}
