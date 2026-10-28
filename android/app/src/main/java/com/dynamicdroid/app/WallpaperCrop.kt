package com.dynamicdroid.app

import kotlin.math.min

/** Float rounding can make end - scaledSize exceed start even when an image exactly covers it. */
object WallpaperCrop {
    fun offset(start: Float, end: Float, scaledSize: Float, focus: Float, drift: Float): Float {
        val desired = (start + end) * .5f - focus + drift
        return desired.coerceIn(min(end - scaledSize, start), start)
    }
}
