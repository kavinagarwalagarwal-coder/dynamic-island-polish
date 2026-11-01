package com.dynamicdroid.app

import android.Manifest
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat

/**
 * Headphones and other Bluetooth audio or wearable devices: a short island with the device name
 * and battery when it connects, and a red one when it drops away. Reads only the connection
 * broadcasts the system already sends; no scanning, no location.
 */
class DeviceMonitor(private val context: Context, private val onEvent: (LiveActivity) -> Unit) {
    private var registered = false
    private val connectedAt = HashMap<String, Long>()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            val device = device(intent) ?: return
            if (!relevant(device)) return
            val features = IslandFeatures.read(context)
            val address = runCatching { device.address }.getOrNull() ?: return
            val name = displayName(device)
            when (action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    connectedAt[address] = SystemClock.elapsedRealtime()
                    if (features.headphones) onEvent(activity(address, name, battery(intent, device), lost = false))
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    connectedAt.remove(address)
                    if (features.bluetoothLost) onEvent(activity(address, name, null, lost = true))
                }
                ACTION_BATTERY_LEVEL_CHANGED -> {
                    // Earbuds report battery a moment after connecting; refresh only that first island.
                    val since = connectedAt[address] ?: return
                    if (SystemClock.elapsedRealtime() - since > BATTERY_WINDOW_MS || !features.headphones) return
                    val level = battery(intent, device) ?: return
                    onEvent(activity(address, name, level, lost = false))
                }
            }
        }
    }

    fun start() {
        if (registered || !hasPermission(context)) return
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(ACTION_BATTERY_LEVEL_CHANGED)
        }
        registered = runCatching {
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED); true
        }.getOrDefault(false)
    }

    fun stop() {
        if (!registered) return
        runCatching { context.unregisterReceiver(receiver) }
        registered = false
    }

    private fun activity(address: String, name: String, battery: Float?, lost: Boolean) = LiveActivity(
        key = "device:$address", packageName = context.packageName, mode = IslandMode.DEVICE,
        title = name,
        subtitle = context.getString(if (lost) R.string.island_disconnected else R.string.island_connected),
        appLabel = "Bluetooth", appIcon = null, artwork = null, accent = AppStyle.GREEN,
        progress = battery, lost = lost,
    )

    @Suppress("DEPRECATION")
    private fun device(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        else intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

    private fun relevant(device: BluetoothDevice): Boolean = runCatching {
        when (device.bluetoothClass?.majorDeviceClass) {
            BluetoothClass.Device.Major.AUDIO_VIDEO, BluetoothClass.Device.Major.WEARABLE -> true
            else -> false
        }
    }.getOrDefault(false)

    private fun displayName(device: BluetoothDevice): String = runCatching {
        (if (Build.VERSION.SDK_INT >= 30) device.alias else null) ?: device.name
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: context.getString(R.string.bluetooth_device)

    /** Battery from the system's broadcast, falling back to the device's own report. */
    private fun battery(intent: Intent, device: BluetoothDevice): Float? {
        val fromIntent = intent.getIntExtra(EXTRA_BATTERY_LEVEL, -1)
        val level = if (fromIntent >= 0) fromIntent else runCatching {
            device.javaClass.getMethod("getBatteryLevel").invoke(device) as Int
        }.getOrDefault(-1)
        return if (level in 0..100) level / 100f else null
    }

    companion object {
        /** Sent by the Bluetooth stack on Android 8.1+, but not part of the public SDK. */
        private const val ACTION_BATTERY_LEVEL_CHANGED = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
        private const val EXTRA_BATTERY_LEVEL = "android.bluetooth.device.extra.BATTERY_LEVEL"
        private const val BATTERY_WINDOW_MS = 15_000L

        fun permission(): String? = if (Build.VERSION.SDK_INT >= 31) Manifest.permission.BLUETOOTH_CONNECT else null

        fun hasPermission(context: Context): Boolean {
            val p = permission() ?: return true
            return ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
        }
    }
}
