# Plan: test the picks, catalogs, memory work and home pages on Android 8 to 11 (and what Android 14 couldn't test)

PR #25 (`2e6668f`) was tested only on Android 14 (Skyworth 4K Google TV Stick, 1080p UI). Android 8 to 11 take other
paths in this app (`Ui.LEGACY`, `Recording` as a Picture on 9 and older, `ScreensaverAlone` on 11) and their GPUs and
memory are weaker. This plan lists what could break there, which devices to use, and what to check on each, in order.

The home screen as pages (PR #27, and step 8 of `plan-home-pages.md`: page 1 as `HomeTopPage`, `HomeShelf` and the
picks sections) was also tested only on that stick, in all three picks styles. What it couldn't test is in
"The home pages" below: TalkBack, the inputs menu (the stick has no inputs), and the older Android versions.

Status (2026-11-01): not started.

## What could break, and why

1. **GPU bitmaps (`Bitmap.Config.HARDWARE`, Android 8+).** Everything now decodes straight into GPU memory
   (`Backdrop.load`, `Catalogs.hardware`, `Details.backdrop`, `Recs.loadPoster`) or copies into it (`Backdrop.gpu`).
   - A GPU bitmap drawn on a **software canvas** throws ("Software rendering doesn't support hardware bitmaps"). On
     Android 8 to 11 the drawer cards are drawn into a software bitmap (`HomeView.Snapshot`), and Android 9 and older
     record into a `Picture` (`Recording.Pic`). Neither should touch a poster or a picks image today; check nothing
     does (a crash with that message is the sign).
   - `Ui.LEGACY` rounds images with a `BitmapShader` instead of `clipPath` (`HomeView.roundImage`): GPU bitmaps in a
     shader on Android 8.0/8.1 are the least tried path here.
   - If a decode into GPU memory returns null on some driver, `Backdrop.load` / `Catalogs.hardware` treat the file as
     unreadable, **delete it and download it again**. On such a TV that would loop: the same image downloaded again
     every time it's shown. The log shows it (below).
2. **720p UIs** (TCL's 1 GB models: `ro.surface_flinger.max_graphics_width` 1280): `Backdrop.W` is 1280, so
   Spotlight's photo is the photo itself (no `.spot.jpg`), cards and posters are 2/3 the size
   (`Catalogs.rowPosterWidth` 150). Different file names and sizes from the 1080p test.
3. **Android 11's screensaver (`ScreensaverAlone`)** runs in the main process, with its own `Recs`: two processes can
   build the same image into `cacheDir/picks` at once (each file is written to a `.part` and renamed, so the worst
   should be one build wasted). It reads the saved 1920×1080 photo, not TMDB's original.
4. **Memory trims.** Android 8 to 13 deliver `TRIM_MEMORY_RUNNING_*` levels while visible, which Android 14 no
   longer sends: with memory short, `Recs.trim` drops the neighbours of the focus while the screen is in use (they
   come back from disk). Check nothing on screen blinks.
5. **Weak GPUs** (a Mi Box 3's Mali-450): the 150 ms rest before TMDB is asked, newest-first poster loading, and
   reading images from disk instead of rebuilding them should all help; check frame times don't get worse.

## Devices

At least one of each row; real TVs over emulators (the emulator's GPU isn't a TV's).

| Android | Why | Examples |
|---|---|---|
| 8 / 8.1 | oldest supported; GPU bitmaps' first versions | Mi Box 3, older Android TV boxes |
| 9 | `Recording` is a `Picture`; no RenderNode | Mi Box S, TCL Android TV (often a 720p UI, 1 GB) |
| 10 | first RenderNode version | Sony BRAVIA 4K UR3 (Android 10), Chromecast with Google TV (early builds) |
| 11 | `ScreensaverAlone`; `Ui.LEGACY`'s last version | Chromecast with Google TV (Android 11 build), Mi Box S updated |

If none is at hand: Android TV emulator images (API 26 to 30) for crashes and the code paths only, not for memory
or frame times.

## Before installing

1. Compile against Android 8's API to catch newer calls: build with `platforms/android-26/android.jar` (see
   `CLAUDE.md`, "The code"). Only the known ones should come up (AGSL, `BitmapShader.setFilterMode`,
   `Recording.Node`, `Ui.Weighted`); anything from this PR is a bug to fix first.
2. Read the TV: `getprop ro.build.version.release`, `ro.product.model`, `ro.surface_flinger.max_graphics_width`,
   `ro.config.low_ram`, `wm size`. Write them in the results below.
3. Install with `adb install -r SkyHomeLauncher.apk`. **Never `am force-stop`**: it disconnects the remote buttons'
   accessibility service until the TV restarts. To restart `:home`, install again.

## Checks (on each device)

Log: `adb logcat -s SkyHomeLauncher AndroidRuntime:E`. Crashes also: `adb logcat -b crash`.

1. **First start (empty cache).** Picks in Cards style: each image logs `picks image <id>: downloaded and built in
   N ms` once. Note the times (Android 14 1080p: 0.8–2.6 s).
2. **Start with the cache** (install again, press Home): no `downloaded and built` or `rebuilt` lines; the cards and
   background show at once, nothing fades in. **The same `<id>` built twice is the delete-and-download loop
   (risk 1).**
3. **Spotlight** (Settings > TMDB > Picks carousel; put it back as it was afterwards): the photo, the soft blur on the
   left and bottom, the poster row. Five quick Rights: the photo of the title the focus stops on within about a
   second, no crash.
4. **Pages 3 and 4:** ↓ from the shelf to page 3 (Continue Watching, Watchlist), on to page 4 (the TMDB rows), ten
   quick Rights: posters drawn (rounded corners: risk 1's `BitmapShader` path), the badge, the details and picture
   after the focus stops. `Software rendering doesn't support hardware bitmaps` anywhere is a bug.
5. **Back to page 1** (Back): `catalog posters dropped (back on the first page)` 10 s later; ↓ again: posters back
   from disk without a visible wait.
6. **Hidden:** open the TV's Settings for 15 s: `memory trimmed (level 20)`; back Home: cards there at once.
   `dumpsys meminfo com.skyhome.launcher:home` visible and hidden (Total, Graphics, Java Heap).
7. **Screensaver:** Settings > Screensaver (preview), or
   `am start -n com.skyhome.launcher/.SettingsActivity --ez screensaver true`: photos from the picks, the next one
   after 30 s. On Android 11, also let the real screensaver start (`ScreensaverAlone`) and check the log of the main
   process.
8. **Frames:** `dumpsys gfxinfo com.skyhome.launcher:home reset`, ten moves on the cards and on a catalog row, then
   `dumpsys gfxinfo` (median, 90th percentile, "Slow bitmap uploads"). If a `main` build is at hand, the same moves
   on it for comparison (on Android 14 they were the same).
9. **720p UI only:** no `.spot.jpg` needed (Spotlight logs no `rebuilt` after the first build); posters and cards look
   sharp at their size.
10. **Remote buttons** still work after all of the above (brands with built-in buttons: TCL, Sony, Vestel, KTC).
11. **The home pages** (next section) on this device.

## The home pages (PR #27 and step 8)

Tested on Android 14 (the stick): page 1 in Spotlight, Cards and Off, moving around it, moving apps, All Apps, pages
2 to 4, Back, frame times (90th percentile 109 ms on the Spotlight walk). Still to test:

1. **Android 8 compile check** for the new classes (`HomeTopPage`, `HomeShelf`, `PicksSection`, `PicksOff`,
   `PicksCards`, `PicksSpotlight`, `Node`, `Stack`, `HomePager`…): with `platforms/android-26/android.jar`, nothing new
   beyond the known calls (Before installing, 1).
2. **On Android 8 to 11 (`Ui.LEGACY`)**, in each picks style: page 1 looks as on Android 14 (no GPU layers there, so
   every layer draws straight to the screen); ↓ through pages 2 to 4 and Back; `LayoutCheck` logs nothing
   (`layout …` lines); frame times while paging are no worse than a `main` build from before PR #27.
3. **The inputs menu** (needs a TV with built-in inputs, e.g. a TCL; a stick or a box has none, so the HDMI and AV
   buttons aren't shown). With Settings > Top Buttons showing *HDMI input* (and *AV input* if the TV has one):
   - one input of the kind: OK opens it at once;
   - several: OK opens the menu under the button, the input with a device on it focused; Up/Down move, OK opens,
     Back closes; the focused button's name hides while the menu is open and comes back after;
   - an input with nothing plugged in is dimmed.
   Step 8 changed what OK on a button runs (`HomeTopPage.activate` → `HomeView.openItem` → `openInputs`) and who
   redraws the buttons (`HomeTopPage.invalidateButtons`); the menu itself (`HomePopover`) didn't change.
4. **TalkBack** (with the person at the TV: it's a TV setting, it speaks, and turning it on edits
   `enabled_accessibility_services`, which also holds the remote buttons' service; turn it on in the TV's Settings >
   Accessibility, never from the computer, and off again after). Step 8 moved page 1's items into `HomeTopPage`
   (`a11yItems`, `a11yText`, `a11yRect`, `a11yFocus`):
   - every top button, tab (Cards only), pick and shelf app is read with its name; Spotlight reads its one focused
     poster (before, three "cards" with no place on screen), Spotlight and Off offer no tabs (before, hidden tabs);
   - moving with TalkBack moves the remote's focus there, on page 1 from any page; OK opens the item;
   - holding OK on a shelf app, then ← →: "Moving <app>, position i of n";
   - All Apps: each app with "i of n", TalkBack's long press puts it on the shelf or takes it off;
   - page 2: ↓ onto the quotes card reads each coin with its price and change; OK does nothing there.

## If something breaks

- **Crash with "hardware bitmaps"** on a software canvas: find the canvas (`Snapshot`, `Recording.Pic`, a `new
  Canvas(bitmap)`) and draw a software copy there, or keep that image software on `Ui.LEGACY`.
- **Decode into GPU memory returns null** (the loop in check 2): in `Backdrop.load` / `Catalogs.hardware` /
  `Details.backdrop`, fall back to a software decode and `Backdrop.gpu()` (which already keeps the software bitmap
  when the copy fails) instead of deleting the file.
- **Blinking while visible** (check 4 of the risks): `Recs.trim` keeps the neighbours when the home screen is
  visible, and drops them only when it's hidden (`TRIM_MEMORY_UI_HIDDEN` and above).
- Each fix: `./build.sh`, `py tools/translate.py --check`, then the checks again on that device and once on Android 14.

## Results

| Device | Android | UI | First build (ms) | Cards visible / hidden (MB) | Spotlight visible (MB) | Frames (median / 90th) | Problems |
|---|---|---|---|---|---|---|---|
| Skyworth 4K Google TV Stick | 14 | 1080p | 780–2600 | 148 / 126 | 114–123 | 38 / 65 ms | none (reference) |
