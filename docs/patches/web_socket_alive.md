# Keep Alive WebSocket

Prevents WebSocket disconnections when app goes to background. Causes battery drain, use with caution.

## Target

<!-- Search markers used to locate this hook's symbols in newer Grindr APKs.
     Sourced from `WebSocketAlive.kt`. When porting this hook to a new
     Grindr version, paste each snippet into JADX's search box to
     locate the new obfuscated class / method name.

     See `AGENTS.md` § 3 (Hook Authoring Rules) for the full convention. -->

```kotlin
// WebSocketAlive.kt
// search for '<unique snippet>'
```

## Verified against

- Grindr 26.13.0 — initial port
- (latest verified Grindr version)

## Notes

<!-- Optional: feature flag gates, why a non-obvious method-body hook was needed,
     behavior on rooted vs unrooted devices, etc. -->
