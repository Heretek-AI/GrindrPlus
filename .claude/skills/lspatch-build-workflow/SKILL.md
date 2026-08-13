---
name: lspatch-build-workflow
description: How to build the GrindrPlus APK and embed it in a target Grindr install. Covers the gradle setupLSPatch task, assembleDebug vs assembleRelease, and the two installation paths (rooted LSPosed / unrooted LSPatch).
metadata:
  type: project
---

# LSPatch / Build Workflow

How to build and ship the GrindrPlus APK, including the difference
between the rooted (LSPosed) and unrooted (LSPatch) installation paths
and which gradle tasks each one needs.

## When to use

- First-time local build of the module
- Setting up a CI run
- Diagnosing a failed `assembleDebug` or `assembleRelease`
- Understanding what `setupLSPatch` does and when to re-run it

## Two install paths

The module ships a single APK that supports **both** installation modes.
The user picks one at install time:

| Path | Rooted? | How the module runs | Build flow |
|---|---|---|---|
| **LSPosed** | Yes | Android system loads `XposedLoader` from `app/src/main/assets/xposed_init` for every package in scope. | Build the GrindrPlus module APK; install on the device; enable in LSPosed UI; the *unpatched* Grindr APK from the Play Store runs as normal and gets hooked. |
| **LSPatch** | No | The Grindr APK is repackaged with GrindrPlus's classes baked in. No Xposed runtime needed. | Build the GrindrPlus module APK; the manager app (or `./gradlew setupLSPatch` + manual steps) repackages a target Grindr `.apk` with the module inside. |

The repository itself is the **LSPosed module APK**. The LSPatch
packaging step happens at install time via the manager app's UI
(`manager/installation/`); it does not need a separate build step in
this repo.

## Required gradle tasks

### `./gradlew setupLSPatch` (one-time, idempotent)

Downloads the latest LSPatch release from `nightly.link/JingMatrix/LSPatch`,
unzips it, places `lspatch.jar` at `app/libs/lspatch.jar`, and extracts
the SO binaries into `app/src/main/assets/lspatch/`. Re-run this if:

- You switched branches and the build complains `lspatch.jar not found`
- You want to pick up a new LSPatch release
- You want to reset the bundled assets

```bash
./gradlew setupLSPatch
```

### `./gradlew assembleDebug` (every build, no secrets needed)

Builds the unsigned debug APK:

```
app/build/outputs/apk/debug/GPlus_v*-debug.apk
```

Use this for local development. The output APK is signed with the
debug keystore (auto-generated), which is fine for `adb install` on a
device. **It is NOT** the same APK that ships to users.

### `./gradlew assembleRelease` (CI only)

Builds the release APK. Needs the following GitHub secrets (defined in
`.github/workflows/build_apk.yml`):

- `KEYSTORE_BASE64` — base64 of the signing keystore
- `KEY_ALIAS`, `KEYSTORE_PASSWORD`, `KEY_PASSWORD` — keystore credentials
- `TELEGRAM_BOT_TOKEN`, `TELEGRAM_CHAT_ID` — for the CI artifact upload
  (optional; controlled by the workflow_dispatch input `share_to_telegram`)

Local release builds are not supported without the keystore. CI is the
right place for this.

### `./gradlew printVersionInfo` (sanity check)

Prints the version banner; useful after a `bump-grindr-version`:

```bash
$ ./gradlew printVersionInfo --quiet
GrindrPlus v4.8.0-26.13.0_<short_hash>
```

CI captures this into `$GITHUB_ENV.VERSION_INFO` to label the artifact.

## Local dev loop

```bash
# 1. (Once per clone) download LSPatch assets
./gradlew setupLSPatch

# 2. (Once per source change) build + install
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/GPlus_v*-debug.apk

# 3. Sanity check
./gradlew printVersionInfo
```

If `adb install` fails with a version-mismatch dialog, you have an older
GPlus installed. Uninstall first (`adb uninstall com.grindrplus`) and
reinstall.

## Where to look when something breaks

| Symptom | Where to look |
|---|---|
| `SDK location not found` | `local.properties` is missing; let Android Studio generate it. |
| `compileSdk 35 not found` | SDK Manager → install Android 35. |
| `lspatch.jar not found` | Run `./gradlew setupLSPatch`. |
| `Unsupported class file major version` | You're on JDK 25; switch to JDK 17 (the project requires 17 per `compileOptions { sourceCompatibility = JavaVersion.VERSION_17 }`). |
| `ClassNotFoundException` at runtime | An obfuscated symbol in `hooks/*.kt` went stale; run the `update-obfuscated-symbols` skill against a fresh JADX dump. |
| Hook fires but the host app crashes | The hook body threw; check logcat for the GrindrPlus log source — `Logger.writeRaw(e.stackTraceToString())` calls surface there. |
| `Version mismatch` dialog | The on-device Grindr version doesn't match `TARGET_GRINDR_VERSION_NAMES` in `BuildConfig`. Bump the version with the `bump-grindr-version` skill. |

## CI workflows

- **`.github/workflows/build_apk.yml`** — manual `workflow_dispatch`.
  Runs JDK 17 (zulu), `printVersionInfo`, `setupLSPatch`, `assembleDebug`,
  `assembleRelease`, signs the release APK with `apksigner`, uploads
  Debug + Release to Telegram, and uploads them as GH artifacts.
  Also runs the `Verify patch-doc coverage` step (added in commit `112b1e8`)
  which fails the build if any hook lacks its `docs/patches/<kebab>.md`.
- **`.github/workflows/extract_base.yml`** — utility workflow.
  `workflow_dispatch` with a `bundle_url` input. Downloads the bundle,
  extracts `base.apk`, uploads it as a GH artifact for JADX decompilation.

## See also

- `AGENTS.md` § 6 (Build & Test Commands) — command reference
- `docs/env_setup.md` — full dev-environment setup including JADX + mitmproxy
- `README.md` — user-facing installation instructions (LSPosed + LSPatch)
- `.claude/skills/verify-build/` — runs `assembleDebug` and surfaces failures
- `.claude/skills/bump-grindr-version/` — automated version bump

## Do not

- Don't manually edit `app/libs/lspatch.jar` or anything under
  `app/src/main/assets/lspatch/` — both are managed by `setupLSPatch` and
  will be overwritten next run.
- Don't try to install a `release` APK directly via `adb` — it's signed
  with the production keystore and Android Studio's debug-keystore trust
  chain will reject it. Use the Debug APK locally; CI uploads the Release.
- Don't bypass `setupLSPatch` by downloading `lspatch.jar` by hand. The
  task also strips two conflicting class paths
  (`ListenableFuture.class`, `errorprone/annotations/*`) that cause
  runtime crashes if left in.
