# Dynamic Droid

Android Dynamic Island. The **shape and motion** are authored in SwiftUI (`Sources/IslandUI`) so they can be compiled to Android with [Skip](https://skip.tools). The live overlay (permissions, notifications, accessibility) stays in `android/`.

## What changed

- Idle is **invisible inside the camera cutout**; activity arrival expands the island out of that point.
- Width, height, radius, center, opacity, and detached-bubble position use interruptible springs. Idle → compact → split → expanded transitions preserve motion.
- Compact and expanded live activities morph from the camera (music, calls, timers, messages).

## Run on a phone

1. Open `android/` in Android Studio (SDK 35).
2. Install the app, then grant **Display over other apps**, **Notification access**, and **Allow tapping over the status bar**.
3. Tap **Start Dynamic Island**.

Or use **Actions → Build Android APK** after this repo is on GitHub.

## SwiftUI + Skip

```
Sources/IslandUI/     SwiftUI island (source of motion)
android/              Overlay, listeners, Canvas port of the same springs
Package.swift         Builds IslandUI on Swift 5.7+
skip.yml              Skip module mapping
```

This Mac is on Swift 5.7 (macOS 12), which cannot run the current Skip CLI (needs Swift 5.9 / Xcode 15). The Android Canvas island is a hand port of the SwiftUI springs so the phone app works today.

On a newer Mac:

```sh
brew install skiptools/skip/skip
skip checkup
swift build
skip export --module IslandUI
```

Then host the exported Compose view from `IslandOverlayService` in place of `IslandView` when you are ready to switch renderers.

## Preview the island on macOS / iOS

Open `Package.swift` in Xcode and preview `IslandPlayground` in `DynamicIslandView.swift`, or:

```sh
swift build
```

## Island interaction and timer refinement

The island uses an opaque black surface. Liquid Glass, screen capture, refraction, and their settings/permissions have been removed. Wallpaper matching affects accents only.

- Motion remains authored in SwiftUI with the existing interruptible Android Canvas spring port. Tighter damping, elastic dragging, press feedback, and system reduced-motion support keep transitions responsive without restarting shape velocity.
- Tap a compact activity or its separate minimal bubble to open its app immediately. Hold either activity for 350 ms, or pull down, to expand it. Swipe upward or tap outside the expanded Android overlay to collapse. Notification controls remain immediate; no double-tap delay is imposed on opening an app.
- Two concurrent activities appear as compact + minimal. Stable activity selection prevents per-second timer/music refreshes from stealing focus. Holding the second bubble promotes it into the expanded card; removal and foreground filtering promote a remaining activity. Incoming calls take priority on arrival. More than two activities are retained, but only two appear simultaneously.
- Timers have a shared morphing ring/digit layout, tabular rolling digits, a frozen/dimmed paused state, real notification pause/resume/cancel actions, and a single completion expansion per run. The original clock app remains responsible for alarms and sound. The service tracks monotonic deadlines; notification updates do not restart the expanded timeout. Rings use the longest observed remaining duration when an app does not provide the original total.
- The native settings preview includes a **Two live** example and working demo timer pause/resume/cancel controls.
- SwiftUI exposes open/expand/collapse callbacks, secondary activity rendering, live countdowns, rolling digits, and timer action callbacks. Android continues to use its native overlay/services; SwiftUI has not been replaced.

### Validation

GitHub Actions runs Android regression tests and builds the debug APK, then runs SwiftUI package tests on macOS. Tests cover focus stability under repeated updates, explicit selection, foreground filtering, activity removal, incoming-call priority, bubble geometry on narrow/scaled screens, one completion per timer run, countdown rounding, and interrupted springs at 30–120 Hz.

Physical-device visual verification is still required: music + timer together, rapidly interrupt expansion/collapse, hold each bubble, pause/resume/cancel real Clock notifications, let a short timer expire, rotate/change fit, and lock/unlock. Available actions and timer metadata depend on the clock app. This recreates supported iOS-style behavior; exact Apple system animations and integrations are not claimed.

## Camera idle and detached timer correction

- Idle contracts and fades into the detected camera hole, stays touch-through, and does not replay a bar animation on an empty unlock.
- Timers default to the detached countdown bubble when another normal activity is present, in either arrival order. Holding the timer explicitly focuses it; collapsing restores the normal pill + timer arrangement. Side-swipes switch the visible activities without opening an app.
- Timer recognition accepts chronometer/countdown notifications without the ongoing flag, and retains known paused timers. Running timers stay eligible while Clock is foreground. Notifications without usable timer data remain limited by what the source app publishes.
- The two surfaces detach/reattach with independent position/scale/opacity springs. The small timer shows remaining time rather than a static logo. Geometry uses the actual view width and stays bounded at scaled calibration values.
- Reference design: Apple’s [Design dynamic Live Activities](https://developer.apple.com/videos/play/wwdc2023/10194/) and [Live Activities guidance](https://developer.apple.com/design/human-interface-guidelines/live-activities). These guide compact/minimal/expanded states, numeric transitions, elastic motion, and content placement; this does not claim access to Apple's private animation implementation.
- CI now renders the native Canvas view on an Android 35 emulator and checks visible, non-overlapping split pills at 70/100/130% scale, expansion, reattachment, and invisible camera idle. Screenshots are uploaded as `island-render-checks`, alongside the debug APK artifact. Physical-device/OEM notification checks remain necessary.

## Android 16 Clock notification compatibility

Custom Clock notifications may contain a running `Chronometer` only inside their `RemoteViews`, with no standard chronometer extras, duration text, or ongoing flag. `NotificationClockReader` reads the supplied notification layout in memory, extracts its monotonic clock base or paused duration (including Android 16 compact notification text), stops the temporary widgets, and passes that real timing data to the overlay. It never reads the screen or guesses a countdown duration.

Settings includes **Clock notification status** with the last package and recognised format, without saving notification text or images. This helps distinguish missing notification access from an unsupported Clock format on a physical device.

The Android 16 emulator test installs a separate `notification-fixture` app. It posts a real media-session notification and a custom Clock notification with no normal timer fields, then verifies the actual accessibility overlay receives and visibly detaches the timer during running, paused, and resumed states. The fixture is test-only and is not included in the shipped app APK.

### Still wallpaper and Liquid Glass (1.3.1)

Under **Wallpaper Glass**, choose the same image used for your phone wallpaper. Android 13+ restricts direct wallpaper image reads, so this uses the system image picker instead of screen capture or broad storage access. The photo stays still. Adjust framing/strength and optionally enable refraction. Camera-hole idle, detached timers and the refined SwiftUI/Android spring morphs remain.

Object recognition, segmented layers, motion effects, bundled models, LiteRT and model download build tasks have been removed. The existing selected original image/settings migrate; old subject/background images are deleted. Images are sampled to at most 1024 pixels per side and stored privately in noBackupFilesDir; Remove wallpaper deletes the copy. No Internet, screen capture, camera or broad storage permission is added.

The crop calculation now tolerates floating-point rounding during morphs, which previously could throw an invalid-range exception. Glass caches the RenderNode recording, refraction effect and rim gradient until the image/style/geometry changes; settled frames reuse them. The adapted AndroidLiquidGlass shader uses three sample taps rather than seven. Static wallpaper starts no frame loop of its own. Driver/API errors fall back to plain wallpaper. A local marker also pauses glass if the process rapidly restarts while glass was active; normal fast process replacement can trigger this conservative fallback too. Users can re-enable it in settings. Java cannot catch native GPU crashes; no crash log from the physical device was available.

Liquid Glass derives from [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass), commit `65ab177e90e5c1d8c62e70cf7755841982da65f6`, adapted to Canvas/RenderNode with its Apache-2.0 license in `assets/licenses/AndroidLiquidGlass-LICENSE.txt`. It refracts the imported wallpaper, not other apps' screens. Android 13+ hardware rendering is required; older/software rendering uses plain wallpaper. SwiftUI remains SwiftUI.

### Activity drop / merge (1.3.2)

A second live activity now pulls out of the main pill as a droplet: the pill narrows, the bubble stretches along a shallow downward arc, a short vector neck pinches off, and the circle settles with a small spring overshoot. Removing it reverses the same spring into the pill. Notification refreshes do not restart the transition, and changes mid-flight preserve the current position and velocity. Reduced-motion skips the neck/arc. No blur layers, new dependencies, or continuous animation loop are added; the still-wallpaper crash fixes remain.
