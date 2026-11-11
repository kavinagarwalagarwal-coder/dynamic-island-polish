package com.dynamicdroid.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Colours, type and the springs every settings control moves on. */
object Fluid {
    val background = Color(NothingStyle.background)
    val surface = Color(NothingStyle.surface)
    val foreground = Color(NothingStyle.foreground)
    val muted = Color(NothingStyle.muted)
    val red = Color(NothingStyle.red)
    val line = Color(NothingStyle.line)
    val good = Color(0xFF9BE4BE)
    val body = FontFamily(NothingStyle.body)
    val mono = FontFamily(NothingStyle.mono)

    /** Lively but settled: a press, a selection, a toggle. */
    fun <T> snappy() = spring<T>(dampingRatio = 0.62f, stiffness = 520f)
    /** Soft and liquid: pages, sections and content flowing in. */
    fun <T> liquid() = spring<T>(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)

    val pill = RoundedCornerShape(18.dp)
}

/** Squashes a little under the finger and springs back with a slight overshoot when let go. */
fun Modifier.pressSpring(source: MutableInteractionSource, pressed: Float = 0.955f): Modifier = composed {
    val down by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (down) pressed else 1f, Fluid.snappy(), label = "press")
    graphicsLayer { scaleX = scale; scaleY = scale }
}

/**
 * Flows in: rises a few dp, unblurs from a slight squash and fades up, [index] steps after the
 * first item, every time [key] changes (a new page). Off under tests and reduced motion.
 */
fun Modifier.riseIn(index: Int, key: Any?, enabled: Boolean): Modifier = composed {
    val progress = remember(key) { Animatable(if (enabled) 0f else 1f) }
    LaunchedEffect(key, enabled) {
        if (!enabled) { progress.snapTo(1f); return@LaunchedEffect }
        delay(index.coerceAtMost(12) * 26L)
        progress.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = 300f))
    }
    graphicsLayer {
        val p = progress.value
        alpha = p.coerceIn(0f, 1f)
        translationY = (1f - p) * 18.dp.toPx()
        scaleY = 0.96f + 0.04f * p
        scaleX = 0.985f + 0.015f * p
    }
}

@Composable
fun Heading(text: String, modifier: Modifier = Modifier, top: androidx.compose.ui.unit.Dp = 22.dp) = Text(
    text, modifier.fillMaxWidth().padding(top = top).semantics { heading() },
    style = TextStyle(fontFamily = Fluid.mono, fontSize = 14.sp, color = Fluid.foreground, letterSpacing = 1.sp),
)

@Composable
fun Note(text: String, modifier: Modifier = Modifier, size: Int = 13, color: Color = Fluid.muted) = Text(
    text, modifier.fillMaxWidth().padding(top = 6.dp),
    style = TextStyle(fontFamily = Fluid.body, fontSize = size.sp, color = color, lineHeight = (size * 1.35f).sp),
)

@Composable
fun Label(text: String, modifier: Modifier = Modifier) = Text(
    text, modifier.fillMaxWidth().padding(top = 16.dp),
    style = TextStyle(fontFamily = Fluid.body, fontSize = 16.sp, color = Fluid.foreground),
)

/** A labelled switch whose whole row is the touch target, with an optional note under it. */
@Composable
fun SettingSwitch(title: String, checked: Boolean, note: String? = null, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val source = remember { MutableInteractionSource() }
    Column(Modifier.fillMaxWidth().pressSpring(source, 0.985f)) {
        Row(
            Modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp)
                .toggleable(checked, source, null, enabled, Role.Switch, onChange),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, Modifier.weight(1f).padding(end = 12.dp),
                style = TextStyle(fontFamily = Fluid.body, fontSize = 16.sp,
                    color = if (enabled) Fluid.foreground else Fluid.muted))
            Switch(checked, null, enabled = enabled, colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White, checkedTrackColor = Fluid.red, checkedBorderColor = Fluid.red,
                uncheckedThumbColor = Fluid.muted, uncheckedTrackColor = Fluid.surface, uncheckedBorderColor = Fluid.line))
        }
        if (note != null) Text(note, Modifier.fillMaxWidth().padding(bottom = 6.dp),
            style = TextStyle(fontFamily = Fluid.body, fontSize = 13.sp, color = Fluid.muted, lineHeight = 17.sp))
    }
}

/** A full-width pill button. */
@Composable
fun ActionButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Box(
        modifier.fillMaxWidth().padding(top = 10.dp).pressSpring(source, 0.975f)
            .defaultMinSize(minHeight = 56.dp)
            .background(Fluid.surface, Fluid.pill).border(BorderStroke(1.dp, Fluid.line), Fluid.pill)
            .clickable(source, androidx.compose.material3.ripple(color = Fluid.foreground), enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = TextStyle(fontFamily = Fluid.body, fontSize = 16.sp,
            color = if (enabled) Fluid.foreground else Fluid.muted))
    }
}

/**
 * A pill chip that fills its share of a row. Selected chips fill white; the colour change flows
 * on a spring and the chip gives a small bounce when it becomes selected.
 */
@Composable
fun RowScope.Chip(text: String, selected: Boolean = false, description: String? = null, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val fill by animateColorAsState(if (selected) Fluid.foreground else Fluid.surface, Fluid.liquid(), label = "chip")
    val ink by animateColorAsState(if (selected) Color.Black else Fluid.foreground, Fluid.liquid(), label = "chipInk")
    val pop = remember { Animatable(1f) }
    LaunchedEffect(selected) {
        if (selected) { pop.snapTo(0.94f); pop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 420f)) }
    }
    Box(
        Modifier.weight(1f).padding(horizontal = 3.dp, vertical = 3.dp)
            .graphicsLayer { scaleX = pop.value; scaleY = pop.value }
            .pressSpring(source)
            .defaultMinSize(minHeight = 52.dp)
            .background(fill, Fluid.pill).border(BorderStroke(1.dp, if (selected) fill else Fluid.line), Fluid.pill)
            .selectable(selected, source, androidx.compose.material3.ripple(color = ink), role = Role.Tab, onClick = onClick)
            .semantics { description?.let { contentDescription = it } }
            .padding(horizontal = 10.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = TextStyle(fontFamily = Fluid.body, fontSize = 15.sp, color = ink, fontWeight =
            if (selected) FontWeight.Medium else FontWeight.Normal), maxLines = 2)
    }
}

/** A row of [Chip]s. */
@Composable
fun ChipRow(content: @Composable RowScope.() -> Unit) =
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(0.dp), content = content)

/**
 * A titled slider over whole-number steps. The thumb follows the finger at once; [onChange] runs
 * while dragging, [onDone] when the finger lifts.
 */
@Composable
fun SliderSetting(
    title: @Composable (Int) -> String,
    value: Int,
    range: IntRange,
    spoken: @Composable (Int) -> String,
    onDone: (Int) -> Unit = {},
    onChange: (Int) -> Unit = {},
) {
    var live by remember(value) { mutableFloatStateOf(value.toFloat()) }
    val current = live.toInt()
    val said = spoken(current)
    Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
        Text(title(current), style = TextStyle(fontFamily = Fluid.body, fontSize = 16.sp, color = Fluid.foreground))
        Slider(
            value = live,
            onValueChange = { v -> val step = v.toInt(); if (step != live.toInt()) onChange(step); live = v },
            onValueChangeFinished = { onDone(live.toInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp)
                .semantics { stateDescription = said },
            colors = SliderDefaults.colors(thumbColor = Fluid.foreground, activeTrackColor = Fluid.red,
                inactiveTrackColor = Fluid.surface),
        )
    }
}

/** Mutually exclusive choices, stacked or side by side. */
@Composable
fun <T> Choices(options: List<Pair<T, String>>, selected: T, horizontal: Boolean = false, onSelect: (T) -> Unit) {
    val item: @Composable (Modifier, Pair<T, String>) -> Unit = { mod, (value, name) ->
        Row(
            mod.defaultMinSize(minHeight = 52.dp)
                .selectable(value == selected, role = Role.RadioButton) { onSelect(value) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(value == selected, null, colors = RadioButtonDefaults.colors(
                selectedColor = Fluid.foreground, unselectedColor = Fluid.muted))
            Spacer(Modifier.width(8.dp))
            Text(name, style = TextStyle(fontFamily = Fluid.body, fontSize = 16.sp, color = Fluid.foreground))
        }
    }
    if (horizontal) Row(Modifier.fillMaxWidth()) { options.forEach { item(Modifier.weight(1f), it) } }
    else Column(Modifier.fillMaxWidth()) { options.forEach { item(Modifier.fillMaxWidth(), it) } }
}

/** A round colour sample. */
@Composable
fun Swatch(color: Color, description: String) = Box(
    Modifier.padding(end = 10.dp).size(28.dp).background(color, CircleShape).border(1.dp, Fluid.line, CircleShape)
        .semantics { contentDescription = description },
)
