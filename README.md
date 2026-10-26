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
