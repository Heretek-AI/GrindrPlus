# AGENTS.md — GrindrPlus

> Canonical project instructions for AI coding agents. `CLAUDE.md` and `GEMINI.md` are vendor aliases pointing here.

## 1. Project Overview

GrindrPlus is an Xposed / LSPosed / LSPatch module that hooks the obfuscated `com.grindrapp.android` (Grindr) Android app to add features and bypass paywalls. The module is dual-mode — it can be installed as an LSPosed module on rooted devices or as an LSPatch-patched APK on unrooted devices. Both modes share the same hook source.

> [!WARNING]
> The README banner marks the project as **end-of-life** because Grindr has rolled out PairIP integrity checks that effectively block hooks. New releases of Grindr will not be supported. The work in this repo is therefore *maintenance-grade*: keep things compiling and documented for any future fork.

- **Module entrypoint**: `app/src/main/java/com/grindrplus/GrindrPlus.kt`
- **Hook DSL**: `app/src/main/java/com/grindrplus/utils/{Hook,Hooker,HookAdapter,HookManager}.kt`
- **Hooks**: `app/src/main/java/com/grindrplus/hooks/*.kt` (29 files; `HookManager.kt:registerHooks` registers them)
- **Manager UI**: `app/src/main/java/com/grindrplus/manager/` (Jetpack Compose; not directly part of the hooking surface)
- **Per-hook design docs**: `docs/patches/<feature>.md`

## 2. Dev Setup

Full setup is in [`docs/env_setup.md`](docs/env_setup.md). Short version:

1. Install Android Studio (with JDK 17) and open the repo root. `./gradlew assembleDebug` should work after Android Studio imports the Gradle project.
2. Download the target Grindr APK (see `version.json` for the version) from APKMirror. The `.apkm` is a zip of per-arch splits — you only need the base APK for decompilation.
3. Decompile with JADX (enable *Show inconsistent code* and *Enable deobfuscation*).
4. Test on a rooted device (LSPosed) or via Shizuku + LSPatch on an unrooted device.

## 3. Hook Authoring Rules

This is the most important section. **Read it before editing any file in `hooks/`.**

### 3.1 The `// search for '<snippet>'` convention

Grindr's bytecode is obfuscated; class and method names like `a`, `ka8`, `ps2` are renamed on every release. When a hook captures an obfuscated symbol, it MUST be followed by a `// search for` marker on the same line:

```kotlin
private val utils = "ka8" // search for ' <= 600000;'
```

The marker is a snippet of **stable decompiled source** (a literal, string constant, or distinctive expression) that the next maintainer can paste into JADX's search box against a new APK to find the new obfuscated name.

**Marker rules:**

- The snippet must be **≥ 12 characters** long.
- The snippet must be a **literal that survives R8/ProGuard deobfuscation** — string constants, numeric literals, distinctive keywords. Do NOT use names that themselves get renamed.
- The snippet must be **unique** in a fresh JADX-decompiled APK. If it matches multiple files, narrow it.
- For method-body snippets (e.g. `param.setArg(1, false)`), the marker goes on the line **above** the hook call, with the form `// search for '<snippet>'`.

Example from `hooks/OnlineIndicator.kt`:

```kotlin
val utils = "ka8" // search for ' <= 600000;'
val isFeatureFlagEnabled = "xh6" // search for 'implements IsFeatureFlagEnabled'

override fun init() {
    findClass(utils)
        .hook("d", HookStage.BEFORE) { param ->
            // ...
        }
}
```

### 3.2 Hook class skeleton

```kotlin
class <FeatureName> : Hook(
    "<display name>",
    "<one-line user-facing description>"
) {
    private val obfuscatedA = "xx1" // search for '<snippet A>'
    private val obfuscatedB = "yy2" // search for '<snippet B>'

    override fun init() {
        // body: use findClass(...).hook(...) / hookConstructor(...)
    }

    override fun cleanup() {
        // unhook any reflection-cached resources, coroutines, threads
    }
}
```

- Extend `Hook` from `com.grindrplus.utils`.
- Use the `findClass(name)` helper (already a member of `Hook`) — do not call `GrindrPlus.loadClass` directly from a hook.
- Use `HookStage.BEFORE` to modify arguments / short-circuit results; `HookStage.AFTER` to patch return values.
- Use `HookAdapter.arg<T>(i)` and `HookAdapter.setArg(i, v)` instead of `param.args[i]` casts.

### 3.3 Registering the hook

After adding `hooks/<FeatureName>.kt`, add an entry to `utils/HookManager.kt:registerHooks`'s `hookList`:

```kotlin
FeatureName(),
```

…sorted alphabetically with the rest. Use the **display name** in the in-app toggle list, not the class name.

### 3.4 Documenting the hook

Create `docs/patches/<kebab-name>.md` using the stub template (see `.claude/skills/patch-doc-coverage/stub.template.md`). Cross-link from `docs/patches/README.md`. CI fails if any hook lacks this doc.

## 4. Patch Authoring Rules

- **One class per file** in `hooks/`.
- File name = PascalCase class name (e.g. `OnlineIndicator.kt`, `AntiBlock.kt`).
- Hook doc filename = snake_case + `.md` (e.g. `online_indicator.md`, `anti_block.md`).
- Each hook captures one user-visible feature; if you're tempted to add unrelated patches to the same hook file, split them.
- All hook code lives under `package com.grindrplus.hooks`.

## 5. Version Bump Workflow

When a new Grindr version drops:

1. **Fetch** the latest version + build number: `python3 fetch_version.py -o version.json`. This pulls from APKMirror and validates against `version.schema.json`.
2. **Patch** the two `listOf(...)` lines in `app/build.gradle.kts`:

   ```kotlin
   val grindrVersionName = listOf("<new>")
   val grindrVersionCode = listOf(<new_code>)
   ```

   Or invoke `/bump-grindr-version` (see `.claude/skills/bump-grindr-version/`).
3. **Verify** the banner: `./gradlew printVersionInfo --quiet` — must print `GrindrPlus v...<new Grindr version>`.
4. **Port** hooks: see `.claude/skills/update-obfuscated-symbols/`. Run `scripts/check-obfuscated-symbols.py` against a JADX dump of the new APK to find which `// search for` markers are stale. Manually update each affected hook and the corresponding `docs/patches/<name>.md` ("Verified against" section).
5. **Build & smoke-test** on a real device (LSPosed or LSPatch).
6. **Commit** with the message `Bump Grindr version support to <X.Y.Z>` (Co-Authored-By line for any AI assistance).

## 6. Build & Test Commands

| Command | What it does |
|---|---|
| `./gradlew setupLSPatch` | Downloads latest LSPatch release into `app/libs/lspatch.jar` and unzips the SO into `src/main/`. Idempotent. |
| `./gradlew assembleDebug` | Builds an unsigned debug APK at `app/build/outputs/apk/debug/GPlus_v*.apk`. |
| `./gradlew assembleRelease` | Builds a release APK. Needs GH secrets (`KEYSTORE_BASE64`, `KEY_ALIAS`, `KEYSTORE_PASSWORD`, `KEY_PASSWORD`) and is normally only invoked by `.github/workflows/build_apk.yml`. |
| `./gradlew printVersionInfo` | Prints `GrindrPlus v<versionCode>-<grindrVersion>_<gitHash>`. Use it to confirm a version bump landed. |
| `bash scripts/check-patch-docs.sh` | Exits 1 if any hook in `hooks/` lacks `docs/patches/<kebab>.md`. Wired into CI. |
| `python3 scripts/check-obfuscated-symbols.py --jadx <dir>` | Prints a Markdown table of every `// search for '<snippet>'` marker and its candidate symbol in the JADX output. Status ∈ {OK, AMBIGUOUS, STALE}. |
| `bash scripts/sync-grindr-version.sh --dry-run` | Wraps `fetch_version.py` + `sed`-patch of the two `listOf` lines. `--dry-run` shows the diff without writing. |

## 7. Do-Not-Touch

- `com.grindrapp.android_26.13.0.apkm` and any other `*.apkm` binary in the repo root — they are downloaded APK bundles and should be `.gitignore`'d (already untracked).
- `app/libs/*` — committed binaries including `lspatch.jar`; rebuilt by `./gradlew setupLSPatch`.
- `.github/workflows/build_apk.yml` secret values (`KEYSTORE_BASE64`, `KEY_ALIAS`, `KEYSTORE_PASSWORD`, `KEY_PASSWORD`, `TELEGRAM_BOT_TOKEN`, `TELEGRAM_CHAT_ID`).
- `local.properties`, `gradle-wrapper.jar`, anything under `build/` or `.gradle/`.
- `docs/img/` images.

## 8. Patch Lifecycle Semantics

- `Hook.init()` runs **once** when the module loads the host app, after `Config` confirms the hook is enabled via the in-app settings screen.
- `Hook.cleanup()` runs when the user toggles the hook off and on, or when `HookManager.reloadHooks()` is called. **It must release** any reflection-cached `Class` references, cancel coroutines (`scope.cancel()`), stop background threads, and reset static state.
- Hooks that touch a static field (e.g. `XposedHelpers.setObjectField`) should consider the case where the field is renamed between Grindr versions — the symbol-marker convention (§3.1) is what protects you here.

---

## Reusable skills

This repo ships skills under `.claude/skills/`:

- `bump-grindr-version` — automate §5 steps 1–3.
- `update-obfuscated-symbols` — automate §5 step 4.
- `patch-doc-coverage` — check and author the `docs/patches/*.md` coverage.
- `verify-build` — run `./gradlew assembleDebug` and surface failures.
- `pairip-bypass` — PairIP DRM reverse engineering, manifest repointing, and native JNI stub injection.
- `16kb-page-alignment` — 16KB memory page alignment diagnostics and fixes for Android 15/16+ (API 35+).
- `test-install-avd` — AVD deployment, Manager UI custom files installation, and testing workflow.

## See also

- `docs/README.md` — same search-for convention, from a human-author angle.
- `docs/env_setup.md` — full dev environment setup.
- `docs/patches/README.md` — index of per-hook design docs.
