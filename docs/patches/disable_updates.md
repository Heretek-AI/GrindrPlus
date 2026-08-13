# Disable updates

Disable forced updates.

## Target

<!-- Search markers used to locate this hook's symbols in newer Grindr APKs.
     Sourced from `DisableUpdates.kt`. When porting this hook to a new
     Grindr version, paste each snippet into JADX's search box to
     locate the new obfuscated class / method name.

     See `AGENTS.md` § 3 (Hook Authoring Rules) for the full convention. -->

```kotlin
// DisableUpdates.kt
val appUpdateZzm = "com.google.android.play.core.appupdate.zzm" // search for 'requestUpdateInfo(%s)'
val appUpgradeManager = "hr8" // search for 'Uri.parse("market://details?id=com.grindrapp.android");'
// search for '<unique snippet>'
// search for '.setMessage(R.string.deprecation_message);'
```

## Verified against

- Grindr 26.13.0 — initial port
- (latest verified Grindr version)

## Notes

<!-- Optional: feature flag gates, why a non-obvious method-body hook was needed,
     behavior on rooted vs unrooted devices, etc. -->
