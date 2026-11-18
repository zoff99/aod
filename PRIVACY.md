# Privacy: what this app reads, and what it can never read

AoD shows a light, or the icons of the apps that are waiting for you. To do
that it needs to know **that** a notification exists — never **what it says**.

## The rule

> The title and the text of a notification are never read, never parsed,
> never copied into a variable or a field, never parcelled, never written to
> disk and never logged.

That covers every content field of `android.app.Notification`:

| Never read | Key |
|---|---|
| `Notification.EXTRA_TITLE` | `android.title` |
| `Notification.EXTRA_TEXT` | `android.text` |
| `Notification.EXTRA_BIG_TEXT` | `android.bigText` |
| `Notification.EXTRA_SUB_TEXT` | `android.subText` |
| `Notification.EXTRA_INFO_TEXT` | `android.infoText` |
| `Notification.EXTRA_SUMMARY_TEXT` | `android.summaryText` |
| `Notification.EXTRA_TEXT_LINES` | `android.textLines` |
| `Notification.EXTRA_MESSAGES` / `EXTRA_HISTORIC_MESSAGES` | `android.messages`, `android.historicMessages` |
| `Notification.EXTRA_CONVERSATION_TITLE` | `android.conversationTitle` |
| `Notification.EXTRA_PEOPLE` / `Person` | `android.people`, names, addresses |
| `Notification.EXTRA_MEDIA_TITLE` / artist / album | `android.mediaTitle`, … |
| `Notification.EXTRA_REMOTE_INPUT*`, `MessagingStyle`, `RemoteInput` | reply payloads |
| `Notification.tickerText` | the ticker |
| `Notification.largeIcon` / `getLargeIcon()` | usually a contact photo |
| `Icon.loadDrawable()`, bitmap / URI / data icons | app-supplied image data of unknown provenance |

There is no "preview" feature any more, no text renderer, no text field on
the object that travels between the components, and no setting that could
turn one back on. Nothing in the app can display message text, so nothing
has any reason to fetch it.

## What is read instead

All notification access lives in **one class**, `notificationobserver.java`.
Everything it takes from a notification is metadata:

| Value | Used for |
|---|---|
| `StatusBarNotification.getPackageName()` | which app posted; icon, color, filter |
| `StatusBarNotification.getKey()` | identity, so a dismissal removes the right icon |
| `StatusBarNotification.getPostTime()` | ordering, newest first |
| `StatusBarNotification.isClearable()` | skipping persistent notifications |
| `Notification.flags` | foreground-service / no-clear / group-summary |
| `Notification.number` | the counter the app itself publishes (unread count) |
| `Notification.getSmallIcon()` → `getResId()` | which status-bar symbol to draw. Only `Icon.TYPE_RESOURCE` is accepted; the id is a number |
| `Notification.color` | the accent the app chose for its notification icon (an ARGB number) |
| `extras.getInt("android.progress")`, `getInt("android.progressMax")` | skip running downloads |
| `extras.getParcelable("android.mediaSession")` | skip media players (a token, no text) |
| `extras.getString("android.template")` | the style **class name**, e.g. `android.app.Notification$MediaStyle`; compared inline, never kept |

A notification's *symbol* is drawn from the posting app's own resources, by
resource id, read-only. An app that supplies a bitmap or URI icon instead of a
resource is ignored — such an icon could be a photo, so it is never loaded and
the launcher icon is used instead.

The bundle is opened for those four keys only. No `CharSequence` is ever
requested from it, and no key outside that list is ever named in the code.

Everything downstream — the overlay service, the renderer, the settings
screen, the fallback canvas — receives only `NotificationItem`: package,
key, post time, color, a battery-event flag and a number. It has no field
that could hold text.

Logs (`adb logcat -s AODDBG`) print package names, keys, counters and state
transitions. No content, because no content exists in the process.

Also: no `INTERNET` permission, no analytics, no accounts, no crash
reporting, no network code at all. The accessibility service subscribes to a
non-existent package, so it receives no events and reads no screen content;
it is used purely as the privilege to draw an overlay window.

## Enforced by the build, not by good intentions

`gradle/privacy-guard.gradle` registers **`verifyNoMessageTextAccess`**, and
`preBuild` depends on it, so it runs before every compile of every variant.
It parses every Java source file (comments and string literals are handled
separately, so documentation may name the forbidden fields) and fails the
build when:

1. any file references `Notification.EXTRA_*`, `tickerText`,
   `MessagingStyle`, `NotificationCompat`, `RemoteInput` or `Person`;
2. any file calls `getCharSequence(...)`, `getCharSequenceArray(...)` or
   `getCharSequenceArrayList(...)`;
3. any file other than `notificationobserver.java` touches `.extras` or
   calls `getNotification()`;
4. a `Bundle` variable is used for anything other than `containsKey`,
   `getInt`, `getParcelable`, `getString`;
5. a string literal names an `android.*` key that is not one of the four
   metadata keys above (permission names excepted);
6. `NotificationItem` declares any `String` field other than `pkg` and
   `key` — so no text can be stored, cached or parcelled by accident;
7. any file mentions `largeIcon` / `getLargeIcon`, calls `Icon.loadDrawable()`
   or accepts `Icon.TYPE_BITMAP` / `TYPE_URI` / `TYPE_DATA`.

Run it on its own:

```
./gradlew :app:verifyNoMessageTextAccess
```

Example of a rejected change:

```
PRIVACY GUARD FAILED - notification content must never be read:
  notificationobserver.java: Notification.EXTRA_* constants give access to notification content
  notificationobserver.java: CharSequence values out of a bundle are text
  notificationobserver.java: Bundle.getCharSequence() is not an allowed metadata read
  notificationobserver.java: "android.text" is not an allowed notification key
  NotificationItem.java: NotificationItem may not carry a String field "title"
```

Extending what is read therefore requires editing the guard on purpose —
a deliberate, reviewable step, not something that can slip in with a refactor.

## Not even in the APK

AndroidX ships notification helpers (`androidx.core.app.NotificationCompat`,
`NotificationCompatBuilder`, `NotificationManagerCompat`, …) that know how to
read titles and message text. Nothing in this app calls them, and both build
types run R8 shrinking (`app/proguard-rules.pro`: shrink only, no
obfuscation, no optimisation), so those classes are not in the dex at all.

You can verify any finished APK yourself, without an SDK:

```
python3 tools/check_apk_privacy.py aod-debug.apk
```

It reads the string table of every `classes*.dex` and fails if it finds a
content key (`android.title`, `android.text`, `android.bigText`, …) or a
content-reading API (`getCharSequence`, `tickerText`, `MessagingStyle`,
`RemoteInput`, `NotificationCompat`). For the shipped build it reports:

```
dex files: 1, strings: 15398
allowed metadata keys present: android.progress, android.progressMax, android.mediaSession, android.template

OK - no notification title or message text can be read by any code in this APK
```

## What is stored on the device

Only settings (colors, sizes, positions, switches), the cached camera-cutout
geometry, the per-app color overrides, the list of selected packages and the
set of package names that have posted a notification (bounded at 400, used to
populate the picker with apps that have no launcher icon).
No notification history, no counters history, no text of any kind.
