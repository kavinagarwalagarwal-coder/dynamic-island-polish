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

## Liquid Glass and timer refinement

- Enable **Refract the background** in settings, then enable or re-enable the accessibility service if Android requests the updated screenshot capability. On Android 14+ the renderer uses `takeScreenshotOfWindow` and an AGSL rounded lens shader: bevel refraction, a small chromatic fringe, and directional edge highlights. Foreground controls stay crisp.
- **Tint glass with wallpaper colour** is independent of **Match island to wallpaper** (accent colours). Use **Use my current wallpaper** or **Pick an image instead** to refresh the palette after changing wallpaper.
- Capture is opt-in, local and memory-only. The underlying active app window is sampled at most every 400 ms; this is a sampled backdrop, not a full-frame-rate compositor effect. No screenshot is written or transmitted. Capture pauses when locked, screen-off, concealed, or disabled; detach/window switches clear the previous frame.
- True refraction requires Android 14+, accessibility access, a hardware Canvas, and an unprotected application window covering the island. System status-bar areas, older versions, secure apps, unsupported OEMs, and unavailable captures use the existing material treatment. The normal application-overlay fallback cannot capture a clean backdrop.
- SwiftUI remains the authored motion source. SwiftUI gets the same damping refinement, a shared morphing timer ring/digits, optional real control callbacks, a monotonic countdown, and an optional system material. The live Android implementation remains the existing native Canvas port. The SwiftUI system material is not a custom cross-app refractive shader.
- Timer controls invoke only notification actions supplied by the clock app; unsupported/localized action labels fall back to existing labelled notification actions. No control pretends to pause a timer. Remaining-time rings use the longest observed remaining duration when the clock app does not publish the original total.

### Validation

The APK workflow runs countdown/fit/interrupted-spring regression tests and `assembleDebug`; the macOS job builds the SwiftUI package. Manual device checks: expand/collapse rapidly at 60/120 Hz; pause/resume/cancel a real clock timer; test 1:00 → 0:59 and hour-long timers; switch foreground apps, lock/unlock, rotate, change fit/tint, and open a secure app. Verify fallback and no previous-app frame on a window switch. API/OEM screenshot behavior and shader appearance still need physical-device validation.
