---
name: verify-build
description: Run ./gradlew assembleDebug and surface build failures with file:line context. Use before opening a PR, after any edit in app/src/main/java, or to confirm a hook still compiles.
metadata:
  type: project
---

# verify-build

Compile-check the module. Skips the slower release build (which needs GH
secrets to sign).

## Inputs

None required.

## Required tools

- `./gradlew` (in repo root)

## Steps

1. Run from the repo root:

   ```bash
   ./gradlew assembleDebug --quiet --no-daemon
   ```

2. On success, the APK lands at
   `app/build/outputs/apk/debug/GPlus_v*.apk`. Print its path and size:

   ```bash
   ls -lh app/build/outputs/apk/debug/*.apk
   ```

3. On failure, Gradle prints errors in `e: file.kt:LINE: message` form.
   Extract and surface the first 80 lines, plus the file:line of each
   compile error.

## What this skill does NOT do

- Does **not** install on a device. That's a manual step.
- Does **not** run instrumented tests — there are none in this repo.
- Does **not** build the release APK. `assembleRelease` needs the keystore
  secrets defined in `.github/workflows/build_apk.yml`.

## Failure modes

- **`SDK location not found`**: `local.properties` is missing or stale.
  Open the project in Android Studio once so it generates the file.
- **`compileSdk 35 not found`**: Android Studio hasn't installed the SDK
  platform. Open SDK Manager and install Android 35.
- **`lspatch.jar not found`**: run `./gradlew setupLSPatch` first. The
  build expects the JAR at `app/libs/lspatch.jar`.
- **`Unsupported class file major version`**: Java version mismatch.
  `compileOptions { sourceCompatibility = JavaVersion.VERSION_17 }` —
  use JDK 17.

## When to run

- After any edit in `app/src/main/java/`.
- Before `git commit` of hook changes (or as a pre-commit hook).
- After `./gradlew setupLSPatch` (to confirm the new `lspatch.jar` is
  compatible with the hook DSL).
