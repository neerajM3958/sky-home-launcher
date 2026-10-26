# Plan: Watch Next shows in "Shows you watch", and their episodes page

Status: implemented; Watch Next TV flow could not be exercised on the current TV. Google TV only (like Trakt's rows and Watch Next).

## Wanted

The shows the TV's apps say you are watching (Android TV's shared Watch Next database) should appear in the
**Shows you watch** row next to Trakt's, the way **Continue Watching** already shows both. OK on one opens the
**episodes page** (the same screen as Trakt's shows), and an episode opens like any other title through the routing, with one
exception: the exact episode that Watch Next says you are on resumes in that app with the app's own intent.

## Decided (from the discussion)

1. A pinned **Resume** row at the top of the episodes page for these shows.
2. On a show with no Trakt data the **Watched** filter is hidden (TMDB has no watched marks).
3. When Trakt is connected, try to find the show's Trakt id from its TMDB id, to get real watched marks (a second step).
4. The existing switch *Home Settings > Apps' Continue Watching* (`Prefs.watchNext`) controls this row too.
5. Every episode opens through the normal routing (Stremio's episode page, the service's show page, the Play Store, ...)
   except the one Watch Next is on, which fires its resume intent.
6. **Watchlist entries count too.** Watch Next has a third kind of entry, the app's own watchlist (`watch_next_type` 2: on this TV
   JioHotstar publishes "Mannat - Har Khushi Paane Ki" and "Anupama" that way). They go into **Shows you watch** only; Continue Watching
   stays as it is (continue and next entries). The pinned row on their page says it opens the app rather than that it resumes.

## What exists already (reuse)

- `WatchNext.read()`: one entry per show (the newest), with `title`, `episode_title`, `season_display_number`,
  `episode_display_number`, `watch_next_type` (continue or next), `type`, the app's `intent_uri` (as `Item.launch`), `package_name`,
  progress and time. `lookup()` finds the show's TMDB id and poster with one cached search per title.
- `Catalogs.withWatchNext()`: merges Watch Next into the Continue Watching row (de-duplicates by TMDB id, then name; sorts by `at`; builds
  the row when Trakt has none). The same pattern gets a sibling for the shows row.
- `EpisodesActivity`: the page, `EpisodeList` (sort and filters, no Android in it), `Launcher.open("tv", id, imdb)` routing for OK.
- `HomeView.activate()` (Z_CATALOG): `launch != null` resumes in the app; `episodes` opens the page.

## How

### 1. WatchNext: keep what a show card needs
- Read the raw `type` column and keep it on `Entry` (0 movie, 1 series, 2 season, 3 episode, 4 clip, ...). Today everything that is not a movie
  is "tv", which would let clips in. A **show entry** is type 1, 2 or 3 (with a season/episode number or an episode title).
- Keep the entry's **kind** too (`watch_next_type`: 0 continue, 1 next, 2 watchlist). `read()` today skips everything but 0 and 1 and keeps one
  entry per title; it will return every kind, and each list picks what it needs:
  - Continue Watching (`items()`): kinds 0 and 1 only, one per title, as now (a watchlist entry never shows there).
  - The shows row (new `showItems()`): kinds 0, 1 and 2, TV types only, one per show. When a show has several, the better kind wins
    (continue, then next, then watchlist), then the newest; so a watchlist entry never hides the resume data of a show being watched.
- The age cut-off (60 days since `last_engagement_time_utc_millis`) stays for all kinds. A watchlist entry with no time counts as old and is
  left out; to revisit if watchlisted shows go missing on real TVs.
- `Catalogs.Item` gets `season`, `episode` (ints, 0 unknown), `episodeTitle` and `wnKind` (0, 1 or 2), set from the entry (today only the text `sub` is kept).
  `Item.launch`, `appPkg`, `at`, `id` (TMDB), `poster` are already there.
- Shows with no TMDB id (no match, or no TMDB key) stay in Continue Watching only: the page needs an id.

### 2. Trakt's shows get a time
- `Trakt.showsRow()` sets `it.at` from `last_watched_at` (it is saved in the row already). Bump `ROWS_VERSION` 4 -> 5 so saved rows are made again.
  That lets the two sources sort together.

### 3. Catalogs: the merged row
- New `withWatchShows(rows)` after `withWatchNext(...)` in `rebuild()`: copies of the show entries from `showItems()` (continue, next and watchlist; new `Item`
  objects, `episodes = true`, `launch` kept; the Continue Watching copies stay as they are) that Trakt's shows row does not list (`listed()`: TMDB id, else name), added to `Trakt.SHOWS`,
  or a new row (after Continue Watching, else first) when Trakt has none. Up to `WatchNext.MAX`, sorted newest first, the row capped at 20.
- Nothing when `Prefs.watchNext()` is off or the permission is missing (already how `watchNext` is emptied).
- The shows row is de-duplicated only against Trakt's *Shows you watch*. A show that is in Trakt's own *Watchlist* row and in a Watch Next watchlist can
  appear in both rows (different rows, different lists): accepted.
- A show card has no caption under the title (like Trakt's); the watchlist or resume wording is on the page, not on the card.

### 4. HomeView: click order
- In `Z_CATALOG`, test `listed.episodes` **before** `listed.launch`, because these cards have both. Continue Watching's copies have no
  `episodes`, so they still resume directly.
- `EpisodesActivity.intent(item)`: add `season`, `episode`, `episodeTitle`, `resume` (the intent as `launch.toUri(0)`) and `app` (package). `traktId` is 0 for these.

### 5. TMDB as the episode source (new `TmdbEpisodes`)
- `load(tmdbId, listener)` calls the same listener the page already has (`onEpisodes(list, fresh)`), so the page needs no second path.
- One request for the show (`/tv/{id}`, `number_of_seasons`), then the seasons with `append_to_response=season/0,season/1,...` in chunks
  of at most 20. Each episode: season, number, name, air date; `watched = false`.
- Saved like Trakt's copy (cache dir, newest 30 shows), the saved copy first, then a fresh one after a few hours.
- Language as the picks (`Recs.lang()`).

### 6. EpisodesView
- Source: `traktId > 0` -> Trakt as today; else TMDB (`tmdbId > 0`). No marks -> `hasMarks = false`.
- **Watched filter hidden** when `!hasMarks`: `barCount()`/`BTN_WIDTHS` drop that button (bar of three, plus Clear filters).
- **Resume row**: when the page has resume data, `list.all` is not touched; `rebuild()` puts a pseudo episode (`resume = true`, new field on
  `EpisodeList.Episode`) at index 0 of `shown`, whatever the filters. Label "Resume" for a continue or next entry, "Watchlist" (an existing string) for a
  watchlist entry; title = the episode title or "S2 · E746" (the show's name when the entry has neither); date column = the app's name.
  Opening it fires the stored intent. First look: selection on it.
- A watchlist entry's season and episode, when it has them, are matched like any other (the marker and the exact-episode resume); the intent opens the
  app where that app puts it, which may be the show's page and not an episode. The hint says "OK opens {app}." there, not "resumes".
- **Marker on the matching episode**: match `season` and `number` in `list.all`; else the number alone if it is unique; else none. The match
  also fires the resume intent (decision 5) and shows the same hint. (Apps number soaps in their own way, so a miss is expected; the pinned row is the reliable path.)
- OK on any other episode: unchanged routing. The IMDb id: `Launcher` keeps it from TMDB (`Info.imdb`); add `Launcher.imdb(kind, tmdbId)` and use it when the
  page's own `imdb` is empty, so Stremio opens the episode and not only the show.
- The hint line: "OK resumes in {app}." (continue and next) or "OK opens {app}." (watchlist) on the resume row and its match (`whereOkGoes()`), with TalkBack text.
- If the intent no longer resolves (app gone) -> fall back to the routing for that episode.

### 7. Trakt id for marks (second step, decision 3)
- When Trakt is connected and `traktId == 0`: `GET /search/tmdb/{id}?type=show` (the Client ID is enough) -> `show.ids.trakt`; kept (tmdb -> trakt).
  If found, switch the page to the Trakt source (marks, Watched filter back, "next to watch" start) and keep the resume row.
- Not found or not connected: stays on TMDB.

### 8. Text and docs
- New strings: "Resume", "OK resumes in {app}.", "OK opens {app}." (and the TalkBack forms). All 24 catalogues (`tools/translate.py`, by hand like the others).
- README (Your Trakt lists / Continue Watching), `CLAUDE.md` (a bullet next to the Watch Next one), this plan's *Built* section with screenshots.

## Edge cases and risks

- **Wrong show.** `lookup()` takes the first exact name match. Same-name shows (remakes, US/UK) can hit the wrong one. Use the entry's year or original language when
  there is one; if two exact matches stay ambiguous, leave the show out of this row.
- **Numbering.** Absolute numbers (S2 · E746) may not exist in TMDB. Covered by the number-only match and the pinned row.
- **Entries come and go.** The row follows the provider (the content observer already refreshes it); the intent is not saved, and is handed over at click time.
- **Watchlist entries are thin.** Apps may give no season or episode, a show's page instead of an episode, or no time. They are matched by name only, so the
  wrong-show risk above weighs more; with no episode numbers the pinned row is the only special row. Watchlisted shows with a poster-less match are left out.
- **Two rows can hold the same show** (Trakt's Watchlist row and a Watch Next watchlist): accepted, see the row section.
- **No TMDB key** -> no ids -> no row (Continue Watching still works). **Permission denied** -> nothing, as today.
- **Time.** TMDB episode lists of long soaps are large (hundreds per season): the season requests run off the main thread and the list shows what is
  saved first. No work on the UI thread, no allocations in `onDraw`.
- **Quota.** One search per new title (kept), a few season requests per show opened (kept for hours).

## Order of work

1. `WatchNext` type/season/episode, `Item` fields, `Trakt` `at` (+ version).
2. `Catalogs.withWatchShows`, `HomeView` click order, the intent extras.
3. `TmdbEpisodes`, the page's source switch, hidden Watched filter, imdb from `Launcher`.
4. Resume row, marker, hint, fallback.
5. Trakt id resolution.
6. Strings, docs, screenshots.

## Verification

- `./build.sh` ends with `Built FreeMyTV.apk`; `tools/translate.py --check`; `tools/check.sh`.
- On a TV, without a publishing app: `content insert` on `content://android.media.tv/watch_next_program` from the shell (the provider records
  `com.android.shell` as the publisher; write `intent_uri` as `#Intent;action=...;component=pkg/cls;end`, no art address). Insert a TV
  episode (`type` 3, `watch_next_type` 0 or 1, `season_display_number`, `episode_display_number`, `episode_title`, the show's exact TMDB name), and
  one with `watch_next_type` 2 (watchlist): it shows in Shows you watch and **not** in Continue Watching; a show with both kinds shows once, with the
  resume data of the continue entry. Read what the TV really holds first (Hotstar on the Redmi: 10 entries, kinds 0, 1 and 2, types 0 and 3).
  Check: the row appears (with and without Trakt connected), de-duplicated against a Trakt show, sorted by recency; OK opens the page; the Watched button
  is gone without Trakt; the Resume row and the marker; OK on Resume fires the intent; OK on another episode opens Stremio's episode page; with Trakt the
  marks come back.
- Remove the test entries afterwards. Screenshots under `plans/images/watch-next-episodes/`.

## To decide

Nothing blocking. Confirmed decisions: keep the 60-day cutoff and leave out entries with no engagement time; use a title year when the
Watch Next title supplies one to distinguish exact TMDB matches, and leave unresolved same-name matches out. The row shows at most 12
Watch Next shows (the cap Continue Watching uses) inside the row's 20.

## Built

- Watch Next now retains entry type, kind, season and episode data. Continue Watching still merges only continue/next entries; Shows you
  watch merges recent TV entries including app watchlists. Both rows follow `Prefs.watchNext` and require the existing TV-listings permission.
- Watch Next shows open the episodes page with TMDB episodes, a pinned Resume/Watchlist row, a resume marker and intent fallback. When Trakt
  is connected, the page resolves a Trakt id for watched marks. Trakt show recency is included in row sorting.
- Added the new strings to all 24 catalogues and updated README and `CLAUDE.md`.
- Computer checks: `build.sh` succeeds; `tools/translate.py --check` passes (352 keys). Installed on `192.168.0.10`; Free My TV's Home screen opened without an Android runtime crash.
- The TV's Watch Next provider returned no rows. Test inserts were denied by `com.android.providers.tv.permission.WRITE_EPG_DATA`. A temporary hardcoded fixture was used only in a local validation build: the TV rendered one Continue Watching card and two deduplicated show cards, opened Breaking Bad's 72-episode page with “Bit by a Dead Bee” pinned, and the pinned Resume action opened the fixture's Settings intent. The fixture and temporary source logs were removed before the final build; no provider test entries were written. The live provider path and an actual streaming-app intent still need a publisher entry to verify.
