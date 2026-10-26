# Sky Home Launcher: notes for Claude

A home screen for Google TV and Android TV that replaces the stock one, plus a settings app that switches between
Sky Home Launcher and the TV's original home screen. A plain Android app in Java, built without Gradle by
`build.sh`. Android/Google TV only — no other platform.

Sky Home Launcher is a fork of [Free My TV](https://github.com/simbasjungles/free-my-tv) (see [NOTICE](NOTICE)):
renamed throughout, and with LG/Samsung support and the companion website dropped.

Before you push, run `tools/check.sh`: the Google TV build and `translate.py --check`. Every pull request runs it
too (`.github/workflows/check.yml`).

The person you're helping may not be technical. Keep it short and friendly, say what you're about to do on their TV
before you do it, and let them do the taps on the TV.

## Installing it for someone

1. **Ask** for the TV's IP address and their country, if they didn't say.
2. **Tools:** a JDK 17+, the Android SDK command-line tools (build-tools 34+, platform android-34) and adb. Check
   what's already there (`java -version`, `$ANDROID_HOME`, `adb version`) and install only what's missing.
   - macOS: `brew install --cask temurin android-commandlinetools android-platform-tools`, then
     `sdkmanager "build-tools;34.0.0" "platforms;android-34"`.
   - Linux: OpenJDK 17, Android's command-line tools and adb from the package manager, then the same `sdkmanager` line.
   - Windows: Git Bash (what Claude Code runs there; `build.sh` handles it) or WSL, then as on Linux.
3. **Settings:** run `./build.sh` once; it creates `config.properties`. Fill in `tmdb_region` (two-letter
   country), `show_crypto`, `currency` and `temperature` to match. The city for the weather is found by the app itself.
4. **The TMDB key is theirs and secret.** Ask them to create one (free: themoviedb.org → Settings → API) and paste it
   after `tmdb_key=` in `config.properties` themselves. Don't ask for it in the chat, never print it, never commit
   it. If they paste it in the chat anyway, put it only in `config.properties`.
   Without a key everything works except the picks and the screensaver photos.
   The same goes for the optional `watchmode_key` (free, watchmode.com; makes titles open their own page in the app): theirs, secret, only in `config.properties`.
5. **Build:** `./build.sh` → `SkyHomeLauncher.apk`. Keep `freemytv.keystore`: updates must be signed with the same key.
6. **Install:** `adb connect <IP>:5555`. The TV asks *Allow USB debugging?*: tell them to accept and tick *Always
   allow*. Then `adb install -r SkyHomeLauncher.apk`. On TCL, also `adb shell "appops set com.skyhome.launcher AUTO_START allow || appops set com.skyhome.launcher APP_AUTO_START allow"`
   (some firmware calls the operation `APP_AUTO_START`; this tries both):
   TCL's `TclAppBoot` blocks background jobs by default, so the switch (and the watchdog) never run. The log shows
   `forbid bind service ... ModeJob`. Some TCL firmware has neither (a 2024 MediaTek Google TV, Android 14, has no
   `TclAppBoot`): both names answer `Unknown operation string`, and nothing needs allowing.
   On a Mac, `adb connect` can fail with `No route to host` while the TV answers (`nc -z <IP> 5555` connects): macOS's
   Local Network privacy blocks the adb server that adb starts in the background. Start it from the same terminal
   instead (`adb kill-server; adb nodaemon server &`), or allow the terminal app under System Settings → Privacy &
   Security → Local Network.
7. **Tell them what the switch does** before they make it: the home screen and screensaver change, a short list of
   promo apps is turned off (never uninstalled), and *Original* brings it all back. On TCL, the remote's Netflix,
   YouTube and Prime buttons keep working through a small accessibility service that only reads those buttons
   (*Sky Home Launcher* → *Remote Buttons* picks what each opens); *Original* turns it off.
8. **They switch on the TV:** *Sky Home Launcher* → *Home Screen* → *Sky Home Launcher*. The app uses the TV's own
   ADB on 127.0.0.1 with its own key, so the TV asks once more to allow debugging. Don't switch modes from the computer.
   If the TV's Wi-Fi was saved during its first setup, the switch stops before changing anything and asks them to
   forget the network and connect again in Settings (Network & Internet); then the switch goes through. Saved
   networks out of range don't show in Settings: find the ones with `cname=com.google.android.tungsten.setupwraith`
   in `adb shell dumpsys wifi` (each block starts with its `ID:`), and with their OK remove them with
   `adb shell cmd wifi forget-network <id>`.
9. **Check:** `adb logcat -s SkyHomeLauncher` shows what the switch did.

## Releases

A release build is the generic `SkyHomeLauncher.apk` (no `config.properties` in the repo, so `build.sh` makes a
keyless one: picks off, city from the TV's time zone), signed with the project's own key
(`FREEMYTV_KEYSTORE_BASE64`, a repo secret) and published by hand from the Actions tab
(`.github/workflows/release.yml`, `workflow_dispatch`). The release tag becomes the version shown in Settings
(`SKYHOME_VERSION` in `build.sh`); a plain local build instead shows its own `local-<timestamp>`.

## Adapting it to another TV

Read first, change after. Read-only commands that tell you what the TV has:

```bash
adb shell getprop ro.product.brand; adb shell getprop ro.product.model; adb shell getprop ro.build.version.release
adb shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME
adb shell cmd role get-role-holders android.app.role.HOME
adb shell settings get secure screensaver_components; adb shell settings get secure screensaver_default_component
adb shell pm list packages -s     # system apps (-3 for the ones they installed)
```

Then, in the code:

- `HomeMode.DEBLOAT`: add the brand's promo, demo, store-mode and telemetry apps, one comment per line. Only those.
- `HomeMode.PROTECTED`: add the brand's system pieces: picture, sound, inputs and HDMI-CEC, the tuner and live TV,
  remote and Bluetooth pairing, setup, updates, system UI. When in doubt, protect it. `mayTurnOff` also refuses
  Android's own pieces (`CORE`: with the permission controller or package installer off, a TV may not start again) and
  factory menus (`FACTORY`), and `neededByTv` keeps on, on the TV itself, anything that asks for `TV_INPUT_HARDWARE`
  (the layer under HDMI and the tuner: off, inputs show "No signal") or receives the remote's Input key
  (`fusion.android.tv.demo` does on several MediaTek boards).
- `HomeApps.SHELF`, `LIVE_TV_APPS` and `HIDDEN`: the shelf apps, the brand's live TV app, apps to hide from All Apps.
- Build, install, have them switch on the TV, read `adb logcat -s SkyHomeLauncher`, then have them switch back to
  *Original* once and confirm everything returned.

Any TV that doesn't run Google TV or Android TV: the way is a Google TV streamer on HDMI (Chromecast with Google TV,
Google TV Streamer, onn 4K, NVIDIA Shield). Set Sky Home Launcher up on the streamer.
The onn 4K Streaming Box (Amlogic S905Y4, Google TV 14 tested) ships no promo apps of its own, so `DEBLOAT` has no onn
entries; its system pieces are in `PROTECTED`. USB debugging opens ADB on 5555 there, as the app needs.

**Uninstalled while in Sky Home Launcher mode?** The apps it turned off stay off. Bring them back with
`adb shell pm enable <package>` (the `DEBLOAT` list and the stock home screen), then
`adb shell cmd package set-home-activity <stock home activity>` and
`adb shell settings put secure screensaver_components <stock screensaver>`.

## The code

- Java 8 language level, minSdk 26 (Android 8), targetSdk 33. No Gradle, no libraries: only the Android SDK.
  Android 9 and older have no RenderNode (`Recording` wraps it, with a Picture there) and no `cmd role`. On Android 8 to 11
  (`Ui.LEGACY`) the home screen skips the GPU layers, draws blurred shadows from cached bitmaps (`Shadows`) and
  rounds images with a shader instead of clipPath: their GPUs (a Mi Box 3's Mali-450) choked on those.
  Android 11 runs the screensaver as `ScreensaverAlone`, in the main process (its DreamActivity needs the dream in the
  app's default process, without a home activity); `res/values-v30/bools.xml` picks it, every other version keeps
  `Screensaver` in `:home`. Keep both in step. `ScreensaverAlone` reads `Prefs` from another process than the
  settings screen's, so the screensaver calls `Prefs.reload()` when it starts.
  To check for newer APIs, compile against `platforms/android-26/android.jar`: only AGSL (`RuntimeShader`, guarded
  by a try/catch), `BitmapShader.setFilterMode` (guarded), `Recording.Node` and `Ui.Weighted` (the Android 9 font weight) should come up.
- The home screen is drawn by hand on one Canvas (`HomeView`); the glass and the sky are AGSL shaders (`Shaders`,
  Android 13+, with a fallback for 8 to 12). Keep it at 60 fps: no allocations in `onDraw`, no work on the UI thread.
- Some TVs draw their UI at 1280×720 on a 1080p panel (TCL's 1 GB models: `ro.surface_flinger.max_graphics_width`
  and `_height`, `ro.config.low_ram`). There every pixel and every MB counts: `Backdrop` builds its images at the UI's
  size (and the picks use TMDB's w1280), and the shelf logos have a copy at the resting size and one at the focused
  size, so both draw pixel for pixel.
- `HomeMode` runs every switch as a background job (`ModeJob`) in the main process; the UI is in the `:home`
  process. `Watchdog` goes back to the stock home screen after 3 crashes in 5 minutes.
- On-screen text: `Ui.tr("English")`, translated from `app/assets/i18n/<lang>.json` (keyed by the English; one
  HashMap lookup, English when a language lacks it). Text built at runtime is a pattern: `Ui.trf("{n} min ago", "n", 12)`,
  never concatenated words. A key `"context|Text"` is the same English where a language may need another word
  (`"unit|Automatic"`, `"format|h:mm"` holds a SimpleDateFormat pattern). A count with a word is
  `Ui.plural("plural|one:{n} season|other:{n} seasons", n)`: each catalogue gives one form per CLDR plural category of
  its language (`one`/`few`/`many`/`other` in Russian, Ukrainian, Polish; only `other` in Japanese, Chinese…), and
  ICU's rules pick one. Portuguese, Spanish, German, Russian and Turkish are hand translations: the reference, edited by hand,
  never replaced with machine ones. Dates and numbers use `Ui.locale()`. Text
  drawn every frame is cached and made again when `Ui.generation` changes. After changing text, run
  `python3 tools/translate.py`: it rebuilds `en.json` from the code and lists what each language lacks; translate those
  in every catalogue (or `--fill` with `ANTHROPIC_API_KEY`, which sends only the strings), and keep placeholders as they
  are. Run `python3 tools/translate.py --check` to validate all catalogues without writing files.
  No right-to-left languages yet (the layout isn't mirrored); CJK and Devanagari use the system font.
  Comments in English, short, only where the why isn't obvious.
- Settings come from `config.properties` through the generated `out/gen/com/skyhome/launcher/Config.java` (never
  edit that). What the person changes on the TV (`Prefs`: Home Settings, Shelf, Remote Buttons, Sleep Timer) wins
  over those first values.
- Settings rows that need something this TV lacks show *Unavailable* (or the specific reason) in place of their
  value, and OK does nothing: `Capabilities.missing` says why, from a need a row names (`Capabilities.NEED_ADB`,
  `NEED_OVERLAY`…). *Settings > Sleep Timer* turns the TV off after 5 minutes to 4 hours (needs Sky Home Launcher
  mode on: it presses the remote's Sleep key through the TV's own ADB, the one the switch uses), with the minutes
  left shown over any app (`SleepOverlay`, an overlay service in its own `:sleep` process; Android TV has no screen
  to allow drawing over other apps, so the first time ADB allows it) or, where that's refused, on the home screen
  itself (`HomeView`).
- The weather's place is found from the TV's internet address (`Locator`, ipwho.is then ipapi.co, kept 12 hours) when
  the config names no city (`Prefs.autoLocation`); *Home Settings > City* picks one by hand (a city search) or goes back
  to *Automatic*. Until a place is found there is no forecast (never another city's: the theme uses 6:00 and 18:00),
  and `HomeData` tries again when the network comes back (a `NetworkCallback`). `hide_services` is the first value of *TMDB > Hidden services* (`Prefs.hiddenServices`, by service key).
- Continue Watching also lists Android TV's Watch Next (`WatchNext`, merged in `Catalogs.rebuild()`, newest first, Trakt's
  items win a tie by TMDB id or name). It is a local query that needs `READ_TV_LISTINGS`, a runtime permission the home
  screen asks for once (`HomeActivity`); denied, the row is Trakt's alone. An entry no activity answers (its app is gone or
  turned off) is left out, and turning an app off makes Android drop its entries until it publishes them again. To test
  without a publishing app: `content insert` on `content://android.media.tv/watch_next_program` from the shell (the
  provider records `com.android.shell` as the publisher, and values can't hold a `:`, so write the intent as
  `#Intent;action=...;component=pkg/cls;end` and no art address).
- Watch Next series also join Trakt's *Shows you watch* row (`WatchNext.showItems`, `Catalogs.withWatchShows`), including
  app watchlists (kind 2), with the same 60-day engagement cutoff as Continue Watching. Shows open `EpisodesActivity`;
  TMDB supplies episodes and a connected Trakt account supplies watched marks after resolving the show's Trakt id.
  The pinned row keeps the publisher's resume intent; the matching episode uses it too. A watchlist entry opens its app.
  Both Watch Next rows follow `Prefs.watchNext`. `TmdbEpisodes` keeps the last 30 show lists and displays a saved list before
  refreshing it. Same-name search results use a year when the provider title contains one; unresolved ambiguity leaves the
  show out. `EpisodeList.Episode.resume` marks the resume row and matching episode; keep these outside `EpisodeList.all`.
- *Remote Buttons > Added buttons* lets the person add any button: the screen asks `RemoteKeyService` (the only place that sees
  keys no app gets) through two small files (`remote-learn.req`, `.res`) for the next key that isn't needed to move around
  (`RemoteKeys.needed`), saved as `learn=` lines in `remote-keys.txt` by key code and scan code, and works on any brand. The
  service is on whenever a brand has built-in buttons or a button was added (`RemoteKeys.wanted`); *Original* still turns it off.
  Besides an app, any button (built-in or added) can open Sky Home Launcher's own Settings (`Prefs.FMT_SETTINGS`, handled in
  `RemoteKeys.open`), alongside the existing `TV_SETTINGS`. An added button's name can be changed from its app picker
  (*Rename this button*, an `AlertDialog` with an `EditText`, the same pattern as `askCity`); `Prefs.renameLearned` rewrites its
  `learn=` line without touching its key/scan code or app.
- The remote's app buttons (`RemoteKeys`, `RemoteKeyService`) use each brand's key codes: they run only on TCL,
  Vestel, KTC (NextStar) and Sony TVs and Formovie projectors, the service is on only in Sky Home Launcher mode, and
  *Original* takes it out of the enabled accessibility services. The system waits for the service on every key, so
  it lives alone in the `:keys` process: what it reads is in `remote-keys.txt` (`Prefs.keyApp`), never in
  SharedPreferences, and `onKeyEvent` does no heavy work.
  Vestel (F&U and other store brands; MediaTek, Android TV 14 tested) maps its Netflix button to `KEYCODE_STEM_1`, a
  global key (`dumpsys window policy` lists them) that the key filter sees first; its YouTube, Prime Video and Google
  Play buttons stay with the system. The stock lists needed no Vestel entries.
  KTC (NextStar; MediaTek, Google TV 14 tested) maps its Tabii button to `KEYCODE_BUTTON_8`, also a global key, which
  KTC's own hot-key receiver would open Tabii with; its Netflix, YouTube and Prime Video buttons stay with the system.
  Sony (BRAVIA VH22, Android TV 12 tested) sends `KEYCODE_BUTTON_9` for its BRAVIA CORE, Disney+, Prime Video,
  YouTube and Crunchyroll buttons, `KEYCODE_BUTTON_4` for Netflix and `KEYCODE_BUTTON_8` for the Google Play button of
  older remotes (BRAVIA 4K UR3, Android 10 tested), global keys for `com.sony.dtv.sonyvendorservice`;
  only the scan code tells them apart, so `Prefs` keys them as `SONY_KEY` + scan code. "Sony default" (empty) leaves
  the key to the TV. Sony ships its own Apple TV build, `com.apple.atve.sony.appletv`.
  Xiaomi projectors made by Formovie (Fengmi; L1, MediaTek, Google TV 14 tested) send the same `KEYCODE_STEM_1` for
  Netflix (`RemoteKeys.STEM_NETFLIX`); their YouTube button goes to MediaTek's `GlobalKeyReceiver` and stays with the system.
- Stremio: when it isn't on the TV, Settings > Stremio says *Install* (`StremioInstall`): it reads Stremio's download list
  (`api.strem.io/downloads/config`, entry `android_tv`), builds the link from the tag, version and the TV's processor (never a
  fixed address), downloads it, checks the file is `com.stremio.one` and hands it to Android's `PackageInstaller`, which asks the
  person to confirm. Its receiver runs in `:home` (the statics are shared with the settings screen).
- Trailers on the catalog screen play in YouTube's embedded player (`TrailerPlayer`), in a window of its own over the video area
  (this TV decodes video onto a plane under the app's window); the YouTube app opens one only after a long OK press, never on
  autoplay.
- Inputs are top buttons, not a drawer row: `HomeApps.TOP_AV` and `TOP_HDMI` (kind `INPUT_GROUP`) list the TV's inputs of that kind
  (`HomeApps.inputGroup`: HDMI ports, or composite/component/S-Video/SCART); one opens at once, several open `HomeView`'s popover
  (`popLayer`, rows under the buttons, Up/Down/OK/Back). A button is left out on a TV without that input.
- Settings > Integrations: when the TMDB key, Watchmode key or Trakt Client ID is missing, OK there starts `IntegrationsServer`
  (a small raw-socket HTTP server, no library, up only while the screen is open) and shows its LAN address as text and a QR code
  (`drawQr`, generalized from Trakt's device-login QR). The one page it serves never reads a key back, only whether one is set
  (shown as a fixed placeholder, cleared on focus); saving only overwrites a field that was actually typed into. The three keys
  live in `Prefs` (`tmdbKey`, `watchmodeKey`, `traktClientId`), the same one-way override over `config.properties` as every other
  TV-settable value.
- The version shown in Settings (`SettingsActivity.versionName`) comes from the apk's own `versionName`, set at
  build time by `build.sh` (`aapt2 link --version-name`, not the manifest's static one): `SKYHOME_VERSION` wins when
  set (a release's tag, set by `release.yml`), else `local-<timestamp>` (every plain local build).
- After any change, `./build.sh` must end with `Built SkyHomeLauncher.apk`.

## Always

- Google TV's setup app (`setupwraith`) goes off before the stock home screen and comes back after it, everywhere
  (`targets` order, `applyOriginal`, `revertHomeNow`, `verifyNow`): with the home screen off and it on, its
  RecoveryActivity takes Home, and a restart there factory-reset a Xiaomi. If it can't go off, the home screen stays on.
  The Kalley 43G200 (`R3_GTV`) turns it back on at every start and boot-looped without its home screen: the switch
  refuses there.
- Never turn off an app that saved a Wi-Fi network: Android deletes its networks and Original can't bring them
  back (`wifiOwners` in `HomeMode`). When the Wi-Fi service doesn't answer, the switch stops on Wi-Fi, and the boot
  check leaves the setup app and the stock home screen for later.
- Never commit `config.properties`, `*.keystore` or any key.
- Never uninstall or clear the data of system apps; only `pm disable-user --user 0`, through `HomeMode`, so
  *Original* can bring it back.
- Don't change the TV's picture, sound, network or account settings.
- Keep the way back working: the *Original* mode and the watchdog.
