# Plan: My Photos, Photo of the day, and screensaver interaction (5 upstream PRs)

Five `Ready` upstream Free My TV PRs, grouped into one plan because they aren't independent:

- [#178](https://github.com/simbasjungles/free-my-tv/pull/178) — My Photos: send your own photos from a computer; the
  screensaver shows them, and without a TMDB key one is the home screen's background each hour.
- [#227](https://github.com/simbasjungles/free-my-tv/pull/227) — My Photos follow-ups: a real correctness fix on
  #178 (a race between the `:home` and screensaver processes importing the same photo twice, and an Android 11/Fire
  OS 8 bug where adb can't reach the app's external folder at all, so sending photos silently did nothing).
- [#102](https://github.com/simbasjungles/free-my-tv/pull/102) — Photo of the day: Bing's daily photo behind the
  clock, as a theme option, only reachable without a TMDB key and depending on #178's `Photos.on()` check.
- [#276](https://github.com/simbasjungles/free-my-tv/pull/276) — Screensaver: OK opens the title on screen in its
  app (off by default).
- [#287](https://github.com/simbasjungles/free-my-tv/pull/287) — Home and screensaver: what any app is playing, in
  the quotes' place while it plays.

**Why grouped.** #178 → #227 → #102 are a straight dependency chain (follow-up fix, then a feature built on the
first one's `Photos.on()`). #276 and #287 are independent features, but both touch the exact same drawing surface
as #178/#102: `DreamView.recordCaption` (the screensaver's caption line, which #178 already conditions on
`s.pick.photo != null` and #276 adds a hint line to), and `HomeView`'s background/quotes-row drawing (which #102's
`photoBg()`/`neutralGlass()`/`drawLightSky()` changes and #287's bars-and-text-in-place-of-quotes both reach into).
Planning all five together means designing one merged version of each shared method instead of five independent
diffs that would collide when applied one after another. They also all add a row to the same Home Settings list
(see the shared subsection at the end).

**Scope note.** All five PRs' diffs include LG/Samsung (`web/`) and `docs/` changes; none of that applies — we
dropped those platforms. Only the `app/` + `tools/` + README/CLAUDE.md portions below are in scope.

---

## 1. My Photos (#178)

**Wanted.** With the TV connected over adb, `tools/photos.py <TV IP> beach.jpg hills.jpg Holidays/` sends photos to
the app's own folder; the next time the home screen, screensaver or settings open, each one is imported once
(turned upright via EXIF, cropped to 16:9, resized, stripped of GPS/other tags) and the original deleted. While on
(*Home Settings → My Photos*), the screensaver shows them instead of the picks' photos, in the order sent; without a
TMDB key, one is the home screen's background, a new one every hour. Up to 100; JPEG/PNG/WebP, HEIC on Android 9+.

**Where.**
- New `Photos.java` — self-contained: file listing/import/crop logic, no Android libraries beyond the SDK (matches
  minSdk 26, no Gradle).
- `Backdrop.java` — a new `make(Bitmap, boolean forLight, boolean own)` overload and a second constructor taking
  `lightBg`/`nightBg`/`bw`/`bh` separately from the frosted image, so a photo of yours keeps more of its own
  sharpness (less blur, less whitened/darkened) than a pick's backdrop.
- `DreamView.java` — `photos()` picks your photos over `recs.showcase()` when on; `prepareNext()` reads
  `pick.photo` directly instead of `recs.readyImage()`; `withScrims()` gets a `withCaption` flag (photos have none);
  `recordCaption()` returns early for `s.pick.photo != null`.
- `HomeView.java` — `resume()` calls `Photos.importInbox()`; `updateCards()` falls back to `Photos.backdrop()` when
  there are no picks and photos are on.
- `Prefs.java` — `photos()`/`setPhotos()`.
- `Recs.java` — `Pick` gets a `File photo` field (null for a TMDB pick).
- `SettingsActivity.java` — a *My Photos* row in Home Settings (count, or "None"/"Off"), OK asks to remove them all.
- New `tools/photos.py` — plain Python, `adb push` to the app's external files dir; no app-side counterpart needed
  beyond what `Photos.java` already reads.
- README: a new "Your own photos" section.

**How.** Port `Photos.java` directly — it's new and self-contained. `Backdrop.java`'s current constructor/`make()`
(check `app/src/com/skyhome/launcher/Backdrop.java` as it stands now, not upstream's pre-#163 version — our #163
port touched `Scene.java` only, not `Backdrop.java`, so this should still apply cleanly, but confirm before
assuming) takes the same two-constructor shape upstream adds.

`DreamView.java` and `HomeView.java` have both diverged since the fork (picks carousel, catalog screen, poster
rows, Trakt integration — none of that existed when #178 was written upstream). Read the current file in full
before touching it: graft `photos()`/`prepareNext()`/`withScrims()`/`recordCaption()` onto the current structure by
intent, not by upstream's line numbers. Same for `HomeView.updateCards()`/`ownReady`.

Rename "Free My TV" → "Sky Home Launcher" and `io.freemytv` → `com.skyhome.launcher` throughout, including in
`tools/photos.py`'s `INBOX` path (`/sdcard/Android/data/com.skyhome.launcher/files/photos`) and its printed
messages.

**Conflict/overlap check.** `DreamView.recordCaption` and `HomeView`'s background-drawing methods are exactly what
#102, #276 and #287 also touch — see each of their sections below and the shared Home Settings subsection. Nothing
here is already built independently (no existing "my own photos" feature in Sky Home Launcher).

---

## 2. My Photos follow-ups (#227)

**Wanted.** Two real bugs, not polish: (1) on Android 11 (and Fire OS 8), adb can't write into another app's
external files folder at all, so `tools/photos.py` would silently do nothing — a `PhotoInbox` content provider lets
adb's shell write through the app itself instead; (2) on Android 11 the screensaver (main process) and the home
screen (`:home`) both call `Photos.importInbox()`, and without a claim step they could import — and therefore
double-count — the same photo twice. Also: a crop that only decoded the *center band* via `BitmapRegionDecoder`
instead of the whole photo, so a 24 MP photo no longer decodes (and upscales) at 48 MB.

**Where.** New `PhotoInbox.java` (a `ContentProvider`, `android:exported="true"`, checks the caller is adb's shell
UID or root); `AndroidManifest.xml` (the `<provider>` entry); `Photos.java` (`privateInbox()`, `waiting()` merging
both inboxes, the `.claim-<when>-<name>` rename-before-import, the `BitmapRegionDecoder` crop in `decode()`);
`tools/photos.py` (falls back to `adb exec-in content write --uri content://...` when the direct `mkdir` fails).

**How.** Straightforward port on top of #178's `Photos.java`, same file. The provider's authority renames to
`com.skyhome.launcher.photos` (from `io.freemytv.photos`); the manifest entry and `tools/photos.py`'s
`content://` URI follow.

**Conflict/overlap check.** None beyond depending on #178 already being in place in the same branch/PR.

---

## 3. Photo of the day theme (#102)

**Wanted.** Without a TMDB key, *Home Settings → Theme → Photo of the day* puts Bing's daily photo behind the
clock: sharp, dimmed at night, with a small caption (what's in it, whose it is). Checked every 6 hours, kept on
disk for offline use; only while your own photos (#178) are off — then no Bing request at all. No key needed.

**Where.**
- New `Wallpaper.java` — self-contained: fetches `https://www.bing.com/HPImageArchive.aspx`, validates the image
  path is Bing's own (`/th?id=...`, no redirects, ≤5 MB JPEG), builds a `Backdrop` from it, caches on disk.
- `Backdrop.java` — a `NIGHT_DIM` constant (0.45) and the plain-photo `Backdrop` constructor pre-computes its
  night-dimmed frost/glass.
- `HomeView.java` — implements `Wallpaper.Listener`; `photoTheme()` (theme is "photo" AND no picks AND your photos
  are off), `photoBg()`, `onWallpaper()` (fetch/release + redraw), folded into `neutralGlass()`, `dark()`,
  `drawLightSky()`, `drawLightTop()` (a caption chip), and `ownReady` (your photos arriving turns this off).
- `Prefs.theme()` — accepts `"photo"` alongside `auto`/`light`/`dark`.
- `SettingsActivity.java` — `THEMES` array gets `"photo"`; cycling skips it when `Recs.hasKey(getContext())` (picks
  would otherwise hide it anyway, so it's only offered when it would actually show).
- config.example.properties, README ("Photo of the day" section), CLAUDE.md (a short "Always" note, see below).

**How.** `Wallpaper.java` ports directly — no libraries, same `HttpURLConnection`/`org.json` pattern as the rest of
the codebase (e.g. `Locator.java`, `Trakt.java`). The real work is `HomeView.java`: read its *current*
`neutralGlass()`, `drawLightSky()`, `drawLightTop()`, `dark()` and `updateCards()` in full (these have all changed
since the fork for the Material/Liquid Glass theming work) and graft the photo-of-the-day path in as one more
background source, alongside #178's own-photo path (built in section 1) — both are "no picks" backgrounds and need
to coexist: your photos win when both could apply (`photoTheme()`'s own `!Photos.on()` check already encodes this
precedence; keep it).

**Conflict/overlap check.** Real overlap with #178 in the exact same `HomeView` methods — build #178 first in this
same pass, then add #102's branch into each of those methods rather than writing two independent diffs. No overlap
with Sky Home Launcher's own Material-look work has been found that would make this `Superseded`; the photo-of-the-
day theme doesn't exist on our side in any form.

---

## 4. Screensaver: OK opens the title in its app (#276)

**Wanted.** *Home Settings → Watch from the screensaver* (off by default). When on: OK on the screensaver (or its
settings-screen preview) opens the title currently shown, in its app, instead of just closing the screensaver; any
other key still closes it as before. A hint line under the caption says so.

**Where.** `DreamView.java` (`watchable()`, `watch()`, the hint line appended in `recordCaption()`); `HomeApps.java`
(splits `open(Activity, Item, View, x, y, w, h)` into a thin wrapper plus `open(Context, Item, Bundle options)`, so
the screensaver — which has no card to zoom out of — can call the latter with `null` options); `Screensaver.java`
(`setInteractive(Prefs.saverWatch())`, `dispatchKeyEvent` distinguishes OK from any other key); `SettingsActivity.java`
(the settings-screen preview's `dispatchKeyEvent` gets the same OK-vs-other-key split, plus a new Home Settings row);
`Prefs.java` (`saverWatch()`/`setSaverWatch()`).

**How.** `HomeApps.open()`'s split is mechanical — read the current signature first (confirm it still matches the
`Activity, Item, View, x, y, w, h` shape the diff assumes). The `DreamView.recordCaption()` hint line builds
directly on top of #178's change there (the `if (s.pick.photo != null) return;` early-return from section 1 already
means photos never show a caption or a hint — keep that ordering, append #276's hint line after the existing
`s.pick.meta` line, inside the same `if (s.pick.photo == null)` branch #178 created). `Screensaver.java` and
`SettingsActivity`'s preview key handling port directly — read the current files first since minor local changes
may exist.

**Conflict/overlap check.** `DreamView.recordCaption` — shared with #178 (see above); sequence #178 first in the
implementation, #276 second. No conflict with #287: #287 draws in `HomeView`'s quotes row and `DreamView`'s
corner/bottom overlay, not the caption block.

---

## 5. What's playing, in the quotes' place (#287)

**Wanted.** *Home Settings → Now Playing widget* (off by default). When on, whatever any app reports playing
(title, artist, app name, from its media session — music, podcasts, radio, anything that publishes one) replaces
the crypto-prices row on the home screen and the screensaver, with a small animated level-meter before the text.

**Where.**
- New `NowPlaying.java` — a `NotificationListenerService` (needed only because Android hands media-session access
  exclusively to an allowed listener; it never reads a notification and unbinds itself the instant it connects) plus
  a `Watch` inner class (`MediaSessionManager.OnActiveSessionsChangedListener`) that screens use to get a live
  `text` field.
- `AndroidManifest.xml` — a new `<service>` entry for it.
- `HomeMode.java` — `nowPlayingAccess()`/`nowPlayingAccessNow()`, which run `cmd notification allow_listener` /
  `disallow_listener` over the TV's own ADB, wired into `applyOriginal()`, `revertHomeNow()` and `verifyNow()` (so
  *Original* and the watchdog's revert always take the permission back).
- `ModeJob.java` — a new `PLAYING` job type.
- `Prefs.java` — `nowPlaying()`/`setNowPlaying()` (committed synchronously: the main process must see it
  immediately to allow/revoke the listener).
- `SettingsActivity.java` — a *Now Playing widget* row, wired to `Capabilities.NEED_LISTENER` for its
  "why unavailable" message.
- `HomeView.java` and `DreamView.java` — draw the bars + text in place of the quotes row (home screen's tab-row
  corner and the bottom-center clock row; the screensaver's top-right corner and bottom caption line).

**⚠ Blocker: depends on `Capabilities.java`, which doesn't exist in Sky Home Launcher.** Upstream's
`Capabilities.java` (`OVERLAY`, `LISTENER`, `adb()`, `refused()`, `prove()`, `forget()`, the `NEED_*` constants) was
introduced by the Sleep Timer PR — **#219, in Plan 2 of this split, a separate PR from this one.** #287 only *adds*
`LISTENER`/`NEED_LISTENER` to an already-existing `Capabilities.java`; it doesn't create the class. Two ways
through:
  - **(a) Recommended.** Build #219 (Plan 2) first — `Capabilities.java` lands with it (for `SleepOverlay`'s own
    "allowed via ADB, provable, revocable" pattern) — then #287 is a small addition to an existing file, exactly as
    upstream wrote it. This also matches the order the two were merged upstream.
  - **(b)** If #287 needs to land before #219 for some reason, build a minimal standalone equivalent here (just
    `LISTENER`, `adb()`, `refused()`, `prove()`, `forget()`) and let #219 later fold in `OVERLAY` alongside it.
  Pick (a) unless there's a reason to decouple the two plans' build order.

**How.** `NowPlaying.java` ports directly — no libraries, API 26-safe (`NotificationListenerService` is API 21+,
`MediaSessionManager` API 21+). The `HomeMode`/`ModeJob` wiring should mirror the *shape* of the existing
`remoteService()`/`ensureRemoteService()`/`REMOTE` job pattern already in our `HomeMode.java` (confirmed present,
same file) rather than being patched from upstream's diff line-for-line, since our `HomeMode.java` has diverged
(different `PROTECTED`/`DEBLOAT` lists, different job set) since the fork. `HomeView`/`DreamView` bar-drawing: read
the current `drawQuotes()` (home screen) and the corner-quotes drawing (screensaver) in full first — both have
changed for the Material look since the fork — and replace (not duplicate) the prices line with the playing-text
line, following upstream's "coins when nothing plays, the bars+text when something does" precedence.

**Conflict/overlap check.** `HomeView.drawQuotes()` and `DreamView`'s corner-quotes drawing are exactly where the
existing crypto-prices widget draws — must coexist correctly (one replaces the other based on state, not stack).
No overlap with #178/#102/#276's methods (different drawing region). Nothing here is `Superseded` — Sky Home
Launcher has no "what's playing" feature today.

---

## Home Settings row layout (shared across #178, #102, #276, #287)

Four of these five PRs each add one row to Home Settings. Upstream's own row list had already grown differently
from ours by the time these were written (it has `FORECAST_ROW`/`QUOTES_ROW` from a weather-forecast PR, #232,
that we haven't ported — still `Pending`). Our current layout (`SettingsActivity.java`, confirmed as of this
plan):

```
0 Language, 1 Temperature, 2 Theme, LOOK_ROW(3) Look, 4 Navigation sounds, 5 Weather widget, CITY_ROW(6) City,
WATCH_NEXT_ROW(7) Apps' Continue Watching, TRAILER_ROW(8) Trailers, QUOTES_ROW(9) Quotes widget, COIN_ROWS(10)+ coins
```

None of `PHOTOS_ROW`, `SAVER_ROW` (Watch from the screensaver), or `PLAYING_ROW` (Now Playing widget) exist yet.
`THEMES` stays `{"auto", "light", "dark"}` until #102 adds `"photo"`.

**To decide at implementation time** (mechanical index work, not a design decision — don't pre-compute exact
constants here, verify against the file as it stands when each piece is built): a sensible merged order is

```
0 Language
1 Temperature
2 Theme (cycles auto/light/dark/photo; "photo" skipped when Recs.hasKey())
PHOTOS_ROW  My Photos
LOOK_ROW    Look
  Navigation sounds
SAVER_ROW   Watch from the screensaver
  Weather widget
CITY_ROW    City
WATCH_NEXT_ROW  Apps' Continue Watching
TRAILER_ROW     Trailers
PLAYING_ROW     Now Playing widget
QUOTES_ROW      Quotes widget
COIN_ROWS+      coins
```

Each row's `adjust()` case and `settingValues()`/`settingLabels()` entries move together; `need()`'s "why
unavailable" wiring must cover `PLAYING_ROW` (`Capabilities.NEED_LISTENER`) the same way `POWER`'s `P_WHERE` row
already covers `Capabilities.NEED_OVERLAY`. My Photos' row is never "unavailable" in the `Capabilities` sense (no
photos sent yet is a different, non-blocking state — "None").

---

## CLAUDE.md additions

Each of #102 and #287 adds a short device-notes-style paragraph to upstream's `CLAUDE.md` ("The code" section).
Port both, reworded for Sky Home Launcher (package `com.skyhome.launcher`, "Sky Home Launcher" not "Free My TV"):
one for `Wallpaper`/the photo-of-the-day theme (what it fetches, from where, the `NIGHT_DIM` dimming, when it's let
go), one for `NowPlaying`/the notification-listener permission pattern (why it needs the TV's own ADB, that it
never reads notifications and unbinds at once, how `HomeMode` keeps it in sync with the widget and takes it back on
*Original*/the watchdog's revert).

---

## Build and test plan

- Build in dependency order within one branch/PR: #178 → #227 → #102 → #276 → #287 (same order upstream merged
  them in), so each step's `./build.sh` stays green before the next one starts.
- Test `tools/photos.py` against a real TV mid-way through (after #227, so the Android 11 content-provider fallback
  path is exercised too, not just the direct adb-push path).
- #287 needs #219 (Plan 2) built first for `Capabilities.java` — see its section's blocker note. If Plan 2 hasn't
  landed yet when this plan is picked up, build items 1–4 here and leave #287 for after #219.
- Real-TV verification: My Photos (send a few, confirm import/crop/orientation, confirm the screensaver and home
  background use them), Photo of the day (needs no TMDB key configured to see it), Watch from the screensaver (OK
  vs. any other key), Now Playing (play something in an app that publishes a media session — YouTube Music, Netflix
  — confirm the row's bars+text replace the coins and that turning the widget off on *Original* actually revokes
  the listener, checked with `adb shell dumpsys notification | grep -i skyhome` or similar).
