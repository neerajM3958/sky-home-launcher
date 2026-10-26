---
name: plan-upstream-pr
description: Write an implementation plan for porting one specific upstream Free My TV PR into Sky Home Launcher, from a Pending row in docs/upstream-fork-prs.md. Use when the user asks to plan, scope or figure out how to port a specific upstream PR (by number or description), or asks what to plan next from the upstream PR index.
---

# Plan an upstream PR port

Takes one `Pending` row from `docs/upstream-fork-prs.md`, works out what porting it into Sky Home Launcher's
current (renamed, diverged) codebase actually involves, and writes that as `plans/port-pr-<number>-<slug>.md`. This
skill only plans — it never writes app code, and never flips a row past `Planned`.

## Steps

1. **Read `docs/upstream-fork-prs.md`.** If the user named a specific PR number, find its row.
   - If it isn't a row at all: tell the user, suggest running `sync-upstream-prs` first if it looks like it should
     exist, and stop.
   - If its status isn't `Pending`: tell the user what it already is (`Planned` with its plan file, `Adopted`,
     `Rejected`, etc.) and ask whether they really want to redo it before continuing.
   - If the user didn't name a PR, shortlist a handful of `Pending` rows (favor ones the index note already flags
     as "likely low risk" or a clean merge) and ask the user to pick one, rather than silently choosing — which PR
     to spend implementation effort on is their call.
2. **Read the PR's actual diff**, not just its title:
   ```bash
   gh pr diff <number> --repo simbasjungles/free-my-tv
   gh pr view <number> --repo simbasjungles/free-my-tv --json title,body,files
   ```
3. **Read our current equivalent code** before assuming upstream's diff applies cleanly. At minimum:
   - Package/class names moved (`io.freemytv` → `com.skyhome.launcher`; files live under
     `app/src/com/skyhome/launcher/`, not `app/src/io/freemytv/`).
   - Any file the PR touches may have diverged further — Sky Home Launcher has since added the picks carousel,
     the catalog screen and poster rows, the Trakt integration, remote-button *learning*, the Integrations screen,
     top input buttons as a popover, and dropped `web/`/`webos`/`tizen`/`docs/` entirely. Check the current state
     of each file the PR touches (`Read`/`Grep` in `app/src/com/skyhome/launcher/`), not upstream's old version of
     it.
   - If the PR touches literal "Free My TV" text or `io.freemytv` strings, the ported version needs
     "Sky Home Launcher" / `com.skyhome.launcher` instead.
   - If the PR is "mixed" (touches both `app/` and a dropped platform), only port the `app/` part; note explicitly
     in the plan what was left out and why.
4. **Write `plans/port-pr-<number>-<slug>.md`**, following this repo's existing plan style (see
   `plans/plan-minor-improvements.md` for the reference shape — `Wanted` / `Where` / `How` / `To decide`, filled in
   with a `Built` line and screenshots only once actually implemented, which this skill does not do):
   - A short intro naming the upstream PR (number, title, link) and one line on what it does.
   - **Wanted** — what the port should achieve in Sky Home Launcher's own terms, not upstream's.
   - **Where** — the files in our tree it touches, by their current (post-rename) names.
   - **How** — the actual porting steps, adapted for whatever has diverged since the fork. Call out anywhere
     upstream's diff can't just be re-applied and needs rewriting.
   - **Conflict/overlap check** — explicit note on whether this overlaps with something Sky Home Launcher built
     independently (per the index's note on that row); if it's actually `Superseded` rather than portable, say so
     here instead of writing a full port plan, and suggest the index should move straight to `Superseded`.
   - **To decide** — anything that needs the user's judgment before implementation starts (naming, UX choices
     upstream made that might not fit our own since-diverged UI, whether to also grab a related PR like a listed
     follow-up).
5. **Update the row in `docs/upstream-fork-prs.md`**: `Status: Planned`, `Note: "See plans/port-pr-<number>-
   <slug>.md"`. Don't touch any other row.
6. **Tell the user** the plan file's path and a one-line summary of what it proposes — don't implement it; that's a
   separate, later step (and a separate ask).

## Notes

- One plan file per PR (not a shared running file) — each is self-contained, matching this session's own choice for
  how upstream ports should be tracked.
- If reading the diff reveals the PR is trivial (a one-line translation fix, say), the plan file can be short —
  match the plan's length to the port's actual complexity, don't pad it out.
