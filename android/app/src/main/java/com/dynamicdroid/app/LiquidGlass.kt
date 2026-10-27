package com.dynamicdroid.app

import android.graphics.*
import android.os.Build
import androidx.annotation.RequiresApi

/** Samples ONLY captured background pixels. Text and controls are drawn afterwards, unwarped. */
@RequiresApi(33)
class LiquidGlass {
    private val shader = RuntimeShader(PROGRAM)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var source: Bitmap? = null
    private var bounds = Rect()

    fun clear() {
        // Do not recycle a bitmap that may still be referenced by a RenderThread display list.
        source = null
        paint.shader = null
    }

    fun update(bitmap: Bitmap, screenBounds: Rect) {
        source = bitmap
        bounds = Rect(screenBounds)
        shader.setInputShader("backdrop", BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
        paint.shader = shader
    }

    fun draw(canvas: Canvas, rect: RectF, radius: Float, scale: Float,
             screenX: Int, screenY: Int, tint: Int, tintAmount: Float, alpha: Int): Boolean {
        if (source == null || !canvas.isHardwareAccelerated) return false
        val screenRect = Rect((rect.left * scale + screenX).toInt(), (rect.top * scale + screenY).toInt(),
            kotlin.math.ceil((rect.right * scale + screenX).toDouble()).toInt(),
            kotlin.math.ceil((rect.bottom * scale + screenY).toDouble()).toInt())
        if (!Rect.intersects(bounds, screenRect)) return false
        shader.setFloatUniform("center", rect.centerX(), rect.centerY())
        shader.setFloatUniform("halfSize", rect.width() / 2f, rect.height() / 2f)
        shader.setFloatUniform("radius", radius)
        shader.setFloatUniform("pixelScale", scale)
        shader.setFloatUniform("origin", (screenX - bounds.left).toFloat(), (screenY - bounds.top).toFloat())
        shader.setFloatUniform("tint", Color.red(tint) / 255f, Color.green(tint) / 255f, Color.blue(tint) / 255f)
        shader.setFloatUniform("tintAmount", tintAmount)
        paint.alpha = alpha
        // Refract only pixels actually covered by the captured app. Keep the material under
        // the status-bar portion instead of clamping/smearing the app's top row across it.
        canvas.save()
        canvas.clipRect((bounds.left - screenX) / scale, (bounds.top - screenY) / scale,
            (bounds.right - screenX) / scale, (bounds.bottom - screenY) / scale)
        canvas.drawRoundRect(rect, radius, radius, paint)
        canvas.restore()
        return true
    }

    companion object {
        // Rounded-rectangle signed distance gives the actual lens-edge normal, including corners.
        // Lens displacement peaks at the bevel and fades to zero through the clear centre.
        private const val PROGRAM = """
            uniform shader backdrop;
            uniform float2 center;
            uniform float2 halfSize;
            uniform float radius;
            uniform float pixelScale;
            uniform float2 origin;
            uniform float3 tint;
            uniform float tintAmount;
            half4 main(float2 p) {
                float2 local = p - center;
                float2 q = abs(local) - (halfSize - radius);
                float2 outside = max(q, float2(0));
                float distance = length(outside) + min(max(q.x, q.y), 0.0) - radius;
                float2 normal = length(outside) > 0.001
                    ? normalize(outside) * sign(local)
                    : (q.x > q.y ? float2(sign(local.x), 0) : float2(0, sign(local.y)));
                float bevel = min(radius, 18.0);
                float edge = 1.0 - smoothstep(0.0, bevel, -distance);
                float2 bend = -normal * edge * edge * bevel * 0.65;
                float2 uv = (p + bend) * pixelScale + origin;
                float2 fringe = normal * edge * 0.65 * pixelScale;
                half4 c = backdrop.eval(uv);
                c.r = backdrop.eval(uv + fringe).r;
                c.b = backdrop.eval(uv - fringe).b;
                float3 color = mix(float3(c.rgb), tint, tintAmount);
                color *= 0.66;
                float light = max(0.0, dot(normal, normalize(float2(-0.5, -1.0))));
                color += light * pow(edge, 4.0) * 0.22;
                return half4(half3(color), 1.0);
            }
        """
    }
}
