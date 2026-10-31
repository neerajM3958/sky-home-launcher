# Plan: improve the picks

The picks (`Recs`, `Backdrop`, and the parts of `HomeView` that draw them) load images visibly and often, make more
TMDB calls than they need, download more than they need, and hold far more memory than they draw. Goal: images load
visibly **once**, on the first launch with an empty cache. After that, every start draws from disk at once, and the
daily refresh swaps titles in silently.

## Decisions (agreed 2026-10-31)

- **A stable list.** The picks change only on the daily refresh. No hourly rotation (`now()`'s `hour * 3` offset
  goes). Spotlight's row stays circular, as now. `pick_rotate_seconds` (the cards moving on by themselves) stays as
  it is: it moves through the same stable list, and changes no titles.
- **The list refreshes every 24 h.** No refresh when the time of day changes (morning, afternoon, night): the query
  doesn't depend on it. The label ("THIS MORNING"…) still follows the clock; it's text only.
- **The images stay in the cache folder (`getCacheDir()`), limited to 40 MB.** Everything in it can be downloaded and
  rebuilt, so if Android empties it (low storage, *Clear cache*), the images load visibly once more and then stay. The
  limit also keeps it small enough that Android empties other apps' caches first (it starts with the apps furthest over
  their quota). Over 40 MB, the least recently used images go first.

## What's wrong now (from the analysis)

1. **Processed images are never saved.** Only TMDB's original JPEG is on disk. Every start of `:home` (Android kills it
   often while another app plays) decodes, scales and blurs each visible pick again, one at a time, and fades each
   one in over a placeholder.
2. **One thread (`Recs.io`) does everything.** A refresh's downloads (6–8 titles in a row) delay the decoding of an
   image that's already on disk.
3. **A refresh shows placeholders and replays the fade-in.** `fetchList` makes new `Pick` objects (`shownAt` back to
   0). New titles show before their details and image exist. In "from my apps" mode a title without an installed app
   disappears afterwards, so the list changes a second time.
4. **The hourly rotation changes what's on screen,** and `updateCards` sends Spotlight's row back to the start
   (`cardStart = 0`).
5. **Extra calls.** The list is fetched again at 5:00, 12:00 and 18:00 and every 6 h. Each title costs two calls:
   `/images` (or the details) plus `Launcher.prefetch`'s `/{kind}/{id}?append=watch/providers,external_ids`.
6. **Downloads are too big.** `original` on 1080p (up to 4K, 1–4 MB) is kept as is. `prune` is first-in-first-out by
   count (24), so it deletes files still in use. `recs-posters/` is never cleaned up.
7. **Memory.** Each pick builds every piece for both styles: about 12.5 MB at 1080p, mostly GPU (`image` 8.3 MB for
   Spotlight only; `wide` + `tall` 2.6 MB and `lightGlass`/`nightGlass` 1 MB for Cards only). The cache keeps
   `max(10, pool + 3)` picks: about 285 MB in Spotlight, 125 MB in Cards. Images removed from the cache aren't
   recycled. `trim()` runs only at `TRIM_MEMORY_COMPLETE`.
8. **Side bugs.** `glassImage()` is always null in the light build, so Spotlight's left/bottom blur never draws
   (`GlassLook`, `MaterialLook`). Posters are `recycle()`d on the io thread while the screen may still draw them.
   The non-light half of `Backdrop.make` never runs. `debugMemory` (TEMP) still logs every 20 s.

Before changing any eviction or recycling, read `plan-memory-management.md` item 5. Recycling `Recs.images` on
eviction crashed `:home` (`bgFrom`/`bgTo` hold references longer than the cache does). Nothing here recycles a
`Backdrop`.

## The steps

Status (2026-10-31): steps 1–6 built on branch `improve-picks` (`./build.sh` and `translate.py --check` pass) and
tested on a Skyworth 4K Google TV Stick (Android 14, 1080p UI), Cards style with `pick_count=10`,
`pick_rotate_seconds=12`:
- First start (empty cache): each image downloaded and built once, 0.8–2.6 s each.
- Cold start (`am force-stop`, Home): no downloads, no builds; every image read from disk, nothing faded in.
- `:home` memory after a cold start: 149–150 MB total PSS, 63–82 MB graphics, against 233 MB and 99 MB before.
- Refresh (interval set to 2 minutes for the test, then back to 24 h): list fetched, the 16 images not on disk
  built in the background (about 0.8 s each), next list ready, published on the 15 s tick with the focus on the
  shelf; nothing shown without its image.
- Screensaver (Settings preview, `am start -n com.skyhome.launcher/.SettingsActivity --ez screensaver true`): shows the
  saved 1920×1080 photos, moves to the next one after 30 s, sharp at the zoom, no errors.
- Spotlight (switched in Settings > TMDB > Picks carousel, then back to Cards): no rebuilds on the switch (the photo and
  blurred versions saved in Cards style were reused; only posters were downloaded); 5 photos in memory (50 MB); 6
  quick Rights: the focused photo on screen within a second, nothing built; the left/bottom soft blur draws.
  `:home` in Spotlight: 197 MB total, 107 MB graphics (each full-screen photo is about 10 MB). Back in Cards it stayed
  at 220 MB until the posters were also cleared on a style change (now done); a cold start in Cards: 148 MB, 61 MB.
- Settings > TMDB's status line said "3 new ones every hour": now "a new list every day", in every catalogue.
- Not checked yet: Android 11.

Where the build differs from the text below, see **As built** at the end.

Each step builds and works on its own, in this order. 1 to 4 fix the loading you see; 5 and 6 make refreshes silent
and cut the calls; 7 is cleanup.

### 1. Save the processed images (disk cache v2)

`Backdrop` gets a disk form. The first time a pick's image is built, the pieces the screen draws go to
`cacheDir/picks/<backdrop path>/`. After that, `loadImage` reads them back with no scaling and no blurring:

| File | What | Size (1080p) |
|---|---|---|
| `photo.jpg` | the 16:9 crop at `Backdrop.W × H` (1280×720 on 720p UIs), JPEG quality 85 | about 250–400 KB |
| `wide.jpg`, `tall.jpg` | Cards' crops at their focused size (only in Cards style) | about 120 KB together |
| `small.bin` | `frost` (192×108) and `glass` (480×270) as raw ints; `light`, `night`, `lightGlass`, `nightGlass` are cheap to recompute from them | about 600 KB, or about 300 KB stored as RGB bytes |

- Decode with `inPreferredConfig = HARDWARE` (Android 8+, the minSdk) straight at the stored size. A missing or
  unreadable file means building it again from the original.
- The original goes once it has been processed: it's downloaded to a `.part` file, processed and deleted. In Spotlight,
  the screensaver (`readyImage`, `Backdrop.photo`) then reads `photo.jpg`, which is display size. Check that Ken
  Burns' zoom (`DreamView`) still looks sharp enough; if it doesn't, keep `photo.jpg` at 1.25× and say so here.
- Download `w1280` where `Backdrop.W <= 1280`. Elsewhere, keep `original`, but `bestBackdrop` already prefers 1920
  wide, and only what's processed stays on disk.
- **Pruning by use, not by count.** Keep the folders of the titles in the current list and the staged next list
  (step 5), plus the most recently used ones up to 40 MB in total. Use an `index.json` (path → last used) rather than
  `lastModified`. If the folder vanishes (Android emptied it), the index is rebuilt empty and the images are fetched
  again. Posters move into the same folder (`poster.jpg`, w342) and fall under the same limit.
- Bump `CACHE_VERSION`. Delete the old `cacheDir/recs` and `cacheDir/recs-posters` once.

Files: `Backdrop.java` (write/read helpers, split into `make` and `load`), `Recs.java` (`download`, `loadImage`,
`loadPoster`, `prune`, `hasImage`, `readyImage`), `DreamView.java` (if it relies on the original's size).

### 2. Build only what the current style draws

`Backdrop.make(src, style)` instead of `forLight`:
- **Spotlight:** `image` (sharp photo), `glass` and `glassImage()` (this fixes the missing blur), `frost` (accent
  colour). No `wide`/`tall`, no light/night variants unless the look's background uses them; check
  `GlassLook.spotlightPhoto`/`MaterialLook.spotlightPhoto` and `drawLightHome` first.
- **Cards:** `wide`, `tall`, `frost`, `light`, `night`, `lightGlass`, `nightGlass` and the 192×108 bitmaps. No
  1920×1080 `image`.
- Switching the style clears `images`, the way `setLight` does now. The disk cache keeps both styles' files, so the
  switch is a read, not a rebuild.
- Delete the non-light half of `Backdrop.make` and `setLight`'s `light` flag (always true).

Per pick: Spotlight about 9 MB, Cards about 4 MB.

### 3. Hold less in memory, limited by bytes

- `images` becomes an access-order `LinkedHashMap` limited by `Backdrop.bytes()`: a budget of about 50 MB in
  Spotlight (the focused photo ±2, about 5) and 40 MB in Cards (the 3 tabs' cards). Images are removed but **never
  recycled** (see item 5 of the memory plan).
- Posters: decode as `HARDWARE`. Stop `recycle()`ing them on eviction (this fixes the race), and limit them by count
  at `SPOTLIGHT_POOL + 3`.
- `HomeActivity.onTrimMemory`: at `TRIM_MEMORY_UI_HIDDEN` (home screen not visible), keep only what's on screen now.
  At `RUNNING_LOW` and above, the same. Fix `trim()` to keep Spotlight's focused pick, not `now(null)`'s first 3.
  Since reading from disk is now one decode, coming back is quick.

### 4. Separate network and decoding; fade in only the first time

- `Recs.io` becomes two executors. `net` (the existing 4-thread `tmdb` pool, also for image downloads) and `decode`
  (one thread, LIFO). The focused pick goes first. A request for a pick that's no longer within ±2 of the focus (or
  of the visible cards) is skipped when it comes up.
- Each `Backdrop` loaded from disk is ready before the next frame where possible: `updateCards`/`showCards` already
  ask for it on resume, so the decode starts at once.
- The fade-in: `shownAt` moves from `Pick` to a `Map<String, Long>` keyed by image path in `HomeView`, and is set
  only when an image was **downloaded** in this session (`Backdrop.fresh`). An image read from disk shows at full
  alpha, with no placeholder fade.
- Spotlight already keeps the previous photo while the next one loads (`spotlightLastBackdrop`); keep that.

### 5. Stable list, swapped in when ready

- `now()`: no `hour` offset. The order is the list's own (popular first, alternating movies and shows), and the
  franchise filter stays.
- **Two lists:** `picks` (on screen) and `next` (being prepared). The daily refresh fetches into `next`, gets details
  for every title (one call each, step 6), drops those without an installed app ("from my apps" mode), downloads and
  processes their images (step 1), then **publishes**:
  - Titles in both lists keep their `Pick` object, in their slot.
  - The slots of titles that left go to new titles, in `next`'s order. Extra new titles go at the end.
  - Publish only while the home screen isn't showing (`pause()`), or while focus isn't on the picks (Cards: not
    `Z_CARDS`; Spotlight is always on the row, so only on `pause()`). Otherwise, keep `next` ready and publish on
    the next `pause()`. The person never sees a title change under the focus.
- `HomeView.updateCards`: when the list changes by publishing, keep `cardStart`/`spotlightRowShift` (Spotlight wraps,
  so any value is valid) rather than resetting them to 0. Run `startCardTransition` only when something on screen
  actually changed.
- Save `next` too (`recs` prefs), so a process killed halfway carries on where it was rather than starting over.
- First launch (nothing saved): as now, show titles as their images arrive. This is the one time loading is visible.

### 6. Fewer calls

- `refreshIfStale`: stale means `now - listAt > 24 h`, or a new key, region, language, setting or set of installed
  apps (`sourceStamp`), or no list at all. Remove `p != period`, `period` and `LIST_EVERY = 6 h`.
- **One call per new title:** `/{kind}/{id}?language=…&append_to_response=images,watch/providers,external_ids&include_image_language=null,<lang>,en`.
  From it, `Recs` takes the backdrop, meta and service, and `Launcher` takes its `Info` (offers and IMDb id) through
  a new `Launcher.remember(kind, id, JSONObject)` that writes the same file `info()` reads. `Launcher.prefetch` is no
  longer called from the picks.
  **The best backdrop is kept:** `bestBackdrop` (textless first, closest to 1920 wide, then highest rated) runs on
  the `images` part of this answer, which is the same list `/images` returns today. In the default mode, pass
  `include_image_language=null` exactly as `fetchBackdrop` does now. Check on the TV that the same titles get the same
  backdrop paths before and after.
- Details only for titles new to the list (already the case; keep it with the two lists).
- `title()`'s extra English request stays (needed, and rare).
- The screensaver's `prefetchAll`: with the whole list ready after each refresh, it only fills gaps (failures). Check
  that it finds nothing to do in the normal case.
- Expected: about 5 list calls (4 discover + providers or genres) plus 1 call per new title, **once a day**, against
  4+ refreshes and 2 calls per title now.

### 7. Cleanup

- Remove `debugMemory`, `debugImageBytes`/`debugBytes` (TEMP) if the memory plan no longer needs them; ask first.
- `Recs`' class comment ("Three per hour") and `now()`'s comments: say the list is stable and refreshed daily.
- `CLAUDE.md`: one line on the picks' disk cache (`cacheDir/picks`, 40 MB) and the daily refresh.

## Checks

On the TV (`adb logcat -s SkyHomeLauncher`, plus a temporary log line per download, build and disk read):

1. **Cold start with a cache:** `adb shell am force-stop com.skyhome.launcher`, press Home. Every visible image comes
   from disk (no download, no build). Nothing fades in. Time it: under 100 ms per image on the TCL/onn.
2. **Process killed by memory:** open Netflix and a few apps, press Home. Same as 1.
3. **First launch:** `pm clear` on a test TV only (never on someone's TV), check images arrive and fade in once.
4. **Refresh:** move `listAt` back 25 h (or temporarily make the interval 2 minutes). The new list is prepared in the
   background, nothing changes while the picks have focus, and after Home or Back the titles have changed with no
   placeholders. Logs show one TMDB call per new title.
5. **Memory:** `dumpsys meminfo com.skyhome.launcher:home` (Graphics, GL mtrack) in Spotlight and in Cards, before and
   after. Expected: about 200 MB less GPU in Spotlight.
6. **Disk:** `du` of `cache/picks` stays under 40 MB after several refreshes. Delete `cache/picks` while the app runs
   and check that the images come back (one visible load) with no errors.
7. **Both styles and switching between them,** Spotlight's left/bottom blur now draws, the screensaver still shows
   photos, Android 11 (`ScreensaverAlone`) included.
8. `tools/check.sh` passes (build and `translate.py --check`; no new text expected).

## As built (differences from the steps above)

- **Least recently used by file time, not `index.json`:** reading an image sets its photo's (or poster's) modified
  time, at most once an hour (`Recs.touch`), and `prune` sorts by the newest time of each image's files.
- **Screensaver:** it reads the saved 1920×1080 photo, so at the end of its slow zoom (1.1×) it upscales by up to 10%.
  720p TVs already did (they download `w1280`).
- **Calls for titles that stay:** `Launcher` keeps where a title streams for a day, so `prepare` still calls
  `Launcher.prefetch` for each title that stays (one call each, once a day: where it streams can change). New titles
  cost one call in all (`fetchBackdrop`/`fetchDetails` hand their answer to `Launcher.remember`). The savings are the
  list calls (once a day instead of 4+) and the second call per new title.
- **Memory budgets:** Spotlight 52 MB (focus ±2), Cards 48 MB (the three tabs' 9 cards at ~4.7 MB each).
- **Next list:** saved as `next` in the `recs` prefs, published from `HomeView.pause()` and, in Cards style, from the
  15 s tick while the focus isn't on the cards. A first list, or one for changed settings or language, shows at once.
  Titles whose details or image failed are left out of the next list.
- **Poster bitmaps** are HARDWARE and no longer recycled when dropped (the row may be drawing them).
- **Spotlight's soft blur** now draws (`glassImage()` is built in both styles). This is a visible change.
- `TRIM_MEMORY_RUNNING_LOW` and above (which includes `UI_HIDDEN`) trims the images to the one on screen.
- Step 7: the temporary memory logging (`debugMemory`, every `debug…Bytes()`) is removed; `CLAUDE.md` not updated yet.
