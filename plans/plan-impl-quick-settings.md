# Plan: Quick Settings side card over any app

Status (2026-10-24): **draft for review, no code changed.** Answer the open questions at the end and I will start.

## Goal

A small card that slides in from the right edge, over whatever app is running, when the remote's settings button is
pressed. It replaces the stock Google TV "quick settings" side panel, which is gone in Free My TV mode (it lives in the
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

## Design

### The choice

A new special value next to `TV_SETTINGS`: `Prefs.QUICK_SETTINGS = "android:quick"`. It appears in the Remote Buttons
picker as "Quick Settings" (`appPkgs.add(...)` where `TV_SETTINGS` is added, `SettingsActivity` ~line 1129). Default for
the Skyworth gear button: **Quick Settings** (it was TV Settings; question 1). Every other button keeps its default.

In `RemoteKeys.onKey()`: a button set to `QUICK_SETTINGS` calls `QuickCard.toggle(context)` on `ACTION_DOWN` with
`getRepeatCount() == 0`, and consumes the key.

### The window

- Added by `RemoteKeyService` with `WindowManager.addView`, type `TYPE_ACCESSIBILITY_OVERLAY` (no extra permission, Android
  8+; the service is the only place it can be added without `SYSTEM_ALERT_WINDOW`).
- Flags: `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL`, so **the app underneath keeps focus and keeps playing** (the stock
  panel pauses nothing either). No focus change means no `onPause` in Netflix.
- Size: about a quarter of the screen width, full height, gravity right. Slide in and out with a translate animation on the
  view (transform only; a TV GPU may be weak).
- One hand-drawn `View` (same approach as `HomeView`): glass-like dark panel, a title row and 5 to 6 tiles. No layouts, no
  libraries, no allocations in `onDraw`.

### Input while the card is open

Because the window is not focusable, D-pad keys still go to the app below. So while the card is open, the **key filter
takes them**: `RemoteKeyService.onKeyEvent` hands every key to `QuickCard.onKey()` first. It moves the focus on up/down,
runs the tile on OK/center, and closes on Back, on the settings button again, or after a timeout (about 8 s without a
key, question 5). It consumes all of those, and returns false for anything else (volume, power, media keys) so they still
work. Not focusable means nothing the card does can steal the app's focus.

The system waits for the filter on every key (up to half a second), so `onKey()` does no I/O: it changes an int and calls
`invalidate()`.

### Tiles (first version)

Each tile starts an intent with `FLAG_ACTIVITY_NEW_TASK` and closes the card. None of them changes a setting by itself
(project rule: no picture, sound, network or account changes).

| Tile | Opens |
|---|---|
| Settings | `Settings.ACTION_SETTINGS` (as today) |
| Inputs | the TV's input picker (`android.intent.action.VIEW` on `content://android.media.tv/...`; or the input of the tuner app; question 3) |
| Free My TV | the home screen (`HomeActivity`) |
| Apps | Free My TV's All Apps (opens `HomeActivity` on it) |
| Sleep timer | **not in v1** unless question 2 says so |
| Original mode | not here: too easy to press by accident; stays in the settings app |

The tiles for unavailable things (no input picker found) are left out, not greyed.

## Steps

1. **`QuickCard.java`** (new, in the `:keys` process): `toggle(Context)`, `isOpen()`, `onKey(KeyEvent)`, the view,
   the timeout (`Handler.postDelayed`, removed on close), and the tile list. Keep the class small: it loads with the key
   service, whose memory counts on 1 GB TVs.
2. **`RemoteKeyService`**: call `QuickCard.onKey()` first in `onKeyEvent`; close the card in `onUnbind`/`onDestroy` and
   on `typeWindowStateChanged` for another app launching on top (so it never stays up over a new app).
3. **`Prefs`**: `QUICK_SETTINGS` constant, `defaultApp(KEY_SKY_SETTINGS)` (line ~270) and whatever `keyApp()` needs to
   accept it. The value is stored in `remote-keys.txt` like the others.
4. **`RemoteKeys.onKey`**: the new branch next to `TV_SETTINGS`. Update the class comment (it says the gear button "needs
   the stock launcher").
5. **`SettingsActivity`**: add "Quick Settings" to the picker list and to the label for a button (the cycle at ~1155 and
   the picker at ~1379/1492 use `appPkgs`, so adding to the list covers them).
6. **Other brands.** TCL, Vestel, KTC and Sony have no settings button in `Prefs.KEYS`. v1 adds nothing there (question 4:
   whether to let any button of those be set to Quick Settings; the picker would show it for all of them).
7. **Text.** New strings through `Ui.tr` ("Quick Settings", tile names), then `py tools/translate.py`, translate in every
   catalogue (Portuguese, Spanish, German and Russian by hand), keep placeholders.
8. **Docs.** `CLAUDE.md` (the Skyworth line about the gear button), README's Remote Buttons text, and the website if it
   lists the remote buttons (`docs/index.html` and `docs/pt/index.html`).
9. **Check.** `./build.sh` ends with `Built FreeMyTV.apk`, `py tools/translate.py --check`, then `tools/check.sh`.

## Testing

On the Skyworth stick, in Free My TV mode with the accessibility service on:

- Press the gear button on the home screen, inside YouTube (playing), inside Stremio and inside a Netflix-style app that
  keeps a video surface: the card appears, the video keeps playing, D-pad moves in the card and not in the app.
- Back, the gear button again and the timeout all close it; the app underneath gets its keys again at once.
- Volume, mute and power work while the card is open.
- OK on each tile opens the right screen and the card is gone afterwards.
- Another app launching while the card is open closes it.
- Switch to *Original*: the accessibility service is turned off, so no card (the gear button is the stock one again).
- A button set to "Nothing" still passes through; one set to TV Settings still opens Settings.
- `adb logcat -s FreeMyTV` shows `remote key ... -> Quick Settings`; no ANR, the key process stays small.

## Risks

- **A slow filter delays every key** for the whole TV (the system waits up to 0.5 s). Mitigation: no I/O or allocation in
  `onKey()`, and a hard rule that any exception returns false.
- **Not focusable means no touch/mouse focus**: a TV remote only, which is the target. A mouse would click tiles only if
  `FLAG_NOT_TOUCH_MODAL` is off; fine either way.
- **Some apps draw over accessibility overlays** (DRM video surfaces on a few TVs): the card might not show on top of
  protected video. Test on a Netflix-like app; if it hides, fall back to a focusable window (the app pauses).
- **Accessibility overlays need the service enabled**: it is only on in Free My TV mode, as before.
- **Skyworth's own receiver** opens the stock launcher panel for this button when we don't consume it. We consume it, so
  no change there, but it was only checked with "TV Settings" so far.
- The 1 GB / 720p TVs: the card is a small hand-drawn view with no bitmaps, so memory is minimal, but it should be
  measured once (`dumpsys meminfo` for the `:keys` process).

## Not in this plan

- Changing picture, sound, Wi-Fi or account settings from the card (project rule).
- Recreating the stock panel's own toggles (they belong to SystemUI).
- LG and Samsung (`web/`): no such key hook there.
- A card for non-supported brands (they would need a different trigger).

## Open questions

1. **Default for the Skyworth gear button:** Quick Settings (recommended) or keep TV Settings and let people pick?
2. **Tiles:** is the list above right? Add Sleep timer (needs a small timer in the key process) or Wi-Fi / Bluetooth screen
   shortcuts (they just open screens)?
3. **Inputs tile:** how does this TV open its input picker? I will read it from the TV first (read-only
   `cmd package query-activities` for `android.intent.action.VIEW` / TV input settings), then decide.
4. **Other brands:** show "Quick Settings" in the picker for TCL, Vestel, KTC and Sony buttons too, even though they have
   no settings button?
5. **Timeout:** close by itself after about 8 seconds, or only on Back / the button?
6. **Focus:** keep the card non-focusable (the app keeps playing, keys handled by the filter; recommended), or make it a
   normal focusable window (simpler, but the app pauses)?
7. **Look:** match the home screen's glass look, or a plain dark card to stay light?
