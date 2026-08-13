---
name: update-obfuscated-symbols
description: Find which `// search for '<snippet>'` markers in hook files are stale against a new Grindr APK. Use after `bump-grindr-version`, when the user says "port hooks to version X", "obfuscation symbols changed", or "use new APK".
metadata:
  type: project
---

# update-obfuscated-symbols

Walk every hook file in `app/src/main/java/com/grindrplus/hooks/`, extract
every `// search for '<snippet>'` marker, and grep the JADX-decompiled output
of the new Grindr APK to find which markers are stale.

**This skill only reports.** It never edits hook files. The maintainer
(or AI agent) reads the report and applies edits.

## Inputs

- A new Grindr APK (or `.apkm`) on disk.
- The path to JADX-decompiled output. If you only have the APK, this skill
  will run JADX for you.

## Required tools

- `python3` (stdlib only)
- `jadx` CLI (only if you need to decompile the APK)
- `bash` + `grep`

## Steps

1. **Decompile the APK** (if you don't already have a JADX dir):

   ```bash
   jadx -d /tmp/jadx-out --deobf --no-res <path/to/new.apk>
   # .apkm is a zip; unzip it first and pick the base.apk
   ```

2. **Run the scanner**:

   ```bash
   python3 scripts/check-obfuscated-symbols.py --jadx /tmp/jadx-out
   ```

   Add `--strict` to make it exit non-zero on any STALE marker (useful in CI).

3. **Read the report**. For each row:
   - `OK` — one candidate file matched; the snippet is still in the new APK.
     Nothing to do for this marker.
   - `AMBIGUOUS` — multiple matches; open JADX and pick the right one by hand.
     Update both the `// search for '...'` snippet AND the captured symbol
     string in the hook file.
   - `STALE` — no match. The snippet is obsolete. Open JADX, find the
     new decompiled code that does what the old snippet did, capture a
     **stable literal** of ≥ 12 chars, replace the marker.
4. **For every updated hook**, edit `docs/patches/<feature>.md` and add a
   new "Verified against" entry with the new Grindr version.

## What the script does

- Parses each `*.kt` under `hooks/` for two patterns:
  - `val <name> = "<symbol>" // search for '<snippet>'`
  - `// search for '<snippet>'` on its own line (body-comment markers)
- `grep -rF -- '<snippet>' /tmp/jadx-out/sources` for each snippet.
- Emits a Markdown table:

  ```
  | Status | Marker | Old symbol | Candidates | Confidence | File |
  ```

- Status ∈ {OK, AMBIGUOUS, STALE}.

## Markers you should always re-check by hand

- `STALE` markers — by definition there's no candidate. JADX decompilation
  can fail on obfuscated code; re-run with `--show-bad-code` if you suspect
  false positives.
- Acronym-bounded markers (e.g. `SSLUnpinning.kt`'s
  `("com.grindrapp.android.chat.ChatDeleteConversationPlugin",`) — these are
  class-name literals, so they're highly stable. But if Grindr ever moves
  that class, the marker will go STALE.

## After this skill runs

- Commit hook updates with the message format
  `Hook: <Feature> — update for Grindr <X.Y.Z>`.
- Commit doc updates separately:
  `Docs: <Feature> — mark verified against Grindr <X.Y.Z>`.
- Run `verify-build` to confirm the module still assembles.
