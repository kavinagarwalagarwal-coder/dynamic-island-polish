package com.dynamicdroid.app

/**
 * Tells when the app on screen is showing a video or photo that fills the screen: a Short, a
 * Reel, a TikTok, a full-screen player or a photo viewer. These usually keep the status bar, so
 * Android's own "fullscreen" signal misses them.
 *
 * Only view class names and sizes are used, never any text or content.
 */
object ImmersiveMedia {
    /** How much of the screen a video or photo must cover to count as "full attention". */
    const val MIN_AREA = 0.62f
    const val MIN_WIDTH = 0.9f

    private val videoViews = listOf("SurfaceView", "TextureView", "VideoView", "PlayerView", "VideoSurface")
    private val photoViews = listOf("PhotoView", "ZoomableImage", "ZoomImage", "SubsamplingScaleImageView",
        "ImageViewTouch", "GestureImageView", "PhotoDraweeView", "ScaleImageView")

    /** Pure rule, unit tested: is this view a media surface big enough to deserve the screen? */
    fun isMediaSurface(className: String, areaFraction: Float, widthFraction: Float, imageApp: Boolean): Boolean {
        if (areaFraction < MIN_AREA || widthFraction < MIN_WIDTH) return false
        val name = className.substringAfterLast('.')
        if (videoViews.any { name.contains(it, ignoreCase = true) }) return true
        if (photoViews.any { name.contains(it, ignoreCase = true) }) return true
        // Plain image views only count in gallery apps; elsewhere they are often just backgrounds.
        return imageApp && name.endsWith("ImageView")
    }

    /** A parent smaller than this cannot hold a qualifying surface, so its subtree is skipped. */
    fun worthDescending(areaFraction: Float) = areaFraction >= MIN_AREA
}
