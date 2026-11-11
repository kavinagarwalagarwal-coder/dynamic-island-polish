package com.dynamicdroid.app

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.format.DateFormat
import androidx.core.content.ContextCompat
import java.util.Date

/**
 * Short notices for things the phone does by itself (Wi-Fi, unplugging, low battery, airplane
 * mode, USB, unlocking, adaptive brightness, a new alarm), plus two live islands: the flashlight
 * while it's on, and the sleep island in the 12 hours before an alarm. Each can be switched off.
 */
class SystemEvents(
    private val context: Context,
    private val post: (LiveActivity) -> Unit,
    private val remove: (String) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var started = false
    private var startedAt = 0L
    private var lastLowLevel = 101
    private var lastPlugged: Boolean? = null
    private var lastAirplane: Boolean? = null
    private var lastUsb: Boolean? = null
    private var lastAlarm: Long? = null
    private var wifiUp: Boolean? = null
    private var torchCamera: String? = null
    private var torchOn = false

    private fun features() = IslandFeatures.read(context)

    /** Android repeats sticky broadcasts on registration; ignore the first moments. */
    private fun settling() = SystemClock.elapsedRealtime() - startedAt < 2_500L

    private fun notice(key: String, glyph: Glyph, title: String, subtitle: String, accent: Int, progress: Float? = null) {
        if (settling()) return
        post(LiveActivity(
            key = "event:$key", packageName = context.packageName, mode = IslandMode.EVENT,
            title = title, subtitle = subtitle, appLabel = context.getString(R.string.app_name),
            appIcon = null, artwork = null, accent = accent, glyph = glyph, progress = progress,
        ))
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            val f = features()
            when (intent?.action) {
                Intent.ACTION_POWER_DISCONNECTED -> if (f.eventUnplugged) {
                    val level = batteryLevel()
                    notice("unplugged", Glyph.UNPLUGGED, context.getString(R.string.event_unplugged),
                        context.getString(R.string.event_battery_at, level), NothingStyle.muted, level / 100f)
                }
                Intent.ACTION_BATTERY_CHANGED -> {
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 /
                        intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
                    val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
                    if (plugged) lastLowLevel = 101
                    // Once at 15% and once at 5% on the way down.
                    val step = when { level <= 5 -> 5; level <= 15 -> 15; else -> 101 }
                    if (!plugged && step < lastLowLevel && level >= 0) {
                        lastLowLevel = step
                        if (f.eventLowBattery && step <= 15) notice("battery-low", Glyph.BATTERY_LOW,
                            context.getString(R.string.event_battery_low), context.getString(R.string.event_battery_left, level),
                            NothingStyle.red, level / 100f)
                    }
                    lastPlugged = plugged
                }
                Intent.ACTION_AIRPLANE_MODE_CHANGED -> {
                    val on = intent.getBooleanExtra("state", false)
                    if (on != lastAirplane) {
                        lastAirplane = on
                        if (f.eventAirplane) notice("airplane", Glyph.PLANE,
                            context.getString(if (on) R.string.event_airplane_on else R.string.event_airplane_off),
                            context.getString(if (on) R.string.event_airplane_on_note else R.string.event_airplane_off_note),
                            if (on) AppStyle.ORANGE else NothingStyle.muted)
                    }
                }
                USB_STATE -> {
                    val connected = intent.getBooleanExtra("connected", false)
                    val previous = lastUsb
                    lastUsb = connected
                    if (previous != null && connected != previous && connected && f.eventUsb) notice("usb", Glyph.USB,
                        context.getString(R.string.event_usb), context.getString(R.string.event_usb_note), AppStyle.BLUE)
                }
                Intent.ACTION_USER_PRESENT -> if (f.eventUnlock) notice("unlock", Glyph.UNLOCK,
                    context.getString(R.string.event_unlocked), DateFormat.getTimeFormat(context).format(Date()), AppStyle.GREEN)
                AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED -> {
                    val next = nextAlarm()
                    val previous = lastAlarm
                    lastAlarm = next
                    if (next != null && next != previous && previous != -1L && f.eventAlarmSet) {
                        val sleep = next - System.currentTimeMillis()
                        if (sleep > 0) notice("alarm-set", Glyph.ALARM,
                            context.getString(R.string.event_alarm_set, DateFormat.getTimeFormat(context).format(Date(next))),
                            context.getString(R.string.event_sleep_for, duration(sleep)), AppStyle.PURPLE)
                    }
                    refreshSleep()
                }
            }
        }
    }

    private val wifi = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = handler.post { wifiChanged(true) }.let { }
        override fun onLost(network: Network) = handler.post { wifiChanged(false) }.let { }
    }

    private fun wifiChanged(up: Boolean) {
        val previous = wifiUp
        wifiUp = up
        if (previous == null || previous == up || !features().eventWifi) return
        notice("wifi", if (up) Glyph.WIFI else Glyph.WIFI_OFF,
            context.getString(if (up) R.string.event_wifi_on else R.string.event_wifi_off),
            context.getString(if (up) R.string.event_wifi_on_note else R.string.event_wifi_off_note),
            if (up) AppStyle.BLUE else NothingStyle.muted)
    }

    private val brightness = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            if (!features().eventBrightness) return
            val auto = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, 0) ==
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
            notice("brightness", Glyph.BRIGHTNESS,
                context.getString(if (auto) R.string.event_brightness_on else R.string.event_brightness_off),
                context.getString(R.string.event_brightness_note), AppStyle.ORANGE)
        }
    }

    private val torch = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId != torchCamera) return
            torchOn = enabled
            publishTorch()
        }
    }

    // ---------------------------------------------------------------- flashlight

    private val cameras get() = context.getSystemService(CameraManager::class.java)

    private fun findTorch(): String? = runCatching {
        cameras?.cameraIdList?.firstOrNull { id ->
            val c = cameras!!.getCameraCharacteristics(id)
            c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
    }.getOrNull()

    /** Steps of brightness the torch supports (1 = on/off only). */
    fun torchLevels(): Int = if (Build.VERSION.SDK_INT >= 33) runCatching {
        cameras?.getCameraCharacteristics(torchCamera ?: return 1)?.get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL) ?: 1
    }.getOrDefault(1) else 1

    private fun torchLevel(): Int = if (Build.VERSION.SDK_INT >= 33) runCatching {
        cameras?.getTorchStrengthLevel(torchCamera ?: return 1) ?: 1
    }.getOrDefault(1) else 1

    fun setTorch(on: Boolean) {
        val id = torchCamera ?: return
        runCatching { cameras?.setTorchMode(id, on) }
    }

    fun toggleTorch() = setTorch(!torchOn)

    /** Sets the torch's brightness, 0..1 (Android 13+ on phones that support it). */
    fun setTorchLevel(fraction: Float) {
        val id = torchCamera ?: return
        val levels = torchLevels()
        if (Build.VERSION.SDK_INT < 33 || levels <= 1) return
        val level = (1 + fraction.coerceIn(0f, 1f) * (levels - 1)).toInt().coerceIn(1, levels)
        runCatching { cameras?.turnOnTorchWithStrengthLevel(id, level) }
        torchOn = true
        publishTorch()
    }

    private fun publishTorch() {
        if (!torchOn || !features().torch) { remove(TORCH_KEY); return }
        val levels = torchLevels()
        post(LiveActivity(
            key = TORCH_KEY, packageName = context.packageName, mode = IslandMode.TORCH,
            title = context.getString(R.string.torch_title),
            subtitle = context.getString(if (levels > 1) R.string.torch_note_levels else R.string.torch_note),
            appLabel = context.getString(R.string.torch_title), appIcon = null, artwork = null,
            accent = TORCH_YELLOW, progress = if (levels > 1) torchLevel().toFloat() / levels else null,
        ))
    }

    val isTorchOn get() = torchOn

    // ---------------------------------------------------------------- sleep before an alarm

    private fun nextAlarm(): Long? = runCatching {
        context.getSystemService(AlarmManager::class.java)?.nextAlarmClock?.triggerTime
    }.getOrNull()

    private val sleepTick = object : Runnable {
        override fun run() { refreshSleep(); handler.postDelayed(this, 60_000L) }
    }

    private fun refreshSleep() {
        val at = nextAlarm()
        val left = (at ?: 0L) - System.currentTimeMillis()
        if (!features().sleep || at == null || left <= 0L || left > SLEEP_WINDOW_MS) { remove(SLEEP_KEY); return }
        val time = DateFormat.getTimeFormat(context).format(Date(at))
        post(LiveActivity(
            key = SLEEP_KEY, packageName = context.packageName, mode = IslandMode.SLEEP,
            title = time, subtitle = context.getString(R.string.sleep_left, duration(left)),
            appLabel = context.getString(R.string.sleep_title), appIcon = null, artwork = null,
            accent = AppStyle.PURPLE, alarmAt = at,
            contentIntent = runCatching {
                context.getSystemService(AlarmManager::class.java)?.nextAlarmClock?.showIntent
            }.getOrNull(),
        ))
    }

    private fun duration(ms: Long): String {
        val minutes = (ms / 60_000L).toInt()
        val h = minutes / 60; val m = minutes % 60
        return if (h > 0) context.getString(R.string.duration_hm, h, m) else context.getString(R.string.duration_m, m)
    }

    private fun batteryLevel(): Int = context.getSystemService(BatteryManager::class.java)
        ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 0

    // ---------------------------------------------------------------- lifecycle

    fun start() {
        if (started) return
        started = true
        startedAt = SystemClock.elapsedRealtime()
        lastAlarm = nextAlarm() ?: -1L
        lastAirplane = Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
        ContextCompat.registerReceiver(context, receiver, IntentFilter().apply {
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED)
            addAction(USB_STATE)
        }, ContextCompat.RECEIVER_EXPORTED)
        runCatching {
            context.getSystemService(ConnectivityManager::class.java)?.registerNetworkCallback(
                NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), wifi)
        }
        runCatching {
            context.contentResolver.registerContentObserver(
                Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS_MODE), false, brightness)
        }
        torchCamera = findTorch()
        runCatching { cameras?.registerTorchCallback(torch, handler) }
        handler.post(sleepTick)
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { context.unregisterReceiver(receiver) }
        runCatching { context.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(wifi) }
        runCatching { context.contentResolver.unregisterContentObserver(brightness) }
        runCatching { cameras?.unregisterTorchCallback(torch) }
        handler.removeCallbacks(sleepTick)
    }

    /** Re-reads the switches: drops the flashlight or sleep island if they were turned off. */
    fun refresh() { publishTorch(); refreshSleep() }

    companion object {
        const val TORCH_KEY = "own:torch"
        const val SLEEP_KEY = "own:sleep"
        const val USB_STATE = "android.hardware.usb.action.USB_STATE"
        const val TORCH_YELLOW = 0xFFFFD84A.toInt()
        private const val SLEEP_WINDOW_MS = 12 * 60 * 60 * 1000L
    }
}
