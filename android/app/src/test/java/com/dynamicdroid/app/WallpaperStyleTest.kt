package com.dynamicdroid.app

import org.junit.Assert.*
import org.junit.Test

class WallpaperStyleTest {
    @Test fun exactCoverRoundingCannotCreateAnEmptyCropRange() {
        val left = 114.45830535888672f
        val right = 245.54168701171875f
        val scaled = (right - left) / 512f * 512f
        assertTrue("Regression fixture must reproduce the old inverted range", right - scaled > left)
        val offset = WallpaperCrop.offset(left, right, scaled, scaled / 2f, 0f)
        assertTrue(offset.isFinite())
        assertEquals(left, offset, 0f)
        for (width in 1..2000) for (bitmapWidth in listOf(257, 512, 768, 1000, 1024)) {
            val start = 130.125f
            val end = start + width * .173f
            val imageWidth = (end - start) / bitmapWidth * bitmapWidth
            val x = WallpaperCrop.offset(start, end, imageWidth, imageWidth * .2f, 0f)
            assertTrue(x.isFinite())
            assertTrue(x <= start)
        }
    }
    @Test fun badStoredSettingsDoNotProduceInvalidTransforms() {
        val safe = WallpaperStyle(x = Float.NaN, y = Float.POSITIVE_INFINITY, strength = 4f).safe()
        assertEquals(.5f, safe.x, 0f)
        assertEquals(.5f, safe.y, 0f)
        assertEquals(.65f, safe.strength, 0f)
    }
}
