# Plan: the home screen as pages, on a small layout framework

`HomeView` is one 4,904-line class: ~250 fields, ~280 methods, nine focus zones handled by two overlapping switches
(`move` and `moveLight`), 33 `Prefs.spotlightOn()` and 12 `picksOn()` branches, and every page's position worked out
together in `updateMotion()` from four springs (`drawer`, `catIn`, `catRow`, `browseIn`). A change to one row's
position moves others (Spotlight's shelf is placed from the catalog's row 0: `layoutShelf`), so aligning anything is
slow and risky. This plan splits it into pages on a small layout framework, so each page lays itself out on its own
and a change in one cannot move another.

Status (2026-11-01): steps 1, 2, 3, 5 and 6 done (3 and 5 together: the framework was built for page 4, its first user). Baseline: Skyworth 4K Google TV Stick, Android 14, Spotlight + Material look, 94.8% janky frames, 90th percentile 121 ms; the moving background redraws ten times a second, so the absolute numbers are high on this stick. Not yet in the framework: the pager and `HomeLayer` (step 7) — until then HomeView still places pages 3 and 4 on screen (`watchingTop`, `catIn`, `browseIn`); `drawPoster`, `drawCard` and `roundImage` are still `HomeView` methods the pages call (to move into a shared painter with step 8, when page 1 is moved and nothing else in HomeView draws posters). Watch Next note: Continue Watching from Trakt playback comes and goes between restarts on this TV with or without these changes (it depends on when Trakt answers), so compare screenshots with that in mind.

## The pages

Four pages stacked vertically. Down past a page's last row goes to the next page; Up past its first row goes back.

| Page | Class | What's on it | Hidden when | Today |
|---|---|---|---|---|
| 1 | `HomeTopPage` | weather, clock and date; the top buttons (and small quotes); the picks (Off, Cards or Spotlight); the shelf | never | `drawLightTop`, `Z_TOP`, `Z_CARDS`, `Z_SHELF` |
| 2 | `HomeWidgetsPage` | the widgets; today only the crypto quotes card (prices with the 24 h change) | no widget is on (quotes off, or no coins) | the "drawer" (`Z_DRAWER`, `SHIFT`, `drawer` spring) |
| 3 | `HomeWatchingPage` | Continue Watching, Shows you watch, Watchlist | no items from Trakt or Watch Next | `Z_CATALOG`, `catIn`, `catRow`, `catalogLayer` |
| 4 | `HomeCatalogPage` | TMDB's catalogs: details, backdrop and trailer of the focused title, one row | no TMDB rows (no key, or not loaded yet) | `Z_BROWSE`, `browseIn`, `browseLayer` |

There's no drawer anymore. Its button row is already always empty (`setDrawerItems(new ArrayList<>())`: inputs became
top buttons), so page 2 starts with the quotes card alone. Each widget is a node that says whether it's on, and later
widgets (the hourly weather, for one) go on the same page.

### Page height

A page is 1×, 2× or 3× the screen's height. Each page says how tall it is (`HomePage.height()`, in screens); it can
work this out from its content (page 3 with many rows takes 2×). Inside a tall page, Up and Down scroll the page to
keep the focused row on screen. At the page's last row, Down goes to the next page.

### Hiding empty pages

Each page answers `isEmpty()` from its own data. The pager builds the list of visible pages again whenever data
arrives (`onCatalogs`, `HomeData`, `Prefs` changes, Watch Next), so an empty page is never drawn, never focused and
never paged to. If the page that has focus becomes empty (Trakt signed out, quotes turned off), focus goes to the
nearest visible page above it, landing on its last row.

In Spotlight the shelf stays on page 1. The title, meta, heading and poster row move up to fit above it, from
`SPOTLIGHT_ROW_TOP` 676 to about 530 (the column layout below works out the exact value). The shelf no longer
follows the catalog's row 0, and `openSpotlightPage`, `closeSpotlightPage` and `SPOTLIGHT_SHELF_GAP` go away.

## The framework

All of it lives in the same package (`com.skyhome.launcher`), so `Look`, `Prefs` and `Ui` stay package-private. There
are no libraries and nothing is allocated in `onDraw`: layout runs only when something it depends on changes (size,
data, prefs, look), never per frame.

### Coordinates

- **Design units:** 1920×1080, multiplied by `k` once, at layout. One `HomeGrid` class holds the shared measurements:
  `MARGIN` 80, `CONTENT_R` 1840, `SAFE_TOP` 60, `SAFE_BOTTOM` 1020, `GAP` 40. Nothing else repeats them.
- **Each page lays out in its own space, from 0 to `height() * 1080`.** No page reads another page's rectangles.
- **One strip, one scroll.** The pager stacks the visible pages one under another (page tops at the sum of the
  heights above them) and has a single `scroll` spring, in pixels along that strip. When focus moves, the pager
  aims `scroll` so the focused row is on screen:
  - entering a page lines its top up with the screen's top;
  - inside a tall page, the focused row's top goes to a fixed line (about 140 from the top, where row 0 rests today),
    clamped so the page's end doesn't scroll past the screen's bottom.

  Moving between pages and scrolling inside one are then the same motion, with no per-page math.
- **Layers stay screen-sized,** even for a 3× page: a layer as tall as a 3× page would cost ~25 MB of GPU memory,
  too much for a 1 GB TCL. A page draws with `translate(0, pageTop - scroll)` and skips nodes outside the screen.
- The glass keeps reading the background at each layer's real position. `scene.setOffset(0, translationY)` already
  does this in `Layer.onDraw`, and it stays.

### Nodes and stacks (alignment)

- **`Node`** is a rectangle with a job:
  - `measure(maxW, maxH)` sets the size it wants.
  - `place(x, y, w, h)` fixes its `frame`.
  - `draw(Canvas)` draws it.
  - `visible()` says whether it takes room. A hidden node takes none, so nothing else needs an `if`.
- **`Column` and `Row`** stack their children with a `gap`, align them (`START`, `CENTER`, `END`) and can pin to the
  top or bottom. Page 1 becomes one column: weather and buttons pinned top, picks in the middle, shelf pinned bottom.
  Moving the shelf up then moves the picks up with it. There's nothing to recompute by hand, and they can't overlap.
- **`Inset`, `Fixed` and `Spacer`** cover the remaining cases: a fixed size, or empty space that's allowed to stretch.

### Focus

- **`FocusRow`** is a horizontal row of focusable items. It owns the index, one focus `Spring` per item, a horizontal
  scroll spring (the `catShift` pattern) and `move(dx)`.
- **`PosterRow`** extends `FocusRow` with posters, badges and lazy image loading. It's used by page 3's rows, page 4's
  row and Spotlight's poster row: one implementation instead of three.
- **Generic up and down.** `HomePage.move(dx, dy)` passes left and right to the focused row. Up and down go to the
  next visible row in that direction, landing on the item nearest in x (today's `nearest()`). Past the last row it
  returns `false`, and the pager changes page. No per-zone `switch`. A row can still react when focus lands on it
  (Cards' tabs select the tab they focus).

### Pages and the pager

```java
abstract class HomePage {
    abstract void layout(float k, int w, int h);   // builds and places its nodes
    abstract List<FocusRow> rows();                // top to bottom
    float height();                                // in screens: 1, 2 or 3
    boolean isEmpty();                             // hidden by the pager
    void enter(int fromEdge, float x);             // focus lands on the first or last row, near x
    void leave();                                  // e.g. pages 3 and 4 drop their posters after POSTERS_KEEP_MS
    boolean activate(); boolean hold(); boolean back();
    boolean tick(float dt);                        // its own springs; true while moving
    // TalkBack: ids are page * 100000 + row * 1000 + index; text and bounds come from the focused node
}
```

- **Each page has its own `HomeLayer`s,** split as today for the weak GPU (page 1 keeps separate layers for the top,
  the picks, the shelf glass and the shelf tiles). Each layer has its own `dirty` flag and only redraws when it's set.
  Pages more than one page away from `scroll` get `showLayer(false)`, as `catalogLayer` does today.
- **`HomeView` becomes the host.** It keeps the sky, backdrop and night look (`HomeBackground`), the pager, the
  overlays, key routing, the `tick` and `rotate` runnables, and `HomeData`/`Recs`/`Catalogs` listeners that pass data to
  the pages. Target: under 800 lines.
- **Overlays** go on top and get keys first: `AppsSheet` (All Apps), `Popover` (inputs and the poster menu), `Toasts`.

### Picks styles

`HomeTopPage` holds one `PicksSection`, chosen at layout from `Prefs.picksStyle()`:

- `PicksOff`: empty.
- `PicksCards`: the tabs and the three cards.
- `PicksSpotlight`: the full-screen photo (drawn by `HomeBackground`), the title and meta, and a `PosterRow`.

Each one gives its rows and nodes to the page's column. The 33 `spotlightOn()` checks become this one choice.

### Guard rails (so a change doesn't break something else)

- **`LayoutCheck.check(page)` (not `Layout`: HomeView already imports `android.text.Layout`)** runs after every layout. It logs (`AppLog.w`) any two sibling nodes that overlap, and any
  node outside the safe area that isn't marked `bleed` (the backdrop and Spotlight's photo are). It's cheap, and it
  doesn't run per frame.
- **The bounds view.** `adb shell setprop debug.skyhome.bounds 1`, then reinstall: every node draws its frame outline
  and name. You can check alignment on the TV without reading code.
- **Before and after screenshots.** For every step below: `adb exec-out screencap -p > temp/shots/<step>/<page>.png`
  for each page and picks style (`temp/` is already out of git).

## Performance rules

These hold at every step and are checked in review. The goal is no slower than today anywhere, and faster in
Spotlight.

1. **Moving between pages moves layers; it doesn't redraw them.** Page-to-page transitions shift each visible
   page's layers with `setTranslationY` (the GPU moves an image it already has), as page 1's layers move today. A
   page's content is drawn only when its own `dirty` flag is set.
2. **Scrolling inside a tall page redraws only the layer that scrolls.** A 3× page keeps a one-screen layer, so
   moving inside it means redrawing at the new offset, as `drawCatalog` already does with `catRow`. The page's
   other layers (and every other page) aren't redrawn.
3. **The glass is drawn as if resting.** A glass layer that slides is drawn once at its resting position and not
   redrawn while it moves, as `drawDrawer` and the top buttons do today (`scene.setOffset` in `Layer.onDraw`).
4. **No layout per frame.** `layout()` runs only when the size, data, prefs or look change; `tick()` only steps
   springs. This removes today's Spotlight slowdown: `updateMotion` calls `layoutShelf` on every frame while
   anything moves, and that clears `crispLogos`, so the focused shelf logos are scaled into new bitmaps on each frame
   of the animation.
5. **No allocations while drawing.** Nodes, rows, paints, shaders and rectangles are made at layout. `draw` and
   `tick` only read and update them.
6. **Hidden and far pages cost nothing.** An empty page has no layer and is never ticked or drawn. A page more than
   one screen away from `scroll` has its layers set to `showLayer(false)`.

## Behaviour changes people will see

1. **Down from the shelf slides the whole page up** to the next visible page (2, 3 or 4), instead of a drawer
   rising under it.
2. **Spotlight:** the shelf is on page 1, under the poster row. Down from the shelf goes to the next visible page.
3. **Empty pages are gone.** With no widgets on, there's no page 2. With no Trakt or Watch Next items, there's no
   page 3. Down from the shelf then goes straight to the catalogs.
4. **Back:** from pages 2 to 4, or from any row on page 1, goes to page 1's shelf. On the shelf it does nothing, as
   today. Overlays and the trailer still close first.
5. **Picks auto-rotate** only while page 1 is showing and focus isn't on the picks, as today.

## Steps

Each step ends with `tools/check.sh` passing and `Built SkyHomeLauncher.apk`. Then install it on the TV and compare
screenshots to the step before. Every step is its own commit, so any one can be reverted.

1. **Baseline.** Take screenshots of every page, in each picks style (Off, Cards, Spotlight) and each look
   (`Themes`), by day and by night. Record frame times with `adb shell dumpsys gfxinfo com.skyhome.launcher reset`,
   then a fixed walk, then `dumpsys gfxinfo com.skyhome.launcher`, keeping *Janky frames* and the 90th and 95th
   percentiles. The walk:
   - along the shelf;
   - Down into the rows and through them;
   - into the catalogs and back;
   - in Spotlight, opening and closing the drawer page (the case that should improve).
2. **Remove dead code.** First confirm each of these is unreachable, then remove it:
   - `Z_HERO` and `Z_STATUS`, `statusItems` and the corner capsule (`layoutStatus`, `capsuleRect`);
   - the `Hero` parts that only `Z_HERO` uses;
   - the drawer's button row (`drawerItems`, `drawDrawerTile`, `layoutDrawer`'s row);
   - the `WIDGETS` zone (TalkBack's way to the drawer cards; page 2's widgets become a focus row instead).

   Expected result: no visible change.
3. **Framework.** Add `HomeGrid`, `Node`, `Stack` (row and column), `FocusRow`, `HomeLayer`, `HomePage`, the pager,
   `LayoutCheck` and the bounds view. Done together with step 5.
4. **Overlays out:** `AppsSheet`, `Popover`, `Toasts`. No visible change.
5. **Page 4, `HomeCatalogPage`,** with `PosterRow`: browse, details, backdrop cross-fade, trailer and its listener.
   This is the most self-contained part today. `HomeView` routes `Z_BROWSE` to it.
6. **Page 3, `HomeWatchingPage`,** reusing `PosterRow`: the tile menu, *Remove*, *Hide*, *Mark as watched*, Watch
   Next, and poster dropping on leave. Its height comes from its rows (1× up to 3×), scrolled by the pager's one
   `scroll`. It's empty when `Catalogs` has no Trakt rows and no Watch Next items.
7. **Page 2, `HomeWidgetsPage`.** The drawer goes away: `SHIFT`, `drawer`, `drawerOpen`, `openDrawer`/`closeDrawer`,
   `catY0`/`catLift`, `cardsOn`. It's empty when no widget is on. The pager now runs pages 2 to 4 and hides the
   empty ones.
8. **Page 1, `HomeTopPage`,** with the three `PicksSection`s. The shelf moves to page 1 in Spotlight. Delete `move`,
   `moveLight`, `retarget`'s zone list and `updateMotion`'s page math.
9. **Clean up `HomeView`:** the host only. Update its class comment and `CLAUDE.md` ("The code": pages, framework,
   bounds view).

## What to check on the TV (each step from 5)

- **Every page in each picks style and look, by day and by night.** No overlaps, and `LayoutCheck` logs nothing.
- **Moving around:** Up and Down through every row and page and back; Left and Right at both ends of every row; Back
  from every page.
- **Hold OK:** on a shelf app (reorder), in All Apps (shelf toggle), on a poster (menu), and on page 4 (trailer).
- **Smoothness:** 60 fps while paging. Repeat the baseline's `gfxinfo` walk: janky frames and the 90th and 95th
  percentiles must not go up from the baseline in any picks style, and Spotlight's page change should go down.
- **Performance rules:** with *Profile GPU rendering* or `adb shell setprop debug.hwui.show_dirty_regions true`,
  a page change flashes nothing (layers only move), and scrolling a tall page flashes only that page's scrolling
  layer.
- **TalkBack:** every focusable item is read out, and OK works.
- **720p UI** (a TCL with 1 GB, if one is available), and the Android 8 compile check (`platforms/android-26`). See
  `plan-test-old-android.md`.
- **Memory:** posters are dropped about 10 s after leaving pages 3 and 4 (`catalog posters dropped` in the log).
- **Empty pages:** turn quotes off, then sign out of Trakt and deny Watch Next. Each time, the page disappears and
  Down skips it. If focus was on that page when it emptied, it lands on the page above.
- **Tall pages:** with enough rows that page 3 is 2× or 3×, Down scrolls through every row, then goes to page 4. Up
  comes back the same way. The page's end never leaves a gap at the bottom of the screen.
