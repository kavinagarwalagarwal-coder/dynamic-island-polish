package com.dynamicdroid.app

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable

/** Midnight-water palette and type, drawn with platform primitives and no bundled brand assets. */
object NothingStyle {
    val background = Color.rgb(5, 17, 26)
    val surface = Color.rgb(12, 34, 47)
    val surfaceLight = Color.rgb(19, 54, 70)
    val foreground = Color.rgb(229, 248, 253)
    val muted = Color.rgb(137, 172, 185)
    val aqua = Color.rgb(99, 220, 244)
    val aquaDeep = Color.rgb(34, 122, 151)
    val sheen = Color.rgb(180, 245, 255)
    val red = Color.rgb(244, 100, 105)
    val line = Color.rgb(45, 91, 108)
    val mono: Typeface = Typeface.create("monospace", Typeface.NORMAL)
    val body: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)

    fun panel(color: Int = surface) = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(Color.rgb(21, 58, 74), color, Color.rgb(8, 27, 39)),
    ).apply {
        cornerRadius = 24f
        setStroke(1, line)
    }
}
