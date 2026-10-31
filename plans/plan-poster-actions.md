# Plan: tile actions (remove / hide) on Continue Watching, Shows you watch and Watchlist

Status: draft, for amending before implementation. No code for this plan is written yet.

## Goal

Hold OK on a poster in the home screen's Trakt rows to get a small menu with **Remove** or **Hide**. Where Trakt has a
real, expected "remove" we call it. Everywhere else the launcher hides the tile on its own, and never writes anything to
Trakt's hidden/dropped lists or to the person's watch history. "Mark as watched" is out of scope for this plan.

## How it works today

- The rows are `Catalogs.Row`s (keys `Trakt.CONTINUE`, `Trakt.SHOWS`, `Trakt.WATCHLIST`) of `Catalogs.Item`s, built in
  `Catalogs.rebuild()`: `Trakt.rows()`, then `withWatchNext` (Watch Next into Continue Watching) and `withWatchShows`
  (Watch Next into Shows you watch). They are drawn in the `Z_CATALOG` zone of `HomeView`.
- Where a tile comes from:

  | Row | Source | Marker on the `Item` |
  |---|---|---|
  | Continue Watching | `/sync/playback` (stopped part-way) | `progress > 0`, `sub` = episode |
  | Continue Watching | `/sync/progress/up_next` (next episode) | `progress == 0`, `sub` = next episode |
  | Continue Watching / Shows you watch | Android Watch Next (other apps) | `launch != null`, `appPkg` |
  | Shows you watch | `/sync/watched/shows` | `episodes`, `traktId` |
  | Watchlist | `/sync/watchlist` | `traktId`, `episodes` for shows |

- `Trakt` only reads (`get()`); its only writes are the login and token refresh through `call()`. The device-flow token is
  not scoped, so the existing login should be allowed to write (confirm with the first real call).
- `Trakt.rows` is saved in the `trakt` SharedPreferences (`encode`/`parse`, `ROWS_VERSION`) and refetched when
  `/sync/last_activities` changes (every 30 min at most).
- A Watch Next entry belongs to the app that published it. We hold `READ_TV_LISTINGS` only, so we cannot delete it.
- OK-hold in `Z_CATALOG` is free: `holdArmed` is set only for `Z_BROWSE`, the sheet and the shelf (`HomeView.java:3089`).
- `HomeView` has one popover (`popLayer`, `openPop`/`choosePop`/`drawPopover`, a11y `POPUP`) but it holds
  `HomeApps.Item`s (the input groups), so it cannot carry actions as it is.

## Design

### What each action does

| Tile | Menu | Effect |
|---|---|---|
| Watchlist | **Remove from Watchlist** | `POST /sync/watchlist/remove` with `{"movies"\|"shows":[{"ids":{"trakt":N}}]}` |
| Continue Watching, stopped part-way | **Remove** | `DELETE /sync/playback/{id}` (needs the playback `id`, see below), then a launcher hide so the show does not come back as its next episode |
| Continue Watching, next episode | **Hide** | Launcher hide only |
| Shows you watch (Trakt) | **Hide** | Launcher hide only |
| Watch Next tiles (either row) | **Hide** | Launcher hide only |

The label tells the truth: "Remove" only where Trakt removes it, "Hide" where only this launcher forgets it. The menu is
not shown when the tile has nothing to offer (it always has at least Hide).

### 1. The launcher's hide list (`Prefs`)

- `Prefs.hiddenTiles()` / `hideTile(key)` / `unhideAll()`: one string in `sp()`, lines of `key<TAB>hiddenAtMillis`,
  capped at 200 (the oldest go first). Same one-way override pattern as the other `Prefs` values; no `Config` entry.
- Key = `row.key + "|" + id` so hiding from one row does not hide the title from another:
  - Trakt tiles: `kind + ":t" + traktId` (e.g. `tv:t1234`).
  - Watch Next tiles: `"wn:" + appPkg + "|" + WatchNext.normalize(title) + "|" + kind`.
  - Helper `Catalogs.hideKey(rowKey, item)` so both sides use one function.
- A hidden tile comes back when it has newer activity than the hide: `item.at > hiddenAt`. A show you start watching again
  shows up again by itself. Watchlist tiles (`at == 0`) are removed on Trakt, so this never applies to them.
- Applied in `Catalogs.rebuild()` after `withWatchNext`/`withWatchShows`, so a hidden Trakt tile does not let its Watch
  Next twin fill the gap (`listed()` dedupes the twin against the unfiltered Trakt list). A row left empty is dropped.
- Settings: a *Reset hidden titles* row next to *Apps' Continue Watching* (`SettingsActivity` ~1696), showing the count and
  doing `unhideAll()` on OK (with a toast), then `Catalogs` rebuilds.

### 2. Trakt writes (`Trakt`)

- Generalize `get()` into `send(method, path, body)` with the same one-time 401 refresh; `get()` keeps its 200-only rule.
  Writes accept 200, 201 and 204. Failures are `IOException("Trakt <code> for <path>")` like today.
- `playbackId` on `Catalogs.Item` (Trakt's `id` from `/sync/playback`), saved in `encode`/`parse` (`"pid"`), which means
  bumping `ROWS_VERSION` to 7 so old saved rows without it are refetched.
- `removeFromWatchlist(Item, Callback)` and `removePlayback(Item, Callback)` run on the existing `net` executor. Before the
  request they take the item out of `rows` and persist, then `changed()` (optimistic). On failure they put it back (same
  index), `changed()`, and call back with the error so `HomeView` toasts it. The callback is posted to the main thread.
- Do not wait for the 30-minute check: the local edit is already the truth. The next refetch brings Trakt's version, which
  matches.

### 3. The menu (`HomeView`)

- Arm the hold: `holdArmed = ... || zone == Z_CATALOG && canActOnTile()`; `hold()` gets a `Z_CATALOG` branch that opens the
  tile menu. A short OK still opens the title (`activate()` unchanged); the hold sets `okHeld` so the release opens nothing.
- Generalize the popover rather than building a second one: replace `List<HomeApps.Item> popItems` with a small
  `PopRow { String label; boolean dim; Runnable action }`. The input groups build `PopRow`s whose action is
  `launch(item, rect)`; the tile menu builds rows whose action is the hide/remove. `choosePop`, `drawPopover` and the
  three a11y spots (`label(popItems, i)`, `POPUP` ids) read `PopRow`. `popTitle` becomes the tile's title.
- Position (decided): beside the focused poster, on whichever side has room. Take the poster's rect (`posterRect`), put
  the panel to its right (`rect.right + gap`); if that would pass `CONTENT_R`, put it to its left (`rect.left - gap -
  POP_W`), and clamp to the screen. The first card opens to the right, the last to the left. Vertically it lines up with
  the poster's top, clamped so it stays on screen. `layoutPop` takes an anchor rect for this (the input groups keep
  their top-right spot), and `popLayer` must cover the whole content area for tile menus.
- After a choice: close the menu, `click.open()`, move focus to the neighbouring tile (or leave the row when it is
  empty, which `Z_CATALOG && rows.isEmpty()` already does at `HomeView.java:2313`), and `showToast(...)`:
  "Removed from Watchlist: {title}" / "Hidden from Continue Watching: {title}".
- TalkBack: `a11yHold()` already exists for All Apps; give it the same `Z_CATALOG` branch.
- Optional later: the remote's Menu key opens the same menu (not in this plan).

### 4. Text

New strings go through `Ui.tr`/`Ui.trf`: "Remove", "Hide", "Remove from Watchlist", "Removed from Watchlist: {title}",
"Hidden from {row}: {title}", "Couldn't remove it. Try again.", "Reset hidden titles". Run `python3 tools/translate.py`,
then add them by hand to the hand-translated catalogues (pt, es, de, ru, tr) and fill the rest (`--fill` or by hand).

## Files

| File | Change |
|---|---|
| `Prefs.java` | hidden tiles list, `hideTile`, `unhideAll`, count |
| `Catalogs.java` | `playbackId`, `hideKey`, filter in `rebuild()` |
| `Trakt.java` | `send()`, `removeFromWatchlist`, `removePlayback`, `pid` in encode/parse, `ROWS_VERSION` 7 |
| `HomeView.java` | `PopRow`, tile menu, hold arming, a11y, toasts |
| `SettingsActivity.java` | *Reset hidden titles* row |
| `app/assets/i18n/*.json` | new strings |
| `CLAUDE.md` | a bullet on tile actions and the hide list (after it works) |

## Edge cases

- Not connected to Trakt: Trakt rows do not exist; only Watch Next tiles can show, so the menu is just Hide.
- A tile from Trakt without `traktId` (should not happen, `item()` needs a TMDB or IMDb id): offer Hide only.
- Offline / Trakt error: the tile returns, toast says so; nothing is hidden locally for a failed Remove.
- The same show in Continue Watching and Shows you watch: hiding in one does not hide the other (the key has the row).
- Hide list growth: capped at 200; clearing app data clears it.
- `Catalogs` rebuilds from several triggers (Trakt change, Watch Next observer): the filter is in `rebuild()` so every
  path applies it.
- The row object is rebuilt as a new list each time, and `HomeView` compares by reference; focus indexes
  (`catRowIndex`, `catCol`) must be clamped after the list shrinks.

## Testing

- `tools/check.sh` (build + `translate.py --check`) and `./build.sh` ending with `Built SkyHomeLauncher.apk`.
- On a TV with Trakt connected: remove a Watchlist title and see it gone on trakt.tv; remove a paused title; hide a
  next-episode tile and a Shows you watch tile; restart the launcher and confirm they stay hidden; watch one episode of a
  hidden show and confirm it returns; *Reset hidden titles* brings the rest back.
- Failure path: airplane mode, Remove, the tile comes back with the toast.
- Watch Next without a publishing app: `content insert` into `content://android.media.tv/watch_next_program` as in
  `CLAUDE.md`, hide it, confirm it stays hidden after the observer fires.
- Short OK still opens a title and episodes page; hold on a shelf app and in Z_BROWSE still behave as before.
- Frame rate: no allocation in `onDraw` from the menu (reuse `PopRow` arrays and rects).

## Out of scope

- Marking a whole show watched, unwatching, and anything that writes to Trakt's hidden or dropped lists.
  (Mark as watched for movies and single episodes was added after the first pass: a second menu row, `POST /sync/history`.)
- TMDB picks rows (`Z_BROWSE`), where hold already plays the trailer.
- Deleting other apps' Watch Next entries.

## Open questions

1. Trakt token write access: confirmed only by the first real `POST`/`DELETE`.

## Decisions

- Menu sits beside the focused poster, left or right depending on room (see Design 3).
- A hidden title comes back on newer activity (`item.at > hiddenAt`).
- Fallback when Trakt refuses a write (403, or a 401 that survives the token refresh): the tile is hidden in the launcher
  instead, the refusal is remembered in the `trakt` prefs (`writeDenied`) so the menu says "Hide" and does not offer a
  Remove that fails, and a reconnect clears it. Watchlist tiles have no activity time, so in that case they stay hidden
  until *Reset hidden titles*, and the title is still on the Trakt watchlist.
