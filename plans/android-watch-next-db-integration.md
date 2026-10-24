# Plan: Android TV "Watch Next" in Continue Watching

Status (2026-10-24): **built on branch `watch-next`, tried on the Skyworth Google TV Stick (Android 14); not committed.**
The questions below are answered (see *Decisions*), the code is `WatchNext.java` plus changes in `Catalogs`, `Trakt`,
`HomeView`, `HomeActivity`, `SettingsActivity`, `Prefs`, `build.sh`, the manifest, the README, the site's privacy answer
and `CLAUDE.md`. What the build taught is at the end.

## Goal

The **Continue Watching** row (today fed by Trakt, see `trakt-integration.md`) also shows what the TV's other apps say you
were watching, taken from Android TV's shared **Watch Next** catalogue, but **only for titles Trakt does not already
list**. With Trakt not connected, the row is the Watch Next entries alone.

Background: upstream PR [simbasjungles/free-my-tv#236](https://github.com/simbasjungles/free-my-tv/pull/236) ("Add
Continue Watching to Android TV home", still open on 2026-10-24) reads the same catalogue for a carousel on the first tab:
`WatchNext.java` (about 300 lines), 299 lines in `HomeView`, and one manifest line. This plan is for our fork's layout
(the Trakt rows and `Catalogs` in the drawer), so it is a small reader of our own, not a merge of that PR.

## What changed since the first draft

- **Rows.** The drawer shows the Trakt rows first, when connected (*Continue Watching* `trakt_continue`, *Shows you watch*
  `trakt_shows`, *Watchlist* `trakt_watchlist`), then the TMDB *Catalogs*. The Trakt rows are **not** in `catalogs=`, so
  there is no `catalogs=` name to add (the old question 12).
- **Opening a title** no longer means "Stremio via `service_map`": `service_map` is gone. A Trakt or TMDB card opens through
  `Launcher` (the title's own app, its Play Store page, or Stremio where *Free My TV > Stremio* says so). A Watch Next card
  is the one thing that bypasses it, with its own `intent_uri`.
- **Settings** live on the TV and `config.properties` only gives first values (`Prefs`): a new setting here follows that
  pattern, and the TMDB and Trakt screens exist (greyed without a key or a Client ID).
- **The PR's permission** is `android.permission.READ_TV_LISTINGS`, not the `READ_EPG_DATA` this plan named first.
- The cards (`Catalogs.Item`) now have `progress`, `sub` ("S1 · E2"), `posterUrl`, `imdb` and TMDB `id`, which this plan
  needs and no longer has to add.

## What Watch Next is

- A shared database in the TV's system, `content://android.media.tv/watch_next_program` (`TvContract.WatchNextPrograms`).
- Apps write to it (Netflix, YouTube, Disney+, Prime Video, Plex and others do); the stock Google TV home screen reads it
  for its "Continue watching" row. Free My TV mode turns that stock launcher off, so the row is gone: this brings it back.
- Columns we would use: `package_name`, `title`, `episode_title`, `season_display_number`, `episode_display_number`,
  `type` (movie, episode, ...), `watch_next_type` (continue = 0, next = 1, new = 2, watchlist = 3),
  `last_playback_position_millis`, `duration_millis`, `last_engagement_time_utc_millis`, `poster_art_uri`,
  `thumbnail_uri`, `poster_art_aspect_ratio`, `intent_uri` (reopens that app at that spot), `release_date`.
- **No IMDb or TMDB id** in an entry: matching against Trakt has to go by title, or by a TMDB lookup (see question 2).
- Reading needs a TV-listings permission. The TV (Android 14) defines both `android.permission.READ_TV_LISTINGS` (what
  PR #236 declares, and the one for Android 8 and newer) and the older `com.android.providers.tv.permission.READ_EPG_DATA`.
  **Not yet known:** whether a normal app is granted either one without the user or ADB doing something (its protection
  level could not be read from `dumpsys` here). It is the first thing to try: declare `READ_TV_LISTINGS`, install, and
  look at `dumpsys package io.freemytv | grep -i listings`.

Verified on the Skyworth 4K Google TV Stick, Android 14 (again on 2026-10-24): the read-only query
`content query --uri content://android.media.tv/watch_next_program` works from the shell and returns **"No result found"**.
None of NetMirror, SmartTube, Stremio, VLC and the others on it publish there, and the official apps that do are not
installed (Netflix and Prime Video are installed but turned off by Free My TV mode). So on this TV the feature shows
nothing until such an app is added (see *Testing*).

## Design

### Sources and merge

1. The Trakt *Continue Watching* row as `Trakt` builds it today (playback, then up next).
2. `WatchNext` items: entries with `watch_next_type` continue (and next, see question 5), newest engagement first, one per
   show (the latest episode of a show wins), minus those whose title is already in the Trakt items.

One row, `trakt_continue`, whose title stays "Continue Watching". It is composed in `Catalogs.rebuild()`, which already puts
Trakt's rows ahead of TMDB's; Watch Next items are read live there (a local query) and **not saved** in the catalogs cache.
With Trakt not connected, `rebuild()` makes the row from Watch Next alone.

### Matching ("not already from Trakt")

Trakt's items carry TMDB and IMDb ids. Normalize titles (lowercase, no punctuation, no year in brackets, no accents) and
compare; for shows the show title is the key, so a Trakt "Breaking Bad up next S1E2" hides a Watch Next "Breaking Bad S1E3".
Trakt wins every tie. Optional exact version (question 2): look each new Watch Next title up on TMDB (`/search/movie` or `/search/tv`
through `Recs.get`, 1 request, remembered) and compare ids with Trakt's, which also gives a portrait poster.

### Opening a card

- A Watch Next card opens with its own `intent_uri`, which resumes inside the app that published it. It does not go
  through `Launcher`, so it needs no TMDB request and ignores the Stremio settings.
- A Trakt card opens through `Launcher`, as it does today.
- `HomeView` has the two paths in its `Z_CATALOG` click: a card that "opens its episodes" goes to `EpisodesActivity`, the
  rest to `openTitle` (`Launcher`). A Watch Next card adds a third, with the `Intent` built from `intent_uri`.

### Look

Same cards as the other rows: poster, title, a progress bar from `last_playback_position_millis / duration_millis` (the
`progress` field), and the "S1 · E2" label for episodes (`sub`). Watch Next art is often wide (16:9) and may be a
`content://` address, while `Catalogs.Item.posterUrl` and the poster cache take http addresses and TMDB paths only (see
question 4).

## Steps

1. **`WatchNext.java`** (new, no libraries): query the provider on a background thread (a `ContentObserver` or a re-query
   when the drawer opens), map each row to a `Catalogs.Item` (new fields: the intent, the publishing package, the art
   address), drop the ones from Free My TV itself and from hidden apps, keep the newest per show, cap at about 12.
2. **Permission.** Declare it in the manifest and check it at run time; when missing, grant it through the app's own ADB
   (`pm grant`, as the mode switch already talks to the TV's ADB) or ask the user (question 7). If it is denied or the query
   throws, the row simply has no Watch Next items: never an error on the home screen.
3. **Merge in `Catalogs.rebuild()`**: combine, normalize, dedupe, sort (question 3), build the row. The home screen
   compares the list by reference, so `rebuild()` keeps making a new list on a change, and a `ContentObserver` (if used)
   calls it.
4. **Images.** Load `poster_art_uri` / `thumbnail_uri` (content or http URI) through the existing poster cache in
   `Catalogs.poster()` (bounded LRU, decoded once), with the TMDB poster as the fallback (question 4). Content addresses
   are new: they are read with the `ContentResolver`.
5. **Opening.** `HomeView.activate()` for a Watch Next item: start `Intent.parseUri(intent_uri)` with a new-task flag;
   catch `ActivityNotFoundException` and show "Couldn't open ..." like the other cards.
6. **Settings.** A `Prefs` toggle with `config.properties` as the first value (a key such as `watch_next`, default on; add to
   `config.example.properties`, `build.sh` and the README's table), and a row for it on the TV (question 12). No `catalogs=`
   name.
7. **Text and docs.** New strings through `Ui.tr`, then `py tools/translate.py`, translate in every catalogue (Portuguese,
   Spanish, German and Russian by hand), README, and the privacy line on `docs/index.html` and `docs/pt/index.html` (the
   "Does it collect any data?" answer: the app now also reads what other apps published, on the TV only; nothing leaves it).
8. **Check.** `./build.sh` ends with `Built FreeMyTV.apk`, `py tools/translate.py --check`, then `tools/check.sh`.

## Requests

None to the network for Watch Next itself: it is a local query. Only with the optional TMDB lookup: 1 request per new
title (a title already seen costs 0), at most a handful per refresh.

## Testing

- The TV has no publishing apps. Options: install one that does (Netflix, YouTube, Plex...), or insert made-up rows with
  `adb shell content insert --uri content://android.media.tv/watch_next_program ...` if the TV allows it from the shell
  (not yet tried), and remove them afterwards.
- Cases: Watch Next only; Trakt only; both with the same title (Trakt wins); a show with several episodes listed; an entry
  whose app was uninstalled; the permission denied; the provider missing (a TV without one).

## Not in this plan

- Writing to Watch Next (publishing our own entries).
- The first-tab carousel of PR #236 (unless question 1 says so).
- LG and Samsung apps (no such catalogue there).

## Risks

- Matching by title can miss a localized or differently spelled title and show a duplicate (question 2 reduces this).
- Some apps publish entries with no progress or with art that is a thumbnail only; the card then has no bar or a cropped image.
- The permission may behave differently across TV brands and Android versions (8 to 14), and may not be grantable to a
  normal app at all (see *What Watch Next is*): then the row stays Trakt-only on those TVs.
- Entries stay until the app removes them: an old, finished title can linger.
- Watch Next apps that scrobble to Trakt too (Stremio does, when connected) can appear in both; the title match is what
  hides the duplicate.

## Decisions

1. **Where:** only inside the drawer's Continue Watching row.
2. **Matching:** a TMDB search per title (kept: a hit for good, a miss for a day) gives the id to compare with Trakt's and a
   portrait poster. Without a TMDB key the entry keeps the app's own data and Trakt's duplicates are caught by name.
3. **Order:** everything mixed, most recent first. Trakt's items now carry the time of their last play (`Item.at`, saved).
4. **Images:** TMDB's poster by title, else the app's own art (cut to the portrait card), else no card.
5. **Types:** continue and next. Watchlist and new-episode entries are left out.
6. **Apps:** every app that publishes except Free My TV; `hide_services` is ignored. Turned-off or removed apps are skipped.
7. **Permission:** the TV's own dialog, asked once on the home screen; turning the setting on again asks again.
8. **Without Trakt:** the row is Watch Next's alone. Without a TMDB key: the app's own data.
9. **Opening:** the entry's own intent. An entry no activity answers is not shown.
10. **Limits:** 12 entries (before Trakt's), nothing older than 60 days, read when the home screen opens, every 15 seconds
    it is up (throttled to 20 s) and whenever an app changes its entries (a `ContentObserver`, throttled to 3 s).
11. **PR #236:** ours is independent.
12. **Switch:** *Home Settings > Apps' Continue Watching*, default on (`watch_next` in `config.properties` is the first value).
13. **One entry per show:** the latest.
14. **Testing:** rows inserted from the shell (see below).

## What the build taught

- **It is a runtime permission.** `READ_TV_LISTINGS` shows under "runtime permissions" and the system dialog ("Allow Free My
  TV to read all TV listings available on your device?") works on this TV. `pm grant` works too.
- **Other apps' rows are only visible to a holder of the permission.** The shell, which has none, sees only its own rows;
  Free My TV with it saw 13 VLC entries the shell could not. Test rows from the shell are published as `com.android.shell`.
- **The shell's `content insert` can't take a `:` in a value** (it reads three parts and gives a usage message otherwise): no
  `https://` art, no `intent:` or `scheme://` addresses. Test intents are written as `#Intent;action=android.intent.action.MAIN;
  component=com.stremio.one/com.stremio.tv.MainActivity;end`, and the timestamps need seconds arithmetic (the TV's shell
  overflows 32 bits on milliseconds) with `000` appended.
- **`Intent.parseUri(uri, 0)`, not `URI_INTENT_SCHEME`:** with that flag anything not starting with `intent:` is read as a
  plain link, which dropped the `#Intent;...` form. Real entries are either plain links (VLC's `vlclauncher://...`) or full
  intents; flags 0 takes both.
- **Resolve like `startActivity` does** (`MATCH_DEFAULT_ONLY`): a launcher intent without the DEFAULT category resolves
  without it and then fails to start.
- **Art addresses are `content:` or `android.resource:`** (VLC's), not only http: `Catalogs.copy()` reads them through the
  `ContentResolver`, and a picture that is wider than tall is cut to the portrait card (the card stretches what it gets).
- **Watch Next has to be read from the home screen's own refresh,** not only when the drawer opens: with no TMDB key and no
  Trakt there are no other rows, so the drawer would never open for it.
- **Turning an app off makes Android delete its entries.** Disabling VLC for a test erased its saved entries, and opening
  it did not publish them again; VLC does when something is played.
- The Home Settings list is now 11 rows (12 with a Dollar row for `currency=BRL`, which runs past the bottom of the screen).

## Tested on the TV

Permission dialog (allow), permission revoked (row is Trakt's alone, no dialog), the setting off and on, an entry of a
removed app (left out), a watchlist-type entry (left out), an entry with no picture and no TMDB match (left out), the same
title in Trakt's list (shown once), a turned-off app (its entries go), TMDB posters and progress bars, the app's own art
(VLC), OK on a card (opens the app), and a build with no TMDB key (titles, season and episode, order from the app's own
data). **Not tested:** Trakt not connected (the code path is the same, with no Trakt row to merge into), the `ContentObserver`
while an app is really playing, and TVs other than this one.
