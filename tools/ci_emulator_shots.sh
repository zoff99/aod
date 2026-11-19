#!/usr/bin/env bash
#
# CI screenshot pass, run inside the android-emulator-runner step as ONE line:
#
#     bash tools/ci_emulator_shots.sh /home/runner
#
# Why this is a file and not inline YAML: the emulator runner executes every
# line of its `script:` block in a separate `sh -c`, so a variable assigned on
# one line is gone on the next. That is exactly what broke the first run -
# PKG was empty, `am start -n /.MainActivity` failed, and both "app"
# screenshots ended up being the same picture of the launcher.
#
# Everything here is best effort: a capture that fails must not fail the build.
# The device tests run before this script and are the part that can fail CI.

set -u

PKG=com.zoffcc.applications.aod
OUT="${1:-$HOME}"
mkdir -p "$OUT"

say() { echo "=== $*"; }

shot() {
    adb exec-out screencap -p > "$OUT/$1" || true
    ls -l "$OUT/$1" || true
}

adb root || true
adb wait-for-device || true
sleep 3

# ---------------------------------------------------------------------------
# An AOSP emulator has no always-on panel, so a screen-off capture is just
# black. The app has a setting for precisely such phones: the canvas fallback
# draws the dark field itself on an awake panel. Write it before the process
# starts, because SharedPreferences are cached per process.
# ---------------------------------------------------------------------------
say "configure the app for a phone without an always-on panel"
adb shell am force-stop "$PKG" || true

TMPXML=$(mktemp)
cat > "$TMPXML" <<'XML'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <boolean name="canvas_fallback" value="true" />
    <boolean name="aod_light" value="true" />
    <boolean name="screen_on_light" value="true" />
    <boolean name="blackout_full" value="true" />
    <boolean name="relight_relock" value="true" />
    <string name="style">icons</string>
    <int name="timeout_sec" value="290" />
</map>
XML
adb push "$TMPXML" /data/local/tmp/aod_settings.xml || true
adb shell run-as "$PKG" mkdir -p shared_prefs || true
adb shell run-as "$PKG" cp /data/local/tmp/aod_settings.xml shared_prefs/aod_settings.xml || true
rm -f "$TMPXML"

say "grant the notification listener and the accessibility service"
adb shell cmd notification allow_listener "$PKG/$PKG.notificationobserver" || true
adb shell settings put secure enabled_accessibility_services "$PKG/$PKG.overlayservice" || true
adb shell settings put secure accessibility_enabled 1 || true
sleep 6

say "01 settings screen"
adb shell am start -W -n "$PKG/.MainActivity" || true
sleep 10
shot aod_nightly_settings.png

say "02 app picker"
adb shell am start -W -n "$PKG/.AppPickerActivity" || true
sleep 8
shot aod_nightly_picker.png

say "03 dark always-on screen with an icon"
adb shell locksettings set-pin 1234 || true
adb shell cmd notification post -S bigtext -t 'AOD CI' ci1 'test' || true
sleep 3
adb shell input keyevent 26
sleep 12
shot aod_nightly_aod.png
adb shell dumpsys display | grep -E 'mScreenState|mScreenBrightness' > "$OUT/display_state.txt" || true
adb shell dumpsys window | grep -E 'mCurrentFocus|mFocusedApp' >> "$OUT/display_state.txt" || true

say "04 lock screen, still locked, with the light on it"
# First press blanks the canvas, the second wakes to the keyguard. The light
# stays because the phone is still locked.
adb shell input keyevent 26
sleep 4
adb shell input keyevent 26
sleep 10
shot aod_nightly_lockscreen.png

say "logs"
adb logcat -d -s AODDBG AOD-Overlay AOD-Notifications AOD-Main AOD-Boot > "$OUT/aod_log.txt" || true
adb logcat -d > "$OUT/adb_log.txt" || true
ls -l "$OUT"/aod_nightly_*.png || true
