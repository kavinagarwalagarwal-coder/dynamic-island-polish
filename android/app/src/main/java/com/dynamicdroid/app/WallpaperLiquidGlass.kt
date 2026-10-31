/*
 * Copyright 2025 Kyant
 * Licensed under the Apache License, Version 2.0.
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, distributed AS IS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, express or implied.
 *
 * Modified for Dynamic Droid: Canvas/RenderNode adapter around AndroidLiquidGlass's
 * refraction shader. The backdrop is our imported wallpaper, never another app's screen.
 * Source: Kyant0/AndroidLiquidGlass, backdrop/internal/Shaders.kt (commit 65ab177e90e5c1d8c62e70cf7755841982da65f6).
 */
package com.dynamicdroid.app

import android.annotation.TargetApi
import android.content.Context
import android.graphics.*
import android.os.Build
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

class WallpaperLiquidGlass(private val context: Context) {
    internal val hasRuntimeShader get() = backend != null
    internal var wasHardwareRendered = false
        private set
    internal val effectBuilds get() = backend?.effectBuilds ?: 0
    internal val recordings get() = backend?.recordings ?: 0
    private var backend: Backend? = null
    private var failed = false
    fun resetFailure() { failed = false }
    fun release() { backend?.release(); backend = null }

    /** Drop cached GPU content so the next frame re-records it (after screen off, trim, etc.). */
    fun invalidateContent() { backend?.invalidate() }

    /**
     * [stage] is the fixed area the wallpaper is laid out in; [rect] is the island shape inside it.
     * The caller has already clipped [canvas] to the island shape. Keeping the wallpaper anchored
     * to the stage means it stays still while the island morphs, and the GPU layer never resizes.
     * While [resizing], the lens is left off (plain still wallpaper) and rebuilt once at rest.
     */
    fun draw(canvas: Canvas, stage: RectF, rect: RectF, radius: Float, alpha: Int, renderer: WallpaperSkinRenderer,
             resizing: Boolean = false) {
        if (!renderer.enabled || alpha <= 0 || rect.width() <= 0f || rect.height() <= 0f || stage.isEmpty) return
        if (renderer.style.glass && canvas.isHardwareAccelerated && Build.VERSION.SDK_INT >= 33 && !failed) {
            try {
                val current = backend ?: Backend().also { backend = it }
                WallpaperRecovery.markRendering(context)
                current.draw(canvas, stage, rect, radius, alpha, renderer, resizing)
                wasHardwareRendered = true
                return
            } catch (_: RuntimeException) {
                fail()
            } catch (_: LinkageError) {
                fail()
            } catch (_: OutOfMemoryError) {
                fail()
            }
        }
        renderer.draw(canvas, stage, alpha)
    }

    private fun fail() {
        failed = true; release(); WallpaperRecovery.shaderFailed(context)
    }

    @TargetApi(33)
    private class Backend {
        private val node = RenderNode("Wallpaper liquid glass")
        private val shader = RuntimeShader(SHADER)
        // Gradient is defined over a unit square once and stretched with a local matrix, so the
        // rim never allocates while the shape animates.
        private val rimMatrix = Matrix()
        private val rimGradient = LinearGradient(0f, 0f, 1f, 1f, intArrayOf(0x38FFFFFF, 0x05FFFFFF, 0x18FFFFFF),
            floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP)
        private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 1f; this.shader = rimGradient
        }
        private val local = RectF()
        private var stageWidth = 0
        private var stageHeight = 0
        private val shape = FloatArray(5) { -1f }
        private var lastImage: Bitmap? = null
        private var lastStyle: WallpaperStyle? = null
        private var stale = true
        private var lensOn = false
        var effectBuilds = 0
            private set
        var recordings = 0
            private set

        fun invalidate() { stale = true }
        fun release() {
            node.discardDisplayList(); node.setRenderEffect(null)
            lastImage = null; lastStyle = null; stale = true; lensOn = false; shape.fill(-1f)
        }

        fun draw(canvas: Canvas, stage: RectF, rect: RectF, radius: Float, alpha: Int, renderer: WallpaperSkinRenderer,
                 resizing: Boolean) {
            val width = ceil(stage.width()).toInt().coerceAtLeast(1)
            val height = ceil(stage.height()).toInt().coerceAtLeast(1)
            val resized = width != stageWidth || height != stageHeight
            // HWUI frees display lists of detached/hidden windows (screen off, memory trim). Without
            // the hasDisplayList() check the island drew an empty node — just black — until a tap
            // happened to resize it.
            if (resized || stale || !node.hasDisplayList() || renderer.image !== lastImage || renderer.style != lastStyle) {
                local.set(0f, 0f, width.toFloat(), height.toFloat())
                node.setPosition(0, 0, width, height)
                val recording = node.beginRecording(width, height)
                try {
                    recording.drawColor(Color.BLACK)
                    // Opacity belongs to the node, so fades do not rebuild the image recording.
                    renderer.draw(recording, local, 255)
                } finally { node.endRecording() }
                recordings++
                lastImage = renderer.image; lastStyle = renderer.style; stale = false
                stageWidth = width; stageHeight = height
            }
            // Shape in node space, quantised to half pixels so sub-pixel spring noise near rest
            // does not rebuild the effect.
            val l = q(rect.left - stage.left); val t = q(rect.top - stage.top)
            val w = q(rect.width()).coerceAtLeast(1f); val h = q(rect.height()).coerceAtLeast(1f)
            val r = q(radius).coerceIn(0f, min(w, h) / 2f)
            if (resizing) {
                // A lens built for one frame's shape is wrong on the next; rebuilding it every
                // frame churned GPU layers. Show the still wallpaper and restore the lens at rest.
                if (lensOn) { node.setRenderEffect(null); lensOn = false; shape.fill(-1f) }
            } else if (resized || !lensOn || shape[0] != l || shape[1] != t || shape[2] != w || shape[3] != h || shape[4] != r) {
                shader.setFloatUniform("size", w, h)
                shader.setFloatUniform("offset", -l, -t)
                shader.setFloatUniform("cornerRadii", r, r, r, r)
                shader.setFloatUniform("refractionHeight", min(h * .28f, 24f).coerceAtLeast(1f))
                shader.setFloatUniform("refractionAmount", -min(h * .16f, 16f))
                shader.setFloatUniform("depthEffect", .35f)
                shader.setFloatUniform("chromaticAberration", .2f)
                // The effect snapshots uniforms, so it is recreated; the layer size stays fixed.
                node.setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader, "content"))
                lensOn = true
                effectBuilds++
                shape[0] = l; shape[1] = t; shape[2] = w; shape[3] = h; shape[4] = r
            }
            val save = canvas.save()
            try {
                canvas.translate(stage.left, stage.top)
                node.alpha = alpha / 255f
                canvas.drawRenderNode(node)
            } finally { canvas.restoreToCount(save) }
            // Thin highlight just inside the clip; it fades with the body instead of flashing white.
            val inset = rim.strokeWidth / 2f
            rimMatrix.setScale(rect.width(), rect.height()); rimMatrix.postTranslate(rect.left, rect.top)
            rimGradient.setLocalMatrix(rimMatrix)
            rim.alpha = alpha
            canvas.drawRoundRect(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset,
                max(0f, radius - inset), max(0f, radius - inset), rim)
        }

        private fun q(value: Float) = round(value * 2f) / 2f
    }

    private companion object {
        const val SHADER = """
uniform shader content;

uniform float2 size;
uniform float2 offset;
uniform float4 cornerRadii;
uniform float refractionHeight;
uniform float refractionAmount;
uniform float depthEffect;
uniform float chromaticAberration;

float2 safeNormalize(float2 value) {
    return value * inversesqrt(max(dot(value, value), 0.0001));
}

float radiusAt(float2 coord, float4 radii) {
    if (coord.x >= 0.0) {
        if (coord.y <= 0.0) return radii.y;
        else return radii.z;
    } else {
        if (coord.y <= 0.0) return radii.x;
        else return radii.w;
    }
}

float sdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    float outside = length(max(cornerCoord, 0.0)) - radius;
    float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);
    return outside + inside;
}

float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {
        return sign(coord) * safeNormalize(max(cornerCoord, 0.0));
    } else {
        float gradX = step(cornerCoord.y, cornerCoord.x);
        return sign(coord) * float2(gradX, 1.0 - gradX);
    }
}

float circleMap(float x) {
    x = clamp(x, 0.0, 1.0);
    return 1.0 - sqrt(max(0.0, 1.0 - x * x));
}

half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 centeredCoord = (coord + offset) - halfSize;
    // Modified from upstream: corner lookup uses the shape-centred coordinate.
    float radius = radiusAt(centeredCoord, cornerRadii);

    float sd = sdRoundedRect(centeredCoord, halfSize, radius);
    // Outside the island (clipped by the caller) or in the flat centre: no refraction needed.
    if (sd > 0.5 || -sd >= refractionHeight) {
        return content.eval(coord);
    }
    sd = min(sd, 0.0);

    float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;
    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 grad = safeNormalize(gradSdRoundedRect(centeredCoord, halfSize, gradRadius) + depthEffect * safeNormalize(centeredCoord));
    
    float2 refractedCoord = coord + d * grad;
    float dispersionIntensity = chromaticAberration * ((centeredCoord.x * centeredCoord.y) / (halfSize.x * halfSize.y));
    float2 dispersedCoord = d * grad * dispersionIntensity;

    // Modified from upstream: three taps instead of seven spectral taps on mobile GPUs.
    half4 color = content.eval(refractedCoord);
    color.r = content.eval(refractedCoord + dispersedCoord).r;
    color.b = content.eval(refractedCoord - dispersedCoord).b;

    return color;
}"""
    }
}
