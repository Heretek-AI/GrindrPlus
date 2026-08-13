# Disable PairIP

Bypasses the PairIP integrity + license + install-source checks that
block the host app on non-Play-Store installs.

## Target

The hook targets the PairIP SDK class FQCNs directly — these are **stable
across Grindr versions** and don't need `// search for` markers. PairIP
packages are not obfuscated by R8 (they're a vendor SDK).

```kotlin
// DisablePairIP.kt
"com.pairip.application.Application"                            // wrapper app class
"com.pairip.SignatureCheck"                                     // APK signature whitelist
"com.pairip.VMRunner"                                           // native VM (loads libpairipcore.so)
"com.pairip.StartupLauncher"                                    // IAP VM entry point
"com.pairip.licensecheck.LicenseClient"                         // server + local license check
"com.pairip.licensecheck.LicenseActivity"                       // "Get from Play" dialog
"android.content.pm.PackageManager"                             // getInstallerPackageName (SDK < 30)
```

## Approach

Mirrors morphe-patches' `killPairIpFull` + `pairIPManifestPatch`
([source](https://github.com/rushiranpise/morphe-patches/blob/main/patches/src/main/kotlin/app/template/patches/shared/PairIp.kt))
but expressed as Xposed runtime hooks instead of smali patches.

### What the hook does

1. Skips `com.pairip.application.Application.attachBaseContext` so the
   `VMRunner.setContext` + `SignatureCheck.verifyIntegrity` +
   `LicenseClient.checkLicense` triad never runs.
2. `com.pairip.SignatureCheck.verifyIntegrity` → no-op.
3. `com.pairip.VMRunner.<clinit>` → return-void so `loadLibrary("pairipcore")`
   never runs.
4. `com.pairip.StartupLauncher.launch` → no-op.
5. `com.pairip.licensecheck.LicenseClient.checkLicense` → no-op.
6. `com.pairip.licensecheck.LicenseClient.initializeLicenseCheck` → no-op.
7. `com.pairip.licensecheck.LicenseClient.performLocalInstallerCheck`
   → return true (Play Store = "com.android.vending" pass).
8. `com.pairip.licensecheck.LicenseClient.processResponse` → no-op.
9. `com.pairip.licensecheck.LicenseClient.startPaywallActivity` → no-op.
10. `com.pairip.licensecheck.LicenseActivity.onStart` → finish() the
    dialog before it renders.
11. `android.content.pm.PackageManager.getInstallerPackageName` → returns
    "com.android.vending" for any app that asks (SDK < 30).

## Important limitation: timing

The PairIP `Application.attachBaseContext` runs **before** any Xposed
hook can fire. The flow is:

1. Process spawns
2. ClassLoader loads `com.pairip.application.Application` (the
   AndroidManifest entry)
3. `Application.attachBaseContext` runs the verification chain
4. *Only then* does the LSPosed module load + register hooks
5. Hooks fire on subsequent method calls

This means the hook in this file **cannot intercept the initial
verification chain**. The state set by step 3 (NOT_LICENSED, etc.) is
locked in by the time the hook is registered.

### What this hook IS useful for

- The **repeated background check** that PairIP runs every 5 minutes
  (`repeatedCheckEnabled = true`). Our hook stops this on the second
  iteration onward.
- The **LicenseActivity dialog** — if the initial check fails, the
  dialog is shown; our hook finishes it before any UI renders.
- **`InstallSourceInfo.getInstallingPackageName`** for OTHER code paths
  beyond PairIP (analytics, ad SDKs, etc.) that consult the installer.

### What requires build-time patching

To bypass PairIP on the **initial** app launch, you need to patch
Grindr's bytecode **before** the app runs. This is what morphe-patches
does. Two options:

1. **Morphe / LSPatch (recommended)** — install Morphe from
   https://github.com/MorpheApp/morphe-cli or use the in-app
   `manager/installation` flow. Embed the GPlus dex into Grindr and
   the manifest will be patched at the same time.
2. **Hand-rolled arsclib rewrite** — `app/src/main/java/com/grindrplus/manager/installation/`
   uses `arsclib` to manually rewrite the APK. Requires Shizuku or
   root to install the result.

If using the GPlus manager UI, the build-time patch is applied
automatically — the resulting APK has `<application android:name="com.grindrapp.android.RealApplication">`
directly (skipping the PairIP wrapper), and the LicenseActivity
declaration is removed from the manifest.

## Verified against

- Grindr 26.13.0 — initial port (PairIP library `pairipcore.so` loads,
  `LicenseClient.checkLicense` confirmed called from
  `com.pairip.application.Application.attachBaseContext`)

## Other techniques surveyed (not applied)

- **Boot-time class-loader interception** — hooking
  `java.lang.ClassLoader.loadClass` to suppress `com.pairip.application.Application`
  before it loads. Fragile (breaks other class loading) and not
  necessary if the build-time patch is applied.
- **Play Integrity spoofing** — Grindr also calls
  `com.google.android.play.core.integrity.IntegrityManager.requestIntegrityToken`
  after the PairIP layer. Bypassing requires hooking the response
  parser and returning a fake `StandardIntegrityToken` blob. Out of
  scope for this hook.
- **`Application.onCreate` override** — too late; the LicenseCheckState
  is already set by the time `onCreate` fires.
