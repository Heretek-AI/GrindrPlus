# Unlimited profiles

Allow unlimited profiles.

## Target

<!-- Search markers used to locate this hook's symbols in newer Grindr APKs.
     Sourced from `UnlimitedProfiles.kt`. When porting this hook to a new
     Grindr version, paste each snippet into JADX's search box to
     locate the new obfuscated class / method name.

     See `AGENTS.md` § 3 (Hook Authoring Rules) for the full convention. -->

```kotlin
// UnlimitedProfiles.kt
val onProfileClicked = "com.grindrapp.android.ui.browse.e0" // search for 'com.grindrapp.android.ui.browse.ServerDrivenCascadeViewModel$onProfileClicked$1'
// search for '<unique snippet>'
// search for 'new StringBuilder("cascadeClickEvent/position=");'
```

## Verified against

- Grindr 26.13.0 — initial port
- (latest verified Grindr version)

## Notes

<!-- Optional: feature flag gates, why a non-obvious method-body hook was needed,
     behavior on rooted vs unrooted devices, etc. -->
