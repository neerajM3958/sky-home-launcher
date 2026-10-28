# Plan: Spotlight picks carousel (full-screen, stock-TV style)

Status: **approved, implementation starting.** Short-of-memory default and order of work (Questions 1-2 below) are
left to implementation judgement; everything else, including how this works across looks and where the sharp image
comes from, was settled in discussion.

## Where we are

Settings > TMDB > "Picks carousel" is a boolean (`Prefs.picks()`): off, or today's "Cards" look — a light/frosted
background, "Explore / Movies / Shows" tabs, and a row of 3 cards (1 wide + 2 tall) that rotate through the tab's
picks (`HomeView.drawLightTop`, `updateCards`, `drawCarousel`/`drawCardSlot`, cards built from `recs.now(TAB_KIND[tab])`).

Stock Google TV's home screen instead shows one full-screen backdrop (the focused title), with a "Top picks for
you" poster row near the bottom that drives it — move focus in the row, the background and title change instantly.
We're adding that as a second style, named **Spotlight**, alongside the renamed **Cards** (today's look) and **Off**.

Sky Home Launcher already has two looks (`Look.java`, `GlassLook.java`, `MaterialLook.java`, *Home Settings > Look*):
Glass (today's frosted photo, the default) and Material (flat tonal surfaces, faint drifting shapes, no photo on the
home background today — `MaterialLook.background()`). That "no photo" is just how Material's *Cards* background
happens to be implemented today, not a rule Spotlight has to inherit: **Spotlight is a shared feature, not a
Glass-only one.** Both looks show the full-bleed photo; each one gets its own treatment of it (blur/sharpness, shadow
depth, the corner gradient, poster styling, focus highlight), the same way they already diverge on `cardCaption`,
`focusRing` and `background` for Cards mode today. Cards' and Off's existing per-look backgrounds are untouched by
this plan — this only adds a new per-look treatment for Spotlight specifically.

## Idea

Most of the plumbing Spotlight needs already exists and is reused as-is:

- `cards`, `cardStart`, `cardIndex`, `rotateCards(dir)`, `showCards()` already hold "the pick list" and already move
  the background to the focused pick's photo on every left/right (`showCards()` → `recs.backdrop(cardAt(0))` →
  `showBackdrop`). Spotlight doesn't need new state for "which pick is focused" or "background follows focus" — Cards
  mode already does both, just at 3-cards-wide instead of 1-background-wide.
- `Backdrop.image` (`W`×`H` = 1920×1080) is **not** built by today's Cards design (`forLight == true` builds only
  `wide`/`tall` crops and small reduced arrays) — see "Where the sharp image comes from" under Step 4 for the
  correction and the decision (add it to the `forLight` branch, GPU-backed, no new TMDB call or image size, same
  field that already exists but is null today).
- `Z_CARDS` is the real, live focus zone for the pick row (`moveLight`'s `case Z_CARDS`); **`Z_HERO`, `Hero`, and
  `browse()`** are dead code from an older single-hero design — the comment at `HomeView.java:4333` says so directly
  ("move()'s older zones (the hero, the corner capsule) aren't drawn any more"), and `hero.titleLayout` is built
  (`makeHero`, line ~1012) but never drawn. **Don't resurrect them for Spotlight** — build the new draw path off
  `Z_CARDS`/`cards`/`cardStart`, not `Z_HERO`.

What's genuinely new: the drawing (full-bleed image + gradient instead of frosted background + 3 cards), new layout
constants, a poster row instead of 2 side cards, hiding the tabs, the 3-way setting, and **one new `Look` hook** for
the photo treatment (below) — everything else Spotlight draws (poster captions, focus ring, text colour) reuses
`cardCaption`/`focusRing`/`color()`, which already differ by look today.

## Steps

### 1. Prefs: 3-way setting (`Prefs.java`, ~204-215)

Replace the boolean `picks()`/`setPicks()` with a string-backed 3-way under the same `"picks"` key: `"off"`,
`"cards"`, `"spotlight"`.

```java
static final String PICKS_OFF = "off", PICKS_CARDS = "cards", PICKS_SPOTLIGHT = "spotlight";

static String picksStyle() {
    if (app == null) return PICKS_OFF;
    SharedPreferences s = sp();
    if (!s.contains("picks")) return shortOfMemory() ? PICKS_OFF : PICKS_CARDS;
    try {
        return s.getString("picks", PICKS_CARDS);
    } catch (ClassCastException e) {          // migrating an old boolean value
        boolean was = s.getBoolean("picks", true);
        String v = was ? PICKS_CARDS : PICKS_OFF;
        put("picks", v);
        return v;
    }
}
static void setPicksStyle(String v) { put("picks", v); }

static boolean picksOn() { return !picksStyle().equals(PICKS_OFF); }          // cards or spotlight
static boolean spotlightOn() { return picksStyle().equals(PICKS_SPOTLIGHT); }
```

`put(String, String)` already exists (line 86). `picksOffForMemory()` (line 215) becomes
`picksStyleOffForMemory()` with the same "person hasn't chosen" check, used by Settings to show the "Off by default:
this TV is short of memory" line.

Recommendation: short-of-memory TVs keep defaulting to **off**, same as today — not to Spotlight. Spotlight's image
is the same `Backdrop.image` bitmap Cards already loads per pick (no bigger), so there's no extra memory cost to
gate on; the only reason to prefer Cards as a short-of-memory *default* is unfamiliarity/novelty, not cost, so I'd
leave the existing off/not-off default untouched and let the person opt into either style once they turn picks on.
(See Question 1.)

Every other call site that reads `Prefs.picks()` as a boolean (`HomeView.picksOn()`,
`SettingsActivity.tmdbValues()`/`disabledRow`, anywhere else `grep -rn "Prefs.picks()"` finds) switches to
`Prefs.picksOn()` (same meaning: picks are showing, in either style) or `Prefs.spotlightOn()` where the style
specifically matters.

### 2. Settings (`SettingsActivity.java`)

- `tmdbLabels()`/`tmdbValues()` (~1153-1163): "Picks carousel" row's value becomes a 3-way label instead of
  `onOff(...)`: `Off` / `Cards` / `Spotlight` (new `Ui.tr` strings), picked with the same pattern `torrentioSize()`
  already uses for its OK/Left/Right cycle (`Torrentio.SIZES`, ~1805).
- `adjust()`'s `TMDB` branch (~1821): replace `Prefs.setPicks(!Prefs.picks())` with a 3-way cycle:
  ```java
  String[] styles = {Prefs.PICKS_OFF, Prefs.PICKS_CARDS, Prefs.PICKS_SPOTLIGHT};
  int at = java.util.Arrays.asList(styles).indexOf(Prefs.picksStyle());
  Prefs.setPicksStyle(styles[(Math.max(0, at) + dir + styles.length) % styles.length]);
  ```
  (`dir` is already passed into `adjust`; OK always calls `adjust(1)`, so OK alone still cycles forward exactly like
  the old toggle did.)
- `picksOffForMemory()`'s caller (the TMDB screen's status/description line, ~585) switches to the renamed
  `Prefs.picksStyleOffForMemory()`.

### 3. HomeView: hide tabs and treat Spotlight's row as untabbed (`HomeView.java`)

- `picksOn()` (~4163) becomes `Recs.hasKey(...) && Prefs.picksOn()`.
- `updateCards()` (~3782): when `Prefs.spotlightOn()`, always build from `recs.now(TAB_KIND[0])` (the Explore pool)
  regardless of `tab`, instead of `recs.now(TAB_KIND[tab])` — tabs don't exist in Spotlight, and the user confirmed
  the row should pull from the same pool Explore uses today.
- `tabs()`/`setLightButtons` (~619-640) and `moveLight`'s `Z_TOP`/`Z_SHELF`/`Z_CARDS` cases (~4330-4380): everywhere
  `picksOn()` currently gates "are there tabs to focus" needs a second check, since Spotlight has cards but no tabs.
  Concretely: `topIndex = picksOn() ? tab : 3` (line 4338, and the twin at 625) becomes
  `topIndex = picksOn() && !Prefs.spotlightOn() ? tab : 3` — Up from the Spotlight row goes straight to the corner
  buttons, skipping tab slots that don't exist. `drawLightTop`'s tab-drawing block (~4120-4148) is skipped entirely
  when `Prefs.spotlightOn()`.
- `rotateCards`/`showCards`/`cardAt` need no change — they already do "move focus through `cards`, update
  `cardStart`, refresh the background" regardless of style.

### 4. `Look`: one new hook for Spotlight's photo (`Look.java`, `GlassLook.java`, `MaterialLook.java`)

```java
/** Spotlight's full-screen photo: the look draws the picture (sharp, blurred, or a blend of its own choosing) plus
 * its own corner treatment so the weather (top-left) and the icon buttons (top-right) stay legible over it, and
 * its own shadow/scrim depth. `b` is the focused pick's ready image. */
abstract void spotlightPhoto(Canvas c, RectF screen, Backdrop b, float night);
```

Made `abstract`, not defaulted, so neither look silently falls through to "no photo" the way `background()` does
today — Spotlight only ships once both looks have made a real choice.

**Where the sharp image comes from:** today's Cards design (`Recs.setLight(true)`, hardcoded on at `HomeView.java:509`
and never turned off) builds no full-screen bitmap at all per pick — only small card crops (`wide`/`tall`) and tiny
reduced pixel arrays for the frosted background, which is why its cache can hold 10+ picks at once
(`Math.max(10, PICK_COUNT + 3)` in `Recs.loadImage`). `Backdrop.make()` has a second, currently dead branch
(`forLight == false`, never called) that builds one full 1920×1080 `image` bitmap with a scrim already baked in —
leftover infrastructure from the same old single-hero design `Z_HERO` belonged to, and it only keeps 4 cached at a
time since each item is a real bitmap.

Decision: **add a plain sharp `image` bitmap (no baked-in scrim) to the existing `forLight == true` branch**, keeping
the 10+ item cache depth as-is, so every pick in the poster row has a ready Spotlight background the moment it's
focused — no shallower readiness than Cards has today. The scrim/gradient stays *out* of the bitmap and is drawn live
by each look's `spotlightPhoto()` (step 4), not baked in, so Glass and Material can each style their own corner
treatment without needing a second baked variant. Concretely, in `Backdrop.make()`'s `forLight` branch: build
`wide`/`tall`/the reduced arrays exactly as today, and only at the end, instead of `out.recycle()`, do
`Bitmap image = gpu(out);` and pass it into the constructor instead of `null`. `gpu()` already copies to
`Bitmap.Config.HARDWARE` (GPU-backed, not held in process heap — see the class doc comment on `Backdrop`), so this is
one more GPU texture per cached pick (≈10 at once), not 10 more 8 MB heap allocations; still worth confirming on a
low-RAM TV (TCL's 1 GB models) during Step 7's install check, since those are `Ui.LEGACY` and HARDWARE-bitmap support
varies by GPU driver.

- `GlassLook`: close to the original plan — `b.image` drawn full-bleed, a gradient scrim over it (darkest at the
  top-left corner, fading toward the right and bottom; weather's corner needs the darkest point, the icon buttons'
  corner can be lighter since those already sit on their own glass pill), built once per focused pick and cached
  like the existing shadow/blur paints (not rebuilt in `onDraw`).
- `MaterialLook`: its own treatment, not a copy of Glass's — e.g. the photo blended with a tonal scrim using its
  existing `scrim`/`scrimLight` gradient machinery (already used in `cardCaption`, ~line 356-380) instead of a flat
  black fade, so the corner darkening reads as "Material's tonal surface" rather than "Glass's gradient wearing
  Material colours." Exact blur radius / scrim strength / shadow depth are tuning, done on-TV against both looks
  during implementation, not fixed numbers in this plan.
- Both looks keep using their existing `cardCaption`/`focusRing`/`color()` for the poster row's captions, focus ring
  and the title/heading text above the photo — those already differ by look today and need no new hook.

### 5. HomeView: Spotlight's own drawing

**As built** (superseding the original proposal below): the shelf's "half visible, Down reveals it" behavior is a
real clip-and-reveal animation, not a static nudge. A new `Spring spotlightShelf` (next to `drawer`, ~line 242) has
its target recomputed every frame in `doFrame` from the live zone (`Prefs.spotlightOn() && (zone == Z_SHELF ||
zone == Z_DRAWER) ? 1 : 0`, read every frame rather than written at each of the many `zone = ...` call sites, which
is far less error-prone), stepped alongside the other springs, folded into `tilesMoving`/the `platter` invalidate
condition/the `busy` aggregate so both the glass bar and the tiles layers redraw while it moves and the frame loop
doesn't go idle mid-animation. `layoutShelf()` uses its `.value` to interpolate `top` between the resting position
(matching `L_PLATTER_TOP + L_TILE_H`, Cards mode's unchanged position) and a hidden position (`h - SPOTLIGHT_SHELF_PEEK*k
- pad`, `SPOTLIGHT_SHELF_PEEK = 34`) where only a small sliver of the tile row's own top (not the glass bar, which
extends further down, intentionally off-screen) pokes above the fold; `layoutShelf()` is called on every frame the
spring is moving, same as any other per-frame layout change in this file. Cards/Off are untouched (the interpolation
is `if (Prefs.spotlightOn())`-gated).

A second, more fundamental fix was needed and is **not optional**: `onMeasure` (~line 684) hard-codes `heroLayer`'s
height to a fixed `790 * kk` — sized for Cards mode's weather/tabs/cards, which all fit above that line. Spotlight's
poster row and heading sit at y 812-962, below that bound, so the entire row (and anything else drawn there) was
silently clipped by the View's own measured bounds before ever reaching the canvas — confirmed by drawing a plain
solid test rectangle at the poster row's coordinates and finding it invisible too, ruling out a bitmap/drawing bug.
Fix: `measure(heroLayer, width, Prefs.spotlightOn() ? height : Math.round(790 * kk));` — Spotlight gets the full
screen height, Cards mode keeps its original 790px. Without this, no amount of shelf-position tuning would have
mattered; the row was being clipped before the shelf ever drew over it.

Original proposal (kept for context, not what shipped): new layout constants near the existing ones (~64-90):
`SPOTLIGHT_TOP = 0`, `SPOTLIGHT_TITLE_Y = 640`, `SPOTLIGHT_ROW_TOP = 820`, `SPOTLIGHT_ROW_H = 160`, `SPOTLIGHT_SHELF_Y`
pushed down so only ~30-40px shows. Layout stays shared between looks (per `Look.java`'s own doc comment: "the
screens keep their layout... everything that makes the glass one glass and the Material one Material is behind this
class") — only the photo's treatment (step 4) and the reused per-look hooks (`cardCaption`/`focusRing`) differ.
- New `drawSpotlight(Canvas c)`, called from `drawLightTop` (~4112) in place of the tab+card block when
  `Prefs.spotlightOn()`:
  - `look.spotlightPhoto(c, screen, b, dark())` draws the full-bleed background, replacing the frosted
    `lightImage`/`nightImage` background `drawLightImage` uses in Cards mode.
  - Title + genre/rating, same two fields `drawCardSlot`'s caption already draws (`p.title`, `p.meta`), using
    `look.color(TITLE, ...)`/`look.color(META, ...)` for ink so the text follows the current look, moved to the
    dedicated title position instead of inside a card.
  - A new heading (`Ui.tr("Top picks for you")` — new string, needs adding to `en.json` and translating) above the
    poster row, same text colour roles.
  - The poster row itself: horizontally laid out `cardAt(i)` thumbnails for as many as fit (more than the 3 Cards
    mode shows — reuse `b.tall`/`b.wide` crops already built per pick, just smaller on screen), captions via
    `look.cardCaption`, focus ring on the focused one via `look.focusRing`, left/right calling `rotateCards` exactly
    as `Z_CARDS` does today.
- `drawLightTop`'s existing Cards-mode block (tabs + `drawCarousel`/`drawCardSlot`) stays untouched behind an `else`
  — Cards mode is not being rewritten, only gated behind `!Prefs.spotlightOn()`.
- Update the class doc comment at the top of `HomeView.java` (~48-62): it currently says "the 'For You' title and
  the tabs" — that string isn't actually drawn today (no `"For You"` key exists in `en.json`); rewrite this comment
  to describe Cards vs. Spotlight accurately instead of carrying the stale description forward.

### 6. Strings (`app/assets/i18n/en.json` + `tools/translate.py`)

New keys: `"Cards"`, `"Spotlight"` (Settings row values), `"Top picks for you"` (Spotlight heading). Run
`python3 tools/translate.py` to regenerate `en.json` and list what's missing elsewhere; fill every catalogue (or
`--fill` with `ANTHROPIC_API_KEY`) before `tools/check.sh` (which runs `translate.py --check`).

### 7. Build and check

- `./build.sh` ends with `Built SkyHomeLauncher.apk` after every change.
- Install on the TV at 192.168.0.4, cycle Off → Cards → Spotlight → Off from Settings > TMDB, **under both Glass and
  Material** (*Home Settings > Look*): check weather/top buttons stay legible over the photo in each look's own
  treatment, Up/Down/Left/Right/Back/OK feel right in the poster row and into the shelf, the shelf's "half visible"
  peek looks intentional (not clipped), auto-rotation still only runs while idle (reuse the existing
  `pick_rotate_seconds` idle check in the `rotateCards(1)` auto-tick around line 400, pointed at the Spotlight row
  the same way it already points at Cards).
- `tools/check.sh` (Google TV build + `translate.py --check`) before pushing.

### 8. Polish pass (round-2 feedback, after the first on-TV look)

The first install surfaced a real layout collision (poster row at y 812-962 hidden behind the shelf, fixed in Step 5
above) and, once that was visible, six more notes from comparing side-by-side against the stock reference screenshot
(`Top picks for you` on a real Google TV):

1. **Bigger posters, moved up**, not shrunk. The interim fix shrank `SPOTLIGHT_POSTER_H` from 150 to 104 to make room
   for the shelf below — wrong trade. Free up room by moving the row (and the title/heading above it) up the screen
   instead, then re-solve the shelf's position against the real space that creates, not by shrinking posters.
2. **Posters need the title baked into the art**, like stock's. Today's row reuses `Backdrop.wide`/`tall` — crops of
   the TMDB **backdrop**, which is deliberately chosen with *no* text on it (see `Recs.fetchBackdrop`'s doc comment,
   "no text over it"), which is why Spotlight currently draws its own caption on top. Stock instead shows TMDB's
   **poster** art (`poster_path`), which normally carries the official title treatment already. This needs real new
   plumbing, not a style tweak:
   - Add `poster` (the TMDB `poster_path` string) to `Recs.Pick`, parsed alongside `backdrop` (`Recs.java` ~line 564)
     — it's already present in the same list response the app already fetches (`/discover`, `/trending`...), so this
     is free: no new TMDB call.
   - Add a small poster-loading cache to `Recs.java`, modeled directly on the existing `Catalogs.poster(Item, width)`
     (`Catalogs.java` ~line 529-578: downloads `IMAGES + poster_path` at w342, decodes at roughly the draw size,
     caches by path, notifies listeners when ready, all off the UI thread). `Recs.Pick` is a different type from
     `Catalogs.Item`, so this is a parallel small method (`Recs.poster(Pick, width)`), not a shared call — matching
     the pattern, not refactoring the two systems together.
   - `drawSpotlight`'s poster-row loop switches from `pb.wide`/`pb.tall` to this new poster bitmap; when it's not
     ready yet, fall back to the placeholder fill it already has (same pattern `Catalogs`' own poster rows use while
     loading).
3. **Drop the title-text caption on posters** (falls out of #2 — once the art carries the name, `look.cardCaption`'s
   overlay on the poster row is redundant and comes off; the big title/genre/rating above the row, driven by the
   focused poster, stays).
4. **Weather still isn't showing.** Not yet root-caused as Spotlight-specific — `drawTopWeather(c)` runs before
   `drawSpotlight(c)` in `drawLightTop` either way, and nothing in Spotlight's own code touches it — current
   suspicion is the repeated force-stops/mode-switches during testing broke `Locator`'s location fetch for this
   session, not a Spotlight regression, but this needs to be confirmed, not assumed, as part of this pass (check
   `Prefs.weather()`, `data.weather`/`data.weatherAt`, and whether `Locator` actually ran and found a city on a clean
   launch).
5. **Shelf peek should read as 50%, not a sliver.** `SPOTLIGHT_SHELF_PEEK = 34` (tile height ≈126) reads as a thin
   strip; raise it to roughly half the tile height (≈63) so it's clearly recognizable as "the shelf, not fully open"
   rather than a stray edge.
6. **A real gap between the poster row and the shelf**, comparable to the spacing the catalog poster rows already use
   between each other (`CAT_GAP = 36`, horizontal, between posters in one row — not `CAT_PITCH = 440`, which is a
   full row-to-row pitch including a row title and doesn't apply here). Use a comparable vertical breathing gap
   between the poster row's bottom and the shelf's revealed top, not the current tight fit.

Numbers (poster size/position, shelf peek/gap) are tuned on-TV during implementation, the same way Step 5's weren't
fixed in advance — this section states the direction and the reference points (the stock screenshot, `CAT_GAP`), not
final pixel values.

**As built:**

- Final constants: `SPOTLIGHT_TITLE_Y = 526`, `SPOTLIGHT_HEADING_Y = 652`, `SPOTLIGHT_ROW_TOP = 676`,
  `SPOTLIGHT_POSTER_W = 140`, `SPOTLIGHT_POSTER_H = 210` (2:3, matching TMDB poster art's real aspect — the whole
  block moved up from the interim pass, not shrunk), `SPOTLIGHT_POSTERS = 10` (up from 7, since narrower portrait
  posters fit more across the same row width), `SPOTLIGHT_SHELF_PEEK = 63` (≈half the ≈126px tile height),
  `SPOTLIGHT_SHELF_PAD = 16`, `SPOTLIGHT_SHELF_GAP = 36` (matching `CAT_GAP`'s scale). Verified on-TV in both the
  poster-row-focused (sliver shelf) and shelf-focused (revealed) states — no overlap in either.
- The poster pipeline (#2/#3) shipped as planned: `Recs.Pick.poster` (TMDB `poster_path`, parsed in `add()` alongside
  `backdrop`, persisted through `save()`/`parse()`, `CACHE_VERSION` bumped to 14 for the new saved field), a parallel
  `Recs.poster(Pick, width)`/`loadPoster()` modeled on `Catalogs.poster()` (its own small `posters`/`posterPending`
  cache, `w342` via `POSTER_IMAGES`, decoded near the draw width, evicted past `Math.max(10, PICK_COUNT + 3)`).
  `drawSpotlight`'s poster loop now calls `recs.poster(p, pw)` instead of `pb.wide`/`pb.tall`, and the
  `look.cardCaption(...)` call on each poster was removed entirely (the art carries the title).
- **Weather root cause, found and fixed** (not a location/network issue, as suspected): `drawLightTop` called
  `drawTopWeather(c)` *before* `drawSpotlight(c)`, and `drawSpotlight` immediately paints `look.spotlightPhoto(...)`
  — the full-bleed photo — over the *same canvas*, erasing the weather text that had just been drawn. Cards mode
  never hits this because its background photo is a separate layer underneath, not drawn in this same call. Fixed by
  reordering Spotlight's branch to `drawSpotlight(c); drawTopWeather(c);` — photo first, weather on top — confirmed
  working on-TV ("22°, Clear, Faridabad" visible immediately on a cold launch). Cards mode's call order is untouched.

### 9. Round-3 feedback: real scroll, shelf moves to page 2, edge peek, blur

Six more notes after living with Step 8's build. Current state referenced below (`HomeView.java`): `SPOTLIGHT_POSTERS
= 10`, `SPOTLIGHT_ROW_TOP = 676`, `SPOTLIGHT_POSTER_W/H = 140/210`, `SPOTLIGHT_SHELF_PEEK = 63`,
`SPOTLIGHT_SHELF_PAD/GAP = 16/36`, poster row driven by `posterAt(i)` → `cards.get((cardStart + i) % n)`.

1. **Real scroll, not re-sort-to-front.** `posterAt` always puts the focused pick at slot 0 (`cardStart` rotates the
   whole list), so the row "teleports" instead of scrolling. The catalog poster rows already do this correctly —
   `catShift[r]` (a `Spring`, ~line 2224) holds the first visible column; `moveCatalog`'s `dx != 0` branch (~line
   2275-2282) only nudges `catShift[r].target` when the focused column would leave the visible window
   (`CAT_VISIBLE`), otherwise focus just moves within it. Replace Spotlight's row with this pattern: a fixed list
   (`cards`, unreordered), a `spotlightRowShift` Spring (new), a `spotlightCol` index (new, replacing reliance on
   `cardStart` for positioning — `cardStart`/`cardAt(0)` can stay as "which pick is focused" for the background/title,
   just driven by `spotlightCol` instead of rotation).
2. **Posters stay portrait, add more to fill the row.** `SPOTLIGHT_POSTERS = 10` at `140 + 20` gap ≈ 1600px, short of
   `CONTENT_R - MARGIN` (1760). Bump the count (and/or confirm the real available width with the new edge-peek
   behavior from #5, which changes how many fully fit) so the row reaches the content edge.
3. **Shelf peek should read as ~50%, not 25%.** `SPOTLIGHT_SHELF_PEEK = 63` was sized against tile height alone
   (≈126), but the visible sliver competes with the platter's padding (`SPOTLIGHT_SHELF_PAD` above/below), so the
   *visible fraction of the whole platter block* (tile + 2×pad) reads smaller than intended. Size the peek against
   `th + 2 * SPOTLIGHT_SHELF_PAD` (the full platter height), not `th` alone.
4. **Down pages the whole screen, not a reveal-in-place.** This replaces Step 5's `spotlightShelf` sliver/reveal
   Spring and `layoutShelf`'s Spotlight-only interpolation entirely — Cards mode already has exactly the paging this
   needs: `Z_SHELF` → Down → `openDrawer()`/`Z_DRAWER` (~line 1724) → Down again → `Z_CATALOG` (`enterCatalog`-style
   flow, `catIn`/`catRow` springs, ~line 2230-2297) scrolls in the Continue Watching / Shows you watch / Watchlist
   rows. For Spotlight: route `Z_CARDS`'s Down (where it currently targets `Z_SHELF`) to trigger this same
   drawer/catalog paging directly, and make the shelf render as the **first row of that paged-in content** instead of
   sitting at the bottom of the hero screen at all — i.e. in Spotlight, `Z_SHELF`'s layout/zone concept effectively
   moves to live where `Z_DRAWER`/`Z_CATALOG` currently start, not between `Z_CARDS` and `Z_DRAWER` as it does in
   Cards mode. This is a real navigation-model change for Spotlight specifically (Cards mode's shelf position and
   Down-chain are untouched). Follows directly from this: **no shelf sliver shows on the hero screen at all** in
   Spotlight once this ships (Step 8's #5/#6 — the 50% peek and its gap — becomes moot, superseded by this).
5. **Partial poster at the row's edges during scroll.** Already solved in the catalog rows and just needs copying:
   `drawCatalog`'s render loop (~line 2316) draws `first` to `first + CAT_VISIBLE + 1` — one item past the fully-visible
   count — and relies on `cardRect.right < 0 || cardRect.left > w` to skip only what's fully off-screen, so the
   `+1`th item draws partially clipped by the screen edge rather than disappearing at a row boundary. Spotlight's row
   draw loop needs the same "`+1`, clip-tested" pattern instead of a hard `for (i = 0; i < SPOTLIGHT_POSTERS; i++)`
   cutoff. Build it for Spotlight now; porting it back to `drawCatalog` itself (today's catalog rows might already
   half-do this — confirm on-TV whether they already peek correctly or also need the fix) is explicitly out of scope
   for this step.
6. **Soft blur, not just darkening, on the left/bottom of the photo.** `GlassLook.spotlightPhoto` (`GlassLook.java`
   ~line 68-88) currently draws `b.image` sharp everywhere and only darkens the left/bottom via
   `spotlightLeft`/`spotlightBottom` gradients. The stock reference blurs the photo there too, not just darkens it.
   `Backdrop` already builds a lightly-blurred variant per pick for free — `b.glassImage()` (480×270, built lazily on
   first use, the same one `Look.m.homeSharp`/`drawHomeBackdrop` already blend elsewhere) — so this doesn't need a new
   per-frame blur computation (expensive, against the 60fps/no-allocation rule): draw `b.glassImage()` scaled to
   `screen` *under* or blended into the same left/bottom region the gradients already cover (e.g. via the gradient
   shader as that layer's alpha mask, so sharp shows through on the right/top and blurred shows through on the
   left/bottom, with the existing darkening drawn on top as today). Give `MaterialLook` its own equivalent treatment
   for the same reason Step 4 made `spotlightPhoto` abstract — not a copy of Glass's numbers.

As-built numbers for all six are tuned on-TV, same as every prior round — this section states direction and the
concrete existing-code patterns to reuse (`catShift`, `glassImage()`), not final constants.

### 10. Round-4 correction: focus pinned at slot 0, 20-title pool, shelf sliver restored

Round 3's scroll model (focus moves freely, windowed shift) wasn't what was wanted — the user confirmed the
*original* always-focused-at-slot-0 layout was correct, just needed Round 3's animation smoothness, not its
windowing. Implemented directly (no sub-agent), verified live on-TV:

- `scrollSpotlight`/`autoAdvanceSpotlight` now set `spotlightRowShift.target = cardStart` unconditionally (no more
  "only shift once focus leaves the visible window" logic) — the focused column is always slot 0, the row slides to
  it via the same `Spring`, `drawSpotlight`'s draw loop needed no change (it already computed position from
  `spotlightRowShift.value` relative to `cardStart`).
- New `Recs.now(String kind, int count)` overload (`now(String)` now just calls it with `Config.PICK_COUNT`);
  `HomeView.SPOTLIGHT_POOL = 20` and `updateCards()` calls `recs.now(TAB_KIND[0], SPOTLIGHT_POOL)` for Spotlight only
  — Cards mode's own `pick_count`-based rotation is untouched. The underlying `picks` pool TMDB's list calls already
  return is big enough (40-80 raw candidates pre-dedup) that this needed no new fetch.
  `updateCards()`'s eager-backdrop-prefetch loop (`recs.backdrop(p)` for every card) was bounded to a window around
  `cardStart` for Spotlight specifically (`cardStart - 2` to `cardStart + SPOTLIGHT_VISIBLE + 2`), instead of all 20 —
  the row's posters load through the separate, lighter `Recs.poster` cache; only the focused title's full-screen
  photo needs the heavy `Backdrop`, and prefetching all 20 would thrash its `pick_count + 3`-sized cache for no
  benefit.
- Shelf: `layoutShelf()` got its Spotlight-only position interpolation back (sliver — half the platter block's own
  height, `th + 2*pad`, visible above the screen's bottom edge — to the normal Cards dock position), but driven
  directly by `drawer.value` (already stepped every frame it's moving) instead of a new dedicated `Spring` like
  Round 2's `spotlightShelf` was — one less moving part. `updateMotion()` dropped the alpha-based hiding
  (`shelfAlpha` is just `home` now, both styles) since visibility is by position/clipping again, not transparency;
  Spotlight's shelf layer skips the `-d * shift` term of `lift` (that's Cards' "carry the already-docked shelf up"
  motion, which would double up with `layoutShelf`'s own sliver-to-dock travel) but keeps `-catLift`, so the shelf
  still scrolls away correctly once you move deeper into the catalog rows on page 2.

Verified on-device: real scroll with the focus ring staying at slot 0, 12+ full posters with a 13th peeking at the
edge (confirmed scrolling further brings in previously-unseen titles — Spider-Man, Grey's Anatomy, NCIS), a visible
shelf sliver at rest, full shelf on page 2, and Back from page 2 restoring the row's exact scroll position.

**As built:**

- **#1/#5 (scroll + edge peek), shipped together.** `cardStart` is now the focused *column* in Spotlight (an absolute
  index into the fixed, unreordered `cards`, not a rotation offset — `posterAt(col)` is a plain `cards.get(col)`),
  driven by a new `scrollSpotlight(dx)` (clamps at the ends, doesn't wrap) and `autoAdvanceSpotlight()` (the idle
  `pick_rotate_seconds` tick — wraps back to column 0 on reaching the end, snapping the shift rather than animating a
  long slide across the whole row, since a manual scroll stopping at the end is right but an idle carousel halting
  forever isn't). A new `Spring spotlightRowShift` holds the first visible column, exactly `catShift`'s pattern.
  `drawSpotlight`'s loop draws `first` to `first + SPOTLIGHT_VISIBLE + 1` (`SPOTLIGHT_VISIBLE = 11`), clipped by
  `cardRect.right < 0 || cardRect.left > w` — the same `drawCatalog` pattern, not ported back to the catalog rows
  themselves (out of scope here, per the plan). `posterRects[]` is gone, replaced by a single
  `spotlightFocusedRect` (only the focused column's rect is ever needed outside the draw loop — for `openPick`'s
  animation origin and the shelf-entry nearest-x lookup — so an array indexed by on-screen slot was never the right
  shape once slot 0 stopped meaning "the focused one").
- **#2 (fill the row).** Falls out of #1/#5 once the row is a real scrollable window instead of a fixed 10-slot
  array capped by the old `SPOTLIGHT_POSTERS` constant (removed) — it now draws however many `cards` the pool has.
  On this TV the Explore pool itself only has ~10 picks, so the row doesn't visibly scroll yet in practice (nothing
  to scroll *to*) — the mechanism is correct and tested via focus moving cleanly from column 0 to column 9 with the
  ring following it, but real scrolling (and #5's edge peek) will only be visible once the pool is bigger than
  `SPOTLIGHT_VISIBLE`. That's a pool-size question (`Config.PICK_COUNT` / `Recs`), not a Spotlight UI question — not
  touched in this pass.
- **#3, skipped as the plan anticipated** — superseded by #4.
- **#4 (shelf moves to page 2), the real structural change.** Removed entirely: the `spotlightShelf` Spring, its
  stepping/target logic in `doFrame`, and `layoutShelf`'s Spotlight-only sliver interpolation — `layoutShelf` now
  computes the exact same resting position for every style (`L_PLATTER_TOP + L_TILE_H`, Cards' own position).
  What makes Spotlight different is entirely in `updateMotion()`: the shelf's alpha (`platter`/`tiles`) is gated by
  `Prefs.spotlightOn() ? da : home` (`da` is the same `smoothstep(0.1, 0.6, drawer.value)` curve `drawerLayer` already
  fades in with) instead of always-on `home` — so in Spotlight the shelf is invisible at rest and fades/lifts in
  through the *exact* transform Cards mode already uses to carry its always-visible shelf up into the drawer
  (`lift = -drawer.value * SHIFT - catLift`), landing in the same place, as the drawer page's first row, with zero new
  position/transform code. Down from the poster row (`Z_CARDS`, Spotlight) calls a new `openSpotlightPage()`: primes
  catalogs the way `openDrawer()` does, sets `drawer.target = 1` and `zone = Z_SHELF` in one step (Cards mode takes
  two presses — Down to the shelf, Down again to open — Spotlight collapses them since the shelf was never a
  separate resting place to begin with). Up from the shelf, or Back from anywhere on the page
  (`Z_SHELF`/`Z_DRAWER`/`Z_CATALOG`/`WIDGETS`), calls the mirror `closeSpotlightPage()` (`drawer.target = 0` +
  friends, straight back to `Z_CARDS`) — added as an explicit early branch in `back()`, since Cards' own
  `closeDrawer()`/`leaveCatalog()` chain lands on `Z_SHELF` expecting it to still be visible, which in Spotlight it
  deliberately isn't once the page starts closing.
  **Found and fixed one more real bug while verifying this on-TV**: `zone` defaults to `Z_SHELF` (the constructor's
  field initializer and `reset()`, "Home pressed while the home screen is showing") everywhere in this file, which in
  Cards mode is always visible and harmless — but in Spotlight, starting focus on an invisible shelf meant Left/Right
  did nothing (no visible effect) and only the idle auto-advance timer ever moved the background, since
  `autoAdvanceSpotlight`'s own `zone != Z_CARDS` guard was satisfied by sitting on the hidden shelf the entire time.
  Fixed both the constructor and `reset()` to start Spotlight on `Z_CARDS` instead, and `setLightButtons`' own
  `Z_SHELF` fallback (when there's nothing in `Z_TOP` to land on) to prefer `Z_CARDS` in Spotlight too. Verified live:
  focus ring tracks the row correctly from a cold launch, Down pages cleanly to the shelf-then-catalog screen, Back
  from deep in the catalog rows returns to the poster row in one press.
- **#6 (soft blur).** Both looks reuse `b.glassImage()` (free, already built) via a cached `ComposeShader`
  (`BitmapShader` of the blurred image, `PorterDuff.Mode.DST_IN` against the look's own existing left/bottom darkening
  gradient as the mask, so the blur fades in exactly where the darkening already does) — rebuilt only when the
  focused pick's bitmap identity changes, not per frame. `GlassLook` masks against its single `spotlightLeft`/
  `spotlightBottom`; `MaterialLook` masks against its day/night tonal-surface gradient pairs (four composed shaders
  instead of two, for the same reason its darkening already has day/night variants). Confirmed visually on-TV
  (Glass only — Material's blur wasn't checked live, per the plan's own scope note for this step).

Verified end to end on 192.168.0.4 (Glass look): cold launch starts on the poster row with a visible focus ring;
Left/Right moves focus correctly (re-tested after the `Z_SHELF` default fix); Down pages to the shelf then the
Continue Watching/Shows you watch/Watchlist rows, each a clean screen with no shelf/poster-row overlap; Back from the
catalog rows returns directly to the poster row in one press. Not independently re-verified: real multi-screen
scrolling and the edge-peek (blocked by the small pool size, see #2 above), and Material's blur treatment.

### 11. Round-5: shelf on page 2, title wrap, corner scrim, backdrop loading

**As built** (all in `HomeView.java` unless noted; verified live on 192.168.0.4):

- **Shelf size and gap.** Tiles are back at Cards' size (the old Spotlight-only `tw *= 0.74` shrink is gone). Only the
  outer padding shrinks (`pad = 10 * k`) since the shelf is docked right above row 0. `catLift()` is reduced by
  `SPOTLIGHT_SHELF_GAP = 60` in Spotlight, pushing row 0 (and so the shelf's dock) lower, so the full glass pill fits
  under the screen's top edge instead of being cut off.
- **Shelf is "row -1" of the catalog.** Its top is `row0Top - offset`, where `row0Top = (catY0() - catIn * catLift() -
  catRow * CAT_PITCH) * k` is the same formula `drawCatalog` uses, so it moves in lockstep with Continue Watching
  and scrolls away with it. The offset eases from the hidden sliver's to the docked one with
  `smoothstep(0, 0.35, catIn)` (the catalog's own fade-in), not a blend by `drawer.value`: two separate springs let
  Continue Watching slide over the shelf mid-transition. The docked gap is `CAT_ROW_GAP` (= `CAT_PITCH - 50 -
  CAT_POSTER_H + 30`, the same space as between Continue Watching and Shows you watch), shrunk once, from the
  resting layout, to fit the headroom (`restRow0Top - platterH - 24k`). It is never a clamp on the live position,
  which had pinned the shelf while the rows kept scrolling up over it. This TV has `cardsOn() == false` (the
  quote-of-the-day feature, unrelated to the picks cards; it picks the smaller `CARDS_TOP - 140` lift), so the gap
  here is smaller than between the other rows.
- **Shelf repaint.** `doFrame` invalidated `platter`/`tiles` only when the drawer started or stopped, which is right
  for Cards (the shelf only moves by its layer's translationY). Spotlight recomputes the rects every frame from
  `catIn`/`catRow`/`drawer`, so `spotlightShelfMoving = spotlightOn() && (catMoving || drawerMoving)` now invalidates
  them while those animate (it showed as a stuck shelf).
- **Sliver kept.** An experiment that hid the sliver on page 1 was reverted at the user's request; the shelf still
  peeks (about half the platter) on the hero page.
- **Title wraps.** `wrapTitle` breaks at a space into at most two lines (`breakText`, second line ellipsized), cached
  by title/width/`Ui.generation` so `onDraw` doesn't allocate. The last line stays on `SPOTLIGHT_TITLE_Y`, so the
  block grows upward and the meta line stays put.
- **Top-right scrim.** A `RadialGradient` centred on the top-right corner (built once per width) is drawn over the
  photo in `drawSpotlight`, so the weather-side buttons stay readable on bright photos. It sits above both looks'
  `spotlightPhoto`, not inside them. Checked only on dark photos; a real blur there is not done.
- **Crash fix (`Recs.wanted`).** The circular row lets `cardStart` go negative (Left from the first pick), and
  `(viewStart + i) % size` then indexed -1. It uses `Math.floorMod` now.
- **Missing backdrops (The Crown, Criminal Minds).** `wanted()` built its window from `now(viewKind)` (`pick_count`
  long) while Spotlight's row is `SPOTLIGHT_POOL` (20): picks past the short list never got details or a photo. It now
  uses `now(viewKind, poolCap())`, and takes that branch whenever Spotlight is on (before, only with `pick_count > 3`).
  The window also covers two picks behind the focus. In `drawSpotlight`, the neighbours (focus ±2) are warmed with
  `recs.backdrop()` when the focus moves, and the last good backdrop stays up while the focused one loads instead of
  a plain colour. The Backdrop cache holds 6 (was 4) in Spotlight. A failed details fetch is now logged per title.
- **Strings.** "Cards", "Spotlight" and "Top picks for you" are translated in all 24 catalogues (`translate.py
  --check` passes, as CI needs). Not native-reviewed.

Not done: a real blur behind the top-right buttons; Material's look wasn't re-checked on the TV this round.

## What changes for you

| | Off | Cards (today) | Spotlight (new) |
|---|---|---|---|
| Background | light gradient | frosted/blurred photo | full photo, look's own corner treatment (both looks) |
| Tabs | — | Explore / Movies / Shows | — (same pool as Explore) |
| Picks shown at once | — | 3 (1 wide + 2 tall cards) | 1 full-screen + a scrollable poster row |
| Shelf | fully visible | fully visible | mostly hidden, Down reveals it |
| Settings row | "Off" | "Cards" | "Spotlight" |
| Works under Glass and Material | yes | yes | yes — each look styles its own photo treatment |

## Risks

- **The hand-rolled zone/focus system is the real risk, not the drawing.** `HomeView` has no layout engine — zones,
  `topIndex`/`cardIndex`/`shelfIndex`, and `moveLight`/`move` are all hand-wired. Hiding tabs without breaking
  Up/Down/Left/Right into and out of the shelf/corner-buttons/poster-row is the part most likely to regress existing
  Cards-mode navigation if the `picksOn()` vs. `spotlightOn()` split (step 3) misses a call site — a `grep -rn
  "picksOn()"` sweep across `HomeView.java` before calling this done is cheap insurance.
- **60 fps / no allocation in `onDraw`.** The gradient scrim and the poster row must follow the same pattern as
  existing draw code: paints/shaders built once and reused, not allocated per frame (see `shadowDeep`, `rim`, etc.
  for the existing pattern to copy).
- **Low-RAM TVs.** No new bitmap *type* is introduced (reusing `Backdrop.image`/`wide`/`tall`), but Spotlight draws
  `image` at full screen size every frame instead of a small frosted copy — on `Ui.LEGACY` TVs (8-11, weak GPUs)
  make sure each look's `spotlightPhoto` stays a plain `drawBitmap` with no filter/shadow work there, matching how
  `Shadows`/legacy fallbacks already avoid GPU work on those TVs.
- **Dead-code trap.** `Z_HERO`/`Hero`/`browse()` look like an obvious starting point for "a single focused
  background pick with left/right" but are unused legacy code (see Idea section) — reusing them would mean
  reviving an unmaintained, partially-dead path instead of the live `Z_CARDS` one.
- **Two looks, one feature, built once.** `spotlightPhoto` is `abstract` specifically so Spotlight can't ship
  half-finished for one look (e.g. Glass gets a real treatment, Material falls through to nothing). Both
  `GlassLook.java` and `MaterialLook.java` need real, considered implementations — not one look copying the other's
  numbers — before Step 7's install check.

## Questions for you

1. **Short-of-memory default:** keep the existing off/not-off default untouched (no style-specific default), as
   reasoned in step 1 — or should short-of-memory TVs specifically default to Cards (never auto-pick Spotlight) once
   picks are on by default? I don't see a memory reason to prefer one over the other, so this is really a taste
   question.
2. **Order of work:** build and ship Steps 1-2 (the 3-way setting, with Spotlight temporarily just reusing the Cards
   drawing) first so the setting itself is testable, then Steps 3-5 (the real Spotlight drawing, both looks) — or
   everything in one pass before the first test install?
