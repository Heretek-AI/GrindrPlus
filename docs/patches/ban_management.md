# Ban management

Provides comprehensive ban management tools (detailed ban info, etc.).

## Target

<!-- Search markers used to locate this hook's symbols in newer Grindr APKs.
     Sourced from `BanManagement.kt`. When porting this hook to a new
     Grindr version, paste each snippet into JADX's search box to
     locate the new obfuscated class / method name.

     See `AGENTS.md` § 3 (Hook Authoring Rules) for the full convention. -->

```kotlin
// BanManagement.kt
val authServiceClass = "com.grindrapp.android.api.LoginRestService" // search for 'v3/users/password-validation'
val bannedArgs = "zi0" // search for 'new StringBuilder("BannedArgs(bannedType=")'
// search for '<unique snippet>'
```

## Verified against

- Grindr 26.13.0 — initial port
- (latest verified Grindr version)

## Notes

<!-- Optional: feature flag gates, why a non-obvious method-body hook was needed,
     behavior on rooted vs unrooted devices, etc. -->
