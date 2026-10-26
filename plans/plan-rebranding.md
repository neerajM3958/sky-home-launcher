# Plan: rebranding to Sky Home Launcher

This fork has grown well past "free my tv" (direct commits and PRs: the catalog screen, picks carousel, Trakt
integration, remote-button learning, Stremio install, the Integrations server...). Rename it, drop everything that
isn't Android/Google TV, and give the README a proper overhaul. One PR, branch `rebrand-sky-home`.

Decided in conversation:
- New name: **Sky Home Launcher** (display name; "Sky Home" is fine as a shorter reference in prose).
- Android package id: `io.freemytv` → `com.skyhome.launcher`. This breaks every existing install (new package id =
  a different app to Android, not an update) — accepted.
- GitHub repo: `free-my-tv` → `sky-home-launcher`.
- Drop LG (webOS) and Samsung (Tizen) support entirely. Android/Google TV only, from now on.
- Drop the `docs/` website (freemytv.io) entirely. No custom domain, no GitHub Pages site. The GitHub repo + README
  is the only front door.
- Credit the fork origin (simbasjungles/free-my-tv) in three places: README, the in-app Settings/About, and a
  NOTICE file next to LICENSE.
- I do the code-level rename; the person doing this does the actual GitHub repo rename (Settings → repo name) and
  any keystore regeneration themselves — those aren't things to do from inside a plan.

---

## 1. Drop webOS and Samsung support

**Wanted.** Android/Google TV only, so the whole web-app side goes: it's unused surface that would otherwise need
rebranding too.

**Where.** `web/`, `webos/`, `tizen/` (whole directories); `tools/webapp.py` (shared by webos/tizen builds only);
`tools/check.sh` (its LG/Samsung package steps and the ES5 check in `web/`); `.github/workflows/check.yml` if it
calls those steps directly; `CLAUDE.md` (the two "Installing it on an LG/Samsung TV" sections, the webOS/Tizen bits
of "Releases," "Adapting it to another TV," "The code," and "Always"); `README.md` (the LG and Samsung sections,
the nav links, the "Any TV" section's web-app mention, the credits for `danielthejoker18` and `TizenBrewInstaller`,
the "Settings" table's LG/Samsung-only rows like `hide_apps`); `tools/translate.py` if it reads `web/` for strings
used there; `plans/plan-impl-theming.md` and any other plan referencing `web/`/webos/tizen (leave historical plans
as-is — they describe work already done, not standing docs).

**How.**
1. Delete `web/`, `webos/`, `tizen/`, `tools/webapp.py`.
2. Strip `tools/check.sh` down to the Google TV build, `translate.py --check`, and whatever ES5/web checks only
   applied to `web/` (removed with it).
3. Edit `.github/workflows/check.yml` if it references those paths.
4. Edit `CLAUDE.md`: remove the LG and Samsung install sections, the webOS/Tizen release step, and every other
   webOS/Tizen mention listed above; the remaining doc should read as Android/Google TV only, start to finish.
5. Edit `README.md` similarly (see §5 for the full README rewrite — this cut happens as part of that rewrite, not
   as a separate pass).

**To decide.** Nothing — this is a straight deletion. (If this fork ever wants LG/Samsung back, it's in git
history and in the upstream project.)

---

## 2. Rename the app: display name and package id

**Wanted.** `io.freemytv` → `com.skyhome.launcher`; "Free My TV" → "Sky Home Launcher" everywhere it's the
product's own name (not the upstream project's name, which stays as-is wherever it's cited as the fork source).

**Where.** Every file under `app/src/io/freemytv/` (move to `app/src/com/skyhome/launcher/`, fix `package`
declarations and imports in all of them); `app/AndroidManifest.xml` (package attribute, component names,
`android:label`); `build.sh` (package id references, output apk name `FreeMyTV.apk` → e.g. `SkyHomeLauncher.apk`,
keystore filename `freemytv.keystore`); `config.example.properties` (comments mentioning the app name); `app/res/
values/strings.xml` and `app/res/values-ru/strings.xml` (app name string, any other literal "Free My TV" UI
strings); all 25 `app/assets/i18n/*.json` catalogs (the English keys that are literally "Free My TV" as display
text — most i18n keys are generic UI strings and don't need touching, only ones that embed the product name);
`tools/translate.py`, `tools/make_art.py`, `tools/shots.sh` (package id in `adb shell am start -n io.freemytv/...`
and `am force-stop io.freemytv`); `app/src/io/freemytv/*.java` string literals that reference the package id
directly (e.g. `Prefs.FMT_SETTINGS`, any `getPackageName()`-adjacent hardcoded comparisons — check for
`"io.freemytv"` as a literal, not just the package declaration).

**How.**
1. `git mv app/src/io/freemytv app/src/com/skyhome/launcher` (and the intermediate `app/src/io`, `app/src/com/skyhome`
   directories as needed) — actually since package changes from 2 segments to 3, this is: create
   `app/src/com/skyhome/launcher/`, move every `.java` file there, delete the now-empty `app/src/io/freemytv/`.
2. In every moved file, change `package io.freemytv;` → `package com.skyhome.launcher;`. Any file that imports
   another class in the same package needs no import statement (same as today); check for any fully-qualified
   `io.freemytv.X` references elsewhere (unlikely, since Java same-package files don't import each other, but
   worth a grep).
3. Update `AndroidManifest.xml`: `package="io.freemytv"` → `package="com.skyhome.launcher"`, every
   `<activity android:name=".X">`-style relative reference stays relative (no change needed there), but check for
   any fully-qualified component names, and `android:label` for the app name.
4. Update `build.sh`: the package id constant/variable, the apk output filename (`FreeMyTV.apk` → e.g.
   `SkyHomeLauncher.apk`). **Keystore stays as-is** — `freemytv.keystore` is personal and doesn't need to change
   just because the app is being renamed; keep signing with it, locally and in CI. `.github/workflows/release.yml`
   already restores this exact keystore from a repo secret (`FREEMYTV_KEYSTORE_BASE64`, base64-decoded to
   `freemytv.keystore` before `./build.sh` runs) — no new secret needed, and the secret's *value* doesn't change.
   Only cosmetic: the secret's *name* still says `FREEMYTV_KEYSTORE_BASE64` and the workflow still publishes
   `FreeMyTV.apk` by that literal name (`gh release create ... FreeMyTV.apk`) — update that filename reference to
   match build.sh's new output name in §2's pass over `release.yml` (the secret name itself can stay; renaming a
   GitHub secret means updating it by hand in repo Settings, not worth doing just for cosmetics — flag it to the
   person as optional, not required).
5. Update `app/res/values/strings.xml`, `values-ru/strings.xml`: app name and any literal "Free My TV" strings.
6. Grep all 25 i18n JSON files for `"Free My TV"` as literal value text (not key text) and replace with
   `"Sky Home Launcher"` (or "Sky Home" where shorter fits better in context) in each language's own translation
   of that phrase — this needs care per-language, not a blind find-replace, since translated catalogs may already
   have localized the product name differently or left it as a loanword. Run `python3 tools/translate.py --check`
   after.
7. Update `tools/shots.sh`, `tools/translate.py`, `tools/make_art.py`, `config.example.properties` for the new
   package id / app name references.
8. Grep the whole repo for the literal string `io.freemytv` once all of the above is done, to catch stragglers
   (comments, `CLAUDE.md`, `AGENTS.md`, plan files describing current — not historical — state).

**To decide.** Whether `app/res/values-ru/strings.xml` is the only translated strings.xml or whether other
`values-*` dirs exist (check at execution time — the earlier grep only showed `values` and `values-ru`, but
`values-v30` was mentioned in CLAUDE.md for the screensaver bool and should be checked for brand strings too,
likely none).

---

## 3. Attribution: credit the Free My TV origin

**Wanted.** Be transparent that this is a fork, in three places, without implying the upstream project endorses
this fork.

**Where.** `README.md` (new top section), `LICENSE`/new `NOTICE` file, in-app `SettingsActivity` (an About
row/screen).

**How.**
1. **NOTICE file** (new, next to `LICENSE`): short note that Sky Home Launcher is a fork of Free My TV
   (github.com/simbasjungles/free-my-tv), under the same MIT license, with a line on what's changed since the fork
   (rebranded, Android-only, plus the features this fork has added — picks carousel, catalog screen, Trakt
   integration, remote-button learning, Stremio install, the Integrations server, etc., pulled from git history /
   `plans/plan-minor-improvements.md` rather than re-described from scratch).
2. **README.md**: a short line directly under the title/tagline — "A fork of Free My TV, rebranded and
   Android-only." — linking to the upstream repo, plus the existing Credits section keeps (and clarifies) the
   upstream mention there too.
3. **In-app About.** `SettingsActivity` doesn't have a dedicated About screen today (check at execution time); add
   one, or a line on an existing screen (e.g. the main Settings screen's bottom, or a new small row), showing
   "Sky Home Launcher — a fork of Free My TV" with the upstream repo URL as plain text (TV UIs don't have
   clickable links, so just display it legibly). Keep it small — one or two lines, not a dedicated screen if an
   existing screen has room.

**To decide.** Exact wording and placement of the in-app credit — do this last, after seeing how much room
`SettingsActivity`'s main screen has, and decide then whether it's a line on an existing screen or its own row.

---

## 4. The repo rename itself

**Wanted.** `neerajM3958/free-my-tv` → `neerajM3958/sky-home-launcher`.

**Who does what.** The person using this plan does the actual GitHub rename (repo Settings → Repository name).
GitHub auto-redirects the old URL for a while, but:
- Any README/CLAUDE.md/AGENTS.md text that names the repo path (e.g. the "say to Claude Code" install examples)
  should use the new path once renamed.
- `git remote -v` locally will still point at the old URL after a GitHub-side rename (GitHub redirects pushes/pulls
  automatically, but updating `origin` to the new URL explicitly avoids relying on the redirect).

**Sequencing note.** Doing the GitHub rename before or after this PR merges doesn't matter functionally (GitHub's
redirect covers it either way), but doing it after merge means the PR itself is still opened against the old repo
name, which is fine and normal.

---

## 5. README overhaul

**Wanted.** Beyond the rename, a fuller README: full launcher demo screenshots, a features rundown, how the key
flows work, what integrations exist, and how to set it up — essentially folding in a lot of what's scattered across
`CLAUDE.md` and `plans/plan-minor-improvements.md` into one README a newcomer can read top to bottom. No LG/Samsung
material (§1).

**Where.** `README.md` entirely; screenshots reuse the existing top-level `screenshots/` folder — it already has a
good spread (dark/light/glass home pages across all 3 pages, settings main + every subscreen including Trakt,
TMDB/Catalogs, Stremio, Integrations + its QR, Remote Buttons/Added buttons, Apps turned off, episodes,
screensaver). `.github/preview.webp` (today's single README hero image) either gets replaced by one of
`screenshots/`'s home images or stays as a dedicated hero crop — decide at execution time, not worth a separate
screenshot shoot just for that.

**How.**
1. **Check what's missing** from `screenshots/` against the feature list below before taking anything new — it's
   already close to complete. Likely gaps, if any: the Torrentio addon screen, the HDMI/AV input popover, a
   Watch Next / Continue Watching row in particular (vs. the general home pages already captured). Fill only those
   gaps, on a real TV with Sky Home Launcher installed, following the same naming convention already in use
   (`<theme>-<screen>.png` / `settings-<nn>-<name>.png`) — extend `tools/shots.sh` for anything that fits its
   existing drive-by-key-sequence pattern, take the rest by hand. Re-take anything that still shows the old
   "Free My TV" branding on screen (the Settings screens draw the app name as on-screen text) once §2's rename is
   built and installed.
2. **Structure the new README** (replacing the current one, which was already pretty thorough — keep its tone and
   most of its existing content, just reorganize/expand):
   - Title, tagline, "fork of Free My TV" line (§3), hero screenshot(s).
   - **What you get** — expand the existing bullet list into a fuller features section with screenshots per
     feature where one earns its place (not every bullet needs an image): no ads, your apps on the shelf, picks
     carousel, weather/crypto drawer, day/night theme, the catalog screen, Continue Watching / Watch Next, the
     screensaver, top input buttons, remote-button learning, apps-turned-off control.
   - **Flows** — short walkthroughs with screenshots for the things that are more than a static screen: switching
     to Sky Home Launcher and back to Original, connecting Trakt (QR flow), installing Stremio from the app,
     adding the Torrentio addon, recording a remote button, setting up TMDB/Watchmode/Trakt keys from a phone
     (the Integrations QR flow).
   - **Integrations** — a clear section listing TMDB (required for picks), Watchmode (optional, opens a title's
     own page), Trakt (optional, Continue Watching/Watchlist), Stremio (optional, title fallback + Torrentio),
     Android Watch Next, Open-Meteo (weather), Binance (crypto) — what each does, whether it's required, and
     where its key/setup lives (`config.properties` vs. on-TV Integrations screen).
   - **Install / setup** — the existing "Install" section's AI-assistant and manual paths, updated for the new
     package id, repo name and apk filename; drop the LG/Samsung sections entirely (§1); keep the "Settings" table
     (strip LG/Samsung-only rows like `hide_apps`, `watch_next`'s LG/Samsung caveat if any); keep "The TMDB key,"
     "Opening a title," "Setting these keys from your phone" subsections, updated for the new name/package.
   - **Going back**, **What it changes**, **Any TV** (now "Any Android TV" — drop the LG/Samsung line), **Make it
     yours**, **How it works** (drop the LG/Samsung bullet and file table rows), **Translations**, **Privacy**
     (unchanged in substance), **Credits** (keep TMDB/Watchmode/Open-Meteo/Binance/Material Symbols/Inter/
     cryptocurrency-icons credits; drop the `danielthejoker18`/`TizenBrewInstaller`/Samsung-icons credits since
     that code is gone; keep/add the Free My TV fork credit per §3), **License**.
3. Cross-check every file path mentioned in the README (`app/src/io/freemytv/...` → `app/src/com/skyhome/launcher/
   ...`) after §2's rename.

**To decide.** Whether any existing `screenshots/*.png` need retaking because they still show "Free My TV" text
on screen (the Settings screens and the app name draw it live) — check each one against §2's rename before
reusing it in the README, rather than assuming the whole folder is still accurate once the rename lands.

---

## 6. Inject the app version at build time

**Wanted.** The version shown in Settings (`SettingsActivity.versionName()`, reading
`PackageManager.getPackageInfo(...).versionName`) is whatever's hardcoded in `AndroidManifest.xml`
(`android:versionName="1.1"`) — the same string on every build, local or released. Instead: a release build (built
by `.github/workflows/release.yml`) should show its actual release tag (e.g. `v1.3.0`); a local build (anyone
running `./build.sh` on their own machine) should show `local-YYYYMMDDHHMMSS` (the build's own timestamp), so a
locally-built apk is visibly distinguishable from a released one and from any other local build.

**Where.** `build.sh` (the `aapt2 link` call that currently reads the manifest's static `versionName` as-is — see
`app/AndroidManifest.xml:5`); `.github/workflows/release.yml` (has the release tag in `inputs.tag` already, just
needs to pass it through); `SettingsActivity.versionName()` needs no change — it already reads the version back
from `PackageInfo` at runtime, so whatever aapt2 actually put in the apk is what shows up.

**How.**
1. In `build.sh`, before the `aapt2 link` step, compute: `VERSION="${SKYHOME_VERSION:-local-$(date
   +%Y%m%d%H%M%S)}"` — an env var wins when set (CI sets it), otherwise the `local-` timestamp format is the
   default for every plain local build.
2. Pass it to the existing `aapt2 link` invocation with `--version-name "$VERSION"` — aapt2 link's
   `--version-name` (and `--version-code`) flags override whatever `AndroidManifest.xml` declares, so the apk's
   real versionName is always what `build.sh` computed, and the manifest's own `android:versionName="1.1"` becomes
   just a harmless fallback that's never actually shipped. No manifest templating/sed needed.
3. Leave `versionCode` alone (stays manifest-driven, an integer) — it isn't what's displayed and a timestamp isn't
   integer-shaped anyway; nothing requested there.
4. In `release.yml`, before the `./build.sh` step, export the tag as that env var:
   `echo "SKYHOME_VERSION=${{ inputs.tag }}" >> "$GITHUB_ENV"` — so a release's Settings screen shows the real tag
   (e.g. "Sky Home Launcher v1.3.0") instead of falling back to a local timestamp.
5. `check.yml` (the PR/push build check) sets no such env var, so it builds with a `local-` timestamp too — fine,
   since that workflow only verifies the build succeeds and never publishes the apk.
6. Format check: `date +%Y%m%d%H%M%S` already yields exactly `YYYYMMDDHHMMSS`; prefixed with `local-` that's
   `local-20261026153045`-shaped, matching what was asked for.

**To decide.** The env var's exact name (`SKYHOME_VERSION` proposed, matching the new brand — arbitrary, happy to
rename). Nothing else outstanding.

---

## 7. Sequencing

Suggested order, since later steps depend on earlier ones existing cleanly:

1. §1 Drop webOS/Tizen/docs (clean deletion, no rename entanglement).
2. §2 Rename package id, app name, file moves (`./build.sh` must succeed and produce the new apk name at the end
   of this step — the existing "always run `./build.sh`" rule from CLAUDE.md still applies).
3. §3 Attribution (NOTICE, in-app About, README attribution line — small, do early so it's not forgotten).
4. §6 Version injection (`build.sh` + `release.yml` — small, self-contained, no dependency on §5's screenshots).
5. §5 README overhaul, including taking screenshots on the renamed/rebuilt app (needs §2 done and installed on a
   TV first).
6. §4 GitHub repo rename — the person's own step, any time, ideally after the PR with §1-§3 is ready so the PR
   is reviewable against a known repo name; §5's screenshots can be taken before or after this, it's independent.

Every step ends with `./build.sh` succeeding and `python3 tools/translate.py --check` passing, per CLAUDE.md's
existing rule (`tools/check.sh` after §1 trims out the now-gone LG/Samsung/web checks, so it becomes the
remaining gate).
