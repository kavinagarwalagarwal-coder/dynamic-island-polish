package com.dynamicdroid.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max

/**
 * Liquid glass for the app's own controls, after HyperOS 4 and iOS 26: at rest a knob is a solid
 * white bead; touched, it swells into a clear glass lens (a bright rim, a specular streak, a
 * darker thick edge) that lets the track show through, stretches along the way it is moving and
 * settles back into a bead on a spring. Everything is drawn in the draw phase from spring
 * values, so a moving knob never recomposes or re-lays out anything.
 */
internal fun DrawScope.drawGlassBead(center: Offset, size: Size, lift: Float) {
    val l = lift.coerceIn(0f, 1f)
    val topLeft = Offset(center.x - size.width / 2f, center.y - size.height / 2f)
    val corner = CornerRadius(size.height / 2f)
    // Body: an opaque bead at rest, nearly clear glass when lifted.
    drawRoundRect(
        Brush.verticalGradient(
            listOf(Color.White.copy(alpha = mix(.96f, .20f, l)), Color.White.copy(alpha = mix(.86f, .08f, l))),
            startY = topLeft.y, endY = topLeft.y + size.height),
        topLeft, size, corner)
    // Thickness: glass darkens toward its edge where light bends away.
    if (l > .01f) drawRoundRect(
        Brush.radialGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .30f * l)),
            center, radius = max(size.width, size.height) * .62f),
        topLeft, size, corner)
    // Rim light, brightest top-left and bottom-right like a lit lens.
    drawRoundRect(
        Brush.linearGradient(
            listOf(Color.White.copy(alpha = .95f), Color.White.copy(alpha = .12f + .1f * (1f - l)), Color.White.copy(alpha = .65f)),
            start = topLeft, end = Offset(topLeft.x + size.width, topLeft.y + size.height)),
        topLeft, size, corner, style = Stroke(mix(.6f, 1.3f, l).dp.toPx()))
    // Specular streak across the top.
    val hw = size.width * .58f
    val hh = size.height * .24f
    val top = topLeft.y + size.height * .1f
    drawRoundRect(
        Brush.verticalGradient(listOf(Color.White.copy(alpha = .35f + .5f * l), Color.Transparent), startY = top, endY = top + hh),
        Offset(center.x - hw / 2f, top), Size(hw, hh), CornerRadius(hh / 2f))
}

private fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t

/**
 * A clear glass pill on a dark page (buttons, unselected chips): a faint body, a lit rim and a
 * specular streak, all brightening as [lift] rises while it is touched.
 */
internal fun DrawScope.drawGlassPill(lift: Float, corner: Float) {
    val l = lift.coerceIn(0f, 1f)
    val r = CornerRadius(corner)
    drawRoundRect(
        Brush.verticalGradient(listOf(Color.White.copy(alpha = .10f + .10f * l), Color.White.copy(alpha = .03f + .05f * l))),
        cornerRadius = r)
    val stroke = mix(.8f, 1.3f, l).dp.toPx()
    drawRoundRect(
        Brush.linearGradient(listOf(Color.White.copy(alpha = .55f + .35f * l), Color.White.copy(alpha = .06f),
            Color.White.copy(alpha = .30f + .30f * l)), start = Offset.Zero, end = Offset(size.width, size.height)),
        topLeft = Offset(stroke / 2f, stroke / 2f), size = Size(size.width - stroke, size.height - stroke),
        cornerRadius = CornerRadius(corner - stroke / 2f), style = Stroke(stroke))
    val hw = size.width * .7f
    val hh = size.height * .28f
    val top = size.height * .08f
    drawRoundRect(
        Brush.verticalGradient(listOf(Color.White.copy(alpha = .10f + .22f * l), Color.Transparent), startY = top, endY = top + hh),
        topLeft = Offset((size.width - hw) / 2f, top), size = Size(hw, hh), cornerRadius = CornerRadius(hh / 2f))
}

/**
 * The switch: the track fills with colour as the bead travels, the bead swells to glass while
 * the row is held and stretches with its speed. Purely visual: the row carries the semantics.
 */
@Composable
fun LiquidSwitch(checked: Boolean, enabled: Boolean, source: MutableInteractionSource) {
    val pressed by source.collectIsPressedAsState()
    val travel = remember { Animatable(if (checked) 1f else 0f) }
    // Retargets mid-flight with its velocity kept, so quick taps never snap.
    LaunchedEffect(checked) { travel.animateTo(if (checked) 1f else 0f, Fluid.expand()) }
    val lift = animateFloatAsState(if (pressed && enabled) 1f else 0f, Fluid.collapse(), label = "lift")
    val on = Fluid.red
    val off = Fluid.surface
    val line = Fluid.line
    Canvas(Modifier.size(58.dp, 36.dp)) {
        val dim = if (enabled) 1f else .4f
        val p = travel.value
        val trackH = 30.dp.toPx()
        val trackTop = (size.height - trackH) / 2f
        val r = trackH / 2f
        drawRoundRect(lerp(off, on, p.coerceIn(0f, 1f)).copy(alpha = dim), Offset(0f, trackTop), Size(size.width, trackH), CornerRadius(r))
        drawRoundRect(line.copy(alpha = (1f - p.coerceIn(0f, 1f)) * dim), Offset(0f, trackTop), Size(size.width, trackH),
            CornerRadius(r), style = Stroke(1.dp.toPx()))
        val bead = 24.dp.toPx()
        val pad = (trackH - bead) / 2f
        val x = pad + bead / 2f + (size.width - pad * 2f - bead) * p
        // Speed stretches the bead along its path and thins it a little, like a drop in motion.
        val stretch = (abs(travel.velocity) * .055f).coerceAtMost(.38f)
        val l = lift.value
        val w = bead * (1f + .5f * l) * (1f + stretch)
        val h = bead * (1f + .18f * l) * (1f - stretch * .35f)
        drawGlassBead(Offset(x, size.height / 2f), Size(w, h), l)
    }
}

/** The slider's knob: a bead that swells to glass while pressed or dragged. */
@Composable
fun LiquidSliderThumb(source: MutableInteractionSource) {
    val pressed by source.collectIsPressedAsState()
    val dragged by source.collectIsDraggedAsState()
    val lift = animateFloatAsState(if (pressed || dragged) 1f else 0f,
        if (pressed || dragged) Fluid.expand() else Fluid.collapse(), label = "thumbLift")
    Canvas(Modifier.size(26.dp, 30.dp)) {
        val l = lift.value
        val bead = 22.dp.toPx()
        drawGlassBead(Offset(size.width / 2f, size.height / 2f), Size(bead * (1f + .75f * l), bead * (1f + .3f * l)), l)
    }
}

/** The slider's track: a soft groove that fills with colour up to the knob. */
@Composable
fun LiquidSliderTrack(fraction: Float) {
    val on = Fluid.red
    val off = Fluid.surface
    val line = Fluid.line
    Canvas(Modifier.fillMaxWidth().height(8.dp)) {
        val r = CornerRadius(size.height / 2f)
        drawRoundRect(off, cornerRadius = r)
        drawRoundRect(line, cornerRadius = r, style = Stroke(1.dp.toPx()))
        val w = size.width * fraction.coerceIn(0f, 1f)
        if (w > 0f) drawRoundRect(on, size = Size(max(w, size.height), size.height), cornerRadius = r)
    }
}
