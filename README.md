# Dynamic Droid

Android Dynamic Island. The **shape and motion** are authored in SwiftUI (`Sources/IslandUI`) so they can be compiled to Android with [Skip](https://skip.tools). The live overlay (permissions, notifications, accessibility) stays in `android/`.

## What changed

- Idle island is a **full-width** status bar: time on the left, wifi/cellular and battery on the right.
- Width, height, radius, and **horizontal center** all use the same interpolating spring. Idle → compact no longer jumps from screen-center to the camera hole.
- Compact and expanded live activities still morph from that bar (music, calls, timers, messages).

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
