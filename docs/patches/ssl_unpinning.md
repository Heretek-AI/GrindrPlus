# SSL unpinning

Disables TLS certificate pinning in the host app so Mitmproxy / HTTP Toolkit
can intercept HTTPS traffic during development.

Unlike most hooks in this package, this file is a top-level function
called directly from `XposedLoader.handleLoadPackage` under
`BuildConfig.DEBUG` — it is not registered with `HookManager` and never
appears in the in-app settings UI.

## Target

```kotlin
// SSLUnpinning.kt
findAndHookConstructor("okhttp3.OkHttpClient$Builder", …)
findAndHookMethod("okhttp3.OkHttpClient$Builder", …, "certificatePinner", …)
findAndHookMethod("com.android.org.conscrypt.TrustManagerImpl", …, "verifyChain", …)
```

## Verified against

- Grindr 26.13.0 — stable (targets public OkHttp / Conscrypt API)
- (latest verified Grindr version)

## Notes

- Targets fully-qualified classes from OkHttp / Conscrypt / Firebase —
  the obfuscation-marker convention doesn't apply because these symbols
  are stable across Grindr releases.
- Only runs under `BuildConfig.DEBUG` (see `XposedLoader`); release
  builds don't unpin.
