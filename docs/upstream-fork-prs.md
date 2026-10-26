# Upstream PR index: Free My TV → Sky Home Launcher

Sky Home Launcher is a fork of [Free My TV](https://github.com/simbasjungles/free-my-tv), which keeps shipping its
own PRs. This is the living index of every PR merged there that isn't already in our history, so review work is
never repeated: every upstream PR gets exactly one row below, with a status updated in place as it's reviewed —
never by re-adding a row or re-reading a PR whose status is `Planned` or later.

Maintained by two skills:
- **`sync-upstream-prs`** appends new rows (newly-merged upstream PRs we don't have yet), always as `Pending` — see
  below for how it classifies and seeds them. It never edits an existing row.
- **`plan-upstream-pr`** turns `Ready` rows into implementation plans (one `plans/port-pr-<number>-<slug>.md` per
  PR, or one shared `plans/port-prs-<numbers>-<slug>.md` for a few it judges worth grouping) and flips each row's
  status to `Planned`.

## Status values

- **Pending** — not reviewed yet; the default for anything newly added. Not something either skill acts on for
  planning — a `Pending` row needs a human look first.
- **Ready** — discussed and understood; worth porting. **Set manually** (by hand, or by asking Claude to update the
  row after talking through a `Pending` PR together) — neither skill promotes a row to `Ready` on its own, since
  that's the point of this status: a deliberate decision, not an automatic one. `plan-upstream-pr` picks up `Ready`
  rows (in bulk, by default) to plan.
- **Planned** — `plan-upstream-pr` has written an implementation plan for it; **Note** links the plan file (shared
  with other PRs if it was grouped). Not yet built.
- **Adopted** — ported in, with **Note** naming our commit/PR that did it.
- **Adapted** — the idea was taken but reimplemented differently (naming conventions, or because our own code
  diverged too far to apply upstream's diff directly); **Note** names our commit/PR.
- **Superseded** — we already built equivalent or better functionality independently (our picks carousel, catalog
  screen, Trakt integration, remote-button learning, Integrations screen, etc.); **Note** says what of ours covers
  it.
- **Rejected** — reviewed (or, for the mechanical web/webos/tizen/docs-only case, obviously not applicable) and
  decided against; **Note** says why. Never re-review a `Rejected` row without a specific reason to revisit it.

## How `sync-upstream-prs` refreshes this file

```bash
git remote add upstream https://github.com/simbasjungles/free-my-tv.git   # once, if not already added
git fetch upstream
gh pr list --repo simbasjungles/free-my-tv --state merged --limit 200 \
  --json number,title,mergedAt,url,mergeCommit,files > /tmp/upstream_prs.json
```

For each PR: `git merge-base --is-ancestor <mergeCommit.oid> main` — exit 0 means it's already an ancestor of our
`main` (already incorporated, directly or indirectly; skip, don't touch the Baseline note). Exit non-zero means it
isn't in our history: add a new row **only if its number isn't already a row below** — a PR already listed (in any
status) is never added again, even if it's still "not an ancestor" (expected for anything `Rejected` or ported by
hand instead of merged, since the fork's own commits/SHAs differ from upstream's).

Platform classification is by top-level paths touched: `app/` (and `build.sh`, `config.example.properties`,
`tools/`) is Android; `web/`, `webos/`, `tizen/`, `docs/` is the platform we dropped. A PR touching only dropped-
platform paths is auto-`Rejected` with note `"<dir>/ only — platform dropped"`. A PR touching only `CLAUDE.md`/
`tools/` in a way that's moot (upstream doc/CI housekeeping our own `CLAUDE.md`/`check.sh` already diverged past) is
auto-`Rejected` too, case by case. Everything else is added `Pending`, with a one-line note on relevance and any
overlap/conflict risk with features built independently since the fork (the picks carousel, the catalog screen,
Trakt integration, remote-button learning, the Integrations screen, the rebrand itself).

## Baseline

Our `main` and upstream's `main` share a common ancestor: upstream commit `4d739516` (2026-10-22). Every PR upstream
merged before that is already in our history and isn't listed individually here.

Last full refresh: **2026-10-26** (covered the 48 PRs merged upstream since the baseline commit).

## Index

| # | Title | Merged | Platform | Status | Note |
|---|---|---|---|---|---|
| [124](https://github.com/simbasjungles/free-my-tv/pull/124) | Turkish | 2026-10-23 | app | Ready | Translation catalogue update; low risk, likely a straightforward `tools/translate.py` merge |
| [163](https://github.com/simbasjungles/free-my-tv/pull/163) | Android 8-12: the glass over a photo drawn with bitmaps | 2026-10-23 | app | Ready | Rendering fix for older/low-end GPUs; check against our `Shaders`/`Shadows`/`Ui.LEGACY` changes since fork |
| [167](https://github.com/simbasjungles/free-my-tv/pull/167) | Add Amazon Fire TV (Fire OS) support and remote input focus fixes | 2026-10-23 | app | Ready | New platform support (Fire OS is Android-based); sizable, worth a dedicated look; #292 builds on this one |
| [175](https://github.com/simbasjungles/free-my-tv/pull/175) | Home: smooth tiles, cards, logos and symbols on 4K TVs | 2026-10-23 | app + web (mixed) | Ready | Rendering quality; check against our catalog-screen/poster rendering changes for conflicts |
| [178](https://github.com/simbasjungles/free-my-tv/pull/178) | My Photos: your photos, sent from the computer, for the screensaver and home | 2026-10-23 | app + tools | Ready | Plan together with #227 (its correctness follow-up, same feature) and #102 (shares the same Backdrop/HomeView/Prefs/SettingsActivity touch points, depends on this PR's `Photos.on()`) |
| [190](https://github.com/simbasjungles/free-my-tv/pull/190) | Samsung: Prime Video on 2020 TVs reaches the shelf | 2026-10-23 | web | Rejected | web/ only — platform dropped |
| [194](https://github.com/simbasjungles/free-my-tv/pull/194) | Protect the onn 4K Streaming Box's system pieces | 2026-10-23 | app | Ready | Device protection list addition (`HomeMode.PROTECTED`); low risk, likely a clean merge |
| [205](https://github.com/simbasjungles/free-my-tv/pull/205) | Screensaver: read the settings again when it starts | 2026-10-23 | app | Ready | Prefs.reload() with MODE_MULTI_PROCESS for ScreensaverAlone (Android 11); small, self-contained, no conflicts found |
| [208](https://github.com/simbasjungles/free-my-tv/pull/208) | Xiaomi TVs: the Mi button opens Free My TV, not PatchWall | 2026-10-23 | app | Superseded | We already debloat (turn off) Xiaomi's PatchWall, and the Mi button (a global key) can already be recorded via our remote-button *learning* feature for a custom action — no Xiaomi-specific code needed |
| [209](https://github.com/simbasjungles/free-my-tv/pull/209) | Picks: skip "… Amazon Channel" providers; Apple TV only as TMDB provider 350 | 2026-10-23 | app | Ready | Real bug still present in our `Recs.service()`; also thread the fix through our own `serviceRow()`/`hiddenService()` callers upstream didn't have |
| [210](https://github.com/simbasjungles/free-my-tv/pull/210) | LG and Samsung: shelf logos lifted off the TV's icons, filling the tile | 2026-10-23 | web | Rejected | web/ only — platform dropped |
| [213](https://github.com/simbasjungles/free-my-tv/pull/213) | HY300 projectors: Keystone and Screen Mirroring shortcuts, HDMI that opens | 2026-10-23 | app + tools (+ docs) | Pending | New device support (HY300/Amlogic projector shortcuts); check against our top-buttons/inputs rework since fork |
| [217](https://github.com/simbasjungles/free-my-tv/pull/217) | Remote buttons on Xiaomi TVs sold in Russia | 2026-10-23 | app | Pending | Brand remote-key support; check against our remote-button learning feature |
| [220](https://github.com/simbasjungles/free-my-tv/pull/220) | Shelf: any number of apps, scrolling past what fits, and a check on the ones on it | 2026-10-23 | app + web (mixed) | Pending | Shelf UX change; check against our shelf-editor/catalog-screen changes since fork |
| [225](https://github.com/simbasjungles/free-my-tv/pull/225) | LG and Samsung: Thai in the web app's NAMES and REGIONS | 2026-10-23 | web | Rejected | web/ only — platform dropped |
| [219](https://github.com/simbasjungles/free-my-tv/pull/219) | Settings: a sleep timer, and options this TV lacks show why | 2026-10-24 | app | Ready | New Settings feature; check against our Settings screens (Integrations, Remote Buttons, etc.) for layout/UX conflicts |
| [227](https://github.com/simbasjungles/free-my-tv/pull/227) | My Photos follow-ups: claim before importing, decode only the 16:9 band, a way in on Android 11 | 2026-10-24 | app + tools | Ready | Correctness fix on #178 (a real race condition + an Android 11 send-fails-entirely bug), not optional polish — plan and port together with #178 |
| [230](https://github.com/simbasjungles/free-my-tv/pull/230) | HY300 (Amlogic): the Home button opens Free My TV through JmLauncher | 2026-10-24 | app | Pending | New device support; low risk if self-contained to a new brand's keys |
| [232](https://github.com/simbasjungles/free-my-tv/pull/232) | Weather: the next 5 days instead of the next 6 hours | 2026-10-24 | app + web (mixed) | Pending | Weather widget change; check against `HomeData`/drawer changes since fork |
| [237](https://github.com/simbasjungles/free-my-tv/pull/237) | Samsung: Globoplay and Moonlight reach the shelf, and shelf_apps sets its order | 2026-10-24 | web + tools | Rejected | web/ only (Samsung shelf) — platform dropped |
| [239](https://github.com/simbasjungles/free-my-tv/pull/239) | Shelf: a rim around it and on the focused app, and a shade on the others (UX/UI improvement) | 2026-10-24 | app + web (mixed) | Pending | Shelf visual change; check against our Material/Glass look changes for conflicts |
| [240](https://github.com/simbasjungles/free-my-tv/pull/240) | Traditional Chinese (zh-TW), picked on TVs in Taiwan, Hong Kong and Macau | 2026-10-24 | app + web (mixed) | Pending | New language; the `app/` i18n portion is relevant, skip the web/ portion |
| [241](https://github.com/simbasjungles/free-my-tv/pull/241) | LG install: lock the TV's key by ACL on Windows | 2026-10-24 | webos | Rejected | webos/ only — platform dropped |
| [242](https://github.com/simbasjungles/free-my-tv/pull/242) | Hisense remote buttons (Q6N, Android TV 12) | 2026-10-24 | app | Pending | Brand remote-key support; check against our remote-button learning feature |
| [245](https://github.com/simbasjungles/free-my-tv/pull/245) | Inputs: check the maker's player is really installed, and open them on MIUI TV | 2026-10-24 | app | Pending | Check against our top-buttons/inputs rework since fork |
| [250](https://github.com/simbasjungles/free-my-tv/pull/250) | Time: 24 hours in English when the TV is set to 24-hour | 2026-10-24 | app | Ready | Small i18n/format fix; likely low risk |
| [251](https://github.com/simbasjungles/free-my-tv/pull/251) | Theme: its first value from config.properties | 2026-10-24 | app + build.sh + web (mixed) | Ready | Check against our theme/Integrations `Prefs` override system — may already be covered or may conflict |
| [252](https://github.com/simbasjungles/free-my-tv/pull/252) | Picks and shelf: NOW (UK) and Paramount+'s build on Sony TVs | 2026-10-24 | app + web (mixed) | Ready | Catalog/service addition; check against our picks-carousel service list |
| [253](https://github.com/simbasjungles/free-my-tv/pull/253) | Picks and shelf: Kinopoisk, Okko, Premier, Amediateka and Wink; Moonlight on the shelf | 2026-10-24 | app | Ready | Plan together with #296 — #253 adds Kinopoisk/Premier/Amediateka but doesn't touch the manifest, so without #296's `<queries>` entries they won't be detected as installed on Android 11+ |
| [254](https://github.com/simbasjungles/free-my-tv/pull/254) | Live TV: digital tuner first when a TV has several hardware tuners | 2026-10-24 | app | Ready | Tuner-selection fix; likely low risk |
| [102](https://github.com/simbasjungles/free-my-tv/pull/102) | Photo of the day theme: Bing's daily photo behind the clock | 2026-10-25 | app + web + docs (mixed) | Ready | app/ part only (web/docs dropped); depends on #178's `Photos.on()` check — plan together with #178+#227, build on top once My Photos lands |
| [170](https://github.com/simbasjungles/free-my-tv/pull/170) | Xiaomi TVs: the remote's YouTube and PatchWall buttons in Remote Buttons | 2026-10-25 | app | Pending | Brand remote-key support; check against our remote-button *learning* feature — may be redundant now |
| [211](https://github.com/simbasjungles/free-my-tv/pull/211) | LG webOS 3: the TV's app list from the computer (webos/apps.py) | 2026-10-25 | web + webos | Rejected | webos/web only — platform dropped |
| [261](https://github.com/simbasjungles/free-my-tv/pull/261) | fix(tv): widen Samsung internal-app filter for Tizen 9 | 2026-10-25 | web | Rejected | web/ only — platform dropped |
| [263](https://github.com/simbasjungles/free-my-tv/pull/263) | TCL: the home screen opaque again, so the app just left no longer stays on screen | 2026-10-25 | app | Pending | TCL-specific bug fix; likely low risk, worth adopting |
| [266](https://github.com/simbasjungles/free-my-tv/pull/266) | Watchmode: skip channels resold inside another app (AppleTV+ Amazon Channel, Paramount+ via Amazon) | 2026-10-25 | app | Ready | Directly improves our existing Watchmode integration; likely a clean merge |
| [267](https://github.com/simbasjungles/free-my-tv/pull/267) | Run on 2016 Samsung TVs (Tizen 2.4) | 2026-10-25 | web + tizen | Rejected | web/tizen only — platform dropped |
| [273](https://github.com/simbasjungles/free-my-tv/pull/273) | Prices: dollars when Binance has halted the currency's pairs | 2026-10-25 | app | Ready | Bug fix; likely low risk |
| [276](https://github.com/simbasjungles/free-my-tv/pull/276) | Screensaver: OK opens the title on screen in its app (optional, off by default) | 2026-10-25 | app + web (mixed) | Ready | Check against our screensaver and title-routing (`Routing`/`Launcher`) changes since fork |
| [278](https://github.com/simbasjungles/free-my-tv/pull/278) | CLAUDE.md: the Always rules first; check.sh greps the CSS and --fill leaves hand translations alone | 2026-10-25 | CLAUDE.md + tools | Rejected | Doc/CI reorder moot for us — our `CLAUDE.md` already diverged, and `tools/check.sh` no longer checks `web/` CSS at all |
| [282](https://github.com/simbasjungles/free-my-tv/pull/282) | Samsung install: tested on a 2024 Tizen 9 TV, and what to do when the TV drops the connection | 2026-10-25 | tizen | Rejected | tizen/ only — platform dropped |
| [284](https://github.com/simbasjungles/free-my-tv/pull/284) | Add a screensaver=false option that keeps the TV's own screensaver | 2026-10-25 | app + build.sh | Ready | Config option addition; likely low risk, check our `config.example.properties` layout |
| [287](https://github.com/simbasjungles/free-my-tv/pull/287) | Home and screensaver: what any app is playing, in the quotes' place while it plays | 2026-10-25 | app | Ready | Sizable, needs design work: no `Capabilities.java` on our side, replaces the quotes row (our drawer/Top-Buttons layout has changed since fork), and adds a new `ModeJob` type overlapping our own `ModeJob.KEEP` addition |
| [289](https://github.com/simbasjungles/free-my-tv/pull/289) | Home: the remote's Settings button opens Free My TV's Settings | 2026-10-25 | app | Pending | Check against our `RemoteKeys`/`Prefs.FMT_SETTINGS` (added independently, see CLAUDE.md) — likely `Superseded` on closer look |
| [292](https://github.com/simbasjungles/free-my-tv/pull/292) | Fire TV: HDMI inputs through Amazon's input switch, Live TV on the an… | 2026-10-25 | app | Pending | Builds on #167 (Fire TV support) — review together |
| [296](https://github.com/simbasjungles/free-my-tv/pull/296) | Package visibility: streaming services and OneWorld TV Center in `<queries>` | 2026-10-25 | app | Ready | Plan together with #253 (dependency, see its note); also fixes a pre-existing gap — `com.mediatek.tv.oneworld.tvcenter` is in our `LIVE_TV_APPS` but was never in `<queries>` |
| [297](https://github.com/simbasjungles/free-my-tv/pull/297) | Optimized Traditional Chinese language support | 2026-10-25 | app | Pending | Translation refinement; likely low risk, review with #240 |
| [298](https://github.com/simbasjungles/free-my-tv/pull/298) | LG and Samsung: About this TV in Home Settings (#265) | 2026-10-25 | app + web (mixed) | Pending | Mostly LG/Samsung by title, but touches `app/` too — check what the Android part actually is before rejecting outright |

**Totals.** 48 PRs in this snapshot: 10 `Rejected` (9 web/webos/tizen-only, 1 moot doc/CI), 1 `Superseded` (#208 —
already covered by our PatchWall debloat + remote-button learning), 22 `Ready` (#102, #124, #163, #167, #175, #178,
#194, #205, #209, #219, #227, #250, #251, #252, #253, #254, #266, #273, #276, #284, #287, #296 — #102/#178/#227 to
be planned together; #253/#296 to be planned together), 15 `Pending`.
