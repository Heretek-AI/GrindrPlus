---
name: smali-target-mapping
description: Map an obfuscated Grindr smali/Java method into a GrindrPlus hook. Use when porting hooks to a new Grindr version, when a captured symbol goes STALE, or when starting a new feature from scratch.
metadata:
  type: project
---

# Smali / Obfuscation Mapping for GrindrPlus

This skill walks through the end-to-end workflow of finding the right
target inside the obfuscated Grindr APK and turning it into a hook.

## When to use

- Porting hooks after a new Grindr release breaks one or more captured
  symbols
- Adding a new feature that needs to hook into a specific decompiled
  method (e.g. suppressing an analytics call, patching a UI state model)
- Triaging a STALE marker report from
  `scripts/check-obfuscated-symbols.py`

## Step-by-step

### 1. Locate the target feature in JADX

The project uses JADX for decompilation; see the `jadx` skill in this
directory. Workflow:

1. Download the target Grindr APK (version pinned in `version.json`) from
   APKMirror. The `.apkm` is a zip; use the base APK for decompilation.
2. Decompile:

   ```bash
   jadx -d /tmp/jadx-out --deobf --no-res <base.apk>
   ```

   `--deobf` enables R8 deobfuscation, which gives every obfuscated
   symbol a stable (but still obfuscated) name. `--no-res` skips
   resource decoding (we don't need it for hook authoring).

### 2. Pick a stable literal

Find the target method in `/tmp/jadx-out/sources/`. Don't capture the
class name — capture a **stable literal** near the method:

```java
boolean Ql.t::q(long onlineUntil) {
    return onlineUntil > currentTimeMillis;   // <-- literal: "onlineUntil > currentTimeMillis"
}
```

Good markers (in order of preference):

1. A string literal the host app prints to logcat or sends to the API:
   `onlineUntil > currentTimeMillis`, `chat.v1.conversation.delete`,
   `(STATUS_BLOCK_DIALOG_SHOWN, 1)`.
2. A distinctive numeric literal or comparison: `<= 600000`, `== 0x10`.
3. A fully-qualified class name that *itself* is stable in the host
   app's public API (e.g. `com.grindrapp.android.api.LoginRestService`).

Bad markers:

- Variable names — they're obfuscated.
- Short common phrases like `return null;` — they match dozens of files.
- Comments — JADX strips them.

The snippet must be **≥ 12 characters** and **unique** in the JADX
output. See `AGENTS.md` § 3 for the full rules.

### 3. Capture the symbol

In the hook file, replace the old `val targetClass = "ka8"` with the new
capture:

```kotlin
private val targetClass = "newObfName" // search for 'onlineUntil > currentTimeMillis'
```

If the entire class became un-obfuscated, use the FQCN:

```kotlin
private val chatRestService = "com.grindrapp.android.chat.data.datasource.api.service.ChatRestService"
```

### 4. Update the patch doc

`docs/patches/<kebab>.md` tracks which Grindr versions each hook has
been verified against. Edit the **Verified against** section:

```markdown
## Verified against

- Grindr 25.21.1 — initial support
- Grindr 26.13.0 — ported (renamed `onlineUntil > currentTimeMillis` from `ka8` to `newObfName`)
```

The skill `patch-doc-coverage` regenerates stubs; if a doc already
exists, this skill doesn't overwrite it — you update it by hand to add
the new "Verified against" entry.

### 5. Verify

1. Run `python3 scripts/check-obfuscated-symbols.py --jadx /tmp/jadx-out --hooks app/src/main/java/com/grindrplus/hooks`.
   The status for this hook's marker should be **OK** (one candidate match).
2. Run the `verify-build` skill to confirm the module still compiles.
3. Install the build on a rooted device (LSPosed) or via LSPatch + Shizuku
   on an unrooted device, and confirm the host app's behavior matches.

## Tools in this repo

- **`scripts/check-obfuscated-symbols.py`** — scans `// search for`
  markers against a JADX dir; emits an OK / AMBIGUOUS / STALE table.
- **`scripts/check-patch-docs.sh`** — fails CI if a hook lacks a patch
  doc; run after adding a new hook.
- **`.claude/skills/update-obfuscated-symbols/`** — orchestrates the
  JADX run + script call. Use it as the entry point.
- **`.claude/skills/jadx/`** — pre-built JADX usage skill.

## Do not

- Don't add new `// search for` markers whose snippet is shorter than
  12 characters — `check-obfuscated-symbols.py` will skip them and
  you'll lose the breadcrumb.
- Don't keep two parallel `// search for` markers in the same hook for
  the same symbol (one for the old version, one for the new). Update the
  marker in place.
- Don't try to maintain a central symbol-mapping object — the convention
  is inline per-hook markers. That's what makes the per-hook docs in
  `docs/patches/` actually useful.
- Don't put a `// search for` snippet on the same line as anything other
  than the `val x = "y"` capture it documents. The scanner regex assumes
  one capture per line.
