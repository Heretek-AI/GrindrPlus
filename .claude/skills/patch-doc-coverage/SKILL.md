---
name: patch-doc-coverage
description: Verify every hook in app/src/main/java/com/grindrplus/hooks/ has a matching docs/patches/<kebab>.md file. Generate stubs for missing docs. Use before commit, after adding a new hook, or in CI.
metadata:
  type: project
---

# patch-doc-coverage

`AGENTS.md` requires every hook file to have a per-hook design doc at
`docs/patches/<kebab>.md`. This skill checks coverage and can author
stubs for missing docs.

## Inputs

None required. The defaults assume the standard repo layout:

- Hooks dir: `app/src/main/java/com/grindrplus/hooks/`
- Docs dir: `docs/patches/`

## Required tools

- `bash`
- `python3`

## Steps

1. **Run the check**:

   ```bash
   bash scripts/check-patch-docs.sh
   ```

   Exit code:
   - `0` — every hook has a doc.
   - `1` — at least one hook is missing its doc; the script lists them.

2. **If missing docs are listed, author stubs** (one per missing hook):

   ```bash
   bash scripts/check-patch-docs.sh | grep ' - '
   ```

   For each `<Hook>.kt -> <kebab>.md` line, create `docs/patches/<kebab>.md`
   using the template at
   `.claude/skills/patch-doc-coverage/stub.template.md`.

3. **Rewrite `docs/patches/README.md`** to include a bullet for every hook,
   alphabetically.

4. **Re-run** `scripts/check-patch-docs.sh` to confirm coverage.

## Stub template

The template lives at
`.claude/skills/patch-doc-coverage/stub.template.md`. A stub has:

- `# <Hook display name>` — copy the first arg to the `Hook(...)` constructor.
- One-line "what this hook does" paragraph.
- `## Target` section — paste the `// search for '...'` markers from the
  hook file as a code block, so a maintainer can find the obfuscated
  symbols in a fresh JADX-decompiled APK.
- `## Verified against` section listing which Grindr versions were tested.

## Wiring into CI

`.github/workflows/build_apk.yml` runs `scripts/check-patch-docs.sh` after
JDK setup. Build fails if any hook lacks its doc. To disable on a particular
PR, add the `no-docs` label (the workflow looks for this label and skips the
step in that case — see the workflow file).

## Failure modes

- **Mapping is wrong for an acronym**: edit `scripts/check-patch-docs.sh`'s
  `kebab()` function. Acronym handling lives in two regex steps.
- **Hook is intentionally undocumented (WIP)**: do not merge to master
  without a stub. The CI gate exists for a reason.
