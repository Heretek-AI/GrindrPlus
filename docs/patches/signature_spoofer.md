# Signature spoofer

Forces Firebase / Facebook SDKs to report the *original* Grindr APK
signature instead of the LSPatch-repackaged one. Without this, Firebase
Installations + Facebook Login reject mismatched signatures.

Unlike most hooks in this package, this file is a top-level function
called directly from `XposedLoader.handleLoadPackage` — it is not
registered with `HookManager` and never appears in the in-app settings
UI.

## Target

```kotlin
// SignatureSpoofer.kt
findAndHookMethod("com.google.firebase.installations.remote.FirebaseInstallationServiceClient", …, "getFingerprintHashForPackage", …)
findAndHookMethod("com.google.firebase.remoteconfig.internal.ConfigRealtimeHttpClient", …, "getFingerprintHashForPackage", …)
findAndHookMethod("com.google.firebase.remoteconfig.internal.ConfigFetchHttpClient", …, "getFingerprintHashForPackage", …)
findAndHookMethod("ly.img.android.c", …, "d", …)  // getPackageName
findAndHookMethod("com.facebook.login.KatanaProxyLoginMethodHandler", …, "tryAuthorize", …)
```

## Verified against

- Grindr 26.13.0 — stable (targets public Firebase / Facebook SDK)
- (latest verified Grindr version)

## Notes

- Targets fully-qualified classes from Firebase / Facebook / Ly — the
  obfuscation-marker convention doesn't apply because these symbols are
  stable across Grindr releases.
- `ly.img.android.c::d` is an obfuscated symbol (Ly Image SDK), not a
  Grindr symbol. It maps to `getPackageName`; if Ly renames it in a
  future release, re-search JADX for the equivalent.
- The Firebase / Facebook `getFingerprintHashForPackage` hook always
  returns `823f5a17c33b16b4775480b31607e7df35d67af8` — the original
  Grindr signing-cert SHA-1 fingerprint.
