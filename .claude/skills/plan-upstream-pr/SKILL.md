---
name: plan-upstream-pr
description: Write implementation plan(s) for porting Ready-status upstream Free My TV PR(s) into Sky Home Launcher, from docs/upstream-fork-prs.md. Use when the user asks to plan a specific upstream PR (by number or description), or asks to plan/process the Ready upstream PRs (in bulk, with no number given).
---

# Plan upstream PR(s)

Takes `Ready` rows from `docs/upstream-fork-prs.md`, works out what porting each into Sky Home Launcher's current
(renamed, diverged) codebase actually involves, and writes that as one or more files under `plans/`. Judges whether
a few `Ready` PRs are related/small enough to plan together in one file, or each needs its own. This skill only
plans — it never writes app code, and never flips a row past `Planned`.

## Picking what to plan

1. **Read `docs/upstream-fork-prs.md`.**
2. **If the user named specific PR number(s)**, use those rows.
   - Any that aren't `Ready` yet: say what they currently are. A `Pending` row hasn't been discussed — the point of
     `Ready` is that someone already decided it's worth porting, so don't plan a `Pending` row without the user
     explicitly confirming they want to skip straight past that step this once. Anything `Planned` or later: tell
     the user and ask whether they really want it redone, rather than silently overwriting.
3. **If the user didn't name any PRs** ("plan the ready ones", "what's next", a bare invocation), this is the bulk
   case: take **every** `Ready` row, no shortlisting or asking which one — `Ready` already means someone decided
   it's worth doing, so there's nothing left to ask permission for. If there are no `Ready` rows, say so (suggest
   discussing some `Pending` ones first) and stop.

## Grouping Ready PRs into plan files

Default to **one plan file per PR**. Only group a few `Ready` PRs into one shared plan file when there's a real
reason, not just because it's less typing:

- **Explicitly related** — the index's own `Note` already flags a relationship (e.g. "review together", "follow-up
  to #178"), or the PRs are visibly the same feature in two parts (a feature PR and its own later fix/follow-up).
- **Individually small** — each PR on its own is trivial to plan and implement (a translation-catalogue update, a
  one-line config default, a small bug fix) **and** they don't touch overlapping code in conflicting ways. Several
  small, unrelated-but-equally-trivial PRs (say, three different brands' remote-key additions) can still go in one
  file if each gets its own clearly separated section — bundling here is about not paying per-file overhead for
  trivial work, not about pretending they're one change.

Keep separate whenever a PR is sizable on its own (new platform/device support, a new feature, a UX rework) or its
port needs real analysis against what's changed since the fork — don't fold a complex PR into a group just because
something else in the batch happens to be `Ready` at the same time. When genuinely unsure, default to separate; an
extra small plan file costs little, a bloated combined one is harder to act on.

State the grouping decision plainly in the final summary (which PRs got bundled and why, which stayed solo) so the
user can see the reasoning and object if it's wrong — don't ask for upfront approval of the grouping before writing
anything, that would defeat the point of "bulk."

## Steps, per PR (or per group)

1. **Read the PR's actual diff**, not just its title or the index's one-line note:
   ```bash
   gh pr diff <number> --repo simbasjungles/free-my-tv
   gh pr view <number> --repo simbasjungles/free-my-tv --json title,body,files
   ```
2. **Read our current equivalent code** before assuming upstream's diff applies cleanly. At minimum:
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
3. **Write the plan file.** Follow this repo's existing plan style (see `plans/plan-minor-improvements.md` for the
   reference shape):
   - **Single-PR plan** (`plans/port-pr-<number>-<slug>.md`): intro naming the PR (number, title, link) and what it
     does, then `Wanted` / `Where` / `How` / `To decide`, same as a normal one-off plan.
   - **Grouped plan** (`plans/port-prs-<n1>-<n2>[-<n3>...]-<slug>.md`): one intro explaining why these were grouped,
     then one numbered section per PR (`## 1. <title> (#<number>)`), each with its own `Wanted` / `Where` / `How` /
     `To decide` — the running-list style `plans/plan-minor-improvements.md` already uses, since a bundle of small
     related items is exactly that shape.
   - Either shape includes a **Conflict/overlap check**: explicit note on whether the PR overlaps with something
     Sky Home Launcher built independently (per the index's note on that row); if it's actually `Superseded` rather
     than portable, say so instead of writing a full port plan, and flag that the index should move straight to
     `Superseded` instead of `Planned`.
4. **Update each row in `docs/upstream-fork-prs.md`**: `Status: Planned`, `Note: "See plans/port-pr(s)-...md"` (the
   same file path for every PR in a group). Don't touch any row not being planned in this run.
5. **Tell the user**: which plan file(s) got written, which PRs landed in which file and why (the grouping
   rationale), and a one-line summary of what each proposes. Don't implement anything — that's a separate, later
   ask (and typically its own branch/PR, same as any other feature work here).

## Notes

- If reading a diff reveals the PR should actually be `Superseded` (we already built it, better, independently)
  rather than ported, say so in the plan (or skip the plan file and just say so) and flag that row for the user to
  flip to `Superseded` directly — don't write a pointless port plan for something not worth porting after all.
- Match plan length to actual complexity. A grouped plan's trivial sections should stay short; don't pad a one-line
  translation fix out to look as substantial as a new-feature section next to it.
