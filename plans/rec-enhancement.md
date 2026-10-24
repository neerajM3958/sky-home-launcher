# Recommendations and launching: make them flexible and configurable

**Status: all four phases are built (branch `rec-routing`, tried on a Skyworth Google TV Stick).** The
questions that need an answer are in *Open questions* at the end, each with the default I would take if you say nothing.

What phase 1 taught, and where it differs from the plan:
- **Apps Free My TV turned off count as missing.** On the test TV the promo list (`HomeMode.DEBLOAT`) turned off Netflix,
  Prime Video and YouTube, so none was launchable and every title went to a Play Store page. That is the plan's rule,
  and it is a good one: the store page of a turned-off app shows an **Enable** button.
- **A `*` rule still means "every service goes there"** in phase 1 (that is what `service_map=*=...` meant before), so
  with it nothing is looked up and every title opens in that app, as today. Phase 3 replaces it.
- **A third message.** A title that streams only on services we can't open (for example "VI movies and tv") says "It
  streams on {service}, which Free My TV can't open." instead of "unavailable in your region", which would be wrong.
- **Tie-break.** Equal kinds go by TMDB's order, not by the order of `Recs.SERVICES` as written below.
- **Rent and buy providers** Free My TV has no app table for (Google Play, YouTube, "Amazon Video") live in
  `Launcher.EXTRA`, not in `Recs.SERVICES`, so the picks don't start suggesting them.
- The picks still open through their own stamp (`Pick.pkg`); routing them through `Launcher` belongs to phase 4.

What phase 2 taught:
- The TMDB section is *Region*, *Hide cartoons and anime* and *Catalogs* (the poster rows editor became *Catalogs*, to
  match `catalogs=`); the picks' on/off and counts stay under it as *Recommendations* until phase 4.
- The Catalogs editor reuses the shelf editor: OK turns a row on or off, left and right move it.
- Without a TMDB key the row is greyed and says "Needs a TMDB key", like Trakt without a Client ID.

What phase 3 taught:
- **`service_map` is gone** (build.sh, `Config`, `Recs`, the example config, the README). `Routing.Env` now asks three
  things about Stremio: is it installed, does this service go to it (`Prefs.toStremio`), and does it take what is missing.
  The decision table is tested on the JVM (18 cases, in the scratchpad, not in the repo).
- **"Other services"** is a row of its own on the Stremio screen (key `other`): titles on services we have no app for
  open in Stremio when it is on. *Use Stremio for what is missing* covers the app not being on the TV, no service in
  the region and a lookup that failed. The order for titles on several services is not a setting yet (question 6).
- **The picks had to move with it.** They relied on the `*` rule to be "anything popular", so that mode is now the
  only one the picks use (`Recs.listOnly()` reads `Prefs.recommendFromApps()`, false), and a pick opens through
  `Launcher` like every card (`Pick.pkg == Recs.ROUTED`). Phase 4 adds the setting that turns it back on. A pick's
  service is looked up ahead of time (`Launcher.prefetch`), so there are no dots on a pick.
- The main menu has ten rows and still fits the comparison and two notices.

What phase 4 taught:
- The TMDB section is now Region, *Recommend from*, *Only titles I can stream*, *Include rent or buy*, *Hide cartoons
  and anime*, *Catalogs*; the status block below still fits. *Include rent or buy* is greyed while the one above is off.
- `flatrate_only` (and the new `include_rent_buy`) are the first values; the TV's choice wins. The discover filter is
  `with_watch_monetization_types=flatrate`, or `flatrate|rent|buy` (the `|` is `%7C`), or none.
- Not done: a rent or buy pick doesn't say so in its meta line (question 10). Picks from anything popular carry no
  service, so the line has no name to put it next to.

## Goal

Today almost everything falls back to Stremio. Instead:

1. A title opens **its own service's app** by default (Netflix title in Netflix, and so on).
2. If that app isn't on the TV, open its **Play Store page**, so the person can install it.
3. A settings section for Stremio, on the TV, where the person decides:
   - which services Stremio should catch instead of their own apps;
   - whether Stremio takes everything that is missing.

**Trakt stays as it is**: the *Trakt* row in the main menu (sign-in, the rows) is not part of this plan. Its rows only
start going through the routing below when a card is opened.

## How it works today (what the code does)

| Thing | Behavior now | Where |
|---|---|---|
| Picks, default mode | Only titles on services whose app is installed; each opens its own app (title page too, with a Watchmode key) | `Recs.fetchList`, `SERVICES` (13 services), `installed()` |
| Picks with `service_map` rules | `netflix,prime=<app>` sends those services to another app; `*` is every other service | `Recs.MAP`, `target()` |
| Picks with only a `*` rule ("list only") | Not limited to your apps: any popular title, all opening that one app (Stremio, on the title's page by IMDb id) | `Recs.listOnly()`, `fetchListOnly` |
| Poster rows and Trakt rows | Exist only in list-only mode; every card opens that one app | `Recs.catalogApp()`, `Catalogs`, `HomeView` click |
| App missing | A "Couldn't open {app}." message; no Play Store | `HomeApps.open` (returns false) |
| Where it is set | `config.properties` at build time: `service_map`, `hide_services`, `flatrate_only`, `catalogs`; not changeable on the TV | `Config`, `build.sh` |
| Episodes screen | Opens the row's app (Stremio in practice) on the episode's page | `EpisodesActivity.openEpisode` |

Consequence: you either get "my apps only" (and no poster rows, no Trakt rows) or "everything in Stremio". There is
nothing in between, and nothing to install an app from.

## The model

Every title that can be opened goes through **one routing step**, whatever row it came from (picks, TMDB rows, Trakt
rows). Input: the title (kind, TMDB id, IMDb id, title). Output: a *target*.

```
1. Is the service known? (the title's streaming services in the person's region)
   no  -> step 5
2. Pick one service: an installed app first, then the person's order (below), else the first listed.
3. Stremio installed, and does the person send this service to Stremio?  (Stremio section: services Stremio catches)
   yes -> Stremio, on the title's page
4. Is the service's app installed?
   yes -> that app (the title's page when Watchmode gives it, else the app's home)
   no  -> "missing":
          Stremio installed and takes missing services?  yes -> Stremio
                                                         no  -> the app's Play Store page
5. TMDB answered and the title is on no streaming service in the region:
   Stremio installed and takes these?  yes -> Stremio     no -> the message "This title is unavailable in your region."

0. (before all of the above) The lookup could not be done: it took over 3 seconds, there is no network, or there is no
   TMDB key. That says nothing about the title, so it is **not** "unavailable":
   Stremio installed and takes missing titles?  yes -> Stremio
                                                no  -> the message "Couldn't check where this streams."
```

**Stremio only counts when it is installed.** Without it, every "Stremio" choice (a service sent to Stremio, *Use
Stremio for what is missing*) is treated as off: step 3 is skipped, a missing app goes to its Play Store page, and an
unknown service gets "This title is unavailable in your region." The person's choices are kept, not erased, so they apply again if Stremio is
installed later. Stremio is never a target that opens its own Play Store page.

### Getting the service of a title

Poster rows and Trakt rows carry no service. The data is TMDB's watch providers
(`/{movie|tv}/{id}/watch/providers`), per title and region. Trakt's own `watchnow` is *Limited Access* (documented
"for visibility", not available to all API consumers), so it is not something to rely on.

- Resolve **when OK is pressed**: one request, cached (a day or more) per title and region. About a quarter of a
  second, once per title. The app already does the same for IMDb ids (`Recs.resolveLink`).
- Picks already have it (they come from the same data).
- Without a TMDB key the service can't be known: those titles take step 0.
- **A loader while it waits.** If the answer isn't there after about 150 ms, the card shows **three blinking dots** (a
  small row that pulses one dot after another, like a typing indicator) over its poster, dimmed a little. A cached
  answer opens at once with no flicker. While it waits: OK again does nothing, Back or moving the focus cancels the
  open. The dots are drawn from the clock in the home screen's own frame loop, with no allocations in `onDraw`, like
  the progress bar on a part-way card. If the lookup times out, step 0 runs.
- A title on several services: installed ones first, in an order the person can set (default: the order of
  `Recs.SERVICES`); by kind, subscription first, then free or ad-supported, then rent or buy.
- **The routing ignores the recommend settings.** A title that is only for rent still opens its rent provider's app
  (so "unavailable in your region" means no provider of any kind). The rent and buy providers need entries in
  `SERVICES` too: Apple TV and Prime Video are there; add Google Play Movies & TV (`com.google.android.videos`) and
  YouTube (`com.google.android.youtube.tv`), which sell and rent most titles.

### Play Store

- `market://details?id=<package>` (Google TV and Android TV open the Play Store on it). **To verify on a TV** before
  building: that it opens the right page, and what happens on a TV without a Play Store (then a message).
- Needs the package of each service. The app knows 13 (`SERVICES`); a service we don't know can't go to the store, so it
  follows the unknown-service rule. Some services have two packages (Max: `com.wbd.stream`, `com.hbo.hbonow`; Apple
  TV has Sony's own): the store gets the first.

## What the recommendations are chosen from

Today this is hidden in the `*`-rule trick (and `flatrate_only`). Make it explicit, and **independent of how a title
opens**: *Recommend from* only decides which titles are suggested. Pressing OK on any card, from any row, always goes
through the routing above; the mode never changes it.

Three settings (all in the **TMDB section**, decided):

| Setting | Choices | Does |
|---|---|---|
| **Recommend from** | **Anything popular** (the default) / **My apps** | *Anything popular*: popular titles in the region with no filter on which service has them. *My apps*: only titles on services whose app is installed (today's default without `service_map`) |
| **Only titles I can stream** | on (the default) / off | On: only titles on a streaming service in the region (today's `flatrate_only`: subscription; rent and buy only with the next setting). Off: any popular title, even one that is in the cinema or on no service here |
| **Include rent or buy** | off (the default) / on | Adds the titles you can only rent or buy (on Apple TV, Prime Video, Google Play, YouTube...). Greyed while *Only titles I can stream* is off, since everything is included then |

What this replaces: the old "All services in my region" mode I first proposed is just *Anything popular* with the toggle
on, so there are two choices and a toggle, not three modes.

Consequences of the defaults (*Anything popular*, toggle on):
- Picks can be on a service whose app isn't on the TV. Pressing OK then opens that app's Play Store page (or Stremio, if
  it is installed and takes missing services). That is the point of the Play Store step.
- With the toggle off, a pick can be on no service at all: OK then gives step 5 ("This title is unavailable in your
  region.") or Stremio. Fine for people who only use Stremio, but a poor default, hence the toggle defaulting to on.
- The poster rows and Trakt rows exist in both modes and open through the routing, exactly like the picks.
- Without Stremio nothing about this changes: neither setting depends on it.
- A TV with none of the known streaming apps in *My apps* mode keeps the existing "None of the TV's streaming apps can
  give picks." line.

## Settings on the TV

Two new sections next to the existing ones: **Stremio** and **TMDB**. The *Trakt* row stays as it is. The main menu has
8 rows today and its lower half is full (the comparison, the picks' status, the notices), so the layout changes with
them:

- The **RECOMMENDATIONS** block (the picks' status line, TMDB's required notice "This product uses the TMDB API but is
  not endorsed or certified by TMDB.", and the Watchmode credit) **moves into the TMDB section**, which is where it
  belongs. That frees about 160 px of the 1080, and the two new rows need about 120 (a row is 56 px plus 4).
- Everything else in the menu stays. To check when it is drawn: with 10 rows the notices still end above the bottom edge.

### Sections that need something are disabled without it

One rule for all three sections that depend on something outside the TV:

| Section | Needs | Disabled when | The row says |
|---|---|---|---|
| **Stremio** | the Stremio app (`com.stremio.one`) | it isn't installed | "Not installed" |
| **TMDB** | `tmdb_key` in `config.properties` | it is empty | "Needs a TMDB key" |
| **Trakt** (the row that exists now) | `trakt_client_id` in `config.properties` | it is empty | "Needs a Client ID" |

Disabled means the same everywhere: the row stays in the menu but greyed, its value says why, OK does nothing, TalkBack
reads it as unavailable (`Spoken.Source.clickable` is false for it), and the saved choices are kept for when the
condition is met again. The README says where to get the key or the Client ID.

**What changes for Trakt** (it is built today, so this one is a small change to existing code): the *Trakt* row now
opens its screen even without a Client ID and the screen says "No Trakt Client ID: see the README (config.properties)."
with a greyed Connect button. Instead the row itself is greyed with "Needs a Client ID" (replacing "Not set up"), and
that screen's no-Client-ID message and greyed-button branch are deleted, since the screen can't be reached without one.
**This part is already done** (branch `trakt-needs-client-id`, tried on the TV with and without a Client ID); the same
greyed-row drawing is what the TMDB section reuses in phase 2.

### The Stremio section

- **Services** — a list of services, each with where it opens: *Its app* (default) or *Stremio*. Also *Other services*
  (the ones we don't know), and the order for titles on several services.
- **Use Stremio for what is missing** (on/off): missing apps and titles on no known service.

**The Stremio section is disabled when Stremio isn't installed** (`com.stremio.one`, checked each time the settings
open, with the same package check the app already uses for launching). The row stays visible but greyed, its value
says "Not installed", and OK on it does nothing. Whether it also offers an *Install* button is an open question. The
saved choices stay as they are (see the routing above); installing Stremio enables the section again.

### The TMDB section

Everything about TMDB's data, in one place, in this order (seven rows, so no scrolling):

1. **Region** (described below).
2. **Recommend from**: *Anything popular* (default) or *My apps*.
3. **Only titles I can stream**: on (default) or off.
4. **Include rent or buy**: off (default) or on; greyed while the row above is off.
   (Rows 2 to 4 are the settings in *What the recommendations are chosen from*, decided to live here with the other
   TMDB settings, not in *Home Settings*, which stays as it is.)
5. **Hide cartoons and anime** (on/off): today's `hide_animation`, first value from the config. It adds
   `without_genres=16` to the picks' queries and drops animation from the poster rows (`Catalogs.add`), so a change
   rebuilds both.
6. **Catalogs**: opens the editor below.
7. **Status** (read-only): "Key: set / not set", the picks' status line that is in the main menu today ("On · 24
   titles from the TV's apps, 3 new ones every hour." or the error), and the credits above.

The TMDB key stays in `config.properties`: it is secret, and a 32-character key is not typed with a remote.

**The TMDB section is disabled without a key**, the way the Stremio one is without Stremio: the row is greyed, its value
says "Needs a TMDB key", OK does nothing, and the README says where to get one. The saved choices stay. The *Trakt*
rows and the Trakt sign-in don't use TMDB, so they are unaffected.

#### The Catalogs editor

The same screen as the shelf and the top buttons use: every row TMDB can fill is listed (*Trending today*, *Popular
movies*, *Popular shows*, *Hindi picks*, *Top rated*, *Now playing*, *Upcoming*), the ones that are on first, in their
order, then the ones that are off.

- **OK** turns a row on (it goes after the last one that is on) or off.
- **← →** move a row that is on one place earlier or later: that is the order on the home screen.
- All off is today's `catalogs=none` (no TMDB rows).
- Stored as an ordered list in `Prefs`, like the shelf; the first value comes from `catalogs=`, whose order already
  means the same thing. `Catalogs.keys()` reads it and `Catalogs.wanted()` already notices a change, so only what
  changed is fetched again.
- The **Trakt rows stay first, always**; they are not part of this list.
- It is `SettingsView.openShelfEditor` with a third kind next to the shelf and the top buttons (it has `editingTop`
  today), a new hint ("OK turns a row on or off. ← → change the order.") and the existing row names (`Ui.tr`), so it
  costs one string.

### `config.properties`

The TV settings win, as for every other setting; the config gives the first values. **`service_map` is removed**, not
converted: its job (which services go to Stremio, what Stremio takes) is now the Stremio section, and the list-only
special case it needed goes with it. There is no import: a person who had it in their config sets the Stremio section
once on the TV, and the README says so.

| Key now | Becomes |
|---|---|
| `service_map` | **Removed** (`config.example.properties`, `build.sh`, `Config.SERVICE_MAP`, `Recs.MAP`, the README rows and notes that mention it, the example comments). A service can no longer be sent to *any* app, only to its own app or to Stremio (see question 5) |
| `hide_services` | First value of a per-service *Hidden* state (a hidden service is not recommended and its app is not offered) |
| `flatrate_only` | Stays, as the first value of *Only titles I can stream* (default on) |
| `include_rent_buy` | New, the first value of *Include rent or buy* (default `false`) |
| `tmdb_region` | Stays, as the first value of the *Region* row; also accepts `auto` (the TV's country). The example config says `auto` |
| `hide_animation` | Stays, as the first value of *Hide cartoons and anime* |
| `catalogs` | Stays, as the first values of *Catalogs* (the list of names, or `none`) |
| `trakt_client_id` | Unchanged |

### The region setting

The first row of the **TMDB section** (not *Home Settings*). Its value is the country's name in the TV's language
(*Automatic* first, then the countries).

- **Country names cost no translations:** `Locale("", code).getDisplayCountry(Ui.locale())` gives each name in the
  catalogue's language, sorted with a `Collator` for that language.
- **Choosing:** OK opens the same picker screen the language and the city use (nine rows, the current one checked,
  Back cancels). A hundred or so countries is long for the arrows, so the picker also takes the page keys, as the
  episodes screen does.
- **Where the list comes from:** `/watch/providers/regions` (TMDB), cached for 30 days; if it can't be fetched and
  nothing is cached, `Locale.getISOCountries()`.
- **What reads the region today** (`Recs.REGION`, a `static final` built from the config) and has to read the setting
  instead, through one `Recs.region()` that gives `Prefs` first, then the config, then the TV's country for `auto`:
  the provider list and the discover queries (`watch_region`, 3 places), the title details (`watch/providers` results
  by region), the Watchmode links (`regions=`), `Recs.sourceStamp()` (so the saved picks and the cached provider
  answers are rebuilt on a change), and the settings line "TMDB has none of the TV's apps for tmdb_region=…", which
  must say "for {region}" with the setting's name, not the config key (a changed string, so 24 catalogues again).
- A change takes effect at once: the picks are fetched again (the same path as the other recommendation settings).
- The routing's service lookup (per title and region, cached) already keys its cache by region.
- Not part of this: the LG and Samsung apps choose their region from the TV's time zone (`web/zones.js`).

## Technical outline

- A new `Launcher` (or `Routing`) class holds the whole decision above; **no row decides anything itself**. It returns a
  target: `APP(pkg, uri)`, `STORE(pkg)`, `STREMIO(uri)` or `NONE(message)`. `HomeView`'s card click, the hero cards,
  and `EpisodesActivity` call it. `Recs.target()`, `catalogApp()`, `listOnly()`, `needsTitleLink()` and the `MAP` parsing are deleted: the routing is
all that is left.
- `HomeApps.open` gets a store case (an `ACTION_VIEW` on the market address), and the message when nothing can open.
- Per-title provider data: a small disk cache next to the posters (key: kind, id, region), fetched on the same network
  thread as `Recs`.
- The picks' source query: *My apps* is today's `with_watch_providers` filter; *Anything popular* is today's list-only
  query (no provider filter); *Only titles I can stream* adds `with_watch_monetization_types=flatrate` to either, and
  *Include rent or buy* makes it `flatrate|rent|buy` (the `|` URL-encoded).
  Nothing in it knows which app a card will open: `Recs.listed(..., pkg)` stops stamping a package on each pick.
- `Recs.sourceStamp()` includes the new settings, so the saved picks are rebuilt when they change.
- **Settings storage:** `Prefs` gets the region, the three recommendation settings, *Hide cartoons and anime* and the
  ordered poster rows; each reads the saved value, else the config's first value. `Config.HIDE_ANIMATION` and
  `Config.CATALOGS` are read in `Recs` and `Catalogs` today and move to those `Prefs` getters; every one of them
  is part of `Recs.sourceStamp()` or `Catalogs.wanted()`, so a change rebuilds exactly what depends on it.
- **Episodes follow the routing too** (decided, question 8): `EpisodesActivity.openEpisode` asks the same `Launcher`,
  with the show's TMDB id (the intent gets it as an extra, next to the Trakt and IMDb ids it already has). The target
  then decides what an episode press does:
  - **Stremio:** the episode's own page (`stremio:///detail/series/<imdb>/<imdb>:S:E`), as today.
  - **A service's app:** the show's page when a link is known, else the app's home. Service apps don't accept an
    episode link, so the person picks the episode inside the app; the screen says so (below).
  - **Play Store or a message:** as for any title.
  The show's service is looked up **once when the screen opens** (one cached request, in the background), not on every
  press, so a press is instant and the three dots only show if the person is faster than the answer.
  The hint on the left of the screen names where OK goes, for example "OK opens the show in Netflix." or "OK opens the
  episode in Stremio." (new strings, one per target kind).

## Phases

1. **Routing core, no new screens.** `Launcher`, the Play Store step, the lazy provider lookup, the three outcomes;
   the rows work without list-only mode. `hide_services` keeps its meaning. Until phase 3 exists, `service_map` is read
   in one place only, to say which services go to Stremio and whether it takes what is missing, so nobody loses
   Stremio between the changes. *Done when:* a title whose app is missing opens its store page on the TV; one whose
   app is there opens the app; with a `*` rule it behaves as today.
2. **The TMDB section and the main-menu relayout.** The two new rows and the moved RECOMMENDATIONS block; *Region*
   (with `Recs.region()` replacing `Recs.REGION` everywhere), *Hide cartoons and anime* and the *Catalogs* editor
   as stored settings with the config as first values; the section greyed without a key, and the *Trakt* row greyed
   without a Client ID (one shared "disabled row" in `drawMain`, `rowText` and `activate`). *Done when:* the main menu
   fits 10 rows and the notices on the TV; changing the region rebuilds the picks for that country; turning a poster
   row on or off or moving it changes the home screen at once and survives a restart; the animation setting does the
   same; without a key the TMDB row is greyed, and without a Client ID the Trakt row is.
3. **The Stremio section.** Services, Stremio, the stored values. **In the same change, remove `service_map`** (every
   place in the *Becomes* table above, plus the README text that tells people to set it for the poster rows and the
   Trakt rows). *Done when:* `grep -ri service_map` finds nothing except the README's note on the removal; changing
   a service to Stremio on the TV changes where its cards open at once, and survives a restart; with Stremio
   uninstalled the section is greyed, and cards of a service that was sent to Stremio open that service's app (or its
   store page) instead.
4. **Recommend from**, **Only titles I can stream** and **Include rent or buy**, as rows 2 to 4 of the TMDB section,
   with *Anything popular*, *on* and *off* as the defaults. *Done when:* each combination shows the right set on the
   TV, the saved picks rebuild on any change, and a card opens the same way in every combination.

Each phase: `./build.sh`, `translate.py --check`, the unit-testable part (the decision table) tested on the JVM like
`EpisodeList`, and a pass on the TV with `adb`.

## Risks

- **A request per title** the first time (cached), when OK is pressed. If TMDB is slow the card shows the three dots
  for up to 3 seconds, then step 0 (Stremio if it takes missing titles, else "Couldn't check where this streams.").
  A slow or missing answer is never reported as "unavailable in your region".
- **Wrong or missing provider data** (TMDB's data comes from JustWatch): a title may show a service that doesn't have
  it. Opening the app's home and the person searching is what happens today too.
- **Package names change** (Max, JioHotstar, Apple TV builds). They live in one table; unknown ones are not guessed.
- **A breaking change** for anyone who has `service_map` in their `config.properties`: after phase 2 its poster rows
  and Trakt rows no longer need it, but the Stremio section starts from its defaults. The README and the release notes
  say so, and the PR says it first.
- **More settings, more translations**: with the two sections, the three recommendation rows, the messages and the
  hints, roughly 60 to 80 new strings in 24 languages (the country names cost none). They are written in step with
  each phase, not at the end.
- **The main menu is tight**: the two new rows only fit because the RECOMMENDATIONS block moves out. If the notices
  don't fit on a smaller render (some TVs draw at 1280×720), the layout scales with the screen but should be checked
  there too.
- **Out of scope:** the LG and Samsung apps (`web/`).

## Open questions

Mark your answer, or say "defaults". The *default* is what I would build.

1. **"Stremio for all missing services": what is "missing"?**
   - [ ] the app isn't installed → Stremio instead of the Play Store
   - [ ] the title is on no known service (or the service is unknown) → Stremio
   - [x] both, one toggle (default)
2. **A title on no service in the region** (cinema release, not streaming here):
   - [x] Stremio when "Stremio for what is missing" is on, else the message "This title is unavailable in your
     region." (default)
   - [ ] always the message
   
   (The same words for a title whose service we can't determine, for example with no TMDB key.)
3. **Where does *Recommend from* live?** Decided: in the TMDB section, with the other settings about TMDB's data
   (changed from *Home Settings*; see question 17).
4. **Recommend from: two choices and a toggle** (changed from the three modes I first wrote, see the section above):
   - [x] *Recommend from*: *Anything popular* (default) / *My apps*, plus *Only titles I can stream*, default on
   - [ ] the three modes as first proposed (*My apps*, *All services in my region*, *Anything popular*)
   - [ ] the default of *Only titles I can stream* off (everything popular, even what isn't streaming here)
5. **Settings location:** [x] on the TV, with `config.properties` as the first values for `hide_services` and
   `flatrate_only`, and `service_map` removed (decided) / [ ] config only.
   Removing `service_map` also removes sending a service to *any* app (its example had `youtube=org.smarttube.stable`
   and `*=app.netmirror.newtv`). Choices per service are *Its app* or *Stremio*.
   - [x] that is enough for now (default)
   - [ ] also a per-service app picker, like the remote buttons' (any installed app; more screens and strings)
6. **A title on several services:** [x] installed app first, then an order the person can change, default the order
   of the built-in list (default) / [ ] a fixed order, no setting.
7. **Play Store:** [x] open the store page directly (default) / [ ] ask first. On a TV without a Play Store: a message.
8. **Episodes:** decided: they follow the routing (see the technical outline). One follow-up:
   - [x] with a service app as the target, open the show there and say so in the hint (default)
   - [ ] in that case open the episode in Stremio anyway, when Stremio is installed (the only app that can open an
     episode), and use the routing only when it isn't
9. **The service lookup:** [x] when OK is pressed, cached (default) / [ ] ahead of time for the cards on screen
   (more requests, no wait).
10. **Rent and buy:** decided: a setting, *Include rent or buy* (off by default), in the recommendation settings. Only the
    recommendations use it; the routing never does. Follow-ups:
    - [x] a rent or buy pick says so in its meta line ("Apple TV · Rent or buy"), so nobody is surprised by a price
      (default) / [ ] no label
    - [x] shown as On/Off like the other rows (default) / [ ] as a check box (a check mark when on)
11. **Region:** decided: choosable on the TV, the first row of the **TMDB section** (see *The region setting* below).
    Follow-ups:
    - [x] first value from `tmdb_region` in the config, which also accepts `auto` (the TV's own country); the example
      config says `auto`, existing configs keep their value (default) / [ ] always `auto` at first
    - [x] the choices are the countries TMDB has streaming data for, newest list cached 30 days, the whole ISO list if
      TMDB can't be reached (default) / [ ] a fixed built-in list
    - decided: without a TMDB key the whole TMDB section is greyed ("Needs a TMDB key"), region included
12. **Stremio not installed:** the section is disabled (decided). Should it also offer a way to install it?
    - [x] no, just disabled with "Not installed" (default)
    - [ ] yes: OK on the greyed row opens Stremio's Play Store page
13. **The loader while the service is looked up:**
    - [x] three blinking dots on the card, shown after 150 ms, timeout 3 seconds (default)
    - [ ] the round spinner the settings use
    - [ ] other (say what)
14. **Default of *Use Stremio for what is missing*** when Stremio is installed (it replaces the `*` rule you have now):
    - [x] off: a missing app opens its Play Store page (default)
    - [ ] on
15. **The main menu's lower half:** the RECOMMENDATIONS block moves into the TMDB section to make room for the two new
    rows (decided in principle; the TMDB credit must stay visible somewhere, and the TMDB section is its place).
    - [x] move it (default) / [ ] keep it and drop another block (say which) / [ ] put the two new sections inside
      *Home Settings* as rows instead
16. **Catalogs in the TMDB section:** decided: on or off each **and reorderable**, in the shelf-style editor (see
    *The Catalogs editor*). The Trakt rows stay first.
17. **The three recommendation settings:** decided: they live in the TMDB section (rows 2 to 4), so *Home Settings*
    doesn't grow and doesn't need to scroll.
18. **Anything else** you want in this section?
