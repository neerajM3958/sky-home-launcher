---
name: sync-upstream-prs
description: Refresh docs/upstream-fork-prs.md with any newly-merged PRs from the upstream Free My TV repo (simbasjungles/free-my-tv) that Sky Home Launcher doesn't have yet. Use when the user asks to check, sync, refresh or update the upstream PR index, or asks "what's new upstream" / "did Free My TV merge anything new".
---

# Sync upstream PRs

Keeps `docs/upstream-fork-prs.md` current: appends rows for upstream PRs merged since the last refresh that we
don't already have in our history, classifies their platform, and writes a one-line relevance note. **Never edits
an existing row** — a PR already listed, in any status, is not touched, even if it would otherwise look "not an
ancestor" again (expected for anything already `Rejected`, or ported by hand with different commit SHAs than
upstream's).

## Steps

1. **Make sure the upstream remote exists and is fresh:**
   ```bash
   git remote get-url upstream 2>/dev/null || git remote add upstream https://github.com/simbasjungles/free-my-tv.git
   git fetch upstream --quiet
   git fetch origin --quiet
   ```
2. **List every merged upstream PR with its merge commit and changed files:**
   ```bash
   gh pr list --repo simbasjungles/free-my-tv --state merged --limit 200 \
     --json number,title,mergedAt,url,mergeCommit,files > /tmp/upstream_prs.json
   ```
   If the PR count returned equals 200 (the limit), raise it and re-run — don't silently truncate the list.
3. **Read `docs/upstream-fork-prs.md` first** and collect every PR number already present as a row (any status).
   Any number from step 2 that's already a row is skipped entirely in every later step — don't re-derive its
   platform, don't re-check its ancestry, don't touch its line.
4. **For every PR number not already a row**, check whether it's already in our history:
   ```bash
   git merge-base --is-ancestor <mergeCommit.oid> main   # exit 0 = already ancestor, skip (don't add a row)
   ```
   What's left after this filter is genuinely new.
5. **Classify platform** by the top-level paths in `files`: `app/`, `build.sh`, `config.example.properties`,
   `tools/` → Android-relevant. `web/`, `webos/`, `tizen/`, `docs/` → the platform we dropped. A PR touching paths
   from both groups is "mixed" — still worth a `Pending` row (only the Android-relevant part would ever matter).
6. **Auto-reject mechanically**, no further judgment needed:
   - Touches **only** dropped-platform paths → `Status: Rejected`, `Note: "<dir>/ only — platform dropped"`.
   - Touches only `CLAUDE.md`/`tools/` in a way that's clearly moot for us (e.g. reordering sections we've already
     rewritten, or referencing a check — like the `web/` CSS/ES5 check in `tools/check.sh` — that no longer exists
     on our side). Use judgment here; if genuinely unsure whether it's moot, leave it `Pending` instead of guessing
     `Rejected`.
7. **Everything else gets `Status: Pending`.** Write a one-line `Note`: what the PR does, and specifically flag
   likely overlap or conflict with anything Sky Home Launcher built independently since the fork — the picks
   carousel, the catalog screen and poster rows, the Trakt integration, remote-button *learning* (any button, any
   brand — may make some brand-specific upstream PRs redundant), the Integrations screen (phone-set keys), top
   input buttons, and the rebrand itself (package id, app name, dropped LG/Samsung/web). A quick skim of the PR's
   title and changed files is enough for this note — deep review happens later, in `plan-upstream-pr`.
8. **Append the new rows** to the Index table in `docs/upstream-fork-prs.md`, sorted by `Merged` date like the
   existing rows. Update the "Last full refresh" date and the **Totals** line at the end to reflect the new grand
   totals (don't recompute totals for PRs outside this run — just add this run's counts to what's already there).
9. **Report a short summary** to the user: how many new PRs found, how many auto-rejected and why, how many left
   `Pending`. Don't dump the whole table back into chat — point at the file.

## Notes

- This skill only adds rows and updates the refresh date/totals. It never changes an existing row's `Status` or
  `Note`, and it never deletes a row. If a mistake in an existing row needs fixing, that's a manual edit, not
  something this skill does automatically.
- If `docs/upstream-fork-prs.md` doesn't exist yet, this skill isn't the right place to bootstrap its whole format
  (status vocabulary, baseline section) from scratch — stop and tell the user, rather than inventing a new index
  structure.
