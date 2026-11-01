# Plan: Quick Settings side card over any app

Status (2026-11-01): **implemented and tested on the Skyworth 4K Google TV Stick (Android 14), Glass dark and Material light. Not yet looked at: Glass light, input tiles (a stick has no HDMI), *Original* (listener taken back).** Notes from the tests are at the end.

Review notes (2026-11-01), what was wrong or missing:
- The plan still used the old names (Sky Home Launcher, `SkyHomeLauncher.apk`, `adb logcat -s SkyHomeLauncher`, "Sky Home Launcher mode"). The app is
  Sky Home Launcher now: package `com.skyhome.launcher`, `SkyHomeLauncher.apk`, log tag `SkyHomeLauncher`. Fixed below.
- Confirmed in code: `RemoteKeyService.onKeyEvent` exists, `remote_key_service.xml` already listens for
  `typeWindowStateChanged` (step 2 needs no manifest change), `Prefs.TV_SETTINGS` / `FMT_SETTINGS` and `KEY_SKY_SETTINGS`
  are as described, and `RemoteKeys.open()` is where the new branch goes (`RemoteKeys.java:127`).
- The picker adds `TV_SETTINGS` only `if (RemoteKeys.SKYWORTH || Prefs.hasLearned())` (`SettingsActivity.java:1798`). "Quick
  Settings" must follow the same condition (or all brands, question 4), not be added unconditionally.
- `Prefs.defaultApp` for the gear button is at `Prefs.java:484` (the plan said ~270).
- `SleepOverlay` already draws over any app from its own process; read it before writing `QuickCard`. The difference is
  that `SleepOverlay` is an overlay service and cannot see keys, while the card must live in the `:keys` process to use
  the key filter, with `TYPE_ACCESSIBILITY_OVERLAY`.
- The class comment in `RemoteKeys.java:20` ("the gear button needs the stock launcher") is stale and goes with step 4.

## Goal

A small card that slides in from the right edge, over whatever app is running, when the remote's settings button is
pressed. It replaces the stock Google TV "quick settings" side panel, which is gone in Sky Home Launcher mode (it lives in the
stock launcher and SystemUI, which the switch turns off). Today that button opens the full Settings app
(`Prefs.TV_SETTINGS`).

First target: the Skyworth remote's gear button (`KEY_SKY_SETTINGS`, `KEYCODE_NOTIFICATION`), tested on the Skyworth 4K
Google TV Stick (Android 14).

## What already exists (and why this is small)

- `RemoteKeyService` is an accessibility service in the `:keys` process with `flagRequestFilterKeyEvents`. It sees every
  key **before the focused app**, so it works inside Netflix, YouTube and the rest, and it can consume a key.
- `RemoteKeys.onKey()` maps each button to an app or to `Prefs.TV_SETTINGS` through `Prefs.keyApp()` (read from
  `remote-keys.txt`, never SharedPreferences, because of the `:keys` process). Settings > Remote Buttons already lets the
  person pick per button.
- `RemoteKeys.supported()` limits all this to TCL, Vestel, KTC, Sony, Formovie and Skyworth. Same limit here.

So the trigger, the per-button choice and the on/off switch exist. The new parts are one more choice for a button and
the card.

## Decisions (2026-11-01)

1. The Skyworth gear button defaults to Quick Settings.
2. Tiles as listed below; no Sleep timer.
3. Inputs are separate tiles (Input 1, Input 2, AV…), like the Wi-Fi and Bluetooth tiles.
4. Other brands set it from a custom button (Remote Buttons > Added buttons); "Quick Settings" is in the picker for them too.
5. No timeout: it closes on Back or on the button again.
6. The app underneath **pauses** (answer read as "app should pause"): the card is a normal focusable window.
7. The card follows the active look and the dark/light mode (`Themes.current()`, `Prefs.theme()`).
8. New: the card scrolls, and the bottom has the TV's notifications and a Clear button.
9. The card pauses the app (focusable window): confirmed.
10. The switch may grant notification access (removed by *Original*): confirmed.
11. OK on a notification opens its app: confirmed.

## Design

### The choice

A new special value next to `TV_SETTINGS`: `Prefs.QUICK_SETTINGS = "android:quick"`, shown in the Remote Buttons picker
as "Quick Settings" (`SettingsActivity.loadApps`, ~line 1798) for **every** brand (decision 4), and the default of the
Skyworth gear button (`Prefs.java:484`). Every other button keeps its default.

In `RemoteKeys.open()`: a button set to `QUICK_SETTINGS` calls `QuickCard.toggle(context)` on `ACTION_DOWN` with
`getRepeatCount() == 0` and consumes the key (next to `TV_SETTINGS`, `RemoteKeys.java:127`).

### The window

- Added by `RemoteKeyService` with `WindowManager.addView`, type `TYPE_ACCESSIBILITY_OVERLAY` (no extra permission).
- **Focusable** (no `FLAG_NOT_FOCUSABLE`), so the card takes the D-pad and Back like any window and the app below pauses.
  This drops the key-filter hack of the first draft: `RemoteKeyService.onKeyEvent` gets nothing new, and the view's own
  `onKeyDown` handles Up/Down/OK/Back. Simpler, and nothing slows the filter.
- Protected-video surfaces can no longer hide the card (the app pauses and loses focus).
- Size: about a quarter of the screen width, full height, gravity right; slides in and out with a translate animation
  (transform only; a TV GPU may be weak). Closing hands focus back to the app.
- The button pressed again also closes it: `RemoteKeys.onKey` still sees that key first, so `QuickCard.toggle` closes
  when open (the card, being focused, may get the key first; handle the same key code in its `onKeyDown` too).

### The view

One hand-drawn `View` (the `HomeView` approach: no layouts, no libraries, no allocations in `onDraw`): a vertical list
that scrolls with the focus (one offset eased toward the focused row):

1. Title row ("Quick Settings").
2. Tiles, one per row, icon + name: Settings, Wi-Fi, Bluetooth, each Input, Sky Home Launcher, Apps.
3. **Notifications** at the bottom: a header, one row per notification (app name, title, one line of text), newest
   first, and a **Clear** button as the last focusable row. "No notifications" when empty.

Look: drawn like the Settings screens, with their own code. The background is a real `Scene` (the moving waves, the sky of
the time of day from `Shaders.sky`, stepped every 100 ms; the right edge of the screen's background) and the focus row is the
`Look`'s own `rowFocus` (the glass's white pill, Material's lavender one). Text takes the Look's ink
(`inkAlpha`, `inkFocusedAlpha`), so it is white on the glass and dark on Material light. **The `:keys` process must not read
`SharedPreferences`**, so `Prefs.setLook`/`setTheme`/`setLanguage`/`setQuickTiles` and `HomeData` write look, theme, language,
tiles, sunrise and sunset to `quick-look.txt` (`QuickLook`), read each time the card opens. The window is hardware accelerated
(the shader needs it). Keep `HomeApps` and `Backdrop` out of `:keys`.

What was tried and dropped: a flat panel with solid tiles (looked generic), real window blur
(`isCrossWindowBlurEnabled` is false on the Skyworth stick, `mBlurEnabled=false`), and `Frost` (the home screen's soft glass)
as a static panel (looked like a smooth gradient, not frosted). Over another app the picture behind the card is not available,
so the card can't be frosted; a screenshot through the app's own ADB would, but costs about a second and reads the screen.

### Tiles

Each tile starts an intent with `FLAG_ACTIVITY_NEW_TASK` and closes the card. None changes a setting by itself
(project rule: no picture, sound, network or account changes).

| Tile | Opens |
|---|---|
| Settings | `Settings.ACTION_SETTINGS` (as today) |
| Wi-Fi | `Settings.ACTION_WIFI_SETTINGS` (only the screen; the person changes the network there) |
| Bluetooth | `Settings.ACTION_BLUETOOTH_SETTINGS` (pair a remote, headphones, a keyboard) |
| Input 1, Input 2, AV… | one tile per input of the TV: `ACTION_VIEW` on `TvContract.buildChannelUriForPassthroughInput(inputId)`. Names from `TvInputManager` (`TvInputInfo.loadLabel`/`loadCustomLabel`: "HDMI 1", "AV"). |
| Sky Home Launcher | the home screen (`HomeActivity`) |
| Apps | Sky Home Launcher's All Apps (opens `HomeActivity` on it) |

- Inputs: read `TvInputManager.getTvInputList()` when the card opens (a local query), keep the passthrough ones and the
  tuner, skip hidden ones. A TV with none shows none. Use `TvInputManager` directly, not `HomeApps.inputGroup`, to keep
  `HomeApps` out of `:keys`; read `HomeApps.inputGroup` (`HomeApps.java:307`) first and match how it names and filters.
- Wi-Fi / Bluetooth: `resolveActivity` first; if nothing answers, fall back (Wi-Fi → `ACTION_WIRELESS_SETTINGS`,
  Bluetooth → the "Connected devices" screen) or leave the tile out, not greyed.
- The list scrolls, so the number of tiles no longer matters.

### Notifications

Reading other apps' notifications needs a `NotificationListenerService` the person has allowed. This is the biggest
addition to the plan:

- New `QuickNotifications` service (`BIND_NOTIFICATION_LISTENER_SERVICE`) in the `:keys` process, so the card reads its
  list directly (`getActiveNotifications()`), kept as plain strings, no icons or pictures (memory).
- **OK on a row** opens the notification's app: send its `contentIntent` (`PendingIntent.send()`; fall back to the
  app's launch intent when it has none or it was cancelled), close the card, and cancel that notification the way
  Android's `autoCancel` would (`cancelNotification(key)`) when the notification asks for it. Ongoing ones stay.
  Keep each row's `PendingIntent` only while the card is open.
- **Clear** calls `cancelAllNotifications()` (ongoing ones such as a download or a media session stay, as in Android).
- **Allowing it:** the listener must be in the secure setting `enabled_notification_listeners`. Sky Home Launcher mode
  already has the TV's own ADB, so the switch can run `cmd notification allow_listener com.skyhome.launcher/…` and
  *Original* removes it (`disallow_listener`), the same pattern as the accessibility service. Handle it in every place
  `HomeMode` handles that service (`applyOriginal`, `revertHomeNow`, `verifyNow`).
  Where the TV refuses it, the section says "Notifications are unavailable" (`Capabilities.missing`), without Clear.
- Android TV shows few notifications (no shade): the section is often empty, and that is fine.
- Notification text is private: nothing is logged, written to a file or sent anywhere, and it is dropped when the card
  closes. `AppLog` gets counts only.

## Steps

1. **`QuickCard.java`** (new, `:keys`): `toggle(Context)`, `isOpen()`, the view (list, scroll, theme colours, keys) and
   the tile list. Keep it small: it loads with the key service, whose memory counts on 1 GB TVs.
2. **`QuickNotifications.java`** (new, `:keys`) and its manifest entry: the listener, `snapshot()` and `clearAll()`.
3. **`RemoteKeyService`**: close the card in `onUnbind`/`onDestroy` and on `typeWindowStateChanged` when another app
   comes to the front. No `onKeyEvent` change.
4. **`Prefs`**: `QUICK_SETTINGS`, the gear button's default, `keyApp()` accepting it, and writing `look` and `theme`
   where `:keys` can read them.
5. **`RemoteKeys.open`**: the new branch; update the class comment at line 20.
6. **`SettingsActivity.loadApps`**: add "Quick Settings" for every brand.
7. **`HomeMode`**: allow the notification listener on the switch, remove it on *Original*.
8. **Text**: new strings through `Ui.tr`, then `py tools/translate.py`; Portuguese, Spanish, German, Russian and Turkish
   by hand; keep placeholders.
9. **Docs**: `CLAUDE.md` (the Skyworth gear line; a Quick Settings paragraph), README's Remote Buttons text, and
   `docs/` pages if they list the buttons.
10. **Check**: `./build.sh` ends with `Built SkyHomeLauncher.apk`, `py tools/translate.py --check`, then `tools/check.sh`.

## Testing

On the Skyworth stick, in Sky Home Launcher mode with the accessibility service on:

- Gear button on the home screen, inside YouTube (playing) and inside Stremio: the card slides in, the app pauses, the
  D-pad moves in the card only, and the list scrolls to keep the focused row visible.
- Back and the gear button again close it; the app gets focus back at once and resumes.
- OK on each tile opens the right screen and the card is gone afterwards; each Input tile switches to that input.
- Wi-Fi and Bluetooth tiles open the TV's screens, and are left out where none exists.
- Notifications: OK on a row opens its app and removes it (unless ongoing). Post one (`adb shell cmd notification post -t Title tag Text`), it shows; Clear removes it and shows
  "No notifications". With the listener not allowed, the section says unavailable.
- Change the look and dark/light in Home Settings: the card follows on its next open.
- Another app launching while the card is open closes it.
- *Original*: no card; the accessibility service and the notification listener are both off again.
- A custom button on a TCL/Sony/other TV set to Quick Settings opens the card; "Nothing" still passes the key through.
- A button set to TV Settings still opens Settings. `adb logcat -s SkyHomeLauncher` shows
  `remote key ... -> Quick Settings`; no ANR; `:keys` stays small (`dumpsys meminfo`).

## Risks

- **Notification access is a sensitive permission.** Granted only in Sky Home Launcher mode, removed by *Original*, and
  the text never leaves the card. If the switch cannot grant it, the rest of the card still works.
- **Scroll and focus bugs in a hand-drawn list** (focus off-screen, jumps on a short list): test with 0, 1 and many
  notifications and 10+ inputs.
- **Focusable window and Back:** the card must always give focus back, also when the service loses its binding.
- **Skyworth's own receiver** opens the stock panel for the gear button if we don't consume it. We consume it, but this
  was only checked with "TV Settings" so far.
- **Stale theme in `:keys`** if the file isn't rewritten when the look changes: write it from the setters, read on every open.
- The 1 GB / 720p TVs: flat drawing only, no bitmaps; measure `:keys` once.

## Not in this plan

- Changing picture, sound, Wi-Fi or account settings from the card (project rule). The Wi-Fi and Bluetooth tiles only
  open the TV's screens; no on/off toggle, no connecting to a network.
- Replying to a notification (v1: read, open its app, clear).
- Sleep timer tile.
- Recreating the stock panel's own toggles (they belong to SystemUI).
- LG and Samsung (`web/`): no such key hook there.

## Open questions

None left. All answered on 2026-11-01 (decisions 9 to 11 above).

## Test results (2026-11-01, Skyworth stick, Android 14)

Works: the card opens from the gear button over the home screen, Stremio and its episodes; the app pauses; the D-pad moves
in the card only; Settings, Wi-Fi and Bluetooth tiles open the TV's screens; the notifications list, *Clear* empties it;
Back and the gear button again both close it and hand focus back; notification access is granted by the app itself after
an update.

Found and fixed:
- Google TV has no `ACTION_BLUETOOTH_SETTINGS`. The Bluetooth tile opens `com.android.tv.settings.CONNECTED_DEVICES`
  (Connected devices: paired and previously connected devices, *Pair remote or accessory*). `CONNECT_INPUT` ("Add accessory")
  only searches for new devices and needs `BLUETOOTH_CONNECT`, so it is not used.
- The remote can send the gear key twice: `QuickCard.toggle` ignores a second call within 500 ms.
- Keys sent with `adb shell input keyevent` skip the accessibility key filter: only the real remote opens the card.

Not tested: the input tiles (a stick has no HDMI inputs), a long notification list (scroll), the Material look, light mode,
other brands' custom buttons, and *Original* (check that the listener is taken back).

## Configurable tiles (2026-11-01)

*Home Settings > Quick Settings tiles* picks and orders the tiles (the shelf editor, `editingQuick`). Keys: `settings`, `wifi`,
`bluetooth`, `inputs`, `home`, `apps`, `notifications` and `app:<package>` (a custom tile that opens an app, with the app's own
icon). Saved by `Prefs.setQuickTiles` and copied to `quick-look.txt` (`tiles=`) for the :keys process. The old "Sky Home
Launcher" tile is now "Home". The notifications section is on or off only: its place in the list doesn't move it.
Later: more custom actions than "open an app" (the key is already a prefix: `app:`).

## Final state (2026-11-01)

- Tiles are chosen in *Home Settings > Quick Settings tiles*; Notifications is pinned at the top of that editor as On/Off.
- Bluetooth opens `com.android.tv.settings.CONNECTED_DEVICES` (paired and previously connected devices, *Pair remote or
  accessory*). No `BLUETOOTH_CONNECT` permission, no extra grant.
- The card looks like Settings (see "The view"); it was checked in Glass dark and Material light.
- Double gear presses are ignored for 500 ms. A log line says what closes the card (`quick settings closed: a window of <pkg>
  came to the front`); twice the card seemed to close by itself over the home screen and was not reproduced.
- Test notifications: `adb shell cmd notification post -t Title tag Text` (the shell drops the quotes, so only the first word of
  a multi-word title or text arrives; that is the command, not the app).
- First open after a restart takes about 0.7 s (fonts, the Scene's shaders). Not measured: the key process's memory with the
  Scene up, or its cost on a 1 GB TV.

- **Showing the card without a remote** (screenshots, tests): `adb shell am broadcast -a com.skyhome.launcher.QUICK_SETTINGS --es do show|hide|toggle`.
  `RemoteKeyService` registers the receiver with the `DUMP` permission, which the shell has and apps do not. `tools/shots.sh <folder> <look> <theme> quick`
  uses it. (`adb shell input keyevent` skips the key filter, so it cannot open the card.)
- Screenshots in `screenshots/`: `material-dark-quick-settings(.jpg|-notifications.jpg)`, `material-dark-settings-quick-tiles.jpg`, and the
  Home Settings screens `material-dark-settings-02/02b/02c` retaken (a new last row). The Glass dark set (`glass-dark-settings-02*`) still shows the
  old Home Settings list; retake it with `tools/update-screenshots.sh glass-dark`.
