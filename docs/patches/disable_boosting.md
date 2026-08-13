# Disable boosting

Get rid of all upsells related to boosting.

## Target

<!-- Search markers used to locate this hook's symbols in newer Grindr APKs.
     Sourced from `DisableBoosting.kt`. When porting this hook to a new
     Grindr version, paste each snippet into JADX's search box to
     locate the new obfuscated class / method name.

     See `AGENTS.md` § 3 (Hook Authoring Rules) for the full convention. -->

```kotlin
// DisableBoosting.kt
val drawerProfileUiState = "com.grindrapp.android.ui.drawer.model.DrawerProfileUiState" // search for 'DrawerProfileUiState(showBoostMeButton='
val radarUiModel = "tq8" // search for 'RadarUiModel(boostButton='
// search for '<unique snippet>'
```

## Verified against

- Grindr 26.13.0 — initial port
- (latest verified Grindr version)

## Notes

<!-- Optional: feature flag gates, why a non-obvious method-body hook was needed,
     behavior on rooted vs unrooted devices, etc. -->
