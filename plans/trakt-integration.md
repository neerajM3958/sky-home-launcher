# Trakt integration: plan

**Status (2026-10-24): Phase 1 is built and `./build.sh` passes; not yet tried on a TV.** Done: `trakt_client_id`
config, `Trakt.java` (device login, token refresh, rows), the *Trakt* row and screen in `SettingsActivity`, the rows
in `Catalogs`/`HomeView` (episode label, progress bar, Stremio link from the IMDb id), 22 strings in all 24 catalogues,
the README and the privacy lines on both site pages. Tried on a Skyworth 4K Google TV Stick (Android 14): login, the
Continue Watching row (a paused movie, with its bar), and opening it in Stremio on the title's page all work. The login
screen also shows a QR code of `https://trakt.tv/activate/<code>` (Trakt keeps the code through sign-in): `Qr.java` is
a small encoder (versions 1 to 3, level M), checked by decoding its output and a TV screenshot with OpenCV; a phone
scan of it signed the TV in. Retested with test data added to the account (then removed): Dark paused at 35% (S1 · E2
with a bar), Breaking Bad's next episode from `up_next` (S1 · E2), a watchlist of a movie and a show, and Stremio
opened `stremio:///detail/series/tt5753856`. Real shapes matched the spec. One catch: `up_next` also returns watchlisted
shows that were never started (`progress.last_watched_at` is null), which the Continue Watching row now skips.

**Phase 2 (2026-10-24) is built and tried on the same TV** with test history on three shows (removed afterwards, token
revoked): the *Shows you watch* row and the episodes screen (`EpisodesActivity`, `EpisodeList` for the sorting,
filtering and "next episode", tested on the JVM). What the real calls taught:
- `/sync/watched/shows` never carries posters (its shows are saved without images, for any `extended`), so each new
  show costs one `/shows/{id}?extended=full` request, and its poster is kept (`poster<id>` in the Trakt preferences).
- `/shows/{id}/seasons?extended=episodes` has no air dates; `extended=episodes,full` does: 73 KB for Breaking Bad, 899 KB
  for The Simpsons (891 episodes, loaded and drawn in under 5 seconds). Watched marks come from
  `/shows/{id}/progress/watched?specials=true`.
- Stremio opens an episode with `stremio:///detail/series/<imdb>/<imdb>:<season>:<episode>` (checked on the TV).
- Back goes from the list or the choices up to the button bar first and leaves from there: on a long show the list can
  open near the end of the order, and the bar would be ~90 page presses away otherwise.
Still to do: the screens in TalkBack (`Spoken` is wired in, not heard on a TV), a show that is in progress with few
episodes, the LG and Samsung apps (out of scope), and committing.

Show the person's Trakt history on the home screen: a **Continue Watching** row and a **Watchlist** row, with the
artwork TMDB already gives us. Android and Google TV only (`app/`). `web/` (LG, Samsung) is out of scope: Stremio
isn't on those TVs.

## Why Trakt and not Stremio's own API

- Stremio's library lives behind an unofficial, undocumented API that needs the person's Stremio password. The
  installed app's data is private to it, so there is no way to read it from another app.
- Trakt has an official API and OAuth device-code login (the TV shows a code, the person types it on their phone), so
  no password on the TV.
- Stremio scrobbles to Trakt when the person connects it in Stremio's settings. Trakt then holds what they watched.
- Caveat: titles Stremio didn't scrobble (Trakt not connected at the time, or below the watched threshold) are missing,
  and the data lags a little.

## What it uses

| Piece | Source |
|---|---|
| What they watch, progress, watchlist | Trakt (per person, behind a login) |
| Posters, backdrops, titles | TMDB (already in `Recs`), found by the TMDB id Trakt gives |
| Opening a title | `service_map` route (as for other rows), or Stremio's deep link (see open questions) |

## Settings (`config.properties`)

- `trakt_client_id=`: the person's own free Trakt API app (app.trakt.tv/settings/apps/api/new). There is no client
  secret: Trakt issues none to new apps (they sign in with PKCE). Keep it in `config.properties` and out of commits
  like `tmdb_key`; it is public-ish, but the access and refresh tokens it yields are secret.
- Redirect URI: the form needs one, the device flow never uses it. `urn:ietf:wg:oauth:2.0:oob` or `http://localhost`.
- New names for `catalogs=`: `trakt_continue`, `trakt_watchlist`. Without Trakt set up they draw nothing.
- Add the keys, with a comment each, to `config.example.properties`. `build.sh` generates `Config.java` from it.

## Steps

1. **Config.** `trakt_client_id`, and the two catalog names. Check how `build.sh` turns keys into `Config` and add them the
   same way as `tmdb_key`.
2. **`Trakt.java`** (new, no libraries, `HttpURLConnection` like `Recs`):
   - Device flow: `POST /oauth/device/code`, then poll `POST /oauth/device/token` at the interval it returns until it
     succeeds, expires or is denied.
   - Tokens in `Prefs` (access, refresh, expiry). Refresh through `POST /oauth/token` before they expire and on a 401.
     Never log a token or a code's secret.
   - Verified on 2026-10-24 with a secretless app: `/oauth/device/code` and `/oauth/device/token` both work with
     only `client_id` (the code step returns `user_code` of 8 characters, `verification_url`
     `https://auth.trakt.tv/activate`, `expires_in` 600, `interval` 6; the token step answered 200 after approval).
     Poll every `interval` seconds, stop at `expires_in`. Refresh tokens are single-use: every refresh returns a new
     pair, store both. `invalid_grant` on refresh means connect again (show it in Settings, never break the home screen).
     Access tokens last 7 days. Refresh with `client_id` only; not yet tested.
   - Headers on every call: `trakt-api-version: 2`, `trakt-api-key: <client_id>`, `Authorization: Bearer <token>`.
   - Fetches (see *API calls and rows* below): `/sync/playback`, `/sync/progress/up_next`,
     `/sync/watchlist/{type}/{sort_by}/{sort_how}`, and `/sync/last_activities` to skip all of them when nothing changed.
   - Everything off the UI thread, cached on disk with a short life, and silent on failure: the home screen must
     never break or stall because Trakt is down or the token is gone. Reuse `Recs`' cache and `prune` ideas.
3. **Settings screen** (`SettingsActivity`): a new **Trakt** row in the main list, after *Remote Buttons* (the
   labels are at `SettingsActivity.java` ~line 223 and 390; the list is drawn by hand, so add the row to both and to
   the focus and key handling). Text through `Ui.tr` / `Ui.trf`. See *Login screen* below.
4. **Rows.** Map each Trakt item to a `Catalogs.Item` (title, `ids.tmdb`, kind, poster). Use Trakt's `images.poster`
   (cached on disk, no extra call), with TMDB's poster by `ids.tmdb` only as a fallback. Add the two catalogs next to the
   TMDB ones in `HomeView`'s poster rows. Keep it at 60 fps: no allocations in `onDraw`, no work on the UI thread.
5. **Opening a card.** Through `service_map` like the other rows (needs the `*` rule, for example
   `*=com.stremio.one`), or straight to the title in Stremio. Decide after testing the deep link on a real TV.
6. **Picks (optional, later).** Leave titles already in the person's Trakt history out of the picks.
7. **Text and docs.**
   - `python3 tools/translate.py`, then translate the new strings in every catalogue (Portuguese, Spanish, German and
     Russian by hand), keeping placeholders.
   - README, and both site pages `docs/index.html` and `docs/pt/index.html` if the feature is listed there.
8. **Check.** `./build.sh` ends with `Built FreeMyTV.apk`, then `tools/check.sh`.

## API calls and rows

Source: Trakt's OpenAPI spec, `https://developer.trakt.tv/openapi.json` (read 2026-10-24; the reference page itself is
a JS app a fetch can't read). Checked against a real token on 2026-10-24 (a test account with one paused movie and
nothing else):

- Verified: `/sync/last_activities` (200; fields as in the spec), `/sync/playback?extended=full&limit=20` (a movie item:
  `type`, `progress` in percent, `paused_at`, `movie.ids.{imdb,tmdb,trakt}`, `movie.title`, and
  `movie.images.poster` as a **list** of scheme-less paths ending `.webp`), `/sync/progress/up_next`,
  `/sync/watched/shows`, `/sync/history` and `/sync/watchlist/movies,shows/added/desc` (all 200, `[]` for that empty
  account, so the path with `movies,shows` is accepted).
- Poster download: `https://media.trakt.tv/images/...webp` answers 200 with no referer (600x900, 37 KB); the app decodes it at
  half size.
- Token refresh with only `client_id` (`grant_type`, `refresh_token`): 200, a new pair; `POST /oauth/revoke` also works.
- Not seen with real data: an episode's playback item, an `up_next` item and a watchlist item (the parser follows the spec
  and ignores what is missing). Retest these with an account that has history.
- Default page size is about 10 when `limit` is left out: every call sends a `limit`.

| Row | Call | Gives |
|---|---|---|
| Continue Watching, part-way | `GET /sync/playback?extended=full` | `type` (`movie` or `episode`), `progress` (%), `paused_at`, `movie`, or `episode` plus parent `show`; all with `ids.tmdb` and `images.poster` |
| Continue Watching, up next | `GET /sync/progress/up_next?extended=full&limit=N` | per show: `show` (with `images`), `progress.next_episode`, `progress.last_watched_at`; replaces `/sync/watched/shows` plus one `/shows/{id}/progress/watched` per show |
| Watchlist | `GET /sync/watchlist/{type}/{sort_by}/{sort_how}?extended=full&limit=N` | `rank`, `listed_at`, `type`, then the movie or show |
| Skip refetching | `GET /sync/last_activities` | timestamps per list; if unchanged since the last fetch, make no other call |

- `/sync/progress/up_next` is documented in the spec (OAuth, paginated, extended info). `/sync/progress/up_next_nitro` is
  Trakt's app endpoint with filters; ignore it.
- `/shows/{id}/progress/watched` stays as a fallback only if `up_next` misbehaves; its `next_episode` is only for aired
  episodes, and it returns every season and episode, which is large.
- Posters come from Trakt: `extended=full` puts `images.poster` on movies and shows. Paths have no scheme (prepend
  `https://`), are WebP on Trakt's CDN, and Trakt asks apps to cache them and not to hotlink. `Catalogs` caches posters on
  disk already. Fall back to TMDB (`ids.tmdb`) only for a title with no image.
- Which endpoints really return `images` with `extended=full` is not stated: check in the *verify* task.

Rows: with Trakt connected, the drawer gets **2 more rows** (5 TMDB rows by default, so 7): **Continue Watching**
(playback first, then up next, de-duplicated by show, about 12 items) and **Watchlist** (up to 20). No Trakt rows when
not connected. They go through `Catalogs`' `Row`/`Item`, so caching, lazy poster loading and the open-in-Stremio path are
shared.

Calls per refresh (at most every 6 hours, when the drawer opens, like `Catalogs`):
- nothing changed: **1** (`last_activities`);
- something changed: `playback` + `up_next` + `watchlist` = **3**, and **0** for artwork;
- plus **1** token refresh about every 7 days.

Trakt's limit is 500 GET calls per 5 minutes for a signed-in user (`429` with `Retry-After`, though some 429s carry no
headers): at most 3 calls per refresh is far below it. Back off on 429 and never retry in a tight loop.

Still to verify with an account that has history: `images.poster` on an episode's show and on `up_next` and watchlist
items, the `up_next` ordering, and the watchlist's `type` field. Keep tokens out of any file or log; run helper scripts
with `py -I`.

The check against `last_activities` runs when the drawer opens, at most every 30 minutes (the file says 6 hours above:
the one-request check made a shorter interval cheap), and the rows are fetched only when the `stamp` it builds has changed.

## Login screen

Free My TV → **Trakt**. The row's value is "Not connected", "Connected" or "Reconnect".

1. OK on the row starts the login: `/oauth/device/code`.
2. The screen shows the 8-character `user_code` in large type (room for all 8), `trakt.tv/activate` under it
   (`verification_url` from the reply) and a countdown from `expires_in`.
3. The person opens that address on a phone or computer, signs in to Trakt if needed, enters the code and approves.
4. The TV polls `/oauth/device/token` every `interval` seconds in the background. On 200, save the tokens in `Prefs`,
   show "Connected" (with the Trakt user name if `/users/settings` is cheap to add) and go back to the list.
5. The Continue Watching and Watchlist rows show up on the home screen on its next refresh.

| State | What the row and screen do |
|---|---|
| Connected | OK offers *Disconnect*: clears the saved tokens only, nothing on the Trakt account |
| Code expired (10 min) | Says so; OK starts a new code |
| Denied, or no network | Short message and *Try again* |
| Refresh fails with `invalid_grant` | Row shows "Reconnect"; the home screen keeps working, just without the Trakt rows |
| No `trakt_client_id` in `config.properties` | Row says to see the README, like the TMDB row does without a key |

The person never types a password on the TV. Back or leaving the screen stops the polling. Each person puts their
own Client ID in `config.properties`, as with the TMDB key; a released build can't ship one unless the project later
makes a shared Trakt app.

## Phase 2: "Shows I usually watch" and the episodes screen

Built after the two rows above work. A third Trakt row and a new screen drawn by hand in `HomeView`.

### The row

- Catalog name `trakt_shows` (title "Shows you watch"), next to the other Trakt rows.
- Source: `GET /sync/watched/shows` (sign-in required; plays and `last_watched_at` per show; not run yet, so confirm
  the fields in the verify task). Order by `last_watched_at`, **most recently watched first**, up to about 20 shows.
  Ties (same time) fall back to total plays. The list holds shows you actually watch, not every show you ever
  tried: drop shows watched once, more than a year ago (so "usually" stays meaningful). Both are rules we can change.
- 1 call on the same 6-hour refresh. Posters from Trakt, like the other rows.
- OK on a card opens the episodes screen (not Stremio).

### The episodes screen

Opens on a show; Back returns to the row with focus where it was.

- **Data:** `GET /shows/{id}/seasons?extended=full,episodes`: season, number, title, `first_aired` for each episode,
  one call per show opened, cached on disk (short life for a show still airing). Watched marks from
  `GET /shows/{id}/progress/watched` (one more call, same cache). Check both against the spec in the verify task.
- **Each line:** `S3 · E10`, title, air date (formatted with `Ui.locale()`), a mark for watched, and a "new" mark for an
  episode that aired in the last 7 days. Episodes not aired yet are dimmed with their date.
- **Header:** show title, poster, "12 of 84 episodes", and which filters are on.

### Sorting (a button, saved in `Prefs` and kept for every show)

- Number, newest first (default): season, then episode, both descending, so S3E10 comes before S3E1 and S2E10.
- Number, oldest first.
- Date, newest first; Date, oldest first.
- Applied locally; no extra API calls.

### Filtering (a row of buttons above the list)

| Filter | Choices |
|---|---|
| Season | All, then one per season (and Specials, if the show has any) |
| Date | Any time, Last 7 days, Last 30 days, This year, a year picked from the years the show aired in, Not aired yet |
| Watched | All, Unwatched, Watched |

- Filters combine (for example Season 2, Unwatched). Each button shows its current value, and a small "Clear filters"
  button appears when any filter is on.
- Sort is remembered across shows; filters start at All every time a show opens.
- An empty result says "No episodes match" with a Clear filters button, never a blank screen.

### Ease of navigation (remote only)

- **Layout:** filter and sort buttons in one row at the top, the list below. Down from the row goes to the list, Up from
  the first line goes back to the row, so nothing is a trap.
- **Opens on the right line:** the first unwatched episode in the current order (or the top one if all are watched),
  so Continue is one press.
- **In the row:** Left and Right move between buttons; OK opens a small list of that button's choices (Up and Down,
  OK to pick, Back to close). Choosing applies at once.
- **In the list:** Up and Down move by one line; holding repeats; a page up and page down key (or Channel Up and Down)
  moves by a screenful; Left and Right jump a season back or forward while the list is sorted by number.
- **Where you are:** a position count ("12 of 84"), a scroll bar, and the season label stays pinned at the top.
- **OK on an episode:** opens it in Stremio when the link works (see open questions); Back always returns one step.
- **Looks:** large focus highlight like the home screen's, text sized for a TV across a room, and readable at the
  1280×720 render some TVs use.
- **Speed:** draw the list on the one Canvas like the rest of `HomeView`; no allocations in `onDraw`, no network or file
  work on the UI thread, show a "Loading episodes…" state, and reuse the cached episodes at once while refreshing.
- **Failure:** no network or Trakt down shows the cached list if there is one, otherwise a short message and *Try again*.

### Opening an episode

Stremio's episode id is `<imdb>:<season>:<episode>`, and a link would be `stremio:///detail/series/<imdb>/<imdb>:S:E`
(from memory, not tested). The show's `ids.imdb` comes from Trakt. Test the link on a TV before relying on it; if it
fails, open the show's page, or use `service_map`'s `*` app.

### Open questions for this phase

- Which shows count as "usually watched": all you have watched, or only those watched more than once or in the last
  year (the default above)? The order is settled: most recently watched first.
- Does `/shows/{id}/seasons?extended=full,episodes` carry `first_aired` for every episode, and how large is the answer for
  a long show (hundreds of episodes)? Measure it.
- Show absolute numbering (`number_abs`, for anime) as an extra sort when Trakt has it?
- Text: every new label goes through `Ui.tr` and `tools/translate.py`; count words ("84 episodes") with `Ui.plural`.

## Rules to keep (from CLAUDE.md)

- Never commit `config.properties`, a keystore or any key; the Trakt secret and tokens never go in the log or the chat.
- Java 8, minSdk 26, targetSdk 33, no Gradle, no libraries: only the Android SDK.
- Comments in English, short, only where the why isn't obvious.
- Don't touch the TV's picture, sound, network or account settings. Keep *Original* and the watchdog working.

## Open questions

- **Row order:** should Continue Watching go first, above the TMDB rows?
- **Deep link:** does `stremio:///detail/<type>/<id>` open the title on Android TV? Test it on a TV (the check is
  `adb shell am start -a android.intent.action.VIEW -d "stremio:///detail/movie/tt0816692"`) before relying on it.
- **Trakt's terms:** a personal-use app with each person's own key is fine; confirm the current API terms before
  publishing it in a release.
- **Rate limits:** Trakt limits calls per window. Cache the results, and fetch only when the home screen starts or
  every few hours.

## Not in scope

- Writing anything back to Trakt or Stremio (marking watched, progress).
- LG and Samsung (`web/`).
- Reading the Stremio library directly.
