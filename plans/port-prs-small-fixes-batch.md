# Plan: port a batch of small Ready upstream PRs

13 `Ready` upstream PRs, each small and self-contained (checked against our current, renamed code, not upstream's
old version): #124, #163, #194, #205, #209, #250, #252, #253, #254, #266, #273, #284, #296. Grouped in one file per
`plan-upstream-pr`'s own rule — individually trivial, and the two exceptions below aside, none touch overlapping
code in conflicting ways.

**Two items from the requested batch of 15 are not planned here, found while checking our current code:**

- **#251 ("Theme: its first value from config.properties") is already built, independently, on our side.**
  `Prefs.theme()` already defaults to `Config.THEME`, `build.sh` already generates it from `prop theme auto`, and
  `config.example.properties`/`README.md` already document `theme=`. This happened during our own Material/Liquid
  Glass theming work after the fork. **Recommend flipping its index row straight to `Superseded`**, not `Planned`.
- **#297 ("Optimized Traditional Chinese language support") can't be ported standalone.** It only edits
  `app/assets/i18n/zh-TW.json` — a file that doesn't exist in our repo. It depends on **#240** ("Traditional
  Chinese (zh-TW), picked on TVs in Taiwan, Hong Kong and Macau"), which creates that catalogue and is currently
  `Pending`, not `Ready`. **Recommend leaving #297's row as `Ready` but not planning it until #240 is ready too** —
  plan them together when that happens, not as part of this batch.

Every section below: `Wanted` / `Where` / `How` / `To decide`. Conflict/overlap risk is called out inline where the
index's note flagged one; everywhere else, none was found.

---

## 1. Turkish translation catalogue (#124)

**Wanted.** Upstream's native-speaker pass over the machine-made Turkish catalogue: wording fixes throughout
`tr.json`, plus marking Turkish as hand-translated (alongside Portuguese, Spanish, German, Russian, Thai) in the
README's Translations section.

**Where.** `app/assets/i18n/tr.json`, `README.md`'s Translations section.

**How.** The upstream diff is almost entirely literal string replacements inside `tr.json` (better wording for
existing keys — no new or removed keys). A handful of strings reference "Free My TV" by name within the Turkish
text (e.g. `"Couldn't check Free My TV: {error}"` as a *key*, Turkish *value* unaffected by the rename since the
key text itself isn't translated, only matched) — since our fork already renamed the underlying English keys to
"Sky Home Launcher" during the rebrand, this diff can't apply as a literal patch: each changed Turkish *value*
needs re-applying by hand against our current `tr.json` (matching by key, not line number), skipping any key
upstream's diff touches that no longer exists in our catalogue (post-rebrand key differences) and keeping our own
Sky Home Launcher branded strings as they are structurally. Add "and Turkish" to the README's hand-translated
languages line.

**To decide.** Nothing — mechanical, low risk.

---

## 2. Glass rendering without AGSL, drawn from bitmaps (#163)

**Wanted.** On Android 8-12 (no AGSL shader support — `Scene`'s `liquid == null` / `glass == null` paths), the
frosted-glass effect over a photo currently falls back to a flat tinted fill with no blur at all. This PR makes it
show an actual (cheaper) blurred version of what's really behind the glass: a small box-averaged copy of the photo,
stretched through the rounded shape with bilinear filtering standing in for a real blur, plus a rim and a top sheen
to match the AGSL look.

**Where.** `app/src/com/skyhome/launcher/Scene.java` only (one file, matches the PR's own scope exactly — confirmed
our file is unchanged in this area since the fork, same `liquid`/`glass` null-check structure).

**How.** This is the one item in the batch with real logic (135 added lines, not just config/data): a new `soften()`
box-blur helper, a `bitmapGlass()` draw path, and small per-instance reused buffers (`softA`/`softB`/`softFrost`,
their pixel arrays and shaders) wired into `setGlassImages`, `setGlassMix`, `setFrost`, `liquid()`, and `glass()`.
Port the diff directly — it's self-contained to this one file with no dependency on anything changed elsewhere in
our fork (our shader/legacy split in `Ui.LEGACY` is unchanged since the fork per `CLAUDE.md`). Comments already
explain the approach; keep them.

**To decide.** Testing needs an actual Android 8-12 device/emulator (an AGSL-capable TV won't exercise this code
path at all) — flag that it can't be verified on a modern dev TV alone.

---

## 3. Protect the onn 4K Streaming Box's system pieces (#194)

**Wanted.** Add onn's (Walmart; Amlogic S905Y4) system packages to `HomeMode.PROTECTED` so the switch never
disables its remote pairing, update notices, remote app-button handler, hardware info, Amlogic system UI, settings,
mic toggle or launcher provider.

**Where.** `app/src/com/skyhome/launcher/HomeMode.java`'s `PROTECTED` set (confirmed: same `new
HashSet<>(Arrays.asList(...))` structure, same insertion point right after the Xiaomi-projector/Freeview entries,
unchanged since the fork). `CLAUDE.md`'s device-notes list.

**How.** Append the ten onn/Amlogic/droidlogic package names from the diff to `PROTECTED`, with the same comment
grouping style already used for every other brand in that list. Add the one-sentence onn device note to `CLAUDE.md`
(update "Free My TV" → "Sky Home Launcher" in the note's wording).

**To decide.** Nothing — purely additive, no conflicts.

---

## 4. Screensaver re-reads settings when it starts (#205)

**Wanted.** `ScreensaverAlone` (the Android-11, main-process screensaver) currently caches `SharedPreferences` from
its first read; a Home Settings change (weather, quotes, coins) doesn't reach it until the main process restarts.
Add `Prefs.reload()` (reopens the prefs file with `MODE_MULTI_PROCESS`) called from `DreamView.start()`.

**Where.** `app/src/com/skyhome/launcher/Prefs.java` (new `reload()` method — confirmed absent today),
`app/src/com/skyhome/launcher/DreamView.java` (`start()`).

**How.** Add `Prefs.reload()` exactly as upstream wrote it; call it once at the top of `DreamView.start()`. No
Android version gating needed beyond what already exists — `ScreensaverAlone` only exists on API 30 per
`HomeMode.DREAM`, same as upstream.

**To decide.** Nothing — small, self-contained, already explained to the user in detail earlier in this
conversation.

---

## 5. Picks: skip resold "Channel" providers, fix Apple TV's provider id (#209)

**Wanted.** `Recs.service()` currently false-matches TMDB providers like "HBO Max Amazon Channel" (a resold
subscription, not the real app) via its `\bmax\b` regex, and can't tell TMDB's two separate "Apple TV" provider ids
apart (350 = the real streaming service, 2 = the Store, rent/buy only). Fix both: skip any provider name containing
`" channel"`, and only match Apple TV when `providerId == 350`.

**Where.** `app/src/com/skyhome/launcher/Recs.java`. Upstream touches 2 call sites of `service()`
(`installedProviderIds()`/`fetchList`-equivalent, and `fetchDetails`'s flatrate loop) — **our fork has 2 more**:
`serviceRow()` (used by `Launcher` for title routing) and `hiddenService()` (Hidden Services setting), both added
independently since the fork.

**How.** Change `service(String name)` to `service(String name, int providerId)`, add the `" channel"` skip and the
`providerId != 350` Apple TV guard, same as upstream. Then thread `providerId` through **all four** call sites, not
just upstream's two — `serviceRow()`'s callers in `Launcher.java` need to pass a provider id from whatever JSON
they're already reading (check what's available there), and `hiddenService()`'s caller likely doesn't have a
provider id handy (it works from a saved service key, not a live TMDB answer) — for that one, decide whether it
needs the id at all (the hidden-services list matching is by key, not raw provider name, so it may not hit this bug
the same way; check before assuming it needs the same treatment).

**To decide.** Whether `hiddenService()` actually needs the `providerId` parameter or can keep its current
single-arg behavior — needs a look at how it's actually called before committing to the signature change there too.

---

## 6. 24-hour time format in English when the TV is set to 24-hour (#250)

**Wanted.** English currently always shows 12-hour time ("9:47 PM") even when the TV's system setting is 24-hour.
Other languages aren't affected (their own catalogue format strings already follow their own convention). Add
`Prefs.clock24()` (reads `DateFormat.is24HourFormat`) and a parallel `DATE_PATTERNS_24` cache in `Ui.java` used only
for English when the TV is in 24-hour mode.

**Where.** `app/src/com/skyhome/launcher/Prefs.java` (new `clock24()`), `app/src/com/skyhome/launcher/Ui.java`
(`datePattern()`, `clock()`, `time()`, the new `DATE_PATTERNS_24` map and `TWENTY_FOUR` override table),
`app/src/com/skyhome/launcher/HomeData.java` (`hour()`).

**How.** Port directly — confirmed our `Ui.datePattern()`/`DATE_PATTERNS` structure is unchanged since the fork, so
upstream's diff applies cleanly: a second cache map, a `language.equals("en") && Prefs.clock24()` check, and three
hardcoded 24-hour format overrides (`format|h:mm`, `format|h:mm a`, `format|h a` → `HH:mm`).

**To decide.** Nothing — mechanical, low risk.

---

## 7. NOW (UK) and Paramount+'s Sony-TV build as catalog/shelf services (#252)

**Wanted.** Add Sky's NOW (`com.bskyb.nowtv.beta`, UK) and Paramount+'s separate build shipped on Sony TVs in the
UK/Canada (`com.cbs.ca`) as recognized streaming services.

**Where.** `app/AndroidManifest.xml` (`<queries>`: `com.cbs.ca`, `com.bskyb.nowtv.beta`),
`app/src/com/skyhome/launcher/HomeApps.java` (`SHELF` table: two new rows), `app/src/com/skyhome/launcher/Recs.java`
(`SERVICES` table: two new rows, `SERVICE_NAME` map: one new entry), `config.example.properties` (the
`hide_services` comment's example list).

**How.** Confirmed our `SHELF`/`SERVICES` tables and manifest `<queries>` block are structurally unchanged at the
exact insertion points upstream uses (right after the `hulu`/`paramount`/`peacock` entries) — port directly, four
small table-row additions plus two manifest lines. No web/ portion to skip (this one's "mixed" tag in the index was
from `web/data.js`'s matching regex, which we drop since we have no `web/`).

**To decide.** Nothing — purely additive.

---

## 8. Kinopoisk, Okko, Premier, Amediateka, Wink, Moonlight on the shelf/picks (#253) — plan with #296

**Wanted.** Add five Russian-market streaming services (Kinopoisk, Okko, Premier, Amediateka, Wink) and Moonlight
(a game-streaming app) to the shelf and picks, including a new logo-extraction mode (`"white"`) for Kinopoisk's logo
over a busy gradient background.

**Where.** `app/src/com/skyhome/launcher/HomeApps.java` (`SHELF` table: 6 new rows; `bannerLogo()`: new `white`
parameter and its extraction branch; `shelfLogo()`: passes it through), `app/src/com/skyhome/launcher/Recs.java`
(`SERVICES`/`SERVICE_NAME`), `config.example.properties`.

**Dependency on #296:** this PR alone doesn't touch `app/AndroidManifest.xml`'s `<queries>` — without **#296**'s
visibility entries (`ru.kinopoisk.tv`, `ru.more.play`, `gpm.tnt_premier`, `ru.amediateka`, `ru.rt.video.app.tv`),
Kinopoisk/Premier/Amediateka can't be detected as installed on Android 11+ TVs at all (`getLeanbackLaunchIntentForPackage`
needs package visibility). **Build and land both together.**

**How.** Confirmed `bannerLogo(Context, String, boolean tint, boolean holes)` is unchanged at our fork's current
state — the signature change to add `boolean white` and its extraction logic (`smoothstep` on luminance/saturation
instead of the normal ink-distance gate) ports directly. Table rows and manifest entries are straightforward
additions at the same insertion points as #252 above (do both catalog PRs — #252 and #253 — in the same sitting
since they touch the exact same tables, to avoid two separate near-identical edits).

**To decide.** Nothing beyond the #296 pairing, already noted.

---

## 9. Live TV: prefer the digital/hardware tuner when a TV has several (#254)

**Wanted.** `HomeApps.tunerInput()` picks the first hardware tuner it finds; on TVs with multiple hardware tuners
(MediaTek: digital, cable, analog), it can pick analog first, which opens to no channels in DVB-T2 countries. Rank
tuners so digital comes first, analog last.

**Where.** `app/src/com/skyhome/launcher/HomeApps.java` (`tunerInput()`, new `tunerRank()` helper).

**How.** Port directly — confirmed `tunerInput()` is unchanged since the fork. Small, self-contained change: track
the best-ranked hardware tuner instead of just the first one found, add the `tunerRank()` helper (string-contains
check for "analog"/"cable" in the tuner id).

**To decide.** Nothing — mechanical, low risk.

---

## 10. Watchmode: skip channels resold inside another app (#266)

**Wanted.** Same underlying issue as #209's TMDB-provider fix, but for Watchmode's own source list: a Watchmode
"source" named e.g. "AppleTV+ Amazon Channel" or "Paramount+ (Via Amazon Prime)" is a resold subscription whose
link opens Amazon's or Apple's app, not the actual streaming app the pick is for. Skip sources whose name contains
"channel" or "(via". Also bumps the cache key prefix (`wm:` → `wm2:`) so previously-wrong cached links get looked
up again, with cleanup of both old- and new-prefixed stale entries.

**Where.** `app/src/com/skyhome/launcher/Recs.java` (`linkKey()`, `findLink()`, the cache-cleanup loop in `save()`).

**How.** Port directly — confirmed this Watchmode section of `Recs.java` is unchanged since the fork. Straightforward:
the `resold` boolean check, the `wm2:` prefix bump, and widening the stale-key cleanup to match both prefixes.

**To decide.** Nothing — mechanical, directly improves our existing Watchmode integration.

---

## 11. Prices: dollars when Binance has halted the currency's pairs (#273)

**Wanted.** Binance sometimes keeps answering price requests for a halted currency pair with a frozen last price
instead of an error (their own example: AUD pairs halted since 2026-10-06, still "trading" at a stale price).
Detect this (`closeTime` more than 24h in the past) and fall back to dollars, same as the existing no-pairs-at-all
fallback — but don't persist the fallback (the market may reopen, so retry at next start).

**Where.** `app/src/com/skyhome/launcher/HomeData.java` (`refreshIfStale()`, new `halted()` static helper).

**How.** Port directly — confirmed this section of `HomeData.java` is unchanged since the fork. Add the `halted()`
check right after the existing no-pairs fallback check, same shape (re-fetch `getPrices()` in dollars when
triggered, don't cache the `inDollars` flag itself for next launch).

**To decide.** Nothing — small, self-contained bug fix.

---

## 12. `screensaver=false` config option (#284)

**Wanted.** A way to keep the TV's own factory screensaver while still switching the home screen and promo apps —
for someone who likes their TV's screensaver (ambient art mode, say) but still wants everything else Sky Home
Launcher does.

**Where.** `build.sh` (new `Config.OWN_SCREENSAVER` from `prop screensaver true`),
`app/src/com/skyhome/launcher/HomeMode.java` (`applyFree()`, `verifyNow()`, `targets()` — each gated on the new
flag), `config.example.properties`, `README.md`.

**How.** Confirmed all three `HomeMode.java` call sites upstream touches are unchanged since the fork — port
directly: wrap each of the three screensaver-setting operations in `if (Config.OWN_SCREENSAVER)`. Add the
`screensaver=true` default and its comment to `config.example.properties`, and the README table row + "What it
changes" bullet update (adjusted for our rename).

**To decide.** Nothing — clean, additive, no conflicts with our Integrations/screensaver-preview work (those don't
touch `HomeMode`'s switch/verify flow).

---

## 13. Package visibility: streaming services + OneWorld TV Center in `<queries>` (#296) — plan with #253

**Wanted.** Add `<queries>` entries for the apps #252/#253 need to detect (see #253's section above for why this
matters), plus `com.mediatek.tv.oneworld.tvcenter` — a package **already in our `LIVE_TV_APPS` list but never in
`<queries>`**, a pre-existing gap on our side independent of this PR.

**Where.** `app/AndroidManifest.xml`'s `<queries>` block.

**How.** Add the nine package entries from the diff. Build and land together with #253 (and #252, since they share
the same manifest block) — see #253's section for the dependency reasoning.

**To decide.** Nothing — purely additive, fixes a real (if minor) pre-existing gap as a side effect.

---

## Build and test plan for the whole batch

- `./build.sh` must still end with `Built SkyHomeLauncher.apk` after each item (or after the whole batch — these
  are independent enough to build incrementally, but one final full build is the real gate).
- `python3 tools/translate.py --check` after #124 (translation edits).
- Item 2 (#163) needs an Android 8-12 device/emulator to actually exercise — flag this explicitly when reporting
  results, since a modern dev TV won't touch that code path.
- Everything else is logic-only and should be checked by reading the diff carefully against `./build.sh` succeeding;
  real-TV verification (picks showing the new services, the screensaver option, the tuner preference) is a nice-to-
  have, not a hard requirement for a batch this size and this low-risk.
