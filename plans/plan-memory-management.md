# Plan: memory management

The home screen (`:home`) runs noticeably heavier than the TV's stock launcher: ~200-270 MB PSS live, ~212 MB on the
Settings comparison table's 2-minutes-after-a-switch measurement, against the stock launcher's ~134 MB. A running list
of what was measured, what was ruled out, and what's left to try. Each item is its own section; add the next one at
the bottom. A finished item gets a **Built** line with what was measured; a ruled-out one gets a **Dead end** line
instead, so it isn't re-tried.

Status (2026-10-27): item 1 built, on branch `memory-card`. Items 2, 7 and 8 built/tested, measured, and ruled out
(or mostly ruled out) — documented as dead ends. **Item 5 (recycle evicted images immediately) is built for
`Catalogs.posters` only**: the same change crashed `:home` live on the TV (`Canvas: trying to use a recycled bitmap`)
for `Recs.images` and `Details.backdrops`, because `bgFrom`/`bgTo` and `browseBack`/`browsePrev` hold a long-lived
reference outside those caches' own eviction order — reverted for both, see item 5's write-up before touching either
cache's eviction again. **Item 3 (release unused hardware layers) is built and now fully verified**: `drawerLayer`/`catalogLayer`/
`browseLayer`/`sheetLayer` now start as `LAYER_TYPE_NONE` and only switch to `LAYER_TYPE_HARDWARE` while their own
page is actually showing (`showLayer()`, `HomeView.java`) — confirmed safe (no crashes, correct rendering) on all
four pages, including the catalog/browse screens once TMDB catalogs were turned on (couldn't reach those two in an
earlier session). GL mtrack's live before/after wasn't clean to prove with single snapshots (GPU drivers pool freed
allocations rather than instantly returning them to PSS accounting) — the benefit is memory becoming reclaimable for
a page that may never open in a session, not a guaranteed visible drop right now. **Item 4 (cap `LOGOS`/`TILES`) is
built**: both converted to access-order `LinkedHashMap`s with a size cap (32/48), *not* recycling evicted entries —
checked first and found `HomeApps.shelf()`'s `Item.logo`/`Item.tile` fields are held directly by `HomeView.shelf`
across many frames (the same long-lived-reference shape that crashed item 5), so this follows the cap-only,
no-recycle pattern `TILES`' existing uninstalled-app pruning already used safely. **Item 6 is resolved, no bug**:
confirmed live that `bgFrom` is nulled the instant a crossfade completes (`HomeView.java:1180-1182`); recycling it
there would be wrong regardless, since `Recs.images` still owns that same `Backdrop` object and may legitimately
redraw it later.
**Item 9 (shelf) is fixed and verified**: the focus-animation
shadow was generating a new GPU offscreen surface almost every frame (`adb shell dumpsys gfxinfo`'s `GPU Caches`
section, not `meminfo`, found this directly — no Android Studio needed); fixed by making only the targeted tile
animate and drawing its shadow from already-computed bounds instead of a live canvas transform. 5 shelf moves now
cost `+2.56 MB` instead of a projected `~47 MB`. Posters/catalogs needed no change (already correct).

**Picks: fixed and verified (item 9's write-up).** First investigation pass concluded "same mystery as All Apps
grid," but that was a methodology bug — `force-stop`-then-launch resets focus to the shelf, so every "picks"
isolation test was silently re-testing shelf navigation. Redone with a screenshot verifying the actual focus ring
before every measurement: the real cause is `drawTravelCard` (the sliding-carousel renderer, active during every
rotation since 3 picks means `cards.size() > 1`), not `drawLightCard` at all. The dominant cost was `saveLayerAlpha`
— used only to apply one edge-fade alpha to the whole card — not the shadow; two attempts to fix it by quantizing the
layer's size to a grid failed (the real problem is several differently-shaped cards needing differently-sized layers
*simultaneously* within one frame, not frame-to-frame resizing). Fixed by dropping the offscreen layer and applying
`alpha` directly to each of the card's paints instead (image, shadow, placeholder, rim, text, and the look's own
focus ring/caption via a new `alpha` parameter). Verified: +1.42 MB/+17 entries per 2 rotations, down from +6.84
MB/+19 (~79% fewer bytes), matching the "saveLayerAlpha disabled" isolation result almost exactly. **The All Apps
grid's own growth is now also found and fixed** (see item 9's final section): it wasn't a drawing cost at all —
`Spoken.focusMoved()` was broadcasting a full TalkBack-oriented accessibility event on every focus move whenever
*any* accessibility service was enabled, including `RemoteKeyService` (needed on TCL/Vestel/KTC/Sony TVs for remote
app buttons, no screen reader involved); now gated on an actual spoken-feedback service being enabled instead.
Item 2's diagnostic flags
are still sitting in `MaterialLook.java`, marked `TEMP`, not yet reverted; the full per-cache diagnostic logging pass
(item 7's groundwork) is still in place too (`TEMP`, not yet reverted) across `Catalogs.java`, `HomeApps.java`,
`Symbols.java`,
`ServiceBadge.java`, `Shadows.java`, `Backdrop.java`, `Recs.java` and `HomeView.java`. Items 3-6 still proposed, not
started.

**The realistic target.** 50 MB total isn't reachable without cutting features — not because of anything in this
app's design, but because of what any visible, hardware-accelerated Android app costs before it draws a pixel.
Measured on this TV: Google's own `com.android.tv.settings`, doing far less than our home screen, is 85 MB with an
activity open; our own `:memcard` process (just a text pill, no images, no shaders) is 27 MB on its own. A credible,
feature-preserving target is cutting the current ~212 MB down to roughly 110-150 MB.

---

## 1. A debug memory card: free/total RAM and the foreground app's memory, over any app

**Wanted.** A way to see memory usage live, on the TV, without pulling `dumpsys` over ADB by hand every time: a small
overlay, top right, showing free/total RAM and whichever app is in front's own memory, broken down by process.

**Where.** New file `MemoryCard.java` (an overlay service, its own process `:memcard`, modeled on `SleepOverlay`);
`Config.SHOW_MEMORY_CARD` (`build.sh`, `config.properties`: `show_memory_card`); `HomeActivity.onCreate`,
`HomeReceiver` (boot, package-replaced) and `HomeMode.applyFree`/`applyOriginal` start it; `AndroidManifest.xml`
registers the service.

**How.** Free/total read straight from `ActivityManager.getMemoryInfo()` (no ADB, always accurate, any mode). The
foreground app's memory needs ADB (same TV's-own-ADB `HomeMode` already uses): `dumpsys activity activities` for
`topResumedActivity`/`mResumedActivity` (Android 13+ renamed the field), then `dumpsys meminfo` (no package filter)
parsed at its `Total PSS by process:` section, summing every process whose name equals or starts with `<pkg>:` —
`dumpsys meminfo <pkg>` alone only matches the process named *exactly* that string, silently missing multi-process
apps (ours included: `:home`, `:memcard`, `:keys`). Refreshes every 2 seconds. Off by default
(`show_memory_card=false`), gated entirely by the config flag.

**Decided.** Works in both Sky Home Launcher and Original mode, not just while switched on: `applyOriginal` normally
revokes the `SYSTEM_ALERT_WINDOW` overlay permission on every switch back to Original
(`HomeMode.java`, `appops set … SYSTEM_ALERT_WINDOW default`); that revoke is now skipped when
`Config.SHOW_MEMORY_CARD` is true, so the card survives switching. The permission can still only be *granted* for
the first time via ADB, which needs Sky Home Launcher mode at least once — same one-time bootstrap Sleep Timer's
pill already has.

**Built.** Confirmed on the TV in both modes: over Stremio (`App: 178 MB`), over the stock Google TV launcher in
Original mode (`App: 146 MB`), and showing the full per-process breakdown (`home: 87 MB`, `memcard: 18 MB`,
`main: 6 MB`, `keys: 6 MB`, `App: 117 MB`, `Free: 683 MB`, `Total: 1920 MB`). All 24 translation catalogues updated,
`translate.py --check` and `build.sh` both pass. Committed on branch `memory-card` (`4ef8ecf`), not pushed.

---

## 2. Dead end: the animated sky background and focus glow aren't the cost

**Wanted.** Test whether Material 3 Expressive's animated background (drifting shapes, two radial glows, a redraw
loop ~10x/second) and the focus glow are a meaningful share of the ~140 MB "GL mtrack" (Graphics) figure.

**Where.** `MaterialLook.java`: the constructor's `m.animatedBackground`/`m.focusGlow` flags, and `paintBackground()`.

**How (the test, still in place, marked TEMP).** `m.animatedBackground = false; m.focusGlow = false;`, and
`paintBackground()` gets an early return drawing a flat `0xFF1565C0` blue instead of the gradient/glow/drifting
shapes.

**Dead end.** No meaningful change. Clean, 2-minutes-after-switch comparison-table measurement before: 212-247 MB
across prior runs. After, same measurement: **212 MB**. Live GL mtrack samples were, if anything, slightly *higher*
after the change (111-145 MB) than some before-samples (79-116 MB) — within normal session-to-session variance, not
a regression, but certainly not a win either. The sky/glow code is cheap Canvas drawing (gradients and path shapes);
the real cost is elsewhere. **Revert this before merging anything** — `git checkout -- app/src/com/skyhome/launcher/MaterialLook.java`
on branch `memory-card`, or wherever it's carried forward to.

---

## 3. Release hardware hardware layers when their page isn't on screen

**Wanted.** `HomeView` sets 9 views to `LAYER_TYPE_HARDWARE` (`HomeView.java:458`) — each becomes a persistent
GPU-backed texture sized to its bounds, allocated once and never toggled off, regardless of whether its content is
visible. At this TV's resolution (1920x1080), three of them map directly to content that isn't always needed:

| Layer | Maps to | Size | GPU memory |
|---|---|---|---|
| `catalogLayer` | Page 3 (catalogs) | 1920x1080 | 8.29 MB |
| `browseLayer` | Browse overlay | 1920x1080 | 8.29 MB |
| `sheetLayer` | Bottom sheets | 1920x1080 | 8.29 MB |
| `drawerLayer` | Page 2 (Continue Watching / Shows You Watch / Watchlist) | 1920x600 | 4.61 MB |

(`heroLayer`, `platter`, `tiles`, `status`, `popLayer` stay resident too, but they're page-1 content, i.e. the
resting screen, so keeping them hardware-accelerated is reasonable as-is.)

**Where.** `HomeView.java:458` (the `setLayerType` call), and wherever each layer's page is opened/closed (the
catalog screen, the browse overlay, sheets, the drawer) to know when to flip the type.

**How.** This is Android's own documented pattern for hardware layers: a temporary aid during animation, not a
permanent canvas. Call `setLayerType(LAYER_TYPE_HARDWARE, null)` when a page opens or starts animating, and
`setLayerType(LAYER_TYPE_NONE, null)` once it's closed and settled (not mid-animation — toggling during a scroll
would cost a frame hitch). `catalogLayer`/`browseLayer`/`sheetLayer` together are ~25 MB; `drawerLayer` adds another
~4.6 MB.

**To decide.** Exactly when "settled" is — right when the page closes, or after a short idle delay (in case the
person flips back and forth quickly, where re-creating the layer each time could itself cost dropped frames).
Validate the ~25 MB estimate first with the same before/after measurement method as item 2, since the naive
width×height×4-bytes math is a lower bound (real HWUI layers can cost more from mipmaps/padding/driver overhead).

---

## 4. Cap `HomeApps.LOGOS` (and tighten `TILES`) the way `Catalogs.posters` already is

**Wanted.** `HomeApps.LOGOS` (`HomeApps.java:131`) is a plain, uncapped `HashMap<String, Bitmap>` — every shelf app's
logo, and every app's tile from the All Apps screen, is decoded once and held for the rest of the process's life.
Compare to `Catalogs.posters` (`Catalogs.java:91-94`), a `LinkedHashMap` capped at `MAX_POSTERS = 40` with
`removeEldestEntry` eviction: a correct, bounded cache. `LOGOS` has no such cap. `TILES` (`HomeApps.java:130`) gets
*partial* pruning on each All Apps rebuild (`HomeApps.java:444`, `removeIf` against the currently-seen set), but
only for keys containing a `.`, and only when that screen is revisited.

This isn't a memory leak in the strict sense (nothing is unreachable; the GC is behaving correctly) — it's an
uncapped cache that grows with how much of the app has been browsed and never shrinks back down.

**Correction (2026-10-27).** Originally blamed for the 67-91 MB Native Heap fluctuation seen in early sessions; direct
instrumentation (item 7's diagnostic log) now shows `LOGOS` stayed at `0 bytes (0 entries)` through extensive
shelf/drawer/All-Apps browsing on this TV — its apps apparently all fall through to the `TILES`/`appTile()` path, not
`shelfLogo()`. `TILES` itself stayed flat too. The real cause of that fluctuation was `HomeView.crispLogos` (item 7).
Demoted below item 7 accordingly — on this TV `LOGOS` has nothing to evict (only 9 apps installed, all fitting
comfortably under any reasonable cap), though it's still worth fixing as a correctness issue for TVs with many more
apps installed.

**Where.** `HomeApps.java`: `LOGOS` and `TILES` map declarations (line 130-131), `shelfLogo()` (line 604-626,
`LOGOS.put` at 623), the two `TILES.put` call sites (900, 976), and the `allApps()` pruning (444).

**Built (2026-10-27) — capped, deliberately not recycled.** Checked every reader before touching eviction, per item
5's lesson: `HomeApps.shelf()` resolves `it.logo`/`it.tile` once per `Item` and `HomeView.shelf` (`HomeView.java:168`)
holds that same `List<Item>` across every frame until the next `reload()` (up to `APPS_MAX_AGE_MS` = 15 minutes) —
the identical long-lived-reference shape that crashed `Recs.images`. Recycling an evicted `LOGOS`/`TILES` entry here
would risk the same crash the moment a still-displayed `Item` tried to draw its now-recycled `.logo`/`.tile`.

So instead of `Catalogs.posters`' recycle-on-evict pattern, this follows `TILES`' own existing (and already safe)
`removeIf` pruning: cap the map size, let the entry just fall out of the cache with no `.recycle()` call. Both
`LOGOS` and `TILES` are now access-order `LinkedHashMap`s with a `removeEldestEntry` cap (`MAX_LOGOS = 32`,
`MAX_TILES = 48` — bigger than `posters`' 40 since `MAX_SHELF = 8` already needs up to 16 `LOGOS` slots for 8 shelf
logos + their `-dark` variants, leaving headroom for All-Apps-browsed extras; `TILES` serves both the 8 shelf items
and every launchable app's All Apps tile). An evicted logo re-decodes cheaply from its already-saved-to-disk PNG if
needed again; an evicted tile just gets rebuilt. `TILES`' existing uninstalled-app `removeIf` (`HomeApps.java:461`)
is untouched and still runs alongside the new size cap.

**Verified on the TV**: built, installed, opened the shelf and All Apps (all 9 tiles render with correct logos/art),
no crashes. This TV's 9 installed apps never approach either cap, so eviction itself wasn't exercised live — same
caveat the original "Correction" note above already flagged — but the structural fix (bounded growth, matching
`Catalogs.posters`' shape minus the unsafe recycle call) is in for TVs with many more apps installed.

---

## 5. Recycle evicted posters/pictures immediately, instead of waiting for GC

**Built for `Catalogs.posters` only (2026-10-27); the same change crashed the launcher for `Recs.images` and
`Details.backdrops` and was reverted for both.** `.recycle()` doesn't free Java-heap memory (that's what the GC
already handles) — since Android 8 a `Bitmap`'s pixel buffer lives in the **native heap** (or, for a
`HARDWARE`-config bitmap like `Backdrop.wide`/`tall`, a GPU-backed graphic buffer counted under **Graphics**/
`GL mtrack`), and neither is reliably freed promptly just by dropping the Java reference: ART's GC triggers mainly
off *Java*-heap pressure, so a multi-MB native/GPU buffer can sit around unreferenced-but-uncollected for a while.
`.recycle()` frees it immediately and deterministically instead — *when nothing else still points at it.*

**The crash.** Tried the identical pattern (capture the evicted entry, `Handler.postDelayed(evicted::recycle, 1000)`
from the main thread, 1 second of margin against a frame still in flight on the RenderThread) in all three caches.
Live on the TV, browsing the hero picks quickly crashed `:home` with `RuntimeException: Canvas: trying to use a
recycled bitmap` inside `drawHomeBackdrop`/`drawHomePicture` — the background sky photo, not even the picks
carousel. Root cause: `HomeView.showBackdrop()` (`HomeView.java:1039-1046`) stores whatever `Backdrop`
`recs.backdrop(currentPick())` returns into `bgFrom`/`bgTo`, and `drawHomeBackdrop` draws straight from those fields
every frame for as long as that pick stays the current background — potentially minutes, not frames. `Recs.images`'
eviction (`Recs.java:1068`) is **FIFO by insertion order** with no idea `bgFrom`/`bgTo` still references the oldest
entry; a 1-second delay is nowhere near long enough when the actual reference lifetime is "however long the person
leaves this pick showing." `Details.backdrops` has the exact same shape: `HomeView.drawBrowseDetails()`
(`HomeView.java:2507-2518`) stores `details.backdrop(browseInfo)`'s result into `browseBack`/`browsePrev`, drawn
every frame through a cross-fade. **Both reverted** — `Recs.images`' three eviction sites (the cap loop, `setLight()`'s
`clear()`, `trim()`'s `retainAll`) and `Details.backdrops`' `removeEldestEntry` are all back to plain removal, no
recycle, with a comment explaining why.

**`Catalogs.posters` is structurally different and was kept.** Its only two call sites
(`HomeView.drawPoster():2317`, `EpisodesActivity.drawLeft():786`) both call `catalogs.poster(it, width)` **fresh,
every single frame**, and only draw the result when that same call returns non-null that frame — there is no field
anywhere that caches the `Bitmap` across frames without re-validating it against the live map first (`EpisodesActivity`'s
`posterBitmap`/`posterShader` fields are themselves rebuilt only inside the `p != null` branch, from that frame's
fresh `p`, so a recycled `p a` is never what gets drawn). Confirmed safe by grepping every `.poster(` call site in the
codebase before trusting it, after the `Recs`/`Details` crash showed why that check matters.

**Verified on the TV after the revert**: rebuilt, reinstalled, then specifically stress-tested the exact mechanism
that crashed — 30 rapid presses browsing the hero left, 30 more right (`browse(dir)` → `recs.backdrop()` →
`showBackdrop()` → `bgFrom`/`bgTo`, over and over) — no crash, same process (`pid` unchanged) throughout, renders
correctly. `Catalogs.posters`' recycle survived the same general navigation (shelf, catalogs, tabs, Continue
Watching/Watchlist/Shows You Watch rows) with no "trying to use a recycled bitmap" errors.

**Lesson for next time:** before adding `.recycle()`-on-eviction to *any* cache, grep every call site that reads from
it and confirm the result is never stashed in a field that outlives the single draw call it was fetched for. A cache
whose only readers re-fetch fresh every frame is safe to recycle promptly; a cache whose reader stores "the current
X" in a field (a cross-fade, a "now showing" pointer) is not — the field's lifetime, not the cache's own eviction
order, is what decides how long the bitmap actually needs to stay alive.

**Not yet re-measured for total PSS impact for the one fix that's still in** (`Catalogs.posters`) — it closes a real
gap (prompt vs. eventual freeing) but doesn't change that cache's steady-state size, so it mainly helps memory come
back down sooner after a burst of new posters (scrolling Continue Watching/Watchlist), not the resting baseline.

---

## 6. Investigate `Backdrop`'s retained bitmaps during a crossfade

**Wanted.** Originally suspected as a major contributor; turned out smaller than expected once actually measured.
Each `Backdrop` instance is ~11 MB (`Backdrop.java:28-30`: one full 1920x1080 `image` at 7.91 MB, plus small
192x108 blurred variants and card crops — *not* six full-screen bitmaps as first assumed). `HomeView` can hold two
at once during a crossfade (`bgFrom`/`bgTo`), so worst case ~22 MB, not the 60-100 MB first guessed.

**Where.** `HomeView.java` (`bgFrom`/`bgTo` fields, wherever a crossfade completes and the old one should be
released), `Backdrop.java`.

**Resolved (2026-10-27): no bug, no change needed.** `HomeView.tickBackgroundFade()` already nulls `bgFrom` the
instant the crossfade's ease reaches 1 (`HomeView.java:1180-1182`: `if (e >= 1) { bgFadeAt = 0; bgFrom = null; ... }`).
Verified live: pressed browse-left/right on the hero, waited for the fade to settle, and the `mem:` debug log's
`backdrop (%d+%d)` field went from `1+1` to `0+1` a few seconds later, confirming `bgFrom` drops out promptly.

**Recycling it would be wrong, not just unnecessary.** `bgFrom`/`bgTo` are borrowed references into `Recs.images`'
cache (`recs.backdrop(currentPick())` in `browse()`/`showBackdrop()`, `HomeView.java:1032-1045`) — the *same*
`Backdrop` object, not a copy. `Recs.images` still owns it and may legitimately redraw it later (scrolling back to
that pick in the carousel). Calling `.recycle()` here on crossfade-complete would free bitmaps `Recs.images` still
considers live — the exact mistake item 5 already found the hard way, just reached from the other end (HomeView
"finishing" with a Backdrop it never owned in the first place, rather than the cache evicting one still in use).

---

## 7. Dead end: `HomeView.crispLogos` isn't the shelf-scroll cost either

**Wanted.** After all 7 instrumented caches (`posters`, `tiles`, `logos`, `symbols`, `badges`, `shadows`, `backdrop`,
`Recs.images`/picks) stayed completely flat during the shelf-scroll growth, `HomeView.crispLogos` (`HomeView.java:171`,
an uncapped `HashMap<String, Bitmap>` building scaled logo copies per tile per size state via `crispLogo()`/
`downscale()`, `HomeView.java:1582-1611`) looked like a strong, plausible culprit — it matched `CLAUDE.md`'s note
about shelf logos getting "a copy at the resting size and one at the focused size," and it was the one cache missed
by the earlier audit (an instance field, not a static class-level map).

**Dead end.** Instrumented it directly (summing `Bitmap.getAllocationByteCount()` over its entries, same as every
other cache) and re-ran the isolated single-focus-move test. `crisp 0KB(0)` the entire time — before and after a
focus move that grew native heap 14.9→25.8 MB. It's never even populated: tracing `drawShelfTile` shows this TV's
apps all have `it.logo == null` (consistent with `LOGOS` also measuring 0 throughout every earlier test — these
apps' icons come from `it.tile`/`TILES` instead, not the `shelfLogo()`/`crispLogo()` path at all), so
`crispLogo()` is never called for this TV's shelf. And the `TILES` rendering path it falls through to
(`roundImage()`, `HomeView.java:1528-1560`) allocates nothing new per frame on Android 12+ — just a `clipPath` +
`drawBitmap` against the already-cached `it.tile`.

**Why this matters more broadly.** Breaking the isolated test down by category is the real finding here: GL mtrack
(Graphics) moved roughly 2-3x more than Native Heap at every step (+16.8 MB vs. +6.9 MB, then +14.7 MB vs.
+5.1 MB). Every cache instrumented in this plan — including this one — only measures `Bitmap.getAllocationByteCount()`,
which is CPU-side pixel data. It cannot see GPU texture/layer memory at all, by construction. So auditing more Java
`Map<String, Bitmap>` fields, however thoroughly, can only ever explain the smaller half of this specific cost. The
larger half is Android's HWUI renderer doing GPU-side work in response to the focus-move redraw — which point this
plan's instrumentation approach has reached its limit, and item 3 (the hardware-layer toggle, the one GPU-side,
page-visibility-tied mechanism already identified in this codebase) is the most credible untested lever left, not
because it's been proven responsible, but because it's the only remaining candidate this methodology can even see.

**Where.** `HomeView.java:171`, `:1582-1596`, `:1599-1611` — left in place, still instrumented (`crisp` in the
`mem:` log line), in case a TV whose apps *do* populate `LOGOS` shows different behavior. Still worth capping per
item 4's reasoning (defense in depth for TVs with more apps / actual `shelfLogo()` usage), just not the cause here.

---

## 8. Dead end (partial): `tiles`' hardware-layer status isn't the shelf-scroll cost either

**Wanted.** With every Bitmap cache ruled out (item 7), test whether `tiles` being `LAYER_TYPE_HARDWARE`
(`HomeView.java:485`) — untested in isolation, unlike the other three layers in item 3 — explains the GPU-side
growth from focusing shelf tiles.

**Dead end (partial).** Temporarily forced `tiles.setLayerType(LAYER_TYPE_NONE, null)` and re-ran the identical
isolated two-focus-move test:

| | `tiles` as hardware layer | `tiles` forced to `NONE` |
|---|---|---|
| GL mtrack after move 1 | +16.8 MB | +19.4 MB |
| GL mtrack after move 2 | +14.7 MB | +5.6 MB |
| Total over 2 moves | +31.5 MB | +25.0 MB |

A ~20% reduction, not elimination. `tiles`' hardware-layer status is a minor contributor at most — the large
majority of the growth happens with or without it. Reverted (`HomeView.java:485` back to its original form).

**What's left.** Superseded by item 9 — turns out `adb shell dumpsys gfxinfo` (not `meminfo`) gives a direct GPU
cache breakdown without needing Android Studio at all. See item 9 for the actual mechanism.

**To decide.** Resolved by item 9.

---

## 9. Found: Skia's `RenderTarget` scratch cache grows one entry per animation frame — not per tile

**Wanted.** After item 8, re-checked whether GPU memory could be measured directly rather than inferred. It can:
`adb shell dumpsys gfxinfo <process>` (distinct from `dumpsys meminfo`) has a `GPU Caches` section Skia's OpenGL
backend reports directly — exact byte counts and entry counts per cache category, no Android Studio needed.

**Measured (2026-10-27), same TV, mid-session:**

```
GPU Caches:
  Image:
    Texture: 1.68 MB (10 entries)
  Scratch:
    Texture: 2.00 MB (2 entries)
    Buffer Object: 78.03 KB (3 entries)
    RenderTarget: 39.00 MB (94 entries)     ← dominant, before 2 more focus moves
Total GPU memory usage: 42.77 MB
```

Two more shelf-tile focus moves later, same session, nothing else touched:

```
RenderTarget: 58.25 MB (171 entries)        ← +19.25 MB, +77 entries, for 2 tile focuses
```

**77 new entries for 2 tile focuses is roughly one new `RenderTarget` per animation frame, not per tile.** A
`RenderTarget` is an offscreen GPU surface Skia renders into before compositing to screen (used for blur effects,
`Canvas.saveLayer()`-style alpha compositing, shadow rendering). Skia's scratch pool only reuses an existing entry
if a new request matches its *exact size*; something in this codebase is requesting a differently-sized offscreen
surface on every single frame of an animation, so the pool can never reuse anything and just keeps growing (up to
its `Max resource usage: 99.53MB (x48)` budget before Skia starts evicting).

**Confirmed (2026-10-27), isolation test.** `drawShelfTile`'s shadow (`shadows.draw(...)`, `HomeView.java:1480`,
inside the `c.scale(s, s)` block at `:1478` where `s` is the continuously-animating focus-grow value) uses a real
`BlurMaskFilter` on this Android 14 TV — confirmed in `Shadows.java:36-38`: `if (!Ui.LEGACY) { c.drawRoundRect(shape,
r, r, paint); return; }`, i.e. the `Shadows` cached-bitmap/nine-slice system (the rest of that file) is Android
8-11-only here; modern Android draws the blur fresh through whatever canvas transform is active. Temporarily
short-circuited the call (`if (false) shadows.draw(...)`) and re-ran the identical two-focus-move `gfxinfo` test:

```
Before (shadow disabled): RenderTarget: 23.94 MB (8 entries)
After 2 focus moves:      RenderTarget: 23.94 MB (8 entries)   — byte-for-byte identical, zero growth
```

Zero growth, exact match, both samples. With the shadow restored (the original code), the same two moves grow
`RenderTarget` by ~19 MB / 77 entries (item 9's earlier measurement). This is conclusive: the shelf tile's deep
shadow, rasterized every animation frame through a continuously-scaling canvas transform, is the mechanism. Each
frame's `s` is a slightly different float, so the blur's effective device-space size is slightly different too, and
Skia's scratch `RenderTarget` pool can only reuse an entry that matches a previous request's *exact* size — so it
essentially never does, and keeps allocating new ones for the length of the animation.

**Where.** `HomeView.java:1462-1521` (`drawShelfTile`), `:1476-1483` (the `c.scale`/shadow block), `Shadows.java:36-38`
(the non-`LEGACY` path).

**How (the real fix, not yet built).** Two directions, in order of how much they preserve the current visual:
1. **Draw the shadow outside the animated scale.** The shadow's local-space shape (`tmp`, built from fixed `bw`/`bh`/
   `r0`) is already constant — only the canvas's `c.scale(s, s)` makes its *device-space* size vary. If the shadow
   is drawn in a separate `c.save()`/`c.restore()` block using a fixed (or discretely-stepped) scale instead of the
   continuously-animating `s`, its rasterized size would repeat across frames and Skia's scratch pool could reuse
   one `RenderTarget` instead of allocating a new one each frame.
2. **Quantize `f`** (round the focus-animation value to a small number of discrete steps, e.g. 20, instead of a
   continuous float) before it's used to compute the shadow's offset/blur — coarser but simpler, and bounds the
   worst case to "20 RenderTargets max," not "one per frame for the length of every animation, forever."

**To decide.** Which of the two — (1) is more correct (truly fixes the mechanism) but needs care that the shadow
still looks right disconnected from the live scale; (2) is a smaller, lower-risk change but only bounds the problem
rather than eliminating it. Needs the same build-and-`gfxinfo`-measure cycle used throughout this investigation to
verify before considering it done.

**Scope — confirmed limited to shelf tiles, not proven elsewhere (2026-10-27).** The same scale+shadow code pattern
also exists at 6 other `shadows.draw` call sites sharing the "grow on focus" shape (`s = 1 + grow * f`): drawer tiles
(`HomeView.java:1843`), sheet/catalog cards — i.e. `drawCard`, used by `drawPoster` for Trakt's Continue
Watching/Watchlist/Shows You Watch rows *and* TMDB's browse-screen catalogs (`:2073`, `:2304`), the picks carousel
(`:3887`, `:4187`), top tabs (`:4090`), and round buttons (`:4268`). Two call sites are exempt by inspection (static
shadow, size doesn't vary with focus): the popover list rows (`:1785`) and the browse-screen stage shadow (`:2506`).

Tested posters directly, same method as the shelf test (identical `gfxinfo` before/after a scroll): scrolled 2
posters in the Watchlist row, `RenderTarget` stayed **byte-for-byte identical — 44.69 MB (18 entries), before and
after.** Only `Image: Texture` grew (+0.29 MB, one new poster decoded, already accounted for by `Catalogs.posters`).
Despite sharing the scale+shadow code shape, posters don't reproduce the bug — explained below. Picks carousel
(`:3887`/`:4187`) untested — Picks are off on this TV, but shares the shelf's per-item-array pattern (`cardFocus[i]`,
not a single shared Spring), so it's a likely-but-unconfirmed second instance.

**Why posters don't reproduce it — confirmed by isolation (2026-10-27).** Found the real, two-part cause by testing
each suspected factor individually and combined:

1. **Per-item vs. shared focus state.** Shelf/drawer tiles use a *per-item* `Spring[]` array
   (`tileFocus[i] = new Spring(...)`, one persistent object per app, `HomeView.java:1312`); catalog/poster rows use a
   *single shared* `Spring` (`catFocus`, `HomeView.java:238`) with every non-targeted item hardcoded to `f = 0`
   (`HomeView.java:2248`). Moving shelf focus animates the *old* tile 1→0 and the *new* tile 0→1 **simultaneously**,
   two differently-sized shadows per frame; posters only ever have one active shadow animating at a time.
2. **Canvas transform vs. explicit bounds.** The shelf draws its shadow inside `c.scale(s, s)` (`HomeView.java:1480`,
   `s` continuously animating) so Skia rasterizes the blur through a varying transform; `drawCard` computes the
   scaled rect explicitly (`cell.set(cx - base.width()*s/2, ...)`, `HomeView.java:2070`) with no canvas transform at
   all, and passes that already-sized rect straight to `shadows.draw`.

**Isolated test, same two-focus-move method throughout, each change reverted before the next:**

| Variant | `RenderTarget` growth (2 moves) | vs. baseline |
|---|---|---|
| Original (both factors present) | +19.0 MB / +77 entries | — |
| Single shared Spring only (factor 1 fixed) | +13.3 MB / +57 entries | ~30% reduction |
| Explicit bounds only (factor 2 fixed) | +12.8 MB / +52 entries | ~32% reduction |
| **Both fixed together** | **+1.3 MB / +6 entries** | **~93% reduction** |

**Neither factor alone is sufficient — it's specifically the combination.** With two tiles simultaneously animating
at independently-varying sizes, switching *how* the size is computed (transform vs. explicit rect) barely helps,
because two different sizes are still requested every frame either way. Only removing the simultaneity *and* using
the already-working explicit-bounds pattern together closes it to near-zero (the residual +1.3 MB / +6 entries is
consistent with normal one-off chrome redraws, not pathological per-frame growth).

**The trade-off.** The clean, fully-explicit-bounds-only fix is low-risk and visually invisible (just changes how a
constant shape's screen size is computed). But it's not sufficient alone — closing the gap needs *also* addressing
the simultaneous dual-animation, and the tested way to do that (collapse to a single shared Spring, matching the
catalog pattern) is a real behavior change: the tile losing focus would stop visibly shrinking back down,
snapping to rest instead. An alternative not yet tested: keep both tiles' independent Springs (preserve the
shrink/grow visual) but round/quantize `s` to a small number of discrete steps before it's used for the shadow's
size, so repeated frames request the same few sizes and Skia's cache can reuse them — bounds the worst case without
changing what's visible. Needs its own isolation test before being trusted.

**Conclusion: fix shelf tiles specifically** (the only call site with positive evidence of the bug, now with a fully
understood, two-part, measured mechanism). The drawer-tile call site (`HomeView.java:1843`) and the picks carousel
(`:3887`/`:4187`) share the per-item-Spring pattern (factor 1) and may share factor 2 too — worth checking with the
same method before assuming they need the same fix, but not yet tested. Top tabs and round buttons are smaller UI
elements with the same pattern; lowest priority to check given their small size even if affected.

**Built (2026-10-27).** Both fixes applied permanently in `drawShelfTile` (`HomeView.java:1464-1482`): `f` is now
`(i == shelfIndex ? tileFocus[i].value : 0)` (only the targeted tile animates; others snap to rest), and the shadow
draws from the already-computed `cell`/`r` instead of inside a `c.scale(s, s)` block. Verified on the TV, same
isolated method: fresh process, baseline `RenderTarget: 25.37 MB (21 entries)`, 5 shelf moves cost only
`+2.56 MB / +11 entries` (vs. the ~`+47 MB / +190 entries` the original code would project for 5 moves). Visual
trade-off confirmed present and accepted: the tile losing focus now snaps to rest instantly instead of shrinking
back smoothly, matching how posters/catalog items already behave.

**Quantization tested and rejected as not worth it.** Tried stepping the shadow's own size (not the tile's visible
grow) to a small number of discrete values, keyed off `f`, on top of the already-applied fix — isolated two-move
test: 16 steps gave `+1.06 MB / +5 entries` (vs. `+1.3 MB / +6` unquantized); a much coarser 4 steps gave
`+0.81 MB / +4 entries`. Marginal, diminishing gains (~2% further reduction even at a visually-chunky 4 steps) for
real complexity and animation-smoothness risk. The residual ~1 MB/transition is close to quantization's own
theoretical floor (entries added ≈ step count per genuinely novel transition) and — importantly — doesn't
accumulate: once a given tile's sizes are cached this session, re-focusing it again is free. Reverted; not applied.

**All Apps grid — found and fixed (2026-10-27).** The All Apps sheet (`drawSheet`, `HomeView.java:2047-2060`) is a
separate code path using `drawCard` directly (already has factor 2 — explicit bounds, same as posters) but with
`gridFocus[i]`, a **per-item `Spring[]` array** (`HomeView.java:270`, `:1988`) — i.e. only factor 1 (simultaneous
dual-animation) looked present, same diagnosis that worked for the shelf. Measured directly, same method, after the
shelf fix was already in place (so reaching All Apps cost almost nothing: `+2.56 MB/+11 entries` for 5 shelf moves):
opening the sheet cost `+11.78 MB/+18 entries` (the open transition), then 2 focus moves *inside* the grid cost a
further `+8.0 MB/+3 entries`.

Applied the identical fix used for the shelf (`i == gridIndex ? gridFocus[i].value : 0`) and re-measured: **zero
change — `+8.03 MB/+3 entries`, same as before the fix.** Isolated further to find out why: disabled `drawCard`'s
shadow draw entirely (`if (false) shadows.draw(...)`) — still `+7.97 MB/+1 entry`, no improvement. Disabled
`roundImage`'s `clipPath` (the other per-frame, continuously-resizing GPU operation in the draw path) — still
`+7.97 MB/+1 entry`. Disabled the **entire per-item loop body** (nothing drawn for any grid item at all) — **still
grew, +7.97 MB/+1 entry.** Separately confirmed it's not just idle drift: two `gfxinfo` samples 4 seconds apart with
zero key presses showed *exactly* the same `RenderTarget` figure, no growth at all.

**The real cause: not drawing at all.** With the per-item loop fully disabled and growth still happening, the only
thing left running on a grid focus move is `HomeView.retargetGrid()`'s `spoken.focusMoved()` call
(`HomeView.java:2046`). `Spoken.focusMoved()`/`send()` (`Spoken.java:67-69,181`, before this fix) only skipped their
work when `AccessibilityManager.isEnabled()` was false — but that flag goes **true the moment any accessibility
service is enabled, not just a screen reader**. `RemoteKeyService` (`remote_key_service.xml:5`,
`accessibilityFeedbackType="feedbackGeneric"`, needed on TCL/Vestel/KTC/Sony TVs purely to read the remote's app
buttons — its `onAccessibilityEvent` is a no-op) is itself an accessibility service, so `isEnabled()` is true on any
such TV whether or not a real screen reader is running. Confirmed by a live A/B test: with
`enabled_accessibility_services` cleared (`adb shell settings delete secure enabled_accessibility_services`), the
identical 2-move test showed **zero `RenderTarget` growth** (`52.63 MB (127 entries)` before and after, byte for
byte); restoring `RemoteKeyService` reproduced the original `+8.03 MB/+3 entries` exactly.

**Built.** `Spoken.java`: replaced both `manager.isEnabled()` checks with a new `spokenActive()` that also requires
at least one enabled service with `AccessibilityServiceInfo.FEEDBACK_SPOKEN` (what a real screen reader like
TalkBack registers as; `RemoteKeyService`'s `feedbackGeneric` doesn't qualify) — re-checked at most once a second
(cached, `spokenCheckedAt`) rather than on every key, since `getEnabledAccessibilityServiceList` is a binder call.
`focusMoved()`'s `TYPE_WINDOW_CONTENT_CHANGED` broadcast (plus its delayed `settled` follow-up) and `send()` now both
gate on this, so a TV with only `RemoteKeyService` enabled — or no accessibility service at all — does none of this
work, while a real screen reader still gets every event it did before.

**Verified on the TV.** Rebuilt, reinstalled, `RemoteKeyService` left enabled (the realistic case this TV actually
runs). Same 2-move test, grid position confirmed by screenshot before and after (`Browser` → `Cx File Explorer`):
`RenderTarget` went from `52.31 MB (63 entries)` to `52.37 MB (65 entries)` — **`+0.06 MB/+2 entries`**, essentially
flat, down from `+8.03 MB/+3 entries`. A further 6 focus moves (down a row, left twice, right three times) added
**zero** additional growth (`5.08 MB (33 entries)` unchanged across all of them, a separate run after Skia's own
budget-triggered scratch-cache compaction had already brought the baseline down). No crash, no regression: the
grid's label/tile rendering, navigation and (unrelated) `TalkBack`-if-present path are all untouched by this change.

**Picks — found the real cause, after a methodology bug invalidated the first attempt (2026-10-27).** First pass
(the "same dead end as All Apps grid" conclusion below) was wrong: the `force-stop → launch → press key` test
sequence relaunches the app, and a fresh launch defaults focus to the **shelf**, not the cards — every isolation
test on `drawLightCard` (shadow, `clipPath`, sheen/rim, the entire card body) had actually been re-testing *shelf*
navigation, not picks. Caught by spot-checking with a screenshot at every step from this point on (confirming which
zone has the focus ring before measuring) rather than trusting the key-press sequence alone.

Once genuinely verified to be in the cards zone (screenshot shows the hero card's focus ring, not the shelf's) and
rotating for real (titles visibly changed between screenshots), two things became clear:
1. `drawLightCard`'s own fix (targeted-only + explicit bounds, still kept, still correct) **never even applies**
   during normal rotation with 3 picks: `cards.size() > 1` is true, so `carousel.value != carousel.target` right
   after `rotateCards()`, which routes drawing through the **sliding-carousel path** (`drawTravelCard`,
   `HomeView.java:3865-3926`) instead, for the length of the slide. `drawLightCard` only draws at rest.
2. Isolated `drawTravelCard` directly (same build-measure-revert cycle, zone verified by screenshot each time):

| Disabled | `RenderTarget` growth (2 rotations) |
|---|---|
| Nothing (baseline) | +6.84 MB / +19 entries |
| Shadow only | +5.75 MB / +4 entries |
| `c.saveLayerAlpha(fadeBounds, ...)` only (forced plain `c.save()`) | +1.34 MB / +16 entries |
| **Both** | **+0.25 MB / +1 entry** |

**Confirmed: the same two-factor "continuously-resizing offscreen GPU operation" pattern as the original shelf bug,
manifesting through different code.** `drawTravelCard`'s shadow (`HomeView.java:3886-3890`) is inside a
`c.scale(s, s)` block exactly like the shelf's was. Its `c.saveLayerAlpha(fadeBounds, ...)`
(`HomeView.java:3882`) is a *second*, independent offscreen-layer source this investigation hadn't considered
anywhere else: `fadeBounds` is built from `cell` (`HomeView.java:3879,3881`), which depends on the continuously-
animating `s`/position every frame as cards slide past center — so the alpha-compositing layer needs a new size
almost every frame too, on top of the shadow's own churn. Unlike the shelf, simultaneity isn't the issue here (one
card's shadow+layer don't stack on another's) — it's that *both* GPU operations individually resize continuously.

**First two fix attempts failed — quantizing the layer's size doesn't work.** Tried snapping `fadeBounds` outward to
a coarse grid (`GRID_PAD`, floor/ceil) so repeated frames would reuse one offscreen-layer size, on the theory that
this was the same "frame N differs from frame N-1" problem as the shelf:
- Explicit-bounds shadow + grid-snapped `fadeBounds` together: **+6.55 MB / +18 entries** — barely better than
  baseline.
- Same, with the shadow's own bounds also grid-snapped: **+9.84 MB / +9 entries** — fewer entries, but *more* bytes
  than baseline (the grid was too coarse, so each surface over-allocated).
- `fadeBounds` quantization alone (shadow reverted to the original canvas-scale form): **+6.84 MB / +19 entries —
  identical to the unfixed baseline.** Zero benefit on its own.

**Why: it's not a frame-to-frame cache-reuse problem.** `drawCarousel` draws up to 5 card slots per frame (`first` to
`last`), each at a different x-position with a different shape (the wide/tall morph) — so on any given frame,
*several differently-sized `saveLayerAlpha` layers are needed simultaneously*, not sequentially. Quantizing one
card's size to a grid doesn't help when its neighbor needs a different bucket in the same frame; this is a different
shape to the shelf bug (which really was one thing changing size frame-over-frame).

**Built and verified (2026-10-27): drop the offscreen layer entirely.** `saveLayerAlpha` was there only to apply one
edge-fade `alpha` to the whole card at once. Replaced it with the same `alpha` applied directly to each paint the
card already uses — `shadowDeep`'s color alpha, `bitmapPaint`'s alpha for the image (combined with the existing
wide/tall crossfade alpha), the placeholder `fill` color's alpha, the rim's color alpha, and the title/meta text
paints' alpha — and threaded a new `alpha` parameter through `Look.focusRing`/`Look.cardCaption` so `MaterialLook`'s
own ring and caption scrim fade too (`ring()`'s amount and the scrim/ink `setAlpha` calls are now multiplied by it).
No more `c.saveLayerAlpha`/`fadeBounds`/grid-snapping in `drawTravelCard`; the shadow is unchanged (still the
original `c.scale(s, s)` form — confirmed not worth touching, see table above). Measured, zone-verified by screenshot
before and after rotating: **+1.42 MB / +17 entries per 2 rotations**, down from the +6.84 MB / +19 entries baseline
(~79% fewer bytes) — closely matching the earlier "`saveLayerAlpha` disabled only" isolation result (+1.34 MB / +16
entries), confirming `saveLayerAlpha` itself (not the shadow) was the dominant cost. Visually checked at rest and
mid-slide on the TV: no artifacts, text/images/fade all look correct.

**All Apps grid's own mystery — since found and fixed, see the grid's own section above.** This methodology bug
didn't touch that investigation (it never involved rotating/sliding cards); the real cause turned out to be
`Spoken.focusMoved()`'s accessibility event broadcast, not anything drawing-related, confirmed by a live A/B test
toggling `enabled_accessibility_services`.

**Posters and catalogs confirmed to need no change.** Continue Watching/Watchlist/Shows You Watch already use a
single shared `catFocus` Spring (`HomeView.java:238`); TMDB's browse-screen catalogs already use a single shared
`browseFocus` Spring (`HomeView.java:250`). Both already match the reference pattern this investigation copied from.

---

## 10. Found and built: `Details.backdrops` was decoding full-resolution, undersampled against its actual display size

**Wanted.** With TMDB catalogs turned on, the TV reported 350+ MB PSS. Investigated live: `Recs.images` (53 MB) and
`Catalogs.posters` (11.8 MB, 40-entry cap) were both still correctly capped, unaffected by catalogs — not the cause.
`gfxinfo`'s `Image: Texture` entry (Skia/HWUI's own GPU texture cache, separate from our Java-level caches) swung
6 MB → 61 MB → 30 MB while scrolling one catalog row: confirmed bounded (it does evict) but with a large working set,
because catalogs expose far more distinct posters than anything else in the app (100+ titles across many genre rows
vs. 13 picks) — this is Android's own rendering-pipeline cache, not something app code controls directly.

**Found.** `Details.backdrops` (the one large image in the browse screen's details card, `Details.java`) was invisible
in the `mem:` debug log entirely (never added to it) and decoded with **no downsampling at all** —
`BitmapFactory.decodeFile(f.getPath())`, no `Options`/`inSampleSize` — despite downloading at TMDB's w1280
(~1280×720) and only ever being drawn into `HomeView.browseStage()`'s ~782×440 window (`height = 440 * k`,
`width = height * 16/9`), under a third the pixel count. Every other image cache in the app
(`Catalogs.posters`, `Recs.images`) already samples down to roughly its display size; this one didn't.

**Built (2026-10-27).** Added the same `inJustDecodeBounds` → `inSampleSize` → final `createScaledBitmap` pattern
`Catalogs.poster()` already uses, targeting `BACKDROP_W`/`BACKDROP_H` (`Details.java`: 440×16/9 and 440, in
1920-wide units, scaled by a `UI_SCALE` computed the same way `Backdrop.java`'s is — from `DisplayMetrics` directly,
since `Details` has no access to `HomeView`'s `k`). Added `Details.debugBackdropBytes()` and wired it into
`HomeView`'s `mem:` log line (as `details %dKB(%d)`) so this cache is never an invisible blind spot again.

**Verified on the TV**: built, installed, opened the browse screen — backdrop image renders correctly, looks sharp
at its actual display size, no crash. `mem:` log confirms: ~1.3 MB per entry now (was ~3.7 MB), matching the expected
~37% pixel-count ratio (782×440 vs. 1280×720) almost exactly. At the cap of 3 entries: ~11 MB → ~4 MB.

**Not the dominant factor in the 350+ MB figure** — the GPU texture cache's much larger working set (tens of MB,
Android-managed, not app-controlled) is still the bigger piece of what changes when catalogs are turned on. This fix
is a real, concrete, low-risk win on top of that, not a fix for the GPU cache growth itself, which isn't something
app code can directly force smaller — it's inherent to how much more content catalogs expose on screen.

---

## TEMP debug code still in the tree — not yet reverted (2026-10-27)

Deliberately kept in: the investigation isn't fully closed (the All Apps grid's GPU growth, flagged at the end of
item 9, is still unexplained) and this instrumentation is what a future session would need to chase it. Each is
marked `// TEMP (memory investigation, remove after)` at its site — grep that exact string to find every one if this
list drifts out of date. Remove all of these together once the All Apps mystery (or whatever comes after it) is
closed, not piecemeal — they're cheap to keep and easy to find as one block.

- **`HomeView.java`**: the `debugMemory` Runnable (~line 431) — logs every bitmap cache's byte count plus native heap
  total every 20s (the `mem:` log line used throughout this whole investigation); `handler.postDelayed(debugMemory, 5_000)`
  (~541, starts it) and `handler.removeCallbacks(debugMemory)` (~571, stops it) are its start/stop hooks.
- **`Backdrop.debugBytes()`** (~line 74): a `Backdrop` instance's total bytes (bitmaps + the raw `int[]` pixel arrays).
- **`Catalogs.debugPosterBytes()`** (~line 517): the `posters` cache's byte/entry count.
- **`Details.debugBackdropBytes()`** (~line 91): the `backdrops` cache's byte/entry count (added in item 10).
- **`HomeApps.debugTileBytes()`/`debugLogoBytes()`** (~line 144): `TILES`/`LOGOS` caches' byte/entry counts.
- **`Recs.debugImageBytes()`** (~line 141): the picks' `images` (`Backdrop`) cache's byte/entry count.
- **`ServiceBadge.debugBytes()`** (~line 39), **`Shadows.debugBytes()`** (~line 28), **`Symbols.debugBytes()`**
  (~line 31): their respective caches' byte/entry counts.

None of this affects memory itself — it's read-only diagnostic logging, safe to ship as-is, just noise to clean up
once there's nothing left to measure with it.
