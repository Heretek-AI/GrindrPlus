# Enable unlimited

Enable Grindr Unlimited features.

## Target

<!-- Search markers used to locate this hook's symbols in newer Grindr APKs.
     Sourced from `EnableUnlimited.kt`. When porting this hook to a new
     Grindr version, paste each snippet into JADX's search box to
     locate the new obfuscated class / method name.

     See `AGENTS.md` § 3 (Hook Authoring Rules) for the full convention. -->

```kotlin
// EnableUnlimited.kt
val paywallUtils = "bya" // search for 'app_restart_required'
val persistentAdBannerContainer = "nb.d" // search for '(ComposeView) ViewBindings.findChildViewById(view,'
// search for '<unique snippet>'
// search for 'com.grindrapp.android.chat.presentation.ui.ChatActivityV2$subscribeToInterstitialAds$1$1$1'
// search for 'bind(Landroid/view/View;)Lcom/grindrapp/android/databinding/ProfileTagCascadeFragmentBinding;'
// search for '"bind(Landroid/view/View;)Lcom/grindrapp/android/databinding/DrawerProfileBinding;"'
// search for 'bind(Landroid/view/View;)Lcom/grindrapp/android/databinding/FragmentRadarBinding;'
// search for 'Intrinsics.checkNotNullParameter(roles, "roles");'
```

## Verified against

- Grindr 26.13.0 — initial port
- (latest verified Grindr version)

## Notes

<!-- Optional: feature flag gates, why a non-obvious method-body hook was needed,
     behavior on rooted vs unrooted devices, etc. -->
