# Dynamic Droid — native Android

Native Kotlin Android implementation of the live-activity island.

## Run

1. Open the `android` directory in Android Studio.
2. Let Android Studio install Android SDK 35 and sync Gradle.
3. Run the `app` configuration on Android 8.0+.
4. In the app, grant **Display over other apps**, enable **Notification access**, and turn on
   **Allow tapping over the status bar** (accessibility). On Android 13+ a sideloaded app's
   accessibility switch is greyed out until you open App info → ⋮ → **Allow restricted settings**.
5. Tap **Start Dynamic Island**.

## Build without Android Studio

1. Push the repository to GitHub.
2. Open the repository's **Actions** tab.
3. Select **Build Android APK**, then choose **Run workflow**.
4. Open the completed run and download the **dynamic-droid-debug-apk** artifact.
5. Unzip it and install `app-debug.apk` on the phone. Android may ask you to allow
   installs from the browser or file manager used to open it.

## Native integrations

- `IslandOverlayService`: system-level overlay backed by a foreground service.
- `IslandNotificationListener`: maps call, media, navigation, timer, and message notifications into island states. For media, it reads the active `MediaSession` metadata and playback state, including artwork, duration, and the supported transport controls.
- `IslandAccessibilityService`: owns no logic and reads no screen content; it only lets the island use an accessibility overlay, which sits above the status bar so taps at the top reach the island instead of opening the notification shade. Without it the island falls back to a normal overlay.
- `IslandView`: native Canvas rendering with compact/expanded morphing, album art, animated playback progress, and tap targets for previous, play/pause, next, and seeking.

The idle island is a full-width status bar (time on the left, network and battery
on the right). Compact activities spring into a camera-centered pill; expanded
activities morph into a card that covers the whole screen width. Shape springs
match `Sources/IslandUI` so Skip and the overlay stay in sync.
When the wallpaper theme is enabled, the pill's body takes a very dark version of the
wallpaper's main colour. It temporarily grows for an expanded activity, automatically
collapses after a few seconds, and the idle pill lets touches fall through to the app
underneath. Tapping an expanded
activity opens its source notification; action buttons invoke actions exposed by
that notification. Music controls use the active media session when the source
app exposes one, with notification-action labels as a fallback. Album art is
scaled before it is passed to the overlay. Charging state is read directly from Android.

Android does not allow third-party apps to replace the OS status bar. The island is therefore an explicitly user-enabled overlay and is subject to device-vendor background-process policies.

## Phone fit and setup (v1.4)

The native home screen has Playground, Setup, Appearance and Phone fit pages. Setup shows
connection status; Playground has interactive sample activities and the app-to-island handoff.
Phone fit includes a temporary 12-second home-screen test, which does not replace real notifications.

Automatic placement follows a camera cutout with an accessibility overlay in portrait. It moves
below the status bar for ordinary app overlays, wide notches, corner cutouts and landscape. Camera mode still
uses the safe fallback with an ordinary overlay, because Android places that window below the
status bar. On Samsung or any OEM where system UI still covers the camera-area island, choose
**Phone fit → Below status bar**. Negative vertical offsets cannot move this fallback into the bar.
No root, screen capture or system-bar hiding is used. Fold/rotation changes rebuild the overlay;
normal activity data stays in the service shelf. Device firmware behavior still needs physical testing.

App handoff uses a continuous curved flight, smooth deformation stage boundaries and a soft
landing. Cached notification-card content fades before the narrow flight so text is not stretched
through the final opening. Reduced motion and interruptible spring behavior are retained.

CI runs the full instrumentation suite on API 36 and a native navigation/standard-overlay
compatibility check on API 26 and 30, in addition to JVM and SwiftUI checks.

## Personalization (v1.5)

- **Rules:** set per-activity priority, choose whether a running timer detaches, allow only selected notification apps, hide the island while chosen apps are open, configure up to three source-app quick actions, and set daily quiet hours in local time.
- **Per-app style:** choose its accent, compact/expanded starting layout and animation speed.
- **Timer presets:** start five, ten, fifteen or twenty-five minute Clock timers and add a timer label. Presets hand the request to the installed clock app using Android's `ACTION_SET_TIMER` intent and `SET_ALARM` permission; the island still relies on the clock's notification for live timer data.
- **Phone placement:** save, load and remove named fit profiles. Profiles remain on the device.
- **Media:** album art, playback progress, seek and transport controls work when a media notification/session supplies them; select which controls appear per player.

Notification allow/hide rules, styles, priorities, quiet hours and profiles are stored locally in app preferences. Notification content itself continues to be processed in memory by the existing listener.
