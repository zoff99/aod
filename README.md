# AoD - Notification Light for Android

> **Almost 100% of this app was written by AI.** The code was produced by an AI
> assistant from plain-language instructions; a human set the requirements,
> tested every build on a real phone and decided what shipped. Commits made
> that way are marked `[AIassisted]`.
>
> This is stated so you know it up front, not as a warning. Everything is here
> to read and audit: plain Java, no internet permission, no notification text
> is ever read, and CI runs unit tests, a device smoke test, a privacy scan of
> the finished APK and a scan for hidden characters in the source.

A notification light / LED for phones that do not have one. When a
notification arrives, a colored light glows **around the front camera
cutout**, along the **screen edges**, or as a small **LED dot** -- on the
lock screen and while the display is in its always-on / doze state.
Alternatively it shows the **app icons** of the apps that have a
notification and no light at all.
No root, no Xposed, no hardware LED required.

Pure Java, no Kotlin, no tracking, **no internet permission at all**.

<a href="https://f-droid.org/app/com.zoffcc.applications.toloshare"><img src="https://raw.githubusercontent.com/zoff99/ToLoShare/master/images/f-droid.png" width="200"></a>

## Latest Automated Screenshots

<img src="https://github.com/zoff99/aod/releases/download/nightly/aod_nightly_settings.png" width="200">&nbsp;<img src="https://github.com/zoff99/aod/releases/download/nightly/aod_nightly_picker.png" width="200">

<!-- &nbsp;<img src="https://github.com/zoff99/aod/releases/download/nightly/aod_nightly_lockscreen.png" width="200">&nbsp;<img src="https://github.com/zoff99/aod/releases/download/nightly/aod_nightly_aod.png" width="200"> -->

## How it works (no root)

The app combines three standard Android mechanisms:

1. **NotificationListenerService** (`notificationobserver`)
   Reads posted notifications (title, text, package), filters out noise
   (foreground/persistent notifications, progress bars, media sessions,
   group summaries), resolves a light color per app (manual override ->
   dominant color of the app icon -> default), and injects synthetic
   *charging / full / low-battery* events. Do-Not-Disturb is respected.

2. **AccessibilityService used only as an overlay privilege** (`overlayservice`)
   The light is drawn into a `TYPE_ACCESSIBILITY_OVERLAY` window. Such
   windows need **no "display over other apps" permission**, sit above the
   keyguard, and on many devices stay visible while the panel shows its
   always-on display. The service disables its own accessibility event
   stream at runtime (it subscribes to a non-existent package), so **no
   screen content is ever read** -- accessibility is used purely for the
   window type.

3. **A black always-on field, drawn by the same overlay**
   While the display is off, the overlay paints an opaque black rectangle
   over the whole panel, which hides the system AOD clock and widgets.
   On OLED those pixels are effectively off, so an idle screen is a
   static, near-zero-cost black field: no animation, no wake
   locks, and **nothing at all is drawn when there is no notification**.
   The light and the app icons are painted
   into that field, so nothing ever has to wake the panel -- pressing the
   power button turns the display off and it stays off. Covering only the
   top two thirds, leaving the bottom third as the phone draws it, is an
   opt-out setting.

   For phones that show *nothing* when the display is off there is an
   opt-in fallback (`AodScreenActivity`, off by default): a pure-black,
   very dim `showWhenLocked` + `turnScreenOn` + `KEEP_SCREEN_ON` activity
   that gives the light something to sit on and supports double-tap wake.
   Because it turns the panel on, it is never used unless asked for.

While the light runs, a timeout-bounded `PARTIAL_WAKE_LOCK` keeps the
animation timers alive during doze. The hidden `DRAW_WAKE_LOCK` level is
probed once and used only where the OEM permits it. All wake locks are
released when the burst ends.

**Burn-in protection:** the light and the app icons are placed a
few pixels differently **at the start of every burst**, and move again every
**10 minutes** if a single burst lasts that long. That interval is a setting
with four snap points -- **1, 2, 10 or 20 minutes**, 10 by default; shorter
spreads the wear over more pixels but costs a few more wake-ups. The hop
itself is deliberately tiny: 2-3 dp, well under a millimetre (roughly 6-9
pixels on a typical phone), the same for every style. Burn-in is prevented by
handing the load to the neighbouring pixels, not by travelling far, so a
sub-millimetre step is enough and stays almost invisible. Within one burst
nothing slides around at all, so what you see stays calm; across repeated
bursts the pixels differ. Switch *Move icons* off to pin them to one spot.

## Features

- Styles: camera ring, screen edge glow, status-bar LED dot, or app icons
- App icons: one icon per app with a notification, in centered rows below
  the camera; original colors, white or black; size 5-96 dp (default 40 dp); optional
  background plate; a subtle burn-in move every 1/2/10/20 minutes (default 10).
  Completely still -- no
  pulsing, flashing or blinking, and no per-frame redraws
- Icon artwork: the small symbol an app posts to the status bar (default),
  or its launcher icon (`Notification.getSmallIcon()`, loaded by resource id from that
  app's resources). Status-bar symbols are flat alpha masks, so with
  "original colors" they are filled with `Notification.color` -- the accent
  the app chose for its own notifications -- falling back to the dominant
  color of its launcher icon, then the global color. Very dark colors are
  lifted so they stay visible on black; a symbol that already has real
  colors keeps them; an app without a symbol falls back to its app icon
- Icon position: center the rows or start them at the left edge, horizontal
  offset +/-200 dp, vertical start below the camera / from the top / from the
  bottom, icons per row (0 = fill the width, or 1-8),
  gap between icons and between rows, at most 1-24 icons, center or align a
  shorter last row, optional clipping to the blacked-out area, and a
  "reset position" button. Offsets are limited so the block stays on the panel
- Vertical offset for all AOD items (in Basics): one 0-600 dp offset moves the
  whole group -- icons and clock together -- relative to the "Vertical start"
  anchor above. With the clock on, the top clock line starts at this distance
  and the icons sit below it; with the clock off, the icons start there
- Notification counts: optional number under each icon (from two upwards, or
  always), text size 10-28 dp, optional rounded badge; the row height reserves
  room for it so rows never overlap the number. Two sources: the number of
  notifications of that app, or the app's own counter (`Notification.number`,
  usually unread messages) when it publishes one
- Clock above the icons: optional time and/or date (default: nothing), each
  with its own size (8-72 dp), font (six built-in faces), horizontal position
  and height above the icon block. Only visible while a notification is
  showing; the time redraws once a minute, a date-only clock once at midnight,
  and it drifts with the icons
- Animations (light styles): pulse, rotate, flash, steady; adjustable speed
- ECO mode (reduced frame rate) and interval mode (on/off cycling)
- Per-app colors (long-press an app icon in the strip), auto color from
  the app icon, or one global color
- Double tap to wake (on the fallback canvas)
- Charging / battery-full / low-battery light
- Night quiet hours, Do-Not-Disturb respect (on by default)
- App filter (all apps or a selected set). The picker lists every launchable
  app -- the manifest declares `<queries>` for `ACTION_MAIN`, which Android 11+
  requires to see them at all, without `QUERY_ALL_PACKAGES` -- plus every
  package that has posted a notification, so apps without a launcher icon can
  be filtered too. The list can be narrowed to all / selected / not
  selected apps, and a hint reminds you that a long press sets that app's
  light color
- Persistent notifications: ignore all, allow calls, allow calls + selected
  apps, or allow all; optional dismiss-on-unlock
- Camera-ring calibration: automatic cutout detection + manual offset/size
- Always-on brightness that follows the system brightness slider
- Light duration on a snapped slider: 1, 2, 5, 10, 15, 20, 30, 45 min,
  1 h, 1.5 h, 2 h, 3 h -- twelve stops, each one a tick mark, no odd
  second counts; brightness percentage and thickness stay free sliders
- Settings in nine foldable sections with a small search line on top
- Settings backup: export and import as readable JSON (SAF folder/file
  picker, no storage permission)
- Test button, live debug log, boot persistence

## The settings screen

The screen is a short list of named sections instead of one long column:
**Basics**, **Light and color**, **Duration and battery**, **App icons**,
**Icon position and layout**, **Notification counts**, **Clock above the icons**,
**When to light up**, **Camera ring calibration**, **Advanced and testing**.
Tap a header to fold or unfold it; the chevron and the number of settings in
that section show its state. Only *Basics* is open on a fresh install, and
every section remembers how you left it, so the screen stays as short as you
made it.

The search line above the list is one small row that never scrolls away. It
filters all sections at once: type `night`, `count` or `brightness` and only
the matching settings remain, in sections that open themselves. Clearing the
field brings back your own folded state. The search reads the setting names,
their plain-language explanations and the choices inside the dropdowns, so
`edge` finds the light style and `unread` finds the counter source.

Layout-wise every setting is one container -- control plus explanation --
which is what makes both the folding and the search possible.

## Calls and other persistent notifications

An ongoing phone call is a persistent notification, exactly like a music
player or a download, so a plain "ignore persistent" switch swallows calls
too. The setting is therefore a choice with four values: *Ignore all*,
*Calls only*, *Calls + selected apps* (default), *Everything*.

A notification counts as a call when any of these metadata signals is
present -- no title, text or caller is ever read:

- `Notification.category` is `call`
- it has a `fullScreenIntent` (an incoming call takes over the screen; on
  Android 14+ mainly calls and alarms are allowed to do that). Only the
  *presence* is checked; the intent is never opened or sent
- it uses the system call layout (`Notification.CallStyle`, recognised by
  the style class name in `android.template`)

**The catch worth knowing:** the AOSP dialer sets `CATEGORY_CALL` only on
its *incoming* call notification. Its ongoing in-call notification carries
no category, no full-screen intent and no call layout -- it is simply
ongoing and colourised. That is why the always-allow app list exists, and
why it is **seeded with your default dialer** on first use: tick nothing and
both incoming and ongoing calls already light up. The same list covers any
other app whose persistent notifications you want (a navigation app, a
timer), and it is a second selection mode of the app picker.

Calls look like every other notification: same style, same animation, same
per-app color, and the light duration ends them like any other burst. The
app filter still wins: an app you excluded there never lights up.

## Settings backup

*Backup and restore* writes `aod-settings.json` into a folder you pick once,
and reads it back from any file you pick. Both go through the Storage Access
Framework (`DocumentExporter` / `DocumentImporter`), so the app needs **no
storage permission** and keeps access only to what you chose.

The format is pretty-printed JSON, UTF-8, written with `org.json` which is
part of Android -- nothing to add, nothing to download:

```json
{
  "format": "aod-settings",
  "version": 2,
  "app": "1.1.0",
  "exported": "2026-11-20T12:34:56",
  "settings": {
    "style": "icons",
    "timeout_sec": 3600,
    "filter_apps": ["org.example.messenger"],
    "app_colors": "org.example.messenger=#FF00E5FF"
  }
}
```

Readable in any editor, and JSON escaping means quotes, backslashes, control
characters and non-ASCII text survive a round trip unchanged.

- **A complete snapshot, not a diff.** Every setting is written with its
  *effective* value, including the ones never touched. Defaults live in one
  table (`SettingsSchema`) that `Prefs`, the settings screen and the backup
  all read, so if a later version changes a default, importing an old backup
  still restores exactly what that user had.
- One table drives both directions: type, allowed choices and the same
  numeric range the sliders offer. A hand-edited file cannot ask for a
  999999-second light or a 4000 dp icon; out-of-range numbers are clamped,
  unknown choices are skipped.
- **Removed settings are ignored, the rest still imports.** Keys are
  validated one by one, so a file from a newer version imports everything
  this version knows and silently counts the rest as ignored. Settings the
  file does not mention keep their current value. A file that is not a
  settings backup at all is refused before anything is written.
- Exported: every setting, both app lists, the per-app colors and the camera
  calibration (which is device specific -- re-detect it on another phone).
  Not exported: transient state such as which sections are folded, whether
  the accessibility service was seen on, and the picker's memory of packages
  that once notified.
- Import asks first, then refreshes the screen and re-evaluates the light.

## What the number under an icon counts

`Notification.number` is an optional field an app sets with
`Notification.Builder.setNumber()`; it is what Android's own launcher badge
shows. A messenger typically writes its unread message count there, so five
messages bundled into a single notification can show **5** instead of **1**.

Rules used here:

- **Notifications** mode: plain count of that app's notifications.
- **App's own counter** mode (default): the highest counter among that app's
  notifications, or their sum if *add up* is switched on, combined with the
  counter of its group summary (often the only place the app-wide total is
  published -- summaries are read for the number, never drawn as an icon).
- The result is never lower than the number of notifications, so an app that
  always writes `1` cannot hide that three notifications are waiting.
- `0` or no counter at all -> fall back to the notification count.

The field is voluntary and its meaning is up to the app: some write per-chat
unread, some the app-wide total on every notification (hence *highest* by
default, *add up* as the alternative). To see what your apps really publish:

```
adb logcat -s AODDBG | grep -E "icons:|summary counter"
```

`notif/own=[1/5]` means one notification carrying the counter 5; `summary=`
shows the counters taken from group summaries.

## Clock above the icons

In the *App icons* style the current time and/or date can be drawn above the
icon block. The setting offers four modes: **nothing** (default -- the screen
stays exactly as it is), **time**, **time + date**, **date**.

The clock follows the same rule as the icons: it is only drawn while a
notification is visible. No notification means a completely black screen,
clock included. It is part of the same still image -- no blinking, no frame
loop. The time is redrawn once a minute; a date-only clock is redrawn once at
midnight; with the clock off nothing is scheduled at all. Each redraw is a
short alarm wake-up that pushes one frame to the dozing panel, and the
burn-in drift moves the clock together with the icons.

Each line is configured separately:

- **Size**: 8-72 dp (time defaults to 50 dp, date to 20 dp).
- **Font**: normal, light, medium, condensed, serif, monospace -- all built
  into Android, nothing downloaded.
- **Horizontal position**: left, center (default) or right, with the same
  edge margin the icon rows keep.
- **Height above the icons**: 0-400 dp between the bottom of the line and the
  top of the icon block (time defaults to 44 dp, date to 16 dp, so with both
  on the date sits between the time and the icons). The whole group is placed
  by the shared "Vertical offset for all AOD items" in Basics: the top clock
  line starts at that offset and the icons hang below it. If a line would sit
  near the top edge, it is pushed down to the panel margin instead of being
  clipped away.

The time uses the phone's 12/24-hour setting; the date is the locale's
weekday-day-month order without a year. Setting the time, changing the time
zone or the language refreshes the clock immediately -- but only when a clock
line is actually shown; with the clock off these events do nothing.

## Setup

1. Install, open the app. On startup it asks for whatever is still missing,
   one dialog at a time: **notification access**, then the **accessibility
   service**, then **ignoring battery optimizations**. Each opens the right
   system screen; the next is offered when you come back. Buttons in the app
   open the same screens later. The accessibility service is used only to
   draw the overlay; it reads nothing.
2. Notifications that were already present before the app was installed or
   updated are read on startup (with your app filters applied) and shown on
   the AOD right away -- you do not have to wait for a new one to arrive.
3. Optional one-tap setup via ADB:
   `adb shell pm grant com.zoffcc.applications.aod android.permission.WRITE_SECURE_SETTINGS`
   then use the "Auto-enable accessibility" button that appears.

### Accessibility after an app update

Replacing the package makes the system re-evaluate its accessibility
bindings, and on many builds the service ends up switched off -- an app is not
allowed to grant itself accessibility. With the ADB grant above (which
survives updates; only an uninstall clears it) the app puts the service back
by itself: after an update (`MY_PACKAGE_REPLACED`), on boot, and when the
settings screen is opened. It appends its own component to
`enabled_accessibility_services`, so other accessibility services are left
alone, and it only ever acts when

- `WRITE_SECURE_SETTINGS` was granted, **and**
- the in-app master switch is on, **and**
- the service has been enabled on this device before (a first install still
  asks the user).

`adb logcat -s AODDBG` shows `restore: accessibility re-enabled after update`.

On Android 13+ a sideloaded app may also have its accessibility toggle blocked
by *restricted settings*: allow it once under *App info -> ... -> Allow restricted
settings*, or sideload with `adb install -r -i com.android.vending ...`.

Test without a second device:

```
adb shell cmd notification post -S bigtext -t 'Title' 'Tag' 'Hello light'
```

The tag is the identity of the notification: posting the same tag again
*replaces* it, so the count stays 1. Use different tags to see it rise:

```
for t in one two three; do
  adb shell cmd notification post -S bigtext -t 'Title' "$t" 'Hello light'
done
```

## Logging

Two streams, nothing else:

| Tag | What it carries |
|---|---|
| `AODDBG` | the verbose troubleshooting log (`Dbg.log`): state transitions, icon layout, counters, suppression decisions. Switchable. |
| `AOD-Overlay`, `AOD-Notifications`, `AOD-Main`, `AOD-Boot` | a handful of lifecycle and error messages per component, always on |

```
adb logcat -s AODDBG                                   # troubleshooting only
adb logcat -s AODDBG AOD-Overlay AOD-Notifications AOD-Main AOD-Boot
```

`Dbg.DEBUG_LOGGING = false` silences the `AODDBG` stream completely; the
component tags are built from `Dbg.TAG_PREFIX` so they cannot drift apart.
Neither stream ever contains notification titles or message text -- the app
never reads them (see [PRIVACY.md](PRIVACY.md)).

Wake locks appear in `dumpsys power` as `aod:notifications`, `aod:light`,
`aod:draw` and `aod:wake`.

## Build

- JDK 17, Android SDK 34, Gradle 8.9 (wrapper included)
- `./gradlew assembleDebug` (shrinking is on in both variants: unused
  library code is dropped, no obfuscation -- see `app/proguard-rules.pro`)
- the **test** APK has its own rules (`app/test-proguard-rules.pro`): R8 runs
  but shrinks, optimises and renames nothing. AGP minifies the androidTest
  variant because the tested build type is minified, yet it does not inherit
  the app's rule files, so without them R8 fails on compile-only references
  inside AndroidX Test (`javax.lang.model.element.Modifier`,
  `kotlin.jvm.internal.SourceDebugExtension`). A test harness also must not be
  shrunk: JUnit and Espresso find their classes reflectively
- `python3 tools/check_apk_privacy.py <apk>` -- verifies the finished APK
- `tools/find_non_ascii_chars_in_source.sh` -- source encoding guard, runs
  before every compile (see below)
- `./gradlew :app:testDebugUnitTest` -- host tests for the icon grid, the
  counter rules (`IconMath`) and the duration steps (`LightDuration`), no
  device needed
- `./gradlew :app:connectedDebugAndroidTest` -- device smoke test: the settings
  screen builds, sections fold, search finds a setting, the duration slider is
  stepped, and both services are declared
- `.github/workflows/info_test.yml` -- CI: source encoding check, privacy
  guard, unit tests, APK build and scan, then device tests and screenshots on
  an emulator (`tools/ci_emulator_shots.sh`, which also switches on the canvas
  fallback so the dark screen is capturable on an emulator that has no
  always-on panel). On a push to master the `nightly` tag is moved to that commit
  and the four screenshots are published to the `nightly` release under the
  same names every time (`aod_nightly_settings.png`, `_picker`, `_aod`,
  `_lockscreen`), replacing the previous set
- `.github/dependabot.yml` -- weekly dependency and GitHub Actions updates
- minSdk 28 (Android 9), targetSdk 34
- Dependencies: appcompat, recyclerview (UI only). Everything else is
  platform API.

## Source encoding, and why it is checked

A review diff shows glyphs, not code points. A right-to-left override can make
a line read as something other than what it compiles to ("Trojan Source"), a
zero-width character can split an identifier while looking like nothing at
all, and a homoglyph can hide a Cyrillic `a` inside an ASCII word. None of that
is visible in a normal review of a contribution, so it is checked mechanically
instead of by eye.

The rules:

| files | rule |
|---|---|
| source (`*.java`, `*.xml`, `*.gradle`, `*.sh`, `*.py`, `*.pro`, `*.properties`, ...) | **pure ASCII** -- string resources use `\uXXXX` escapes, comments use `--` |
| documentation and store text (`*.md`, `*.txt`, `*.svg`) | a fixed list of visible typographic characters (dashes, quotes, ellipsis, bullet, arrow, box drawing, ...) |
| every file | hidden characters are **never** allowed: bidi overrides and isolates, zero-width characters, BOM, soft hyphen, tag characters, variation selectors, private-use areas, C1 controls |

The last rule cannot be opened by an allowlist: `check_nonascii.sh -a 202E`
still refuses U+202E.

```
tools/find_non_ascii_chars_in_source.sh            # whole repo (tracked files)
tools/find_non_ascii_chars_in_source.sh -v         # also list the allowed ones
tools/find_non_ascii_chars_in_source.sh --staged   # only what is being committed
tools/check_nonascii.sh -a 2014,2192 README.md     # one file, own allowlist
tools/check_nonascii.sh -b -a E9 file              # raw byte mode
```

`./gradlew :app:verifyAsciiSources` runs the repo check on its own, and
`preBuild` depends on it, so a build fails on a violation. As a pre-commit
hook:

```
printf '#!/bin/sh\nexec tools/find_non_ascii_chars_in_source.sh --staged\n' \
    > .git/hooks/pre-commit && chmod +x .git/hooks/pre-commit
```

`--staged` reads the staged blobs, not the working tree, so editing a file
after `git add` cannot smuggle anything past it.

## Brightness of the always-on screen

Three rules in Android's `DisplayPowerController` decide everything here:

1. A window that sets `LayoutParams.screenBrightness` wins over every other
   source -- **and asking switches auto brightness off entirely**
   (`autoBrightnessEnabled` requires that no brightness has been decided
   yet). With adaptive brightness on, the slider works *through* the
   auto-brightness controller, so any request of ours disconnects the panel
   from the slider.
2. A **dozing** panel with no request uses the ROM's fixed doze brightness
   (`config_screenBrightnessDoze`, `REASON_DOZE_DEFAULT`). No slider moves
   that, and no app can read it.
3. An **awake** panel with no request uses the system brightness: adaptive
   or manual, slider included, live.

Reading `Settings.System` does not help: with adaptive brightness on,
Android writes its *own sensor-computed* value into that setting
(`REASON_AUTOMATIC` -> `updateScreenBrightnessSetting`), and while the slider
is being dragged it uses a temporary value that is never published at all
(`REASON_TEMPORARY`). There is no public API for the live brightness.

So matching the phone is not a value to compute, it is interference to
avoid. Two switches decide what happens:

- **Keep the panel awake for the dark screen** (`AWAKE_PANEL`, **off by
  default** and recommended off): the overlay window itself carries
  `FLAG_KEEP_SCREEN_ON` while a
  light is showing, so rule 3 applies instead of rule 2. The flag cannot wake
  an already sleeping panel, so one short `ACQUIRE_CAUSES_WAKEUP` request is
  sent per burst and the flag then holds it. No activity is involved: a
  `showWhenLocked` activity used for this was dismissed by some ROMs within
  milliseconds of appearing, which produced a wake/sleep loop.
- **Use the system brightness** (`FOLLOW_BRIGHTNESS`, on by default, only
  available while the panel is kept awake): the overlay asks for no
  brightness of its own (`BRIGHTNESS_OVERRIDE_NONE`), so rule 1 never kicks
  in either.

Any `ACTION_SCREEN_OFF` that arrives while the flag is held clears it at
once. How long the panel stayed up tells the two causes apart: under 12 s is
the ROM refusing (pause 2 minutes), longer is a power-key press (pause 20 s,
or 2 minutes after three presses in a row). A genuine unlock resets both. A
wake by the user deliberately does *not* shorten the pause, or the next power
press would be answered by waking the panel again. No wake loops.

That gives three states:

| keep awake | use system | panel | brightness | cost |
|---|---|---|---|---|
| on | on | awake | the phone's own, slider live | highest |
| on | off | awake | own percentage, usually honoured | middle |
| off | -- | dozing | own percentage, ROM may clamp or ignore it | lowest |

In the first state the always-on screen is literally driven by the same
brightness as the lock screen, and the status-bar slider moves it live.
Following is impossible on a dozing panel, which is why that switch is
disabled when the panel is allowed to doze.

What the awake panel costs: a dozing panel refreshes itself and lets the SoC
sleep, an awake one keeps the display pipeline running -- roughly 100-400 mW
extra on OLED (very roughly 0.5-2 % of battery per hour of light-up time),
considerably more on LCD, where the backlight lights the whole panel. It is
bounded, not all-night: the flag is held only while a light is showing, the
burst timeout ends it, no notification means nothing is woken at all, and a
screen-off while holding it backs off for two minutes so the power button
stays authoritative (the light keeps working on the dozing panel meanwhile).
That time also shows up as screen-on time in Android's battery stats.

Some ROMs run their own always-on display outside the framework path and pin
its backlight themselves. On those, neither a window brightness request nor
the system slider changes the dark screen -- measured on one such device:
requesting 100 % and 5 % looked identical. There is no app-side fix for that;
the brightness lives in the ROM's own settings (Lock screen / Always-on
display). The settings screen says so instead of pretending otherwise.

The debug log prints `dark`, `panel` (`awake (window flag)` / `awake canvas` /
`dozing`), `mode`, `awake`, `follow`, `pct` and `requested` for every change,
plus `awake panel: HOLDING display awake` / `released` / `off after ...ms -> pause ...s`.

## Battery and CPU

A still light should cost nothing between the few frames it draws, so the
timing is built around that:

- **Still image (the default icons style, interval off):** the end of the
  light and the burn-in move (10 minutes by default, 1/2/10/20 selectable) are
  `AlarmManager` alarms
  (`setExactAndAllowWhileIdle`). One wakeup each, instead of a wake lock held
  for the whole burst. A `Handler` timer is armed as well as a free backstop:
  it costs nothing while the CPU sleeps and ends the burst earlier if the
  phone happens to be awake anyway. Whichever fires first wins.
- **The CPU wake lock is only held while something needs it** -- an animating
  style (ring/edge/dot with pulse, flash or rotate), interval mode, or a
  dozing panel with no alarms available. An awake panel already keeps the
  phone awake, so no lock is taken there at all. It is acquired and released
  as the panel state changes, not once per burst.
- **The draw lock is held for drawing, not for the burst.** A still image
  takes 1.5 s at the start and under a second for each later change (new
  notification, burn-in move, geometry or colour change, taking the light
  down). Only an animating style holds it for its whole run. Previously it
  was held for the entire burst -- minutes of awake CPU to show a picture
  that never moves.
- **A still image gets no GPU layer.** `LAYER_TYPE_HARDWARE` is only set for
  the styles that animate; the icons style draws straight into the window
  surface, which gives back roughly 10 MB of GPU memory and the per-frame
  layer upkeep.
- **ECO mode really sleeps between frames** instead of asking for every
  vsync and discarding the frames, and the slow redraw tick is one reusable
  `Runnable` rather than a fresh allocation every 100 ms.
- **The battery receiver only exists while the battery light is on.**
  `ACTION_BATTERY_CHANGED` arrives on every level and charge change; with
  the feature off it was waking the process for nothing.
- **No keyguard polling while the panel is off.** An unlock always arrives as
  `ACTION_SCREEN_ON`, which re-arms the 300 ms watch; polling a dozing phone
  every two seconds only woke the CPU (~145 needless wakeups per burst).
- Short, bounded locks are used where work really happens: 2.5 s to evaluate
  a screen-off, 2 s around an alarm's draw, 1 s per notification event.

Exact alarms need `SCHEDULE_EXACT_ALARM` from Android 12 on. Where it is not
granted ("Alarms & reminders" in the app's system settings), the app logs
`exactAlarms=false` and falls back to the Handler timers plus the wake lock
they need -- correct, just not as cheap on a dozing panel. Nothing prompts for
it; the permission is optional.

Every decision is in the log: `timers: path=static|animated via=alarm|handler
exactAlarms=...` and `cpu lock HELD|released animated=... dark=... alarms=...`.

## Compatibility notes

- Whether an overlay is visible **on top of the native AOD** depends on
  the OEM; where it is not, an opt-in dark canvas fallback is available.
- The light geometry is portrait-only.
- Keeping any light active costs battery; use ECO / interval mode and
  short timeouts. The dark canvas keeps the panel technically "on" at low
  brightness -- on OLED this is cheap, on LCD it is not.
- Matching the system brightness needs the panel kept awake while the light
  is up, which costs more battery than a dozing always-on screen. Switch
  **Keep the panel awake for the dark screen** off to let the panel doze
  and use an own, fixed brightness instead.
- Long-term static light can contribute to burn-in; the built-in position
  shifting mitigates this, but use at your own risk.

## App icon

Original artwork, drawn as vectors: a dark rounded square with a single
thin cyan ring and one white dot on it. The card sits in the adaptive
icon *background* layer and is sized so that it stays a rounded square
inside the launcher mask (circle, squircle, square or teardrop) instead of
being clipped by it. A monochrome layer is included for themed icons, plus
legacy bitmap mipmaps for Android 7 and older.

Vector masters, the palette, size rules and the asset renderer live in
[`docs/`](docs/brand.md) -- `icon.svg`, `icon-square.svg` (store listing),
`icon-monochrome.svg` and `render_assets.py`. Store text and images are in
`fastlane/metadata/android/en-US/`.

## Privacy

No `INTERNET` permission, no analytics, no accounts, no tracking libraries.

**The app never reads the title or the text of a notification.** Not
`EXTRA_TITLE`, `EXTRA_TEXT`, `EXTRA_BIG_TEXT`, `EXTRA_SUB_TEXT`,
`EXTRA_INFO_TEXT`, `EXTRA_SUMMARY_TEXT`, `EXTRA_TEXT_LINES`, `EXTRA_MESSAGES`,
`tickerText` or any other message payload. Those values are never fetched,
never parsed, never put in a variable or field, never parcelled, never
written to disk and never logged -- so they cannot leak later by accident.

What is read is metadata only: the posting package, the system key, the post
time, the clearable/foreground/group flags, `Notification.number` (the
counter the app itself publishes), and four non-text extras keys used for
suppression (`android.progress`, `android.progressMax`,
`android.mediaSession`, `android.template`).

This is enforced at build time, not just promised in a document: the
`verifyNoMessageTextAccess` task (`gradle/privacy-guard.gradle`) runs before
every compile and fails the build if any source file reads notification
content, names a content key, keeps a text field on the parcel that travels
between components, or opens the bundle outside the one boundary class.
Run it on its own with `./gradlew :app:verifyNoMessageTextAccess`.

The APK does not even contain the code: both build types shrink unused
classes away, which removes AndroidX's own notification helpers
(`NotificationCompat` and friends). `tools/check_apk_privacy.py` reads the
string table of every dex in a finished APK and fails if any content key or
content-reading API is present -- it reports zero for the shipped build.

Full details: [PRIVACY.md](PRIVACY.md).
