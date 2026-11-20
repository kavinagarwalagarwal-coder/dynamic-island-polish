package com.dynamicdroid.app

import android.app.AppOpsManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/** One thing the island needs from Android, in the order the guide asks for them. */
enum class SetupStep(val required: Boolean) {
    /** Read notifications: music, timers, calls, messages. */
    LISTENER(true),
    /** The accessibility service: the island sits around the camera and takes taps. */
    ISLAND(true),
    /** Android 13+: the island's own status notification, which keeps it running. */
    POST_NOTIFICATIONS(false),
    /** Not closed in the background by battery saving. */
    BATTERY(false),
    /** Usage access: smoother app-to-island moves. */
    USAGE(false),
}

/** What is already granted. Pure data, so the plan can be tested off-device. */
data class SetupState(
    val listener: Boolean,
    val island: Boolean,
    val postNotifications: Boolean,
    val battery: Boolean,
    val usage: Boolean,
) {
    fun done(step: SetupStep) = when (step) {
        SetupStep.LISTENER -> listener
        SetupStep.ISLAND -> island
        SetupStep.POST_NOTIFICATIONS -> postNotifications
        SetupStep.BATTERY -> battery
        SetupStep.USAGE -> usage
    }
}

object SetupPlan {
    /** The steps this phone needs (the notification permission only exists on Android 13+). */
    fun steps(sdk: Int): List<SetupStep> =
        SetupStep.values().filter { it != SetupStep.POST_NOTIFICATIONS || sdk >= 33 }

    /** The next step to do: required ones first, then the optional ones, in order. */
    fun next(state: SetupState, sdk: Int): SetupStep? {
        val steps = steps(sdk).filterNot(state::done)
        return steps.firstOrNull { it.required } ?: steps.firstOrNull()
    }

    /** Ready to use: every required step is done. */
    fun ready(state: SetupState, sdk: Int) = steps(sdk).filter { it.required }.all(state::done)

    fun doneCount(state: SetupState, sdk: Int) = steps(sdk).count(state::done)

    /**
     * One ADB command that grants everything, for anyone with a computer. It adds the island to
     * the accessibility services already on instead of replacing them, and keeps to characters
     * that survive Windows cmd, PowerShell and Unix shells.
     */
    fun adbCommand(appId: String, codePackage: String = "com.dynamicdroid.app"): String {
        val a11y = "$appId/$codePackage.IslandAccessibilityService"
        val listener = "$appId/$codePackage.IslandNotificationListener"
        return "adb shell 'cmd notification allow_listener $listener; " +
            "appops set $appId SYSTEM_ALERT_WINDOW allow; appops set $appId GET_USAGE_STATS allow; " +
            "appops set $appId ACCESS_RESTRICTED_SETTINGS allow; " +
            "pm grant $appId android.permission.POST_NOTIFICATIONS; " +
            "pm grant $appId android.permission.WRITE_SECURE_SETTINGS; " +
            "dumpsys deviceidle whitelist +$appId; " +
            "s=\$(settings get secure enabled_accessibility_services); " +
            "case \"\$s\" in *IslandAccessibilityService*) ;; null) settings put secure enabled_accessibility_services $a11y ;; " +
            "\"\") settings put secure enabled_accessibility_services $a11y ;; " +
            "*) settings put secure enabled_accessibility_services \"\$s:$a11y\" ;; esac; " +
            "settings put secure accessibility_enabled 1'"
    }
}

/** Where to get new versions: the newest GitHub release of this app. */
object AppLinks {
    const val REPO = "https://github.com/kavinagarwalagarwal-coder/dynamic-island-polish"

    /** Direct download of the newest APK for this build (standard or Android 17). */
    fun latestApk(appId: String) =
        "$REPO/releases/latest/download/" + if (appId.endsWith(".android17")) "DynamicDroid-Android17.apk" else "DynamicDroid.apk"
}

// ------------------------------------------------------------------ checks and pages

internal fun MainActivity.setupState() = SetupState(
    listener = androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName),
    island = IslandAccessibilityService.isEnabled(this) && IslandAccessibilityService.instance != null,
    postNotifications = Build.VERSION.SDK_INT < 33 ||
        checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED,
    battery = getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) == true,
    usage = ForegroundWatcher.hasPermission(this),
)

/**
 * Whether Android is blocking accessibility and notification access as a "restricted setting":
 * Android 13+ does that for apps installed outside an app store until the user allows it in App
 * info. True, false, or null when this phone can't say.
 */
internal fun MainActivity.restrictedSettingsBlocked(): Boolean? {
    if (Build.VERSION.SDK_INT < 33) return false
    val installer = runCatching { packageManager.getInstallSourceInfo(packageName).installingPackageName }.getOrNull()
    if (installer == "com.android.vending") return false
    return runCatching {
        getSystemService(AppOpsManager::class.java).unsafeCheckOpNoThrow(
            "android:access_restricted_settings", applicationInfo.uid, packageName) != AppOpsManager.MODE_ALLOWED
    }.getOrNull()
}

/** Opens the exact page for a step: straight to this app's switch where Android allows it. */
internal fun MainActivity.openStep(step: SetupStep) {
    fun open(vararg intents: Intent) {
        for (i in intents) try { startActivity(i); return } catch (_: ActivityNotFoundException) {} catch (_: SecurityException) {}
    }
    val appUri = Uri.parse("package:$packageName")
    when (step) {
        SetupStep.LISTENER -> {
            val detail = if (Build.VERSION.SDK_INT >= 30) Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    ComponentName(this, IslandNotificationListener::class.java).flattenToString()) else null
            open(*listOfNotNull(detail, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)).toTypedArray())
        }
        SetupStep.ISLAND -> {
            val component = ComponentName(this, IslandAccessibilityService::class.java).flattenToString()
            val detail = if (Build.VERSION.SDK_INT >= 33) Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                .putExtra(Intent.EXTRA_COMPONENT_NAME, component) else null
            if (detail == null) toast(getString(R.string.setup_a11y_toast))
            open(*listOfNotNull(detail, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)).toTypedArray())
        }
        SetupStep.POST_NOTIFICATIONS ->
            if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 41)
        SetupStep.BATTERY -> open(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, appUri),
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        SetupStep.USAGE -> open(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, appUri), Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    }
}

internal fun MainActivity.openAppInfo() =
    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))

internal fun MainActivity.openLatestRelease() = try {
    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AppLinks.latestApk(packageName))))
} catch (_: ActivityNotFoundException) {}
