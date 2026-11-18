# AoD — notification light for Android

A notification light / LED for phones that do not have one. When a
notification arrives, a colored light glows **around the front camera
cutout**, along the **screen edges**, or as a small **LED dot** — on the
lock screen and while the display is in its always-on / doze state.
Alternatively it shows the **app icons** of the apps that have a
notification and no light at all.
No root, no Xposed, no hardware LED required.

Pure Java, no Kotlin, no tracking, **no internet permission at all**.

## How it works (no root)

The app combines three standard Android mechanisms:

1. **NotificationListenerService** (`notificationobserver`)
   Reads posted notifications (title, text, package), filters out noise
   (foreground/persistent notifications, progress bars, media sessions,
   group summaries), resolves a light color per app (manual override →
   dominant color of the app icon → default), and injects synthetic
   *charging / full / low-battery* events. Do-Not-Disturb is respected.

2. **AccessibilityService used only as an overlay privilege** (`overlayservice`)
   The light is drawn into a `TYPE_ACCESSIBILITY_OVERLAY` window. Such
   windows need **no "display over other apps" permission**, sit above the
   keyguard, and on many devices stay visible while the panel shows its
   always-on display. The service disables its own accessibility event
   stream at runtime (it subscribes to a non-existent package), so **no
   screen content is ever read** — accessibility is used purely for the
   window type.

3. **A black always-on field, drawn by the same overlay**
   While the display is off, the overlay paints an opaque black rectangle
   over the top two thirds of the panel, which hides the system AOD clock
   and widgets. On OLED those pixels are effectively off, so an idle
   screen is a static, near-zero-cost black field: no animation, no wake
   locks, and **nothing at all is drawn when there is no notification**.
   The light, the app icons and the optional notification text are painted
   into that field, so nothing ever has to wake the panel — pressing the
   power button turns the display off and it stays off. The bottom third
   of the panel is deliberately left untouched.

   For phones that show *nothing* when the display is off there is an
   opt-in fallback (`AodScreenActivity`, off by default): a pure-black,
   very dim `showWhenLocked` + `turnScreenOn` + `KEEP_SCREEN_ON` activity
   that gives the light something to sit on and supports double-tap wake.
   Because it turns the panel on, it is never used unless asked for.

While the light runs, a timeout-bounded `PARTIAL_WAKE_LOCK` keeps the
animation timers alive during doze. The hidden `DRAW_WAKE_LOCK` level is
probed once and used only where the OEM permits it. All wake locks are
released when the burst ends.

**Burn-in protection:** the light, the app icons and the preview text
slowly change position while shown — about once a minute, further for
icons because they cover many more pixels than a thin ring.

## Features

- Styles: camera ring, screen edge glow, status-bar LED dot, or app icons
- App icons: one icon per app with a notification, in centered rows below
  the camera; original colors, white or black; size 24–96 dp; optional
  background plate; slow drift against burn-in. Completely still — no
  pulsing, flashing or blinking, and no per-frame redraws
- Animations (light styles): pulse, rotate, flash, steady; adjustable speed
- ECO mode (reduced frame rate) and interval mode (on/off cycling)
- Per-app colors (long-press an app icon in the strip), auto color from
  the app icon, or one global color
- Notification preview text, drawn by the overlay (optional)
- Double tap to wake (on the fallback canvas)
- Charging / battery-full / low-battery light
- Night quiet hours, Do-Not-Disturb respect
- App filter (all apps or a selected set)
- Ignore persistent notifications; optional dismiss-on-unlock
- Camera-ring calibration: automatic cutout detection + manual offset/size
- Light duration, canvas brightness, thickness sliders
- Test button, live debug log, boot persistence

## Setup

1. Install, open the app.
2. Grant **notification access** (button in the app).
3. Enable the **accessibility service** (button in the app). It is used
   only to draw the overlay; it reads nothing.
4. Allow **ignoring battery optimizations** so timers survive doze.
5. Optional one-tap setup via ADB:
   `adb shell pm grant com.zoffcc.applications.aod android.permission.WRITE_SECURE_SETTINGS`
   then use the "Auto-enable accessibility" button that appears.

Test without a second device:

```
adb shell cmd notification post -S bigtext -t 'Title' 'Tag' 'Hello light'
```

## Build

- JDK 17, Android SDK 34, Gradle 8.9 (wrapper included)
- `./gradlew assembleDebug`
- minSdk 28 (Android 9), targetSdk 34
- Dependencies: appcompat, recyclerview (UI only). Everything else is
  platform API.

## Compatibility notes

- Whether an overlay is visible **on top of the native AOD** depends on
  the OEM; where it is not, the dark canvas fallback is used automatically.
- The light geometry is portrait-only.
- Keeping any light active costs battery; use ECO / interval mode and
  short timeouts. The dark canvas keeps the panel technically "on" at low
  brightness — on OLED this is cheap, on LCD it is not.
- Long-term static light can contribute to burn-in; the built-in position
  shifting mitigates this, but use at your own risk.

## Privacy

No `INTERNET` permission, no analytics, no accounts. Notification content
never leaves the device and is only used to pick a color and (optionally)
show a preview on your own lock screen.
