# Local saved phrases

Save unlimited phrases locally.

## Target

<!-- Search markers used to locate this hook's symbols in newer Grindr APKs.
     Sourced from `LocalSavedPhrases.kt`. When porting this hook to a new
     Grindr version, paste each snippet into JADX's search box to
     locate the new obfuscated class / method name.

     See `AGENTS.md` § 3 (Hook Authoring Rules) for the full convention. -->

```kotlin
// LocalSavedPhrases.kt
val phrasesRestService = "com.grindrapp.android.api.PhrasesRestService" // search for 'v3/me/prefs'
val createSuccessResult = "xz3" // search for 'Success(successValue='
val chatRestService = "com.grindrapp.android.chat.data.datasource.api.service.ChatRestService" // search for '"v4/chat/conversation/{conversationId}"'
// search for '<unique snippet>'
```

## Verified against

- Grindr 26.13.0 — initial port
- (latest verified Grindr version)

## Notes

<!-- Optional: feature flag gates, why a non-obvious method-body hook was needed,
     behavior on rooted vs unrooted devices, etc. -->
