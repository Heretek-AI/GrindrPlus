package com.grindrplus.hooks

import com.grindrplus.utils.Hook
import com.grindrplus.utils.HookStage
import com.grindrplus.utils.hook
/**
 * Disable analytics.
 *
 * Disable Grindr analytics (data collection).
 *
 * Hooks `com.grindrapp.android` to add/modify this feature. See
 * `docs/patches/disable_analytics.md` for design notes and version-port history.
 *
 * **Obfuscation marker convention.** Every capture of an obfuscated
 * symbol (single- or two-letter class/method name) in this file carries
 * an inline comment whose body is a stable, unique snippet from the
 * JADX-decompiled source. When porting this hook to a new Grindr
 * version, paste each snippet into JADX's search box to find the new
 * obfuscated name. See `AGENTS.md` § 3 for the full convention.
 *
 * **Lifecycle.** `init()` is invoked once by HookManager.registerHooks()
 * after `Config` confirms the hook is enabled. `cleanup()` is invoked on
 * HookManager.reloadHooks() and must release any reflection-cached
 * resources, coroutines, or threads.
 */

class DisableAnalytics : Hook(
    "Disable analytics",
    "Disable Grindr analytics (data collection)"
) {
    override fun init() {
        // Braze
        findClass("com.braze.Braze\$Companion")
            // See https://braze-inc.github.io/braze-android-sdk/kdoc/braze-android-sdk/com.braze/-braze/-companion/outbound-network-requests-offline.html
            // Investigate switching to https://braze-inc.github.io/braze-android-sdk/kdoc/braze-android-sdk/com.braze/-braze/-companion/enable-mock-network-requests-and-drop-events-mode.html
            .hook("setOutboundNetworkRequestsOffline", HookStage.BEFORE) {
                param -> param.setArg(0, true)
            }

        // Digital Turbine
        findClass("com.fyber.inneractive.sdk.network.i")
            .hook("a", HookStage.BEFORE) {
                param -> param.setResult(null)
            }

        // Google Analytics
        findClass("com.google.firebase.analytics.FirebaseAnalytics")
            .hook("setAnalyticsCollectionEnabled", HookStage.BEFORE) { param ->
                param.setArg(0, false)
            }

        // Google Crashlytics
        findClass("com.google.firebase.crashlytics.FirebaseCrashlytics")
            .hook("setCrashlyticsCollectionEnabled", HookStage.BEFORE) { param ->
                param.setArg(0, false)
            }

        // Ironsource
        findClass("com.ironsource.mediationsdk.server.ServerURL")
            .hook("getRequestURL", HookStage.BEFORE) {
                param -> param.setResult(null)
            }

        // Liftoff (Vungle)
        findClass("com.vungle.ads.internal.network.VungleApiClient")
            .hook("config", HookStage.BEFORE) {
                param -> param.setResult(null)
            }

        // Unity
        findClass("com.unity3d.services.ads.UnityAdsImplementation")
            .hook("getInstance", HookStage.BEFORE) {
                param -> param.setResult(null)
            }
    }
}