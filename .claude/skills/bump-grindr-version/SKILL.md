---
name: bump-grindr-version
description: Bump the target Grindr version in version.json and app/build.gradle.kts in one pass. Use when the user says "bump Grindr version", "update target version", or "new Grindr release".
metadata:
  type: project
---

# bump-grindr-version

Automate the first half of the Grindr version-bump workflow (steps 1–3 of
`AGENTS.md` § 5). This skill **only** updates the metadata; it does not port
hooks — that's `update-obfuscated-symbols`.

## Inputs

None required. Optional flags are forwarded to the underlying script.

## Required tools

- `python3` (with `requests` + `bs4` — same env that runs `fetch_version.py`)
- `jq`
- `bash`

## Steps

1. Run `bash scripts/sync-grindr-version.sh` from the repo root.
   - Pass `--dry-run` to preview the diff first.
2. Verify the banner: `./gradlew printVersionInfo --quiet`. Must print
   `GrindrPlus v...<new Grindr version>`.
3. **Stop here.** Hooks still need porting. Recommend `update-obfuscated-symbols`.

## What the script does

- Runs `python3 fetch_version.py -o version.json.tmp` to fetch the latest
  version + build from APKMirror.
- `jq -r '.versionName,.versionCode'` to extract the new values.
- `sed -i.bak` patches the two `listOf(...)` lines in `app/build.gradle.kts`
  (`grindrVersionName`, `grindrVersionCode`).
- With `--dry-run`, prints `diff -u` of both files and exits without writing.
- Without `--dry-run`, writes `version.json` and a `.bak` of `app/build.gradle.kts`.

## Exit codes

- `0` — success (or "already up to date")
- `1` — `fetch_version.py` failed (APKMirror unreachable, rate-limited)
- `2` — could not parse the fetched version
- `3` — `app/build.gradle.kts` not found

## Failure modes

- **APKMirror rate-limit / 5xx**: `fetch_version.py` raises. The script surfaces
  this as exit 1. Wait and retry.
- **`jq` missing**: exit 2. Install via your package manager.
- **Gradle banner still shows old version**: the sed regex didn't match (e.g.
  the `listOf(...)` lines were refactored). Re-check `app/build.gradle.kts`
  and update the sed patterns in `scripts/sync-grindr-version.sh`.

## After this skill runs

The user's next step is to port the obfuscation markers. Suggest
`update-obfuscated-symbols` (which needs the new Grindr APK decompiled with
JADX).
