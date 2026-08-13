# Smali-patch Grindr (research) — bypass PairIP + install-source at the bytecode level

A standalone, command-line path that applies the morphe-patches
`killPairIpFull` + `pairIPManifestPatch` techniques directly to a
downloaded Grindr APK. Useful for research, unit tests, and debugging
LSPatch behavior. **Not** the production deployment path (the GPlus
manager's LSPatch flow is).

## Two scripts

| Script | What it does |
|---|---|
| `scripts/patch-grindr-manifest.patch.py` | Replaces `com.pairip.application.Application` → `com.grindrapp.android.RealApplication` in AndroidManifest; removes `<activity name="com.pairip.licensecheck.LicenseActivity">` and `<uses-permission name="com.android.vending.CHECK_LICENSE">`. |
| `scripts/patch-grindr-smali.patch.py` | Neutralises the 10 PairIP SDK methods at the bytecode level (returns no-op or true). Uses `apktool`'s smali round-trip. |

Both scripts assume `apktool`, `zipalign`, and `apksigner` are on `$PATH`
and that a debug keystore exists at `/tmp/debug.keystore` (or pass one
via `apksigner --ks`).

## Manual deployment sequence

```bash
# 1. Decompile + extract base.apk from the .apkm bundle
unzip -j com.grindrapp.android_26.13.0.apkm 'base.apk' 'split_config.x86_64.apk' 'split_config.xhdpi.apk' -d /tmp/grindr-extract

# 2. Apply manifest patch
python3 scripts/patch-grindr-manifest.patch.py /tmp/grindr-extract/base.apk /tmp/grindr-manifest-patched.apk

# 3. Apply smali patch (only works on the manifest-patched APK)
python3 scripts/patch-grindr-smali.patch.py /tmp/grindr-manifest-patched.apk /tmp/grindr-final.apk

# 4. zipalign + sign all components with the same key
zipalign -p -f 4 /tmp/grindr-final.apk /tmp/grindr-final-aligned.apk
apksigner sign --ks /tmp/debug.keystore --ks-pass pass:android --key-pass pass:android \
    --out /tmp/grindr-final-signed.apk /tmp/grindr-final-aligned.apk

# 5. Repeat signing for the splits (must use the same key)
zipalign -p -f 4 /tmp/grindr-extract/split_config.x86_64.apk /tmp/split_x86_64_aligned.apk
apksigner sign --ks /tmp/debug.keystore --ks-pass pass:android --key-pass pass:android \
    --out /tmp/split_x86_64_signed.apk /tmp/split_x86_64_aligned.apk
# ... same for split_config.xhdpi.apk

# 6. Uninstall any existing Grindr + install
adb uninstall com.grindrapp.android
adb install-multiple /tmp/grindr-final-signed.apk /tmp/split_x86_64_signed.apk /tmp/split_xhdpi_signed.apk
```

## What gets bypassed

The patch sequence targets every guard in the **morphe-patches
`killPairIpFull`** list:

- `com.pairip.SignatureCheck.verifyIntegrity` → no-op
- `com.pairip.VMRunner.<clinit>` → no-op (skips `loadLibrary("pairipcore")`)
- `com.pairip.VMRunner.invoke` → no-op
- `com.pairip.StartupLauncher.launch` → no-op
- `com.pairip.licensecheck.LicenseClient.checkLicense` → no-op
- `com.pairip.licensecheck.LicenseClient.initializeLicenseCheck` → no-op
- `com.pairip.licensecheck.LicenseClient.checkLicenseInternal` → no-op
- `com.pairip.licensecheck.LicenseClient.processResponse` → no-op
- `com.pairip.licensecheck.LicenseClient.startPaywallActivity` → no-op
- `com.pairip.licensecheck.LicenseClient.performLocalInstallerCheck` → returns true
- AndroidManifest: `com.pairip.application.Application` → `com.grindrapp.android.RealApplication`
- AndroidManifest: `LicenseActivity` declaration removed
- AndroidManifest: `CHECK_LICENSE` permission removed

## Verified against

- Grindr 26.13.0 (signature `170510`) — manifest patch works cleanly.
  Smali patch works at the bytecode level but **introduces a kotlin
  metadata corruption** when used with `apktool 2.10.0` + `baksmali 2.5.2`;
  the resulting APK crashes at startup with
  `NullPointerException: key can't be null` from
  `kotlinx.coroutines.scheduling.DefaultScheduler.<init>`.

## Known limitation: apktool + Kotlin metadata

`apktool`'s smali round-trip drops or corrupts parts of the `kotlinx.metadata`
annotations that the Kotlin runtime depends on at class initialization.
This is a known limitation; the official workaround is to use
`com.reandroid.apk.ARSCDecoder` (or `arsclib`, which is already a project
dependency — see `app/src/main/java/com/grindrplus/manager/installation/steps/PatchApkStep.kt`)
to manipulate the DEX files directly without going through smali.

The GPlus manager's `PatchApkStep` uses exactly this approach:

```kotlin
val apkModule = ApkModule.loadApkFile(baseApk)
// ... modify apkModule in place with arsclib ...
apkModule.writeApk(baseApk)
```

This avoids the smali round-trip and preserves Kotlin metadata. Use the
manager's LSPatch flow for production deployments.

## Production deployment

The actual deployment path for GrindrPlus patches uses the GPlus manager
UI on the target device:

1. Install the GPlus APK (the manager).
2. Open the manager → Install tab.
3. Select the target Grindr version from the dropdown.
4. Tap Install → the manager downloads Grindr, applies LSPatch (which
   embeds the GPlus dex AND applies the same morphe-style pairip
   bytecode patch), signs, and installs.
5. On rooted: works directly (LSPosed scope).
6. On unrooted: requires Shizuku (or `adb install` afterwards).
7. The build-time LSPatch is the only way to bypass PairIP cleanly
   because the runtime hooks inside the GPlus dex can't fire early
   enough to intercept `Application.attachBaseContext`.

## Other techniques surveyed (not applied)

- **Direct dex lib patching with `dexlib`** — preserves Kotlin metadata,
  supports arbitrary method patching. More code than the smali scripts
  but cleaner. Would need to be added as a project dep.
- **Frida with `--no-pause` at app startup** — loads before Application
  attaches, can hook `<clinit>` directly. Requires root.
- **Boot-time class-loader interception** — hooks
  `java.lang.ClassLoader.loadClass` to suppress
  `com.pairip.application.Application`. Fragile (breaks other class
  loading) and adds no value over the LSPatch approach.
