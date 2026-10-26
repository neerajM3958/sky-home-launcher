# Plan: Fire TV support, 4K rendering quality, sleep timer

Three `Ready` upstream PRs, bundled into one plan file for convenience only — **not** because they're related.
Confirmed after reading all three diffs: no shared touched files, no dependency between them. #175 and #219 both
touch `HomeView.java`/`SettingsActivity.java`, but in different, non-overlapping areas (see each section's
Conflict/overlap check). Build and land them as separate changes/commits even though they're planned together;
nothing here requires them to ship in the same PR.

- [#167](https://github.com/simbasjungles/free-my-tv/pull/167) — Add Amazon Fire TV (Fire OS) support and remote
  input focus fixes
- [#175](https://github.com/simbasjungles/free-my-tv/pull/175) — Home: smooth tiles, cards, logos and symbols on 4K
  TVs
- [#219](https://github.com/simbasjungles/free-my-tv/pull/219) — Settings: a sleep timer, and options this TV lacks
  show why

---

## 1. Amazon Fire TV (Fire OS) support (#167)

**Wanted.** Fire TV can't become the Home app at all — Amazon's own launcher holds `CATEGORY_HOME` at
`priority=950`, which Android TV's normal `cmd package set-home-activity` can't outrank, and `cmd role` doesn't
exist in the form the switch expects either. So on Fire OS, Sky Home Launcher can't replace the home screen: it
opens as a regular app instead (gear icon on the home screen → Settings, app icon → the home screen itself), while
*Original* and the watchdog still work (there's nothing to revert on Fire TV, but the code paths shouldn't crash).
Also widens package visibility, the shelf/services tables and `HomeMode.PROTECTED`/`mayTurnOff` for Amazon's own
apps and Fire TV's builds of Prime Video, Apple TV, Hulu, Peacock, YouTube and Tubi.

**Where.**
- `HomeMode.java`: new `AMAZON` flag (`Build.MANUFACTURER`/`Build.BRAND` check), `PROTECTED` additions, `mayTurnOff`
  (blanket `com.amazon.*` refusal), `bootCheck`/`applyFree`/`verifyNow` early-outs, and `applyFree`'s extra
  `resolve-activity` check after `set-home-activity` (Fire OS can print `Success` and still not actually hand over
  the Home role).
- New `FireHomeActivity.java` (`extends HomeActivity`, no `resumeWhilePausing`) + its manifest entry
  (`android:process=":home"`, `singleTask`, `excludeFromRecents`).
- `app/AndroidManifest.xml`: the Fire TV `<queries>` entries, `com.amazon.firetv.youtube`,
  `com.amazon.firebat`/`com.amazon.avod` (Prime Video), `com.apple.atve.amazon.appletv`, `com.hulu.plus`,
  `com.peacock.peacockfiretv`, `com.tubitv.ott`, `com.amazon.tv.livetv`/`com.amazon.mediatek.livetv`.
- `HomeApps.java`: `SHELF` rows for the above packages, `LIVE_TV_APPS` additions, and a de-dupe fix in
  `shelfPackages()` (two packages with the same `SHELF` key — e.g. two Prime Video builds — no longer both fill a
  shelf slot).
- `Recs.java`: matching `SERVICES` rows (same packages, for picks/routing).
- `SettingsActivity.java`: `openFireHome()` — on Fire TV, the launcher/leanback-launcher intent opens
  `FireHomeActivity` instead of Settings; the gear on the home screen still reaches Settings directly (no launcher
  category on that `Intent`).
- `Adb.java`: catches `EOFException` alongside `SocketTimeoutException` when reading the ADB handshake (seen on Fire
  OS's ADB implementation).
- `CLAUDE.md` (the Always-rules/device-notes section).

**How.** Confirmed `HomeMode.java`'s `PROTECTED`/`mayTurnOff`/`bootCheck`/`applyFree`/`verifyNow` are all still at
the structure upstream's diff assumes (this area hasn't been touched since the fork beyond our own #194 onn
addition and #284's `screensaver=false` gating, both additive and non-conflicting with this diff's insertion
points) — port directly, renaming `io.freemytv`/"Free My TV" strings to `com.skyhome.launcher`/"Sky Home Launcher"
throughout (the new `FireHomeActivity` doc comment, the new translated string, `CLAUDE.md`'s note).

`SettingsActivity.onCreate`/`onNewIntent`/`onPause` have diverged since the fork (ours adds `Watchdog.install`,
`Ui.init`, and ADB screenshot-param handling — `--es look`/`--es theme` — that upstream's version at this PR's base
doesn't have): add `if (openFireHome(getIntent())) return;` as the **first** line of `onCreate` (before any of our
additions) and of `onNewIntent`, and null-guard `view.pause()` in `onPause` the way the diff does — our extra
onCreate logic is unaffected since it's after the early return.

`HomeApps.shelfPackages()`'s de-dupe (`keys` set) is a real, independent bug fix (two SHELF rows sharing a key
could both claim a shelf slot) — port it even though it's bundled with the Fire TV work, since it's a one-line,
no-risk addition to a loop we haven't touched.

Skip porting #292 (Fire TV: HDMI inputs, Live TV) — it's `Pending`, not `Ready`, and explicitly builds on this PR;
plan it separately once this lands and someone's decided it's worth doing.

**Conflict/overlap check.** None of this PR's files were touched by anything else in this batch, or by the
recently-merged #124–#296 batch (PR #18), except `HomeApps.java`'s `SHELF`/`LIVE_TV_APPS` tables, which PR #18 also
added rows to (Kinopoisk, NOW, etc.) — purely additive on both sides, no line-level collision expected. No overlap
with #175 or #219 either (different methods in the shared files).

**To decide.** Nothing blocking. Testing needs an actual Fire TV device (a Fire TV Stick or Fire TV Cube) — none
of this is exercisable on the Google TV devices this project has been testing on, so flag that explicitly when
reporting results; the `AMAZON` flag's early-outs mean this is inert everywhere else, which is the main safety net.

---

## 2. Home: smooth tiles, cards, logos and symbols on 4K TVs (#175)

**Wanted.** On a real 4K panel, every bitmap this app draws (shelf tiles, catalog cards, Watchmode/service logos,
the Symbols icon font, Backdrop's wide/tall cards) is built at 1080p-era resolution and scaled up on the GPU for a
4K screen — which blurs. Build each one closer to its actual on-screen resolution (`detail()`/`Ui.downscale()`:
draw up to 2× bigger than the target, then filter down in ≤2× steps, no single step that skips pixels) and snap
layout rectangles to whole pixels so a static tile doesn't sit on a half-pixel boundary. Also: a `>99 price formats
in millions (`4.15M`), and `roundImage` moves from a per-call `clipPath` (which several GPUs render with visibly
stair-stepped corners, not just a performance cost on legacy devices) to always drawing through a `BitmapShader`
made once with the image, not on every frame.

**Where.** `Backdrop.java` (`make`/`card`, new `center()` helper, `wideShader`/`tallShader` fields),
`HomeApps.java` (`Item.setTile()`/`tileShader`, `bannerLogo()` — a real rewrite of its background/distance/ink
math, `bannerTile()`, `iconTile()`, `detail()`, `shrink()`, `darkTile()` now takes a size, `LOGO_VERSION` bump),
`HomeView.java` (`roundImage()` signature, `drawCard()`, `drawShelfTile()`, `drawLightCard()`, `layoutShelf()`/
`layoutDrawer()`/`layoutLight()`'s new `snap()` calls, its own `downscale()` removed in favor of `Ui.downscale()`),
`Ui.java` (new `downscale()`), `Symbols.java` (`warm()`/`warmWeather()`, pre-scaled icon cache), `HomeData.java`
(`price()`/`compact()`'s `M` suffix), `DreamView.java`/`SettingsActivity.java` (`Symbols.warm`/`warmWeather` calls
at layout time, so drawing never scales an icon on the fly), `CLAUDE.md` (the low-RAM/1280×720 TCL note, if this
work changes anything relevant there — check once ported).

**How — real conflicts found, not a clean port.** This PR touches the exact files our independent catalog-screen,
poster-row and Material/Liquid-Glass theming work (built after the fork) and the just-merged #253 "white" logo
mode (PR #18) have also changed, in ways that collide line-for-line, not just nearby:

- **`HomeApps.bannerLogo()`**: this PR's diff assumes the *old*, simpler per-pixel loop (inline background/distance
  math, no precomputed `near`/`dist` arrays) — the same old structure we already found and adapted around when
  porting #253's `white` extraction mode (now merged, PR #18). This PR is actually where upstream itself
  introduces the `background()`/`distance()`/`maxFilter()` refactor (ink detected from a 2px-dilated "nearby ink"
  map, not just the pixel's own distance) that #253's diff had *assumed* was already there. Porting #175 now means
  re-deriving `bannerLogo()` from scratch: start from our current 4-parameter version (`tint, holes, white`, already
  merged), add the `near`/`dist`/`background()`/`maxFilter()` machinery from this PR, and make sure the `white`
  branch (ours) and the new ink-gate logic (this PR's) compose correctly — the `white` branch doesn't use
  `background()`/`dist` at all today, so it's likely unaffected, but verify by eye once built (a Kinopoisk-type
  logo over a busy gradient, and a normal brand logo on a flat background, both need checking).
- **`HomeView.roundImage()`**: has diverged far more than this PR's diff assumes. Upstream's base version has one
  `roundImage(Canvas, Bitmap, RectF, float)` with ~3 call sites (`drawShelfTile`, `drawCard`, `drawLightCard`
  fallback). Ours has **two** overloads — `roundImage(c, image, cell, r)` and `roundImage(c, image, cell, r,
  shift)` — and at least 7 call sites, because our independent catalog-screen/poster work added image crossfades
  (`browsePrev`/`browseBack`, the `lo`/`hi` fade pair, the catalog poster draw) that don't exist upstream at all.
  This PR's core idea (draw through a `BitmapShader` made once with the image — stored on `HomeApps.Item` and
  `Backdrop` now, not cached separately in `HomeView`'s own `WeakHashMap`) is still worth having (it also fixes a
  real correctness/quality issue: stair-stepped `clipPath` corners on some GPUs, not just a legacy-device
  optimization), but it needs to be threaded through **every** current call site, including the `shift`-parameter
  ones this PR never saw. This is `Adapted` territory, not a direct port: re-derive the `BitmapShader`-per-image
  pattern against our actual `roundImage` surface rather than pasting this diff's 4-callsite version over it.
- **`SettingsActivity.java`**: this PR only adds three `Symbols.warm()` calls at the point where `k` (the UI scale
  factor) is first known — low risk, should still apply near-verbatim once the surrounding code is matched up (our
  settings screen has grown substantially since the fork, so find the actual `k = w / 1920f` assignment first
  rather than assuming line numbers).
- **Everything else** (`Backdrop.java`'s crop-before-downscale rework, `Ui.downscale()`, `Symbols.java`'s
  pre-scaled-copy cache, `HomeData.java`'s `M` price suffix, `DreamView.java`'s two `Symbols.warmWeather` calls) is
  self-contained to files/methods this project hasn't touched since the fork — port these directly.

**Conflict/overlap check.** Real, substantial overlap with our own post-fork catalog-screen/poster-row work in
`HomeView.roundImage` and with the just-shipped #253 "white" logo mode in `HomeApps.bannerLogo` — both called out
above. No overlap with #167 or #219 (different methods in the files they happen to share, if any — `HomeApps.java`
is shared with #167 but at non-overlapping insertion points: #167 adds `SHELF`/`SERVICES` rows, this PR rewrites
`bannerLogo`/tile-building methods).

**To decide.** Whether to port `bannerLogo`'s full `near`/`dist`/`maxFilter` ink-detection rework in the same pass
as everything else here, or split it into its own follow-up once the simpler wins (downscaling, `snap()`, the
`BitmapShader` caching, the price `M` suffix) are in and verified — the ink-detection change is the one piece that
touches code we've already modified twice (fork-time, then #253), so it's the highest-risk single piece in this
PR. Lean toward splitting it out if implementation time is tight.

---

## 3. Settings: a sleep timer, and options this TV lacks show why (#219)

**Wanted.** Two features in one PR: (1) *Sleep Timer* — a new Settings row/screen that turns the TV off after 5
minutes to 4 hours (steps of 5), shown as a countdown pill either drawn over whatever app is in front (an overlay
service, allowed through the TV's own ADB since Android TV has no system UI to ask) or, where that's refused, as
text on the home screen itself; it fires by sending the remote's Sleep key through ADB, the same channel the mode
switch already uses. (2) *Capabilities* — a small generic framework so any Settings row that needs something this
TV doesn't have (Sky Home Launcher mode on, remote-button support, the overlay permission, live TV) shows
*Unavailable* with a reason, instead of just not working when pressed.

**Where.**
- New `Capabilities.java` (`missing()`/`adb()`/`remoteButtons()`/`overlay()`/`refused()`/`forget()`/`prove()` — a
  small file-flag-based "proof" system: some capabilities are provable statically (`adb()` reads the state file),
  others only by trying and recording the result (`overlay()`, since Android TV's overlay permission can only be
  learned by attempting it through ADB and checking `Settings.canDrawOverlays()` after).
- New `SleepTimer.java` (the alarm/ADB-sleep logic, `minutesLeft()`/`step()`/`set()`/`stop()`/`check()`/`listen()`)
  and `SleepOverlay.java` (the overlay service, its own `:sleep` process, drawn with `Symbols`/`Ui` the same way
  the home screen draws its pill elements).
- `Prefs.java`: `sleepAt()`/`sleepFrom()`/`setSleepAt()`, `sleepShown()`/`setSleepShown()`,
  `sleepPlace()`/`setSleepPlace()`.
- `HomeActivity.java`/`SettingsActivity.java`: `SleepTimer.listen()`/`.check()` calls in `onCreate`.
- `HomeMode.java`: `SleepTimer.stop()` in `applyOriginal()` and `revertHomeNow()` (Original/the watchdog end the
  timer too), plus `appops set … SYSTEM_ALERT_WINDOW default` and `Capabilities.forget(OVERLAY)` so the overlay
  permission is asked about again next time, not assumed refused forever.
- `HomeReceiver.java`: handles `SleepTimer.ACTION` (the alarm firing), off the main thread (`goAsync` + a
  background thread, since it needs ADB).
- `HomeData.java`: `isNight()` refactored into a static `isNight(theme, now, sunrise, sunset)` so `SleepOverlay`
  (a different process, no live `HomeData` instance) can compute the same day/night state for its pill colors.
- `HomeView.java`: `sleepText` field, `updateSleep()` (polled every 15s alongside the existing picks-refresh timer),
  drawn next to the light-theme's round buttons (`drawLightButtons`) when the overlay can't be shown.
- `SettingsActivity.java`: the big one — a new `POWER` screen (reusing the existing list-screen machinery,
  `rowCount()`/`drawList()`/`listLabels()`/`listValues()`), a *Sleep Timer* row on `MAIN`, `unavailable(row)` /
  `rowText()`'s "Unavailable, {reason}" TalkBack text, and the *why* toast on OK over a disabled row.
- `AndroidManifest.xml`: `SYSTEM_ALERT_WINDOW`, `FOREGROUND_SERVICE`, `SCHEDULE_EXACT_ALARM`/`USE_EXACT_ALARM`
  permissions, the `SleepOverlay` service entry (`android:process=":sleep"`).
- `CLAUDE.md`/`README.md`: the Sleep Timer bullet and the Capabilities-pattern note.

**How — the `SettingsActivity` MAIN-screen insertion is the real risk here, everything else is close to a direct
port.** `Capabilities.java`, `SleepTimer.java` and `SleepOverlay.java` are new, self-contained files with no
dependency on anything that's changed since the fork — port close to verbatim (rename `io.freemytv`/`HomeMode.PKG`
references as needed; `HomeMode.PKG` is already `com.skyhome.launcher` on our side so `SleepTimer.ACTION` needs no
change beyond the constant it reads). `HomeActivity`/`HomeMode`/`HomeReceiver`/`HomeData`/`Prefs` insertion points
are all still where this PR's diff expects (none of these methods were touched by our independent work or by the
just-merged PR #18 batch) — port directly.

`SettingsActivity.java` is where this needs real care, not a line-for-line patch: upstream's base here has **7**
MAIN rows going to **8** (`MAIN_TOP = 140, ROW_H = 53.75f`). Our `MAIN` screen already has **11** rows (`MAIN_TOP =
122, ROW_H = 56`) — Trakt, TMDB, Stremio, Languages, Hidden services, Apps turned off, Torrentio, Torrentio
languages, Added keys, Learn, and Integrations (built independently after the fork, PR referenced in
`plans/plan-minor-improvements.md` item 7) — none of which exist in upstream's version of this file at all. Porting
this PR means:
1. Adding one more `MAIN` row (*Sleep Timer*, going to 12) at whatever position reads best next to the existing
   list (upstream puts it right before *TV Settings*; check whether that still makes sense next to *Integrations*,
   which currently sits in a similar "setup/advanced" position).
2. Re-deriving `ROW_H`/`MAIN_GAP` for 12 rows in our current `MAIN_TOP = 122` layout, the way upstream re-derived
   theirs for 8 rows in `MAIN_TOP = 140` (`"the rows share the … px that seven took"` — same idea, different
   starting numbers).
3. Threading the new `POWER` screen constant into our existing `screen` enum (`MAIN = 0 … INTEGRATIONS = 16` on our
   side, not `MAIN = 0 … SHELF_EDIT = 5` as upstream's diff assumes) and its own `*_ROW` constant the way
   `TRAKT_ROW`/`TMDB_ROW`/`STREMIO_ROW`/`INTEGRATIONS_ROW` already work.
4. The `unavailable(row)`/`rowText()`/the OK-toast logic is new and additive (no equivalent exists on our side yet)
   — port that part directly, but its `row` indices need to line up with wherever *Sleep Timer* actually lands in
   our 12-row `MAIN`, not upstream's 8-row one.
5. `listLabels()`/`listValues()` (a rename/merge of `keyLabels()`/`settingLabels()` into one pair, per this diff) —
   check whether our `REMOTE_KEYS`/`HOME_SETTINGS` screens still use the names this PR expects, since our settings
   screen has grown several more list-screens (`LANGUAGES`, `HIDDEN`, `APPS_OFF`, `TOR_LANGS`, `ADDED_KEYS`) since
   the fork that reuse the same `drawList`/`rowCount` machinery — confirm the merge doesn't silently break one of
   those.

**Conflict/overlap check.** No overlap with #167 (different `HomeMode`/`SettingsActivity` methods) or #175 (#175
touches `SettingsActivity` only for three `Symbols.warm()` calls at the `k` assignment; this PR's `SettingsActivity`
changes are the MAIN-row/list-screen machinery — different code, but both land in the same large file, so apply
#175's three-line addition and this PR's MAIN-row rework in separate passes to keep the diffs reviewable, not as
one tangled edit). Real, substantial divergence from upstream's assumed `SettingsActivity` base — detailed above.

**To decide.** Where exactly *Sleep Timer* sits among our 11 (soon 12) MAIN rows — next to *Integrations* (both
feel like "advanced/setup" rows) or elsewhere; a call for whoever implements this to make and note here once
decided, not something that needs resolving before planning.

---

## Build and test plan for the whole batch

- `./build.sh` must end with `Built SkyHomeLauncher.apk` after each item.
- `python3 tools/translate.py --check` after #167 and #219 (both add new strings; #219 the larger one — `Off in
  {time}`, `Sleep Timer`, `Top center`/`Top right`/etc., `Unavailable`, `Needs Sky Home Launcher mode…`, and more).
- #167 needs an actual Fire TV device to verify at all — nothing on a Google TV dev unit exercises the `AMAZON`
  branch.
- #175's rendering-quality changes are genuinely only checkable by eye, ideally on a real 4K panel (the thing it's
  fixing); a 1080p dev TV won't show the blur this PR is solving, though it should still build and run correctly
  there.
- #219's sleep timer is fully testable on any current dev TV (the ADB-sleep channel is the same one Original/the
  switch already use) — exercise set/step/cancel, the overlay pill showing on another app, the home-screen fallback
  text when the overlay permission is refused, and the *Unavailable* state on a disabled row (e.g. before Sky Home
  Launcher mode is ever turned on).
