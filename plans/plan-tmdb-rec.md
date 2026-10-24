# Plan: fewer TMDB calls, faster picks, catalog rows

Status: **draft for review, no code changed.** Answer the questions at the bottom and I will start.

## Where we are

| | Requests |
|---|---|
| Before this session (cold start) | about 55 |
| Now (cold start, first 45 s) | 15, and it keeps growing by 1 every 12 s while the cards slide |
| Time to first full screen | slow: every call runs one after another on one thread |

Today, one refresh = 5 list calls (services, popular and recent movies, popular and recent shows) + 1 **details call per title shown** (`/movie/<id>`, `/tv/<id>`).

Why the details call is still there:
1. the **IMDb id** (for the Stremio link),
2. **runtime / seasons** (info line),
3. the **best backdrop** and the **English title fallback**,
4. which **service** the title is on (no longer needed: everything opens Stremio).

## Idea

A list call (`/discover`, `/trending`, `/movie/top_rated`...) already returns title, rating, genre ids, overview, poster and backdrop for about 20 titles in **one request**. The TMDB site works the same way: a row is one list, not 20 lookups. (TMDB has no batch call and no GraphQL; `append_to_response` only helps inside one title.)

So:

1. **Cards and rows use only list data.** No per-title details call.
2. **The IMDb id is looked up when you click** (1 call, then remembered). Cost: about 0.3 s before Stremio opens, once per title.
3. **Parallel requests** (4 at a time) so a cold start takes about a second.
4. **New catalog rows** below the weather widget, one list call per row.

## What changes for you

| Part | Today | After |
|---|---|---|
| Carousel info line | Series · Genre · seasons/runtime · ★ rating · service | Series · Genre · ★ rating · app name |
| Backdrop | best of TMDB's images | TMDB's default backdrop for the title |
| Title language | TV language, English fallback | TV language only |
| Cold start | 15+ requests | about 5 (lists) |
| Click on a card | opens Stremio on the title (link ready) | looks up the IMDb id first (about 0.3 s, once), then opens Stremio on the title |
| Rows below the weather | none | 4 to 8 poster rows (see below) |

Lost: runtime, seasons, the best-backdrop choice, the English title fallback. Genre comes from `genre_ids` plus a genre list that is fetched once and kept for a week (2 calls).

## Steps

### 1. Cards without per-title details (Recs.java)
- Build each pick from the list result: title, backdrop, rating, genre.
- Fetch the genre names once (`/genre/movie/list`, `/genre/tv/list`), cache 7 days.
- Drop the `fetchDetails` call and the `wanted()` window; `setView` goes away.
- On click: `GET /movie/<id>/external_ids` (or `/tv/<id>/external_ids`), build `stremio:///detail/...`, save it with the pick. If it fails, open Stremio's home.
- Bump `CACHE_VERSION`.

### 2. Faster loading
- Run the list calls on a small thread pool (4), then join.
- Keep the 6-hour refresh and the saved list.

### 3. Catalog rows
- New file `Catalogs.java`: one list call per row, saved like the picks (6 h), poster images loaded only when a row is near the screen, small size (w185/w342) so it fits in memory on a 4K stick.
- New section in `HomeView`: rows under the weather widget; Down from the shelf scrolls into them, Back returns to the top; OK opens the title in Stremio (same click path as step 1).
- Setting in `config.properties`: `catalogs=trending,popular_movies,popular_shows,hindi,top_rated` (names below; order = order on screen). Empty = no rows.
- TMDB attribution line at the bottom of the rows (their terms ask for it).
- Draw rows on the one Canvas like the rest, with no allocation in `onDraw`; a new bitmap budget for posters.

### 4. Check
- Count requests with a temporary log line (removed afterwards).
- `./build.sh` ends with `Built FreeMyTV.apk`; install on the TV and click through every row; `tools/check.sh` (needs Python 3 and Node) before any push; new text goes into all translation catalogues.

## Catalog ideas (1 call each)

| Name | TMDB call |
|---|---|
| `trending` | `/trending/all/day` |
| `popular_movies` | `/discover/movie` sorted by popularity |
| `popular_shows` | `/discover/tv` sorted by popularity |
| `top_rated` | `/movie/top_rated` |
| `now_playing` | `/movie/now_playing` |
| `upcoming` | `/movie/upcoming` |
| `hindi` | `/discover/movie` with `with_original_language=hi` |
| `genre:<id>` | `/discover/movie` with `with_genres=<id>` (action, comedy, thriller...) |

Expected total: carousels about 5 + rows about 1 each = **about 10 requests for the whole home screen**, every 6 hours.

## Not in this plan
- **IMDb ratings.** TMDB has none; it needs OMDb (a second key of yours, about 1 call per title) and I could not confirm its terms for a TV app. Later, if you want.
- **LG and Samsung app** (`web/`): Android only for now.
- **NetMirror** deep links: the app has none, so nothing to do.

## Risks
- Poster rows add bitmaps; the TV is a 4K Google TV Stick, so memory must be capped (about 20 small posters alive at once).
- Click-time lookup needs the network at the moment of the click. On failure it opens Stremio's home, as now.
- A title with no IMDb id opens Stremio's home.
- The home screen is drawn by hand; adding a scrolling area is the biggest change, and focus movement (up/down/left/right) must still feel right.

## Questions for you

1. **Info line:** OK to lose runtime and seasons (genre and rating stay)?
2. **Backdrop and title:** OK with TMDB's default backdrop and no English title fallback?
3. **Click delay:** OK with about 0.3 s once per title (IMDb lookup on click), or do you want the IMDb id fetched ahead for the visible cards (costs about 3 calls per slide)?
4. **Rows:** which ones, and in what order? Suggested start: `trending`, `popular_movies`, `popular_shows`, `hindi`, `top_rated`.
5. **Row size:** 20 titles per row, posters (portrait), 5 or 6 visible at once. OK?
6. **Navigation:** Down from the shelf/weather area enters the rows; Back goes to the top. OK?
7. **Region:** keep `tmdb_region=IN` for the lists, or worldwide popularity (as `flatrate_only=false` does now)?
8. **Order of work:** steps 1 and 2 first (ship and test), then rows? Or everything at once?
9. **Attribution:** OK to show TMDB's one-line notice under the rows?
