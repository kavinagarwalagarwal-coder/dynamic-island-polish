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
import android.graphics.*
import android.os.Build
import kotlin.math.ceil
import kotlin.math.min

class WallpaperLiquidGlass {
    private val backend = if (Build.VERSION.SDK_INT >= 33) runCatching { Backend() }.getOrNull() else null
    fun draw(canvas: Canvas, rect: RectF, radius: Float, alpha: Int, renderer: WallpaperSkinRenderer) {
        if (!renderer.enabled) return
        if (renderer.motion.glass && canvas.isHardwareAccelerated && backend != null) {
            backend.draw(canvas, rect, radius, alpha, renderer)
        } else renderer.draw(canvas, rect, alpha)
    }

    @TargetApi(33)
    private class Backend {
        private val node = RenderNode("Wallpaper liquid glass")
        private val shader = RuntimeShader(SHADER)
        private val effect = RenderEffect.createRuntimeShaderEffect(shader, "content")
        private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val local = RectF()
        fun draw(canvas: Canvas, rect: RectF, radius: Float, alpha: Int, renderer: WallpaperSkinRenderer) {
            val width = ceil(rect.width()).toInt().coerceAtLeast(1)
            val height = ceil(rect.height()).toInt().coerceAtLeast(1)
            local.set(0f, 0f, width.toFloat(), height.toFloat())
            node.setPosition(0, 0, width, height)
            val recording = node.beginRecording(width, height)
            recording.drawColor(Color.BLACK)
            renderer.draw(recording, local, alpha)
            node.endRecording()
            shader.setFloatUniform("size", width.toFloat(), height.toFloat())
            shader.setFloatUniform("offset", 0f, 0f)
            shader.setFloatUniform("cornerRadii", radius, radius, radius, radius)
            shader.setFloatUniform("refractionHeight", min(height * .28f, 24f).coerceAtLeast(1f))
            shader.setFloatUniform("refractionAmount", -min(height * .16f, 16f))
            shader.setFloatUniform("depthEffect", .35f)
            shader.setFloatUniform("chromaticAberration", .45f)
            node.setRenderEffect(effect)
            canvas.save()
            canvas.translate(rect.left, rect.top)
            // Preserve reveal/fade alpha even while the render node itself is opaque.
            node.alpha = alpha / 255f
            canvas.drawRenderNode(node)
            rim.strokeWidth = 1.3f
            rim.shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(),
                intArrayOf(0x70FFFFFF, 0x08FFFFFF, 0x30FFFFFF), floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP)
            rim.alpha = alpha
            val inset = rim.strokeWidth / 2
            canvas.drawRoundRect(inset, inset, width - inset, height - inset, radius, radius, rim)
            canvas.restore()
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
    return 1.0 - sqrt(1.0 - x * x);
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
    
    half4 color = half4(0.0);
    
    half4 red = content.eval(refractedCoord + dispersedCoord);
    color.r += red.r / 3.5;
    color.a += red.a / 7.0;
    
    half4 orange = content.eval(refractedCoord + dispersedCoord * (2.0 / 3.0));
    color.r += orange.r / 3.5;
    color.g += orange.g / 7.0;
    color.a += orange.a / 7.0;
    
    half4 yellow = content.eval(refractedCoord + dispersedCoord * (1.0 / 3.0));
    color.r += yellow.r / 3.5;
    color.g += yellow.g / 3.5;
    color.a += yellow.a / 7.0;
    
    half4 green = content.eval(refractedCoord);
    color.g += green.g / 3.5;
    color.a += green.a / 7.0;
    
    half4 cyan = content.eval(refractedCoord - dispersedCoord * (1.0 / 3.0));
    color.g += cyan.g / 3.5;
    color.b += cyan.b / 3.0;
    color.a += cyan.a / 7.0;
    
    half4 blue = content.eval(refractedCoord - dispersedCoord * (2.0 / 3.0));
    color.b += blue.b / 3.0;
    color.a += blue.a / 7.0;
    
    half4 purple = content.eval(refractedCoord - dispersedCoord);
    color.r += purple.r / 7.0;
    color.b += purple.b / 3.0;
    color.a += purple.a / 7.0;
    
    return color;
}"""
    }
}

