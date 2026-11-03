package com.dynamicdroid.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImmersiveMediaTest {
    @Test fun fullScreenVideoSurfacesCount() {
        assertTrue(ImmersiveMedia.isMediaSurface("android.view.TextureView", 0.95f, 1f, false))
        assertTrue(ImmersiveMedia.isMediaSurface("android.view.SurfaceView", 0.8f, 1f, false))
        assertTrue(ImmersiveMedia.isMediaSurface("com.google.android.exoplayer2.ui.StyledPlayerView", 0.9f, 1f, false))
    }

    @Test fun inlineVideosInAFeedDoNot() {
        // A 16:9 player at the top of a portrait screen, or a feed card.
        assertFalse(ImmersiveMedia.isMediaSurface("android.view.TextureView", 0.3f, 1f, false))
        assertFalse(ImmersiveMedia.isMediaSurface("android.view.SurfaceView", 0.7f, 0.6f, false))
    }

    @Test fun photoViewersCountButPlainBackgroundsDoNot() {
        assertTrue(ImmersiveMedia.isMediaSurface("com.github.chrisbanes.photoview.PhotoView", 0.9f, 1f, false))
        assertTrue(ImmersiveMedia.isMediaSurface("android.widget.ImageView", 0.9f, 1f, imageApp = true))
        assertFalse(ImmersiveMedia.isMediaSurface("android.widget.ImageView", 0.9f, 1f, imageApp = false))
        assertFalse(ImmersiveMedia.isMediaSurface("android.widget.FrameLayout", 1f, 1f, imageApp = true))
    }

    @Test fun smallContainersAreSkipped() {
        assertFalse(ImmersiveMedia.worthDescending(0.2f))
        assertTrue(ImmersiveMedia.worthDescending(0.9f))
    }
}
