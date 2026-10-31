# Plan: improve the Trakt rows and the catalogs

The poster rows (Trakt's Continue Watching, Shows you watch and Watchlist under the drawer, and TMDB's catalog rows on
the browse screen) have some of the problems the picks had (`plan-improve-picks.md`), on a smaller scale: posters are
small (TMDB's w342, about 30 KB) and Trakt already checks for changes before fetching. Goal: scrolling with no slow
frames from posters, fewer TMDB calls while moving the focus, and lists that never change under the focus.

Status (2026-10-31): steps 1–6 built on branch `improve-picks` (after the picks commit) and tested on the Skyworth
4K Google TV Stick (Android 14, 1080p):
- Trakt rows and the browse screen draw (posters, badges, details, picture).
- Posters are GPU bitmaps: the render thread's uploaded images are 2 MB (61 small ones: badges, the picks' small
  blurred backgrounds), against about 12 MB for the 40 posters before; `:home` Java heap 12.9 MB (was about 25 MB).
  "Slow bitmap uploads" stayed at about 7 per 8 moves: they come from those small images, not posters.
- 10 quick Rights on a row not seen before: 3 TMDB requests (the title the focus stopped on and its neighbours, the
  badge from the same answer), against up to 4 per poster passed before.
- Trakt: `trakt: fetched what was watched` (the watchlist kept).
- Not checked: a TMDB rows refresh being published (24 h; the code path is the picks' one), the episodes screen's
  400 px posters, Android 8 to 11.
- As built: the "skip when scrolled past" check uses the time each poster was last drawn (500 ms) instead of a
  `setView`; posters are saved per width (`<path>-<width>.jpg`: the rows' 225 px, the episodes screen's 400 px);
  the new rows' first 10 posters per row are saved ahead (`Catalogs.rowPosterWidth`).
- The browse screen's picture is also asked only once the focus rests (`browseRested`): before, titles whose details
  were in memory had their picture asked on every frame while passed, and `Details.backdrop` skips a queued picture
  that's no longer the latest asked for.
- Decisions confirmed as proposed: 24 h, 150 ms, 20 MB / 30 MB.

## Decisions

- **TMDB rows refresh every 24 h**, like the picks (now 6 h). TMDB's lists change about once a day (trending is
  "today"). Trakt's rows keep their 30-minute change check.
- **Focus details wait for the focus to rest** about 150 ms before asking TMDB; holding an arrow asks for nothing on
  the way.
- **Disk:** the `catalogs` and `details` folders stay in `getCacheDir()`, limited by size (20 MB and 30 MB), the least
  recently used going first.

## What's wrong now

1. **Posters are uploaded to the GPU while a frame is drawn.** `Catalogs.poster` decodes software bitmaps
   (`Catalogs.java`, `BitmapFactory.decodeFile` with no config): the first draw of each one uploads it on the render
   thread (`dumpsys gfxinfo`: "Slow bitmap uploads" while moving on the browse screen). The 40 cached posters hold
   about 12 MB of heap (`mem:` log: `posters 11849KB(40)`) plus a GPU copy. Dropped ones are recycled 1 s later
   (`removeEldestEntry` → `main.postDelayed(b::recycle, 1000)`), the pattern that crashed `Recs.images`
   (`plan-memory-management.md`, item 5), only delayed. `Details.backdrop` (the browse screen's picture, 782×440, 3
   kept) is the same kind of bitmap.
2. **Two to six TMDB calls per focus move on the browse screen.** `HomeView.touchBrowse` asks `Details.info` for the
   focused title and both neighbours (`/{kind}/{id}?append_to_response=videos`), and `catalogService` asks
   `Launcher.service(…, fetch = true)` for the focused one (`/{kind}/{id}?append_to_response=watch/providers,external_ids`):
   two calls for the same title. Holding Right sends them for every poster passed. Details are kept a week and
   where-it-streams a day, so it costs on titles not seen recently.
3. **Posters load first-in, first-out.** `Catalogs.images` is a plain 2-thread pool: after a quick scroll the posters
   passed load before the ones now on screen.
4. **Lists change under the focus.** TMDB's rows are fetched every 6 h, when the drawer opens, and shown at once (new
   titles as placeholders). Trakt's rows are fetched again after any change on Trakt (an episode finished). Either
   way `HomeView.syncRows` keeps the focus by column, not by title, so the focused poster becomes another title (and
   its details and trailer start).
5. **Disk.** Trakt's posters (larger than w342) and the TV apps' Watch Next pictures (often wide) are saved as
   downloaded and scaled down (and cropped) at every decode. `catalogs` keeps the newest 300 files and `details` the
   newest 200, by download time, not last use; `details` mixes the detail JSON with the backdrop images under one count.
6. **Trakt fetches every list when one changes.** `Trakt.refreshIfStale` compares one stamp built from movies,
   episodes and the watchlist; any change refetches playback, up next, `/sync/watched/shows` (the whole history) and
   the watchlist.

Already fine: Watch Next (a local query, re-read at most every 3–20 s), Trakt's show posters (kept in prefs), the
30-minute `last_activities` check, `Details`' week-long cache.

## The steps

Each builds and works on its own, in this order. 1 and 2 make scrolling smoother and use less memory; 3 cuts the
calls; 4 stops changes under the focus; 5 and 6 tidy up.

### 1. Posters and the browse picture in GPU memory

- `Catalogs.poster`: decode with `inPreferredConfig = HARDWARE` at the drawn size. The steps that need software
  pixels (cropping a wide app picture, scaling) happen once, when the file is saved (step 5); until then, decode
  software, crop/scale, then `Backdrop.gpu()`.
- Drop the delayed `recycle()` on eviction (the row may still draw it); limit by count as now (40), or by bytes.
- `Details.backdrop`: the same (HARDWARE, no recycle; it already isn't recycled).
- Check `HomeView.drawCard`/`roundImage` draw HARDWARE bitmaps on Android 8 to 11 (`Ui.LEGACY` uses a BitmapShader:
  the picks' card images already are HARDWARE there, so it should).

Files: `Catalogs.java`, `Details.java`.

### 2. Posters: last asked first, skipped when scrolled past

- `Catalogs.images` becomes a disk-read queue like `Recs.decode` (one thread, `LinkedBlockingDeque` with
  `offerFirst`) plus a download pool (2 threads) for posters not on disk.
- Before decoding, skip a poster no longer near the screen: HomeView tells `Catalogs` the visible rows and columns
  (`setView(rowKey, firstCol, lastCol)`, ±1 column), the way `Recs.setView` works.

Files: `Catalogs.java`, `HomeView.java` (`drawPoster`'s callers).

### 3. One TMDB call per title, only once the focus rests

- `Details.fetch` asks `/{kind}/{id}?language=…&append_to_response=videos,watch/providers,external_ids&include_video_language=…`
  and hands the answer to `Launcher.remember(kind, id, o)` (built for the picks), so the badge and OK need no call of
  their own.
- `HomeView.catalogService`: with `fetch = true` only when `Details` has nothing for the title (it would ask anyway).
- `touchBrowse`: ask `Details.info` (and the neighbours) 150 ms after the focus stops on a poster
  (`handler.postDelayed`, cancelled by the next move). The text and picture already wait for the answer.
- Same for Trakt's rows under the drawer, if they ask for details or services on focus (check `catalogService`'s
  callers there).

Files: `Details.java`, `HomeView.java`.

### 4. No list changes under the focus

- `HomeView.syncRows` and `syncBrowse`: keep the focus on the same title (by `kind` + `id`, or `hideKey` for Watch
  Next tiles); only when it left the row, the nearest column.
- TMDB rows: `Catalogs.refreshIfStale` fetches into a waiting list, downloads its posters (the first ~10 of each row,
  what shows first), then publishes it when the browse screen isn't open (like `Recs.publish`: from `HomeView.pause()`
  or when the drawer closes). Every 24 h (`EVERY`), saved like the picks' `next`.
- Trakt rows: the same keep-the-focus rule is enough (they change because you did something).

Files: `Catalogs.java`, `HomeView.java`.

### 5. Posters saved at the size drawn; disk by size and last use

- When a poster is downloaded or copied (Trakt's, Watch Next art, TMDB's w342), save it cropped to the card's
  portrait shape and scaled to the drawn width (`CAT_POSTER_W * 1.25` at the UI's scale), JPEG quality 88; delete the
  download. Decoding is then one HARDWARE decode.
- Touch a file when it's read (as `Recs.touch`); prune `catalogs` to 20 MB and `details` to 30 MB, least recently used
  first, keeping the files of the rows on screen. Split `details` into `details/` (JSON) and `details-images/`.
- Bump the cache folder names (`catalogs` → `posters`) so old full-size files go once.

Files: `Catalogs.java`, `Details.java`.

### 6. Trakt: fetch only the lists that changed

- `Trakt.stamp` becomes three stamps (movies + episodes for Continue Watching and Shows you watch, the watchlist for
  the Watchlist), saved apart; fetch only the rows whose stamp changed, keep the others from `rows`.

Files: `Trakt.java`.

## Checks

On the TV (`adb logcat -s SkyHomeLauncher`, `dumpsys gfxinfo com.skyhome.launcher:home`, `dumpsys meminfo`). Never
`am force-stop` the app while testing: it unbinds the remote buttons' accessibility service until the TV restarts or
the setting is written again. Restart `:home` by installing again instead.

1. **Scrolling:** reset gfxinfo, scroll a browse row 10 posters and back: "Slow bitmap uploads" 0 (was 6 or 7).
2. **Memory:** the `mem:` log's `posters` figure moves from heap to graphics; `:home` heap down by about 12 MB.
3. **Calls:** with a temporary log line per TMDB request, holding Right across a row asks only for the title the
   focus stops on; one request per title, none for its badge.
4. **Focus:** with a row focused, mark an episode watched (Trakt) or force a TMDB refresh (interval set to 2 minutes
   for the test): the focused title stays focused.
5. **Disk:** `catalogs`/`details` stay under their sizes after a long scroll through every row.
6. **Trakt:** change only the watchlist on trakt.tv: the log shows one list fetched, not four.
7. Both picks styles, Android 8 to 11 if a TV is at hand (HARDWARE posters through `Ui.LEGACY`'s shader path).
8. `./build.sh` ends with `Built SkyHomeLauncher.apk`; `py tools/translate.py --check` passes (no new text expected).
