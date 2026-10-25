<div align="center">

<img src="docs/assets/icon-512.png" width="96" alt="">

# Free My TV

**A fast, ad-free home screen for Google TV, LG and Samsung TVs.**

Trending picks from your own apps, the weather at a glance, and a look that follows the sunset.<br>
Open source. Built with Claude Code. One button to undo.

[**freemytv.io**](https://freemytv.io) · [Install](#install) · [LG TVs](#lg-tvs-webos) · [Samsung TVs](#samsung-tvs-tizen) · [Any TV](#any-tv) · [Make it yours](#make-it-yours)

<img src=".github/preview.webp" width="880" alt="The Free My TV home screen: a glass shelf of apps, trending picks, and a drawer with the weather and crypto prices">

</div>

## What you get

- **No ads.** No sponsored rows, banners or autoplaying promos. Not one.
- **Your apps, front and center.** The streaming apps you actually use, on a glass shelf. Pick them and their order right on the TV.
- **Trending, not sponsored.** Three fresh picks every hour from what's trending this week and the latest releases, only from apps you already have. Movies and Shows get their own tabs.
- **Weather and markets, one press away.** The hour-by-hour forecast, sunrise and sunset, and live crypto prices.
- **Light by day, dark by night.** It follows your city's real sunset, the way your phone does.
- **A screensaver to match.** Full-screen photos of the picks with a big glass clock.
- **Fast.** 60 fps, every frame in 16.6 ms, on the TV's own chip. The home screen uses 96 MB of memory instead of 242 MB (−60%).<br><sub>Measured on a 98″ TCL Google TV running Android 14.</sub>
- **Set up on the TV.** Language, your city, temperature, theme, the weather and prices cards, which coins, and the apps on the shelf, in Free My TV's own settings.
- **Safe.** No root. Nothing is uninstalled. One button brings back the factory home screen.

## Install

About ten minutes. You need a Google TV or Android TV (Android 8 or newer) and a computer on the same Wi-Fi.

### The easy way: an AI assistant

1. **On the TV**, turn on developer mode: Settings → System → About → press *Android TV OS build* 7 times. Then Settings → System → Developer options → turn on **USB debugging**. Write down the TV's IP address (Settings → Network & Internet).
2. **On your computer**, open an AI coding assistant ([Claude Code](https://claude.com/claude-code), Codex or Antigravity) and say:
   > Install Free My TV (github.com/simbasjungles/free-my-tv) on my TV at 192.168.1.50. I live in Lisbon.

   It downloads the code, installs the tools it needs, builds the app and puts it on your TV, and asks before anything important. Swap in your TV's IP and your city.
3. **On the TV**, open *Free My TV* → *Home Screen* → *Free My TV*. That's when the TV asks *Allow USB debugging?* for Free My TV: tick *Always allow* and accept.

### The manual way

1. **On the TV**, do step 1 above.
2. **Tools:** a JDK (17+), the Android SDK command-line tools (build-tools 34, platform android-34) and adb. On a Mac:
   ```bash
   brew install --cask temurin android-commandlinetools android-platform-tools
   sdkmanager "build-tools;34.0.0" "platforms;android-34"
   ```
   On Linux, get OpenJDK 17, Android's command-line tools and adb from your package manager, then run the same `sdkmanager` line. On Windows, use Git Bash (which Claude Code uses there) or WSL.
3. **Build:**
   ```bash
   ./build.sh
   ```
   The first run creates `config.properties`. Set your [TMDB key](#the-tmdb-key) there, then run it again.
4. **Install:**
   ```bash
   adb connect 192.168.1.50:5555
   adb install -r FreeMyTV.apk
   ```
   On TCL TVs, also let it run in the background (TCL blocks it by default, and the switch never starts):
   ```bash
   adb shell "appops set io.freemytv AUTO_START allow || appops set io.freemytv APP_AUTO_START allow"
   ```
5. **On the TV**, open *Free My TV* → *Home Screen* → *Free My TV*. The TV asks *Allow USB debugging?* once more, now for Free My TV itself: tick *Always allow* and accept.

## LG TVs (webOS)

The same home screen as an LG app: the picks, the glass shelf with your apps, the drawer with inputs, weather and prices, day and night, Home Settings and the shelf editor. It installs through LG's own Developer Mode, with nothing rooted or changed on the TV. LG's home screen stays where it is, and Free My TV is one press of a number key away.

You need an LG TV (webOS 3 or newer) and a computer on the same Wi-Fi, with Python 3.

1. **On the TV**, open the LG Content Store and install **Developer Mode**. Sign in with a free [LG developer account](https://webostv.developer.lge.com), turn on **Dev Mode Status** (the TV restarts), then turn on **Key Server**. Write down the IP address and the passphrase it shows.
2. **On your computer**, get the code and build. `config.properties` works as for Google TV (your [TMDB key](#the-tmdb-key)):
   ```bash
   git clone https://github.com/simbasjungles/free-my-tv && cd free-my-tv
   python3 webos/build.py
   ```
3. **Install.** The first time, it asks for the TV's passphrase; updates don't:
   ```bash
   python3 webos/install.py 192.168.1.50
   ```
4. **On the TV**, while Free My TV is open, hold a number key on the remote. From then on, that key opens it from anywhere.

With an AI assistant, say *"Install Free My TV (github.com/simbasjungles/free-my-tv) on my LG TV at 192.168.1.50. I live in Lisbon."* It builds the app and hands you the install command, so you type the passphrase yourself.

Developer Mode lasts about 40 days: open the Developer Mode app now and then and press **EXTEND**. To remove Free My TV, run `python3 webos/install.py 192.168.1.50 --uninstall`.

## Samsung TVs (Tizen)

The same home screen as a Samsung app, with everything the LG one has: the picks, the shelf, the drawer with the inputs, the weather and the prices, day and night, and its own settings. It installs through Samsung's own Developer Mode, with nothing rooted or changed on the TV, and Samsung's home screen stays where it is.

You need a Samsung TV from 2017 or newer (Tizen 3+) and a computer on the same Wi-Fi.

1. **On the TV**, open **Apps** and type `1 2 3 4 5` on the remote. Turn **Developer mode** on, type your computer's IP address in **Host PC IP**, and restart the TV.
2. **On your computer**, download [TizenBrew Installer](https://github.com/reisxd/TizenBrewInstaller/releases/latest), the community's installer for Samsung TVs, open it and connect to your TV.
3. **Install from GitHub:** type `simbasjungles/free-my-tv`. On 2023 and newer TVs it asks you to sign in to your Samsung account once, to sign the app for your TV.
4. **Straight to it:** in the TV's settings, under Start Screen Options, turn on **Autorun Last App**: the TV turns on into Free My TV.

This build takes your city from the TV's time zone. For your exact city and the trending picks, build it with your own `config.properties` and [TMDB key](#the-tmdb-key): open the folder in Claude Code and say *"Install Free My TV on my Samsung TV at 192.168.1.50."* It sets up Samsung's tools, and on 2023 and newer TVs you sign in to your Samsung account in the browser to make the certificate. Your signed build installs with `python3 tizen/install.py 192.168.1.50`, which needs only Python: no Tizen Studio, and no Rosetta on Apple Silicon Macs.

## Settings

The first values come from `config.properties`, created by the first build (Google TV, LG and Samsung use the same file). Most of them can also be changed on the TV, in *Free My TV* → *Home Settings*, and the TV's choice wins:

| Key | What it does | Default |
|---|---|---|
| `tmdb_key` | Your free TMDB key, for the picks | empty |
| `tmdb_region` | Your country (two letters): which titles are on which app | `US` |
| `watchmode_key` | Optional, free [Watchmode](https://api.watchmode.com/) key: a title (pick, catalog or Trakt card) opens its page in its app | empty |
| `trakt_client_id` | Optional, your free [Trakt](https://trakt.tv) Client ID: *Continue Watching* and *Watchlist* rows under the home screen's cards (see [Your Trakt lists](#your-trakt-lists-optional)) | empty |
| `hide_animation` | Leave cartoons and anime out of the picks | `false` |
| `flatrate_only` | `false`: picks and catalogs may be any popular title, not only ones on a subscription service in your region | `true` |
| `tmdb_languages` | Only titles whose original language is one of these (`en,hi,ko`), for the picks and the catalogs; empty for any. Free My TV → TMDB → Languages changes it on the TV | empty |
| `watch_next` | *Continue Watching* also lists what the TV's other apps say you were watching (Android's Watch Next; read on the TV, nothing leaves it), without repeating what Trakt lists. The TV asks once to allow reading it; *Home Settings* turns it on or off | `true` |
| `include_rent_buy` | `true`: picks and catalogs also include titles you can only rent or buy (with `flatrate_only` on) | `false` |
| `catalogs` | Catalogs below the drawer's weather and quotes (`trending`, `popular_movies`, `popular_shows`, `top_rated`, `now_playing`, `upcoming`, `hindi`); `none` for no rows | `trending,popular_movies,popular_shows,hindi,top_rated` |
| `language` | `auto` (follows the TV), or a language with a [translation](#translations) (`en`, `pt`, `es`, `de`, `ru`, `fr`, `ja`…) | `auto` |
| `show_crypto` | Show Binance crypto prices on home and screensaver | `true` |
| `currency` | Crypto prices in `USD`, `EUR`, `BRL`… | `USD` |
| `temperature` | `C`, `F` or `auto` | `auto` |
| `look` | The look the home screen starts with: `material3-expressive` or `glass` (Liquid Glass). *Home Settings* on the TV changes it | `material3-expressive` |
| `theme` | `auto` (dark from sunset to sunrise), `dark` or `light`. Also changeable on the TV | `auto` |
| `disable_assistant` | Also turn off Google Assistant | `false` |
| `hide_services` | Streaming services to leave out of the picks even when their app is on the TV (`netflix`, `prime`, `disney`…), comma separated; for apps the TV ships that you don't subscribe to. Google TV only; the first value: *TMDB* → *Hidden services* sets them on the TV | empty |
| `pick_count` | How many picks the home screen rotates through, 3 to 12. At 3 it shows this hour's three; with more, left and right slide through them in a circle | 3 |
| `pick_rotate_seconds` | If above 0, the picks move by themselves this often while focus is not on them | 0 |
| `hide_apps` | Apps to leave out of the shelf, All Apps and the picks, by id or name (part of it), comma separated; for apps the TV ships that can't be uninstalled, like Samsung's built-in Netflix. LG and Samsung only | empty |

Changed something? Run `./build.sh` and install again. Keep `freemytv.keystore`: updates are signed with it.

Google TV only, on the TV: *Free My TV* → *TMDB* → *Picks carousel* turns the picks (the cards under the tabs) on or off. On a TV short of memory (a 720p UI, a low-RAM flag or under 1.5 GB) they start off, and so do the trailers played inside the home screen and the catalog rows (unless you wrote your own `catalogs=`), because those hold the most memory; each can be turned on there, and a TV with more memory starts with them on. Off, the tabs are gone and the home screen shows the clock, the weather and the shelf; nothing is fetched for the picks, and the catalogs and Trakt rows stay.

Google TV only: the inputs are round buttons now, not a row in the drawer. *Free My TV* → *Top Buttons* can hold *Live TV*, *AV input* and *HDMI input* (a button shows only when the TV has that input). One input opens at once; with several HDMI inputs, OK opens a small list under the button to choose from (a dimmed one has no device on it). An old *HDMI Inputs* button becomes *HDMI input*.

### The TMDB key

Free, and it takes two minutes: create an account at [themoviedb.org](https://www.themoviedb.org/signup), then open [Settings → API](https://www.themoviedb.org/settings/api) and request a developer key. Paste the *API Read Access Token* (or the *API Key*) after `tmdb_key=`. It's your key: `config.properties` stays out of git.

### Opening a title

A card in the catalogs, the Trakt rows or the picks opens **the app of the service that streams the title in your region** (`tmdb_region`): a Netflix title in Netflix, and so on. When that app isn't on the TV it opens the app's **Play Store page** (on a TV where Free My TV turned the app off, the page has an *Enable* button); when the title is on no service there, it says "This title is unavailable in your region." A title on several services goes by price first (subscription, then free, then rent or buy), and the card shows that service; when its app isn't on the TV it goes to Stremio if you chose that, else to the Play Store, rather than to a pricier copy on an app you have. Among services of the same price, one you have comes first. While the answer comes (one TMDB request, kept for a day) the card shows three blinking dots, and Back or moving away cancels it; if it takes over 3 seconds it says it couldn't check.

Free My TV → *Stremio* (when Stremio is installed) says which services open in Stremio on the title's page instead of in their own app, and *Use Stremio as fallback OTT* sends it the titles whose app isn't on the TV and the ones that stream nowhere we can check; when Stremio isn't on the TV, that row says *Install*: OK downloads Stremio's Android TV app from Stremio's own site (the link is made from Stremio's download list at api.strem.io, nothing fixed in the app) and Android asks you to confirm (the first time also to allow installs from Free My TV). The *Torrentio addon* row there adds the Torrentio addon to Stremio with the languages and the biggest size you choose (it starts with the TMDB section's languages and 5 GB, and remembers your last choice): Torrentio is a third-party addon that finds streams for Stremio, and Free My TV only hands Stremio its install link; Stremio asks you to confirm. The episodes screen opens the episode in Stremio, and any other app on the show. Where a title streams comes from TMDB, so it needs the TMDB key. `service_map` is gone: if you had it in `config.properties`, set the Stremio screen once on the TV instead.

### Opening the title's page in its app (optional)

With a free [Watchmode](https://api.watchmode.com/) key (`watchmode_key=` in `config.properties`), a title opens its own page in the streaming app instead of the app's home, whether it comes from the picks, a catalog or your Trakt rows (not the TV's own Watch Next cards, which resume in their own app). The free plan (2,500 credits a month) is enough: each title costs two requests the first time you open it and is remembered after that. It works where the app takes the title's web address (tested: Prime Video and JioHotstar); where it doesn't, the title just opens the app, as without a key.

### Your Trakt lists (optional)

Free Trakt account, Android and Google TV only. Three rows appear first under the home screen's cards: *Continue Watching* (what you stopped part-way and the next episode of the shows you follow, with a bar on what's part-way), *Shows you watch* (the one you watched most recently first; one you saw once, over a year ago, was a try and is left out) and *Watchlist*.

1. Create an app at [app.trakt.tv/settings/apps/api/new](https://app.trakt.tv/settings/apps/api/new). The form asks for a redirect URI that Free My TV never uses: put `urn:ietf:wg:oauth:2.0:oob`. There is no secret; copy the *Client ID*.
2. Paste it after `trakt_client_id=` in `config.properties`, run `./build.sh` and install again.
3. On the TV: *Free My TV* → *Trakt* → *Connect*. It shows a short code and a QR code: scan the QR code with your phone's camera (the code is already in the link), or open the address and enter the code. No password is typed on the TV, and Free My TV only reads your lists.

OK on a show in *Shows you watch* opens its episodes: every episode with its number and air date, watched ones marked, and a bar of four buttons on top: **sort** (number or date, newest or oldest first), **season**, **date** (last 7 or 30 days, this year, a year, not aired yet) and **watched** (all, unwatched, watched). The filters combine, *Clear filters* resets them, and the sort is remembered. It opens on the next episode you haven't seen; ← → jump a season, Channel + and − scroll a page, Back goes up to the buttons and then out; OK opens the episode in Stremio.

Other titles open as described in [Opening a title](#opening-a-title). *Trakt* → *Disconnect* forgets the sign-in. The rows follow what Trakt has: Stremio, for one, adds to your history only when you connect Trakt in its settings.

### Setting these keys from your phone (no rebuild)

Google TV only. If the TMDB key, Watchmode key or Trakt Client ID is missing, *Free My TV* → *Integrations* starts a small web
address on the TV itself (shown as text and a QR code) and stops it again when you leave the screen. Open that address on your
phone (same Wi-Fi as the TV) to paste in whichever of the three you have — each field links to where to get it — without typing
anything on the remote or touching `config.properties`. A key already set shows as dots; leaving a field as it is keeps it.

## Going back

On the TV: *Free My TV* → *Home Screen* → *Original*. The factory home screen, screensaver and every app come back exactly as they were. If the new home screen ever crashes three times in five minutes, it switches back by itself.

To remove it for good, switch to *Original* first, then uninstall *Free My TV* (Settings → Apps). Uninstalled it while it was on? Install it again and pick *Original*: the list of what it turned off is kept in the TV's settings, so everything comes back.

## What it changes

- It becomes the home screen and the screensaver.
- It turns off, without uninstalling:
  - the stock home screen and screensaver;
  - Google TV's recovery home, Play Games and YouTube Music;
  - on TCL TVs, the TCL Channel, messages, store demo, hotel menu, manuals, mirroring and logging apps;
  - on Philco TVs, AI PONT (promo channels and app downloads) and the manual;
  - on Multilaser TVs, Anoki's screen-content recognition (ACR), the store demo and the manual;
  - on KTC-made TVs (NextStar), Anoki's screen-content recognition (ACR), the store demo and the manual;
  - Google Assistant, only with `disable_assistant=true`.
- On TCL TVs, the remote's Netflix, YouTube and Prime buttons keep working through a small accessibility service that only reads those buttons. Pick what each one opens in *Free My TV* → *Remote Buttons*; *Original* turns the service off. On Vestel-made TVs (F&U and other store brands) and Xiaomi (Formovie) projectors the same goes for the Netflix button, and on KTC-made TVs (NextStar) for the Tabii button. On Sony TVs, the BRAVIA CORE, Netflix, Disney+, Prime Video, YouTube and Crunchyroll buttons (and Google Play, on remotes that have it) can each open the app you pick, or keep Sony's default.
On Skyworth TVs, the Netflix, YouTube, Prime Video, Disney+ and gear (Settings) buttons can each open the app you pick (the gear one can open the TV's Settings); the TV's own receiver only opens the real apps, which are often not installed.
- Picture, sound, inputs, remote pairing, Google services and the Play Store are on a protected list, checked before every change, and so are Android's own pieces and factory menus. Before anything goes off, the TV is asked whether its inputs or its remote's Input key depend on it; those stay on.
- On Google TV, the setup app goes off before the stock home screen and comes back after it, so its recovery screen never takes over the Home button in between.
- Apps that saved a Wi-Fi network are never turned off, since Android would erase the network. If the TV's first setup saved yours, the switch asks you to forget the network and connect again in Settings first (saved networks out of range don't show there; Claude Code can remove them).

## Any TV

Free My TV runs on Google TV and Android TV: TCL, Sony, Hisense, Philips, Xiaomi, Vestel-made TVs (F&U), KTC-made TVs (NextStar) and more, plus streamers like Chromecast with Google TV, the Google TV Streamer, onn 4K and NVIDIA Shield. And on [LG](#lg-tvs-webos) and [Samsung](#samsung-tvs-tizen) TVs, as an app.

**Any other TV:** plug a Google TV streamer into HDMI and free that one. Same steps, same home screen.

**A different brand or model:** ask Claude. Free My TV is made to be adapted by AI. Open the folder in Claude Code and say *"set it up for my Sony"*: it reads your TV, finds its home screen and its promo apps, updates the lists below and walks you through a test. [CLAUDE.md](CLAUDE.md) tells it how.

| What | Where |
|---|---|
| Apps to turn off | `DEBLOAT` in [`HomeMode.java`](app/src/io/freemytv/HomeMode.java) |
| Apps never to touch | `PROTECTED` in the same file |
| Apps on the shelf | `SHELF` in [`HomeApps.java`](app/src/io/freemytv/HomeApps.java) |
| Your brand's live TV app | `LIVE_TV_APPS` in the same file |

## Make it yours

It's your code, and Claude Code finds its way around it. Try:

- *"Add Crunchyroll to the shelf."*
- *"Show the prices in euros."*
- *"Add Catalan."*
- *"Switch to the night look an hour after sunset."*

## How it works

- **A plain Android app, no Gradle, no libraries.** [`build.sh`](build.sh) runs aapt2 → javac → d8 → zipalign → apksigner and makes a 2 MB APK.
- **It uses the TV's own ADB, from inside the TV.** The app connects to `127.0.0.1:5555` with its own key (that's the *Allow USB debugging?* prompt) and runs the same commands you would: `cmd package set-home-activity`, `settings put secure screensaver_components`, `pm disable-user`. Nothing listens to the network.
- **Drawn by hand on one Canvas**, with Android 13 shaders for the frosted glass and the sky (plain fills on Android 8 to 12, with lighter shadows and no GPU layers on 8 to 11 for their older GPUs).
- **A safety net.** A watchdog in a separate process brings back the stock home screen after three crashes in five minutes, and a boot check keeps your choice after TV updates.
- **On LG and Samsung, the same design in plain HTML, CSS and JavaScript** ([`web/`](web)), written for the Chromium of older TVs, with no frameworks. [`webos/build.py`](webos/build.py) packs it with `config.properties` into an `.ipk` without LG's tools, and [`webos/install.py`](webos/install.py) installs it through Developer Mode; on LG it lists and opens apps through the TV's own service bus (Luna), as LG's launcher does. [`tizen/build.py`](tizen/build.py) packs the same app into a Samsung `.wgt`, which uses Tizen's web APIs for the apps and the inputs. To preview it on a computer: `python3 webos/build.py --preview`, then open `out/webos/index.html` (a stand-in lists sample apps).
- **Data** from TMDB (trending titles, new releases and which app streams what in your country), Open-Meteo (forecast, sunrise and sunset), and optionally Binance (24-hour prices when `show_crypto=true`).

| File | What it is |
|---|---|
| [`HomeView.java`](app/src/io/freemytv/HomeView.java) | The home screen |
| [`HomeMode.java`](app/src/io/freemytv/HomeMode.java) | Switching between Free My TV and Original |
| [`Adb.java`](app/src/io/freemytv/Adb.java) | The TV's own ADB, from inside the TV |
| [`Recs.java`](app/src/io/freemytv/Recs.java) | The picks (TMDB) |
| [`HomeData.java`](app/src/io/freemytv/HomeData.java) | Weather and prices |
| [`HomeApps.java`](app/src/io/freemytv/HomeApps.java) | The shelf, inputs and All Apps |
| [`DreamView.java`](app/src/io/freemytv/DreamView.java) | The screensaver |
| [`SettingsActivity.java`](app/src/io/freemytv/SettingsActivity.java) | The settings screen (the app's icon) |
| [`Prefs.java`](app/src/io/freemytv/Prefs.java) | Settings changed on the TV |
| [`RemoteKeys.java`](app/src/io/freemytv/RemoteKeys.java) | The TCL remote's app buttons |
| [`Watchdog.java`](app/src/io/freemytv/Watchdog.java) | The safety net |
| [`web/home.js`](web/home.js) | The home screen on LG TVs |
| [`web/tv.js`](web/tv.js) | The TV's apps and inputs, on LG and Samsung |
| [`webos/build.py`](webos/build.py), [`webos/install.py`](webos/install.py) | The LG app package and its install |
| [`tizen/build.py`](tizen/build.py) | The Samsung app package |

The website ([freemytv.io](https://freemytv.io)) is in [`docs/`](docs): plain HTML, CSS and JS on GitHub Pages.

## Translations

On Google TV, LG and Samsung, each language is one file, [`app/assets/i18n/<lang>.json`](app/assets/i18n), keyed by the
English text in the code. *Automatic* follows the TV's language and uses any language that has a file (English otherwise); *Home
Settings* → *Language* lists them all. Today: English, Portuguese, Spanish, German, French, Italian, Dutch, Polish,
Swedish, Danish, Norwegian, Finnish, Czech, Romanian, Turkish, Russian, Ukrainian, Greek, Japanese, Korean, Simplified
Chinese, Hindi, Indonesian, Vietnamese and Thai. Portuguese, Spanish, German, Russian and Thai were translated by hand and are the
reference (fix them by hand); the others were machine-made, and corrections from native speakers are welcome.

- **Add a language:** `python3 tools/translate.py --new ca`, then fill `app/assets/i18n/ca.json` by hand, or with
  `ANTHROPIC_API_KEY` set, `python3 tools/translate.py --fill --lang ca` (only the strings go to the API).
- **After changing text in the code:** `python3 tools/translate.py` lists what each language lacks; `--fill` translates
  only that, `--prune` drops strings the code no longer uses.
  Run `python3 tools/translate.py --check` to validate all catalogues without writing files.
- Text built at runtime has placeholders (`"{n} min ago"`), so each language puts the words in its own order. A key
  like `"unit|Automatic"` is the same English in another place, where a language may need another word.
- Counts have plural forms: `"plural|one:{n} season|other:{n} seasons"` is translated with one form per plural
  category the language uses (Russian `"one:{n} сезон|few:{n} сезона|many:{n} сезонов|other:{n} сезона"`, Japanese
  just `"other:{n}シーズン"`), and Android's plural rules pick the one for each number.
- **Limits:** right-to-left languages (Arabic, Hebrew, Persian) would need the layout mirrored, so they aren't there
  yet. Chinese, Japanese, Korean and Hindi are drawn with the TV's system font (Inter has no such letters).

## Privacy

No accounts, no analytics, no tracking. The app talks to TMDB, Open-Meteo (for your city, which Android TVs find from the TV's internet address through ipwho.is unless you choose one, and LG and Samsung from your time zone), and Binance only when `show_crypto=true`, Watchmode only when you set a `watchmode_key`, Trakt only after you connect it (your sign-in stays on your TV), YouTube only while a trailer plays in the home screen (and Stremio's site only if you press *Install* for Stremio). Your settings stay on your computer and your TV.

## Credits

- Built with [Claude Code](https://claude.com/claude-code).
- The LG version builds on what [@danielthejoker18](https://github.com/danielthejoker18) found in [FreeMyTvWebOS](https://github.com/danielthejoker18/FreeMyTvWebOS) (MIT): installing through Developer Mode and opening apps through the TV's service bus.
- On Samsung TVs it installs with [TizenBrew Installer](https://github.com/reisxd/TizenBrewInstaller) by [@reisxd](https://github.com/reisxd).
- This product uses the TMDB API but is not endorsed or certified by [TMDB](https://www.themoviedb.org/).
- Links to the titles in the streaming apps by [Watchmode](https://api.watchmode.com/).
- Weather data by [Open-Meteo.com](https://open-meteo.com/) (CC BY 4.0). Prices from the [Binance](https://www.binance.com/) public API.
- Icons: [Material Symbols](https://fonts.google.com/icons) (Apache 2.0). Font: [Inter](https://rsms.me/inter/) (SIL OFL 1.1). Coin logos: [cryptocurrency-icons](https://github.com/spothq/cryptocurrency-icons) (CC0). Samsung app logos: [Simple Icons](https://simpleicons.org) (CC0).
- Not affiliated with Google, TCL, LG, Samsung or any streaming service. App names and logos belong to their owners.

## License

[MIT](LICENSE). Fork it, change it, ship it.
