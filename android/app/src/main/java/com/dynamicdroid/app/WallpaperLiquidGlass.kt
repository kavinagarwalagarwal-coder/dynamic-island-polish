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
import kotlin.math.min

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
    fun draw(canvas: Canvas, rect: RectF, radius: Float, alpha: Int, renderer: WallpaperSkinRenderer) {
        if (!renderer.enabled || alpha <= 0 || rect.width() <= 0f || rect.height() <= 0f) return
        if (renderer.style.glass && canvas.isHardwareAccelerated && Build.VERSION.SDK_INT >= 33 && !failed) {
            try {
                val current = backend ?: Backend().also { backend = it }
                WallpaperRecovery.markRendering(context)
                current.draw(canvas, rect, radius, alpha, renderer)
                wasHardwareRendered = true
                return
            } catch (_: RuntimeException) {
                failed = true; release(); WallpaperRecovery.shaderFailed(context)
            } catch (_: LinkageError) {
                failed = true; release(); WallpaperRecovery.shaderFailed(context)
            } catch (_: OutOfMemoryError) {
                failed = true; release(); WallpaperRecovery.shaderFailed(context)
            }
        }
        renderer.draw(canvas, rect, alpha)
    }

    @TargetApi(33)
    private class Backend {
        private val node = RenderNode("Wallpaper liquid glass")
        private val shader = RuntimeShader(SHADER)
        private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.3f }
        private val local = RectF()
        private val colours = intArrayOf(0x70FFFFFF, 0x08FFFFFF, 0x30FFFFFF)
        private val stops = floatArrayOf(0f, .55f, 1f)
        private var lastWidth = 0
        private var lastHeight = 0
        private var lastRadius = -1f
        private var lastImage: Bitmap? = null
        private var lastStyle: WallpaperStyle? = null
        var effectBuilds = 0
            private set
        var recordings = 0
            private set
        fun release() { node.discardDisplayList(); node.setRenderEffect(null); lastImage = null; lastStyle = null }
        fun draw(canvas: Canvas, rect: RectF, radius: Float, alpha: Int, renderer: WallpaperSkinRenderer) {
            val width = ceil(rect.width()).toInt().coerceAtLeast(1)
            val height = ceil(rect.height()).toInt().coerceAtLeast(1)
            val r = (kotlin.math.round(radius * 4f) / 4f).coerceIn(0f, min(width, height) / 2f)
            val resized = width != lastWidth || height != lastHeight
            if (resized || renderer.image !== lastImage || renderer.style != lastStyle) {
                local.set(0f, 0f, width.toFloat(), height.toFloat())
                node.setPosition(0, 0, width, height)
                val recording = node.beginRecording(width, height)
                try {
                    recording.drawColor(Color.BLACK)
                    // Opacity belongs to the node, so fades do not rebuild the image recording.
                    renderer.draw(recording, local, 255)
                } finally { node.endRecording() }
                recordings++
                lastImage = renderer.image; lastStyle = renderer.style
            }
            if (resized || r != lastRadius) {
                shader.setFloatUniform("size", width.toFloat(), height.toFloat())
                shader.setFloatUniform("offset", 0f, 0f)
                shader.setFloatUniform("cornerRadii", r, r, r, r)
                shader.setFloatUniform("refractionHeight", min(height * .28f, 24f).coerceAtLeast(1f))
                shader.setFloatUniform("refractionAmount", -min(height * .16f, 16f))
                shader.setFloatUniform("depthEffect", .35f)
                shader.setFloatUniform("chromaticAberration", .25f)
                node.setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader, "content"))
                effectBuilds++
            }
            if (resized) rim.shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(), colours, stops, Shader.TileMode.CLAMP)
            lastWidth = width; lastHeight = height; lastRadius = r
            val save = canvas.save()
            try {
                canvas.translate(rect.left, rect.top)
                node.alpha = alpha / 255f
                canvas.drawRenderNode(node)
                rim.alpha = alpha
                val inset = rim.strokeWidth / 2
                canvas.drawRoundRect(inset, inset, width - inset, height - inset, r, r, rim)
            } finally { canvas.restoreToCount(save) }
        }
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
        return sign(coord) * normalize(max(cornerCoord, 0.0));
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
    float radius = radiusAt(coord, cornerRadii);
    
    float sd = sdRoundedRect(centeredCoord, halfSize, radius);
    if (-sd >= refractionHeight) {
        return content.eval(coord);
    }
    sd = min(sd, 0.0);
    
    float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;
    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 grad = normalize(gradSdRoundedRect(centeredCoord, halfSize, gradRadius) + depthEffect * normalize(centeredCoord));
    
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

