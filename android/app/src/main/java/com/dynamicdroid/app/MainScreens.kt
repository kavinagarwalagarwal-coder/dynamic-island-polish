package com.dynamicdroid.app

import android.content.Intent
import android.os.Build
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

private val PAGE_ORDER = listOf("Playground", "Setup", "Appearance", "Rules", "Phone fit", "Features")

/** The whole settings screen: header, page tabs and the current page, flowing between pages. */
@Composable
internal fun MainActivity.MainScreen() {
    val reduce = Motion.reduced(this)
    Box(Modifier.fillMaxSize().background(Fluid.background)) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 20.dp)) {
            Header()
            Tabs()
            AnimatedContent(
                targetState = page,
                transitionSpec = {
                    val forward = PAGE_ORDER.indexOf(targetState) >= PAGE_ORDER.indexOf(initialState)
                    if (reduce) fadeIn(tween(120)) togetherWith fadeOut(tween(80))
                    else (fadeIn(spring(stiffness = 300f)) +
                        slideInHorizontally(spring(dampingRatio = 0.82f, stiffness = 340f)) { w -> (if (forward) w else -w) / 6 } +
                        scaleIn(spring(dampingRatio = 0.75f, stiffness = 340f), initialScale = 0.97f)) togetherWith
                        fadeOut(tween(110))
                },
                label = "page",
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) { name ->
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 14.dp, bottom = 28.dp)) {
                    val flow = Flow(visit, entered && !reduce)
                    when (name) {
                        "Playground" -> PlaygroundPage(flow)
                        "Setup" -> SetupPage(flow)
                        "Appearance" -> AppearancePage(flow)
                        "Rules" -> RulesPage(flow)
                        "Phone fit" -> FitPage(flow)
                        else -> FeaturesPage(flow)
                    }
                }
            }
        }
    }
}

/** Hands out rise-in delays in order, so a page's parts flow in one after another. */
internal class Flow(private val key: Any, private val enabled: Boolean) {
    private var index = 0
    fun next(): Modifier = Modifier.riseIn(index++, key, enabled)
}

@Composable
private fun MainActivity.Header() {
    val (status, ready) = remember(tick) { readinessText() }
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AndroidView(
                factory = { ctx -> LogoMarkView(ctx).also { headerLogo = it } },
                modifier = Modifier.size(40.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.app_name), Modifier.semantics { heading() },
                style = TextStyle(fontFamily = Fluid.body, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Fluid.foreground))
        }
        val color by animateColorAsState(if (ready) Fluid.good else Fluid.foreground, Fluid.liquid(), label = "ready")
        // Not ready yet: the status line takes you to the setup guide.
        Text(status, Modifier.padding(top = 6.dp, bottom = 12.dp)
            .then(if (ready) Modifier else Modifier.clickable(role = Role.Button) { selectPage("Setup") }),
            style = TextStyle(fontFamily = Fluid.body, fontSize = 13.sp, color = color))
    }
}

@Composable
private fun MainActivity.Tabs() {
    listOf(listOf("Playground", "Setup"), listOf("Appearance", "Rules"), listOf("Phone fit", "Features")).forEach { names ->
        ChipRow {
            names.forEach { name ->
                val label = navLabel(name)
                Chip(label, selected = page == name, description = label) { selectPage(name) }
            }
        }
    }
}

/** Hosts one of the activity's real [IslandView]s, moving it here if another page still holds it. */
@Composable
private fun HostedIsland(view: IslandView, modifier: Modifier) {
    AndroidView(
        factory = { ctx -> FrameLayout(ctx) },
        modifier = modifier,
        update = { frame ->
            if (view.parent !== frame) {
                (view.parent as? ViewGroup)?.removeView(view)
                frame.addView(view, FrameLayout.LayoutParams(-1, -1))
            }
        },
    )
}

// -------------------------------------------------------------------- Playground

@Composable
private fun MainActivity.PlaygroundPage(flow: Flow) {
    Column(flow.next()) {
        Heading(stringResource(R.string.play_heading), top = 0.dp)
        Note(stringResource(R.string.play_hint), size = 14)
    }
    ActionButton(stringResource(R.string.play_tour), flow.next()) { showOnboarding() }
    Box(
        flow.next().fillMaxWidth().padding(top = 16.dp).height(280.dp)
            .background(Fluid.surface, RoundedCornerShape(24.dp)).padding(start = 6.dp, end = 6.dp, top = 16.dp, bottom = 8.dp),
    ) { HostedIsland(preview, Modifier.fillMaxSize()) }
    Column(flow.next().padding(top = 8.dp)) {
        ChipRow {
            Chip(stringResource(R.string.demo_music)) { showDemo(demoMusic(true), Presentation.EXPANDED) }
            Chip(stringResource(R.string.demo_idle)) { showIdle() }
            Chip(stringResource(R.string.demo_chat)) {
                preview.setSecondary(null)
                showDemo(demoMessage(), if (features.messagesMediumFirst) Presentation.COMPACT else Presentation.EXPANDED)
            }
        }
        ChipRow {
            Chip(stringResource(R.string.demo_call)) { showDemo(demoCall(), Presentation.EXPANDED) }
            Chip(stringResource(R.string.mode_timer)) { preview.setSecondary(null); showDemo(demoTimer(), Presentation.EXPANDED) }
            Chip(stringResource(R.string.demo_two_live)) { preview.setSecondary(demoTimer()); showDemo(demoMusic(true), Presentation.COMPACT) }
        }
        ChipRow {
            Chip(stringResource(R.string.demo_volume)) { preview.setSecondary(null); showDemo(demoVolume(), Presentation.EXPANDED) }
            Chip(stringResource(R.string.demo_headphones)) { preview.setSecondary(null); showDemo(demoDevice(false), Presentation.EXPANDED) }
            Chip(stringResource(R.string.demo_bt_lost)) { preview.setSecondary(null); showDemo(demoDevice(true), Presentation.EXPANDED) }
        }
        ChipRow {
            Chip(stringResource(R.string.demo_download)) { preview.setSecondary(null); showDemo(demoDownload(), Presentation.EXPANDED) }
            Chip(stringResource(R.string.demo_alarm)) { preview.setSecondary(null); showDemo(demoAlarm(), Presentation.EXPANDED) }
            Chip(stringResource(R.string.demo_calling)) { preview.setSecondary(null); showDemo(demoCalling(), Presentation.EXPANDED) }
        }
        ChipRow {
            Chip(stringResource(R.string.demo_ringer)) {
                val states = listOf(RingerState.SILENT, RingerState.VIBRATE, RingerState.RING, RingerState.DND_ON)
                preview.setSecondary(null)
                showDemo(demoRinger(states[demoRingerStep++ % states.size]), Presentation.EXPANDED)
            }
            Chip(stringResource(R.string.dot_open_short)) { startActivity(Intent(this@PlaygroundPage, DotPlayerActivity::class.java)) }
        }
        ChipRow {
            // The fly-in you get when going home from the app that's playing.
            Chip(stringResource(R.string.demo_fly_in)) {
                preview.setSecondary(null); preview.show(demoMusic(true), Presentation.COMPACT, absorb = true)
            }
            Chip(stringResource(R.string.demo_event)) { preview.setSecondary(null); showDemo(demoEvent(), Presentation.EXPANDED) }
            Chip(stringResource(R.string.mode_torch)) { preview.setSecondary(null); showDemo(demoTorch(), Presentation.EXPANDED) }
            Chip(stringResource(R.string.mode_sleep)) { preview.setSecondary(null); showDemo(demoSleep(), Presentation.EXPANDED) }
            Chip(stringResource(R.string.mode_home)) { preview.setSecondary(null); showDemo(demoHome(), Presentation.EXPANDED) }
        }
    }
    Column(flow.next()) {
        OutlinedTextField(
            value = timerLabel, onValueChange = { timerLabel = it },
            placeholder = { Text(stringResource(R.string.timer_name_hint), color = Fluid.muted) },
            singleLine = true, keyboardOptions = KeyboardOptions.Default,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            shape = Fluid.pill,
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Fluid.foreground, unfocusedTextColor = Fluid.foreground,
                focusedBorderColor = Fluid.foreground, unfocusedBorderColor = Fluid.line, cursorColor = Fluid.red,
                focusedContainerColor = Fluid.surface, unfocusedContainerColor = Fluid.surface),
        )
        Note(stringResource(R.string.timer_presets))
        ChipRow {
            listOf(5, 10, 15, 25).forEach { mins ->
                Chip(stringResource(R.string.timer_preset_minutes, mins)) { startTimerPreset(mins) }
            }
        }
        ChipRow { Chip(stringResource(R.string.demo_app_to_island)) { appToIsland() } }
    }
    Note(stringResource(R.string.play_footer), flow.next().padding(top = 10.dp))
}

// -------------------------------------------------------------------- Setup

@Composable
private fun MainActivity.SetupPage(flow: Flow) {
    tick // re-read when permissions or the service change
    SetupGuideCard(flow)
    Column(flow.next()) {
        Heading(stringResource(R.string.setup_manage_heading))
        Note(stringResource(R.string.setup_fallback), size = 14)
    }
    ActionButton(stringResource(R.string.play_tour), flow.next()) { showOnboarding() }
    ActionButton(setupLabel("overlay"), flow.next()) { requestOverlay() }
    ActionButton(setupLabel("notifications"), flow.next()) {
        startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
    }
    ActionButton(setupLabel("a11y"), flow.next()) { openAccessibility() }
    ActionButton(setupLabel("usage"), flow.next()) { startActivity(Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
    Column(flow.next()) {
        Heading(stringResource(R.string.setup_clock_heading))
        Note(remember(tick) { TimerDiagnostics.read(this@SetupPage) })
    }
    // Errors the island recorded (it carries on after them); one tap copies the report.
    val errors = remember(tick) { CrashLog.read(this@SetupPage) }
    AnimatedVisibility(errors != null, flow.next(), enter = fadeIn() + expandVertically(Fluid.liquid()), exit = fadeOut() + shrinkVertically(Fluid.liquid())) {
        Column {
            Heading(stringResource(R.string.errors_heading))
            Note(stringResource(R.string.errors_note), size = 14)
            Note(errors.orEmpty().lineSequence().take(4).joinToString("\n"))
            ActionButton(stringResource(R.string.errors_copy)) {
                getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(
                    android.content.ClipData.newPlainText("Dynamic Droid errors", CrashLog.read(this@SetupPage).orEmpty()))
                toast(getString(R.string.errors_copied))
            }
            ActionButton(stringResource(R.string.errors_clear)) { CrashLog.clear(this@SetupPage); tick++ }
        }
    }
}

/**
 * The guided setup: what's done, what's next, one tap to the exact page for each step. Coming
 * back from Settings re-reads everything (onResume bumps tick), so finished steps tick off and
 * Continue moves on by itself.
 */
@Composable
private fun MainActivity.SetupGuideCard(flow: Flow) {
    val sdk = android.os.Build.VERSION.SDK_INT
    val state = remember(tick) { setupState() }
    val steps = remember { SetupPlan.steps(sdk) }
    val next = SetupPlan.next(state, sdk)
    val ready = SetupPlan.ready(state, sdk)
    // Say what just got done when the user comes back from Settings.
    var seen by remember { mutableStateOf<Set<SetupStep>?>(null) }
    LaunchedEffect(state) {
        val done = steps.filter(state::done).toSet()
        seen?.let { before -> (done - before).firstOrNull()?.let { toast(getString(R.string.guide_step_done, stepTitle(it))) } }
        seen = done
    }
    Column(flow.next()) {
        Heading(stringResource(R.string.guide_title), top = 0.dp)
        Note(stringResource(R.string.guide_progress, SetupPlan.doneCount(state, sdk), steps.size), size = 15,
            color = if (ready) Fluid.good else Fluid.foreground)
        steps.forEach { step -> SetupStepRow(step, state.done(step), step == next) { openStep(step) } }
        if (next != null) ActionButton(stringResource(R.string.guide_continue, stepTitle(next))) { openStep(next) }
        else Note(stringResource(R.string.guide_all_set), size = 15, color = Fluid.good)
    }
    // Android 13+ blocks these two for apps installed outside an app store until allowed.
    if ((!state.listener || !state.island) && remember(tick) { restrictedSettingsBlocked() } != false) {
        Column(flow.next()) {
            Heading(stringResource(R.string.guide_restricted_title))
            Note(stringResource(R.string.guide_restricted_note), size = 14)
            ActionButton(stringResource(R.string.guide_restricted_open)) { openAppInfo() }
        }
    }
    Column(flow.next()) {
        Heading(stringResource(R.string.guide_adb_title))
        Note(stringResource(R.string.guide_adb_note), size = 14)
        ActionButton(stringResource(R.string.guide_adb_copy)) {
            getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(
                android.content.ClipData.newPlainText("Dynamic Droid setup", SetupPlan.adbCommand(packageName)))
            toast(getString(R.string.guide_adb_copied))
        }
    }
    Column(flow.next()) {
        Heading(stringResource(R.string.guide_update_title))
        val version = remember { runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?" }
        Note(stringResource(R.string.guide_update_note, version), size = 14)
        ActionButton(stringResource(R.string.guide_update_get)) { openLatestRelease() }
    }
}

@Composable
private fun MainActivity.SetupStepRow(step: SetupStep, done: Boolean, isNext: Boolean, onClick: () -> Unit) {
    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp).pressSpring(source, 0.985f)
            .defaultMinSize(minHeight = 56.dp)
            .background(if (isNext) Fluid.surface else Color.Transparent, Fluid.pill)
            .clickable(source, androidx.compose.material3.ripple(color = Fluid.foreground), !done, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val mark by animateColorAsState(if (done) Fluid.good else Fluid.line, Fluid.liquid(), label = "step")
        Box(Modifier.size(26.dp).background(mark, CircleShape), contentAlignment = Alignment.Center) {
            Text(if (done) "✓" else "${step.ordinal + 1}", style = TextStyle(fontFamily = Fluid.body, fontSize = 13.sp,
                color = if (done) Color.Black else Fluid.foreground, fontWeight = FontWeight.Bold))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(stepTitle(step) + if (step.required) "" else "  · " + stringResource(R.string.guide_optional),
                style = TextStyle(fontFamily = Fluid.body, fontSize = 16.sp, color = if (done) Fluid.muted else Fluid.foreground))
            if (!done) Text(stepNote(step), Modifier.padding(top = 2.dp),
                style = TextStyle(fontFamily = Fluid.body, fontSize = 13.sp, color = Fluid.muted, lineHeight = 17.sp))
        }
    }
}

internal fun MainActivity.stepTitle(step: SetupStep) = getString(when (step) {
    SetupStep.LISTENER -> R.string.guide_listener_title
    SetupStep.ISLAND -> R.string.guide_island_title
    SetupStep.POST_NOTIFICATIONS -> R.string.guide_post_title
    SetupStep.BATTERY -> R.string.guide_battery_title
    SetupStep.USAGE -> R.string.guide_usage_title
})

internal fun MainActivity.stepNote(step: SetupStep) = getString(when (step) {
    SetupStep.LISTENER -> R.string.guide_listener_note
    // On, but Android stopped it (after a force stop): only off and on again brings it back.
    SetupStep.ISLAND -> if (IslandAccessibilityService.isEnabled(this)) R.string.setup_a11y_stuck else R.string.guide_island_note
    SetupStep.POST_NOTIFICATIONS -> R.string.guide_post_note
    SetupStep.BATTERY -> R.string.guide_battery_note
    SetupStep.USAGE -> R.string.guide_usage_note
})

// -------------------------------------------------------------------- Appearance

@Composable
private fun MainActivity.AppearancePage(flow: Flow) {
    Column(flow.next()) {
        Heading(stringResource(R.string.look_heading), top = 0.dp)
        Note(stringResource(R.string.look_hint), size = 14)
    }
    Column(flow.next()) {
        SettingSwitch(stringResource(R.string.look_match), theme.enabled) { theme = theme.copy(enabled = it); applyThemeUpdate() }
        Row(Modifier.padding(top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            val a by animateColorAsState(Color(if (theme.enabled) theme.accent else NothingStyle.red), Fluid.liquid(), label = "swatchA")
            val b by animateColorAsState(Color(if (theme.enabled) theme.secondary else NothingStyle.muted), Fluid.liquid(), label = "swatchB")
            Swatch(a, stringResource(R.string.theme_colour))
            Swatch(b, stringResource(R.string.theme_colour))
            Text(stringResource(R.string.look_swatches), style = TextStyle(fontFamily = Fluid.body, fontSize = 13.sp, color = Fluid.muted))
        }
    }
    ActionButton(stringResource(R.string.look_use_wallpaper), flow.next()) { applyWallpaperTheme(WallpaperTheme.fromSystem(this)) }
    ActionButton(stringResource(R.string.look_app_styles), flow.next()) { chooseAppStyle() }
    ActionButton(stringResource(R.string.look_pick), flow.next()) { pickImage.launch("image/*") }

    Column(flow.next()) {
        Heading(stringResource(R.string.glass_heading), top = 28.dp)
        Note(stringResource(R.string.glass_hint), size = 14)
    }
    ActionButton(stringResource(R.string.glass_choose), flow.next(), enabled = !wallpaperBusy) { pickWallpaper.launch("image/*") }
    Column(flow.next()) {
        val hasImage = remember(tick, wallpaperStyle) { WallpaperSkinStore.image(this@AppearancePage) != null }
        SettingSwitch(stringResource(R.string.glass_inside), wallpaperStyle.enabled && hasImage) { on ->
            if (on && !hasImage) pickWallpaper.launch("image/*")
            else { wallpaperStyle = wallpaperStyle.copy(enabled = on); applyWallpaperUpdate() }
        }
        SettingSwitch(stringResource(R.string.glass_refraction), wallpaperStyle.glass, enabled = Build.VERSION.SDK_INT >= 33) { on ->
            wallpaperStyle = wallpaperStyle.copy(glass = on)
            if (on) WallpaperRecovery.clearStatus(this@AppearancePage)
            applyWallpaperUpdate(); tick++
        }
        Note(remember(tick, wallpaperStyle) { WallpaperRecovery.status(this@AppearancePage) }, size = 14)
    }
    wallpaperPreview?.let { view ->
        Box(flow.next().fillMaxWidth().height(180.dp)) { HostedIsland(view, Modifier.fillMaxSize()) }
    }
    Column(flow.next()) {
        val percent = stringResource(R.string.unit_percent)
        SliderSetting({ stringResource(R.string.slider_title, stringResource(R.string.glass_x), it, "%") }, (wallpaperStyle.x * 100).toInt(), 0..100,
            { stringResource(R.string.slider_state, it, percent) },
            onChange = { wallpaperStyle = wallpaperStyle.copy(x = it / 100f); applyWallpaperUpdate() })
        SliderSetting({ stringResource(R.string.slider_title, stringResource(R.string.glass_y), it, "%") }, (wallpaperStyle.y * 100).toInt(), 0..100,
            { stringResource(R.string.slider_state, it, percent) },
            onChange = { wallpaperStyle = wallpaperStyle.copy(y = it / 100f); applyWallpaperUpdate() })
        SliderSetting({ stringResource(R.string.slider_title, stringResource(R.string.glass_strength), it, "%") }, (wallpaperStyle.strength * 100).toInt(), 15..65,
            { stringResource(R.string.slider_state, it, percent) },
            onChange = { wallpaperStyle = wallpaperStyle.copy(strength = it / 100f); applyWallpaperUpdate() })
    }
    ActionButton(stringResource(R.string.glass_remove), flow.next(), enabled = !wallpaperBusy) {
        WallpaperSkinStore.remove(this)
        wallpaperStyle = WallpaperSkinStore.read(this)
        applyWallpaperUpdate(); tick++
    }
}

// -------------------------------------------------------------------- Rules

@Composable
private fun MainActivity.RulesPage(flow: Flow) {
    tick // re-read when permissions or the service change
    Column(flow.next()) {
        Heading(stringResource(R.string.rules_priority), top = 0.dp)
        Note(stringResource(R.string.rules_priority_hint), size = 14)
        SettingSwitch(stringResource(R.string.rules_timer_bubble), IslandCustomization.timerAsBubble(this@RulesPage)) {
            IslandCustomization.setTimerAsBubble(this@RulesPage, it); refreshCustomization()
        }
    }
    IslandMode.values().filter { it != IslandMode.VOLUME && it != IslandMode.DEVICE && it != IslandMode.RINGER }.forEach { mode ->
        Box(flow.next()) {
            val name = modeName(mode)
            SliderSetting({ stringResource(R.string.priority_label, name, it) }, IslandCustomization.priority(this@RulesPage, mode), 0..100,
                { stringResource(R.string.priority_description, name) },
                onDone = { IslandCustomization.setPriority(this@RulesPage, mode, it); refreshCustomization() })
        }
    }
    Column(flow.next()) {
        Heading(stringResource(R.string.rules_filters))
        Note(allowedAppsText(), size = 14)
        ActionButton(stringResource(R.string.rules_choose_allowed)) { chooseApps(allowed = true) }
        Note(hiddenAppsText(), size = 14)
        ActionButton(stringResource(R.string.rules_hide_open)) { chooseApps(allowed = false) }
        ActionButton(stringResource(R.string.rules_quick_actions)) { chooseQuickActions() }
        ActionButton(stringResource(R.string.rules_app_styles)) { chooseAppStyle() }
    }
    Column(flow.next()) {
        Heading(stringResource(R.string.rules_quiet))
        SettingSwitch(stringResource(R.string.rules_quiet_switch), IslandCustomization.quietEnabled(this@RulesPage)) { saveQuiet(it) }
        ChipRow {
            Chip(stringResource(R.string.rules_quiet_from, minuteLabel(IslandCustomization.quietStart(this@RulesPage)))) { chooseTime(true) }
            Chip(stringResource(R.string.rules_quiet_until, minuteLabel(IslandCustomization.quietEnd(this@RulesPage)))) { chooseTime(false) }
        }
        Note(stringResource(R.string.rules_quiet_note))
    }
}

// -------------------------------------------------------------------- Phone fit

@Composable
private fun MainActivity.FitPage(flow: Flow) {
    tick // re-read when permissions or the service change
    Column(flow.next()) {
        Heading(stringResource(R.string.fit_heading), top = 0.dp)
        Note(stringResource(R.string.fit_hint), size = 14)
        Note(placementExplanation())
    }
    Box(flow.next()) {
        Choices(listOf(IslandPlacement.AUTO to stringResource(R.string.fit_auto),
            IslandPlacement.CAMERA to stringResource(R.string.fit_camera),
            IslandPlacement.BELOW_STATUS_BAR to stringResource(R.string.fit_below)), fit.placement) {
            fit = fit.copy(placement = it); updateFit()
        }
    }
    ActionButton(stringResource(R.string.fit_test), flow.next()) { testFit() }
    ActionButton(stringResource(R.string.fit_save_profile), flow.next()) { saveProfile() }
    ActionButton(stringResource(R.string.fit_load_profile), flow.next()) { loadProfile() }
    Note(stringResource(R.string.fit_samsung_note), flow.next(), size = 14)
    Column(flow.next()) {
        val percent = stringResource(R.string.unit_percent)
        val dpUnit = stringResource(R.string.unit_dp)
        // Sliders follow profile loads and resets themselves; no key here, as rebuilding a
        // slider at every step cancelled the drag.
        run {
            SliderSetting({ stringResource(R.string.slider_title, stringResource(R.string.fit_size), it, "%") }, (fit.scale * 100).toInt(), 70..130,
                { stringResource(R.string.slider_state, it, percent) }, onChange = { fit = fit.copy(scale = it / 100f); updateFit() })
            SliderSetting({ stringResource(R.string.slider_title, stringResource(R.string.fit_width), it, "%") }, (fit.pillWidth * 100).toInt(), 70..150,
                { stringResource(R.string.slider_state, it, percent) }, onChange = { fit = fit.copy(pillWidth = it / 100f); updateFit() })
            SliderSetting({ stringResource(R.string.slider_title, stringResource(R.string.fit_left_right), it, "dp") }, fit.offsetX.toInt(), -180..180,
                { stringResource(R.string.slider_state, it, dpUnit) }, onChange = { fit = fit.copy(offsetX = it.toFloat()); updateFit() })
            SliderSetting({ stringResource(R.string.slider_title, stringResource(R.string.fit_up_down), it, "dp") }, fit.offsetY.toInt(), -24..160,
                { stringResource(R.string.slider_state, it, dpUnit) }, onChange = { fit = fit.copy(offsetY = it.toFloat()); updateFit() })
        }
        SettingSwitch(stringResource(R.string.fit_align_camera), fit.alignCamera) { fit = fit.copy(alignCamera = it); updateFit() }
    }
    ActionButton(stringResource(R.string.fit_reset), flow.next()) { fit = IslandFit(); updateFit() }
}

// -------------------------------------------------------------------- Features

@Composable
private fun MainActivity.FeaturesPage(flow: Flow) {
    val f = features
    @Composable fun toggle(title: Int, key: String, checked: Boolean, note: Int? = null, extra: (Boolean) -> Unit = {}) =
        SettingSwitch(stringResource(title), checked, note?.let { stringResource(it) }) { extra(it); setFeature(key, it) }

    Column(flow.next()) {
        Heading(stringResource(R.string.features_islands), top = 0.dp)
        Note(stringResource(R.string.features_islands_note), size = 14)
    }
    Column(flow.next()) {
        toggle(R.string.feature_show_music, "show_music", f.music)
        toggle(R.string.feature_show_calls, "show_calls", f.calls)
        toggle(R.string.feature_calls_fullscreen, "calls_over_fullscreen", f.callsOverFullscreen, R.string.feature_calls_fullscreen_note)
        toggle(R.string.feature_show_messages, "show_messages", f.messages, R.string.feature_show_messages_note)
        toggle(R.string.feature_show_timers, "show_timers", f.timers)
        toggle(R.string.feature_show_navigation, "show_navigation", f.navigation)
        toggle(R.string.feature_show_charging, "show_charging", f.charging)
        toggle(R.string.feature_volume, "volume", f.volume, R.string.feature_volume_note)
        toggle(R.string.feature_headphones, "headphones", f.headphones, R.string.feature_headphones_note) { if (it) requestBluetoothIfNeeded() }
        toggle(R.string.feature_bluetooth_lost, "bluetooth_lost", f.bluetoothLost) { if (it) requestBluetoothIfNeeded() }
        toggle(R.string.feature_downloads, "downloads", f.downloads)
        toggle(R.string.feature_alarms, "alarms", f.alarms)
        toggle(R.string.feature_ringer, "ringer", f.ringer, R.string.feature_ringer_note)
        toggle(R.string.feature_ringer_controls, "ringer_controls", f.ringerControls, R.string.feature_ringer_controls_note)
        toggle(R.string.feature_torch, "show_torch", f.torch, R.string.feature_torch_note)
        toggle(R.string.feature_sleep, "show_sleep", f.sleep, R.string.feature_sleep_note)
        toggle(R.string.feature_home_card, "home_card", f.homeCard, R.string.feature_home_card_note)
        toggle(R.string.feature_swipe_tracks, "swipe_tracks", f.swipeTracks, R.string.feature_swipe_tracks_note)
        toggle(R.string.feature_app_morph, "app_morph", f.appMorph, R.string.feature_app_morph_note)
    }
    Column(flow.next()) {
        Heading(stringResource(R.string.features_events))
        Note(stringResource(R.string.features_events_note), size = 14)
        toggle(R.string.event_switch_unplugged, "event_unplugged", f.eventUnplugged)
        toggle(R.string.event_switch_low_battery, "event_low_battery", f.eventLowBattery)
        toggle(R.string.event_switch_wifi, "event_wifi", f.eventWifi)
        toggle(R.string.event_switch_airplane, "event_airplane", f.eventAirplane)
        toggle(R.string.event_switch_usb, "event_usb", f.eventUsb)
        toggle(R.string.event_switch_unlock, "event_unlock", f.eventUnlock)
        toggle(R.string.event_switch_brightness, "event_brightness", f.eventBrightness)
        toggle(R.string.event_switch_alarm_set, "event_alarm_set", f.eventAlarmSet)
        toggle(R.string.event_switch_payments, "event_payments", f.payments, R.string.event_switch_payments_note)
    }
    Column(flow.next()) {
        Heading(stringResource(R.string.features_messages))
        toggle(R.string.feature_reply, "inline_reply", f.inlineReply)
        toggle(R.string.feature_medium, "messages_medium", f.messagesMediumFirst, R.string.feature_medium_note)
        toggle(R.string.feature_hide_popups, "hide_phone_popups", f.hidePhonePopups, R.string.feature_hide_popups_note)
        val status = remember(tick, f) { popupStatus() }
        AnimatedVisibility(status != null, enter = fadeIn() + expandVertically(Fluid.liquid()), exit = fadeOut() + shrinkVertically(Fluid.liquid())) {
            Column {
                status?.let { s ->
                    Note(s.text, color = if (s.working) Fluid.good else Fluid.muted)
                    if (s.showA11y) ActionButton(stringResource(R.string.setup_a11y_allow)) { openAccessibility() }
                    if (s.showGrant) ActionButton(stringResource(R.string.popups_grant_button)) { explainPopupGrant() }
                }
            }
        }
    }
    Column(flow.next()) {
        Heading(stringResource(R.string.features_look))
        // Apple (Dynamic Island) or Nothing (the original monochrome, dot-matrix look).
        Label(stringResource(R.string.look_title))
        Choices(listOf(IslandLook.APPLE to stringResource(R.string.look_apple), IslandLook.NOTHING to stringResource(R.string.look_nothing)),
            f.look, horizontal = true) {
            IslandFeatures.setLook(this@FeaturesPage, it); applyFeaturesUpdate()
            showDemo(demoMusic(true), if (preview.presentation == Presentation.EXPANDED) Presentation.COMPACT else Presentation.EXPANDED)
        }
        Note(stringResource(R.string.look_note))
        toggle(R.string.feature_short, "short_compact", f.shortCompact, R.string.feature_short_note)
        SliderSetting({ stringResource(R.string.slider_title, stringResource(R.string.feature_height), it, "dp") }, f.compactHeightDp.toInt(),
            IslandFeatureSet.MIN_COMPACT_DP.toInt()..IslandFeatureSet.MAX_COMPACT_DP.toInt(),
            { stringResource(R.string.slider_state, it, stringResource(R.string.unit_dp)) },
            onChange = { IslandFeatures.setCompactHeight(this@FeaturesPage, it.toFloat()); applyFeaturesUpdate() })
        toggle(R.string.feature_corners, "phone_corners", f.phoneCorners)
        toggle(R.string.feature_bubbles, "coloured_bubbles", f.colouredBubbles, R.string.feature_bubbles_note)
        toggle(R.string.feature_glow, "edge_glow", f.edgeGlow, R.string.feature_glow_note)
        toggle(R.string.feature_glow_open, "glow_open_only", f.glowOpenOnly)
        toggle(R.string.feature_camera_fade, "camera_fade", f.cameraFade, R.string.feature_camera_fade_note)
        toggle(R.string.feature_landscape, "landscape_see_through", f.landscapeSeeThrough, R.string.feature_landscape_note)
        Label(stringResource(R.string.feature_motion))
        Choices(listOf(MotionStyle.APPLE to stringResource(R.string.motion_apple), MotionStyle.LIQUID to stringResource(R.string.motion_liquid), MotionStyle.BOUNCY to stringResource(R.string.motion_bouncy),
            MotionStyle.SMOOTH to stringResource(R.string.motion_smooth)), f.motion, horizontal = true) {
            IslandFeatures.setMotion(this@FeaturesPage, it); applyFeaturesUpdate()
            // Show the new motion straight away in the preview.
            showDemo(demoMusic(true), if (preview.presentation == Presentation.EXPANDED) Presentation.COMPACT else Presentation.EXPANDED)
        }
        toggle(R.string.feature_full_width, "full_width_open", f.fullWidthOpen, R.string.feature_full_width_note)
        toggle(R.string.feature_art_backdrop, "artwork_backdrop", f.artworkBackdrop, R.string.feature_art_backdrop_note)
        toggle(R.string.feature_idle_pill, "idle_pill", f.idlePill, R.string.feature_idle_pill_note)
        toggle(R.string.feature_hide_media, "hide_for_media", f.hideForMedia, R.string.feature_hide_media_note)
        Label(stringResource(R.string.feature_outline))
        Choices(listOf(OutlineMode.OFF to stringResource(R.string.outline_off), OutlineMode.EVENTS to stringResource(R.string.outline_events),
            OutlineMode.ALWAYS to stringResource(R.string.outline_always)), f.outline, horizontal = true) {
            IslandFeatures.setOutline(this@FeaturesPage, it); applyFeaturesUpdate()
        }
        toggle(R.string.feature_burn_in, "burn_in", f.burnInCare, R.string.feature_burn_in_note)
        toggle(R.string.feature_lock, "lock_screen", f.lockScreen, R.string.feature_lock_note)
    }
    Column(flow.next()) {
        Heading(stringResource(R.string.features_touch))
        toggle(R.string.haptic_tap, "haptic_tap", f.hapticTap)
        toggle(R.string.haptic_hold, "haptic_hold", f.hapticHold)
        toggle(R.string.haptic_swipe, "haptic_swipe", f.hapticSwipe)
        toggle(R.string.haptic_up, "haptic_up", f.hapticUp)
        toggle(R.string.haptic_dnd, "haptic_dnd_quiet", f.quietHapticsInDnd)
    }
    DotMusicSection(flow)
}

/** Dot music: the island dots, the full-screen player, the four widgets and the live wallpaper. */
@Composable
private fun MainActivity.DotMusicSection(flow: Flow) {
    tick // re-read when permissions or the service change
    val ctx = this
    Column(flow.next()) {
        Heading(stringResource(R.string.dot_section))
        Note(stringResource(R.string.dot_note), size = 14)
        SettingSwitch(stringResource(R.string.dot_island), features.dotMusic, stringResource(R.string.dot_island_note)) { setFeature("dot_music", it) }
        ActionButton(stringResource(R.string.dot_open)) { startActivity(Intent(ctx, DotPlayerActivity::class.java)) }
    }
    Column(flow.next()) {
        Label(stringResource(R.string.dot_widgets))
        ActionButton(stringResource(R.string.dot_add_stage)) { pinDotWidget(StageDotWidget::class.java) }
        ActionButton(stringResource(R.string.dot_add_medium)) { pinDotWidget(MediumDotWidget::class.java) }
        ActionButton(stringResource(R.string.dot_add_small)) { pinDotWidget(SmallDotWidget::class.java) }
        ActionButton(stringResource(R.string.dot_add_wide)) { pinDotWidget(WideDotWidget::class.java) }
    }
    Column(flow.next()) {
        Label(stringResource(R.string.dot_wallpaper))
        Note(stringResource(R.string.dot_wallpaper_note))
        ActionButton(stringResource(if (hasDotPhoto) R.string.dot_photo_change else R.string.dot_photo_pick)) { pickDotPhoto.launch("image/*") }
        AnimatedVisibility(hasDotPhoto, enter = fadeIn() + expandVertically(Fluid.liquid()), exit = fadeOut() + shrinkVertically(Fluid.liquid())) {
            ActionButton(stringResource(R.string.dot_photo_remove)) { WallpaperPhoto.clear(ctx); refreshDotPhoto() }
        }
        SettingSwitch(stringResource(R.string.dot_wallpaper_dots), DotPrefs.wallpaperDots(ctx), stringResource(R.string.dot_wallpaper_dots_note)) {
            DotPrefs.setWallpaperDots(ctx, it); tick++
        }
        SettingSwitch(stringResource(R.string.dot_wallpaper_playing), DotPrefs.wallpaperOnlyPlaying(ctx), stringResource(R.string.dot_wallpaper_playing_note)) {
            DotPrefs.setWallpaperOnlyPlaying(ctx, it); tick++
        }
        ActionButton(stringResource(R.string.dot_set_wallpaper)) { setDotWallpaper() }
    }
    Column(flow.next()) {
        Label(stringResource(R.string.dot_style))
        SettingSwitch(stringResource(R.string.dot_mono), DotPrefs.mono(ctx), stringResource(R.string.dot_mono_note)) {
            DotPrefs.setMono(ctx, it); MusicWidgets.refresh(ctx); applyFeaturesUpdate()
        }
        SettingSwitch(stringResource(R.string.dot_animations), DotPrefs.animations(ctx), stringResource(R.string.dot_animations_note)) {
            DotPrefs.setAnimations(ctx, it); MusicWidgets.refresh(ctx); tick++
        }
        SettingSwitch(stringResource(R.string.dot_beat), DotPrefs.beat(ctx), stringResource(R.string.dot_beat_note)) { setDotBeat(it) }
    }
}
