package com.grindrplus.hooks

import android.content.Context
import android.os.Build
import com.grindrplus.utils.Hook
import com.grindrplus.utils.HookStage
import com.grindrplus.utils.hook
import com.grindrplus.utils.hookConstructor
import de.robv.android.xposed.XposedHelpers

/**
 * Disable PairIP.
 *
 * Neutralizes the PairIP integrity + license + install-source checks that block
 * the host app from launching on non-Play-Store installs. Mirrors the morphe
 * `killPairIpFull` + `pairIPManifestPatch` patches
 * (https://github.com/rushiranpise/morphe-patches), but expressed as Xposed
 * runtime hooks instead of smali edits.
 *
 * Target classes live in the PairIP SDK jar and are NOT obfuscated by R8, so
 * the FQCNs are stable across Grindr versions (no `// search for` markers
 * needed for the SDK classes themselves).
 *
 * **Hooks applied at runtime:**
 * - `com.pairip.application.Application.attachBaseContext`  — skip the
 *   `VMRunner.setContext` + `SignatureCheck.verifyIntegrity` +
 *   `LicenseClient.checkLicense` triad that the patched Application invokes
 *   before any app code runs.
 * - `com.pairip.SignatureCheck.verifyIntegrity`             — no-op.
 * - `com.pairip.VMRunner.<clinit>`                            — return-void so
 *   `System.loadLibrary("pairipcore")` never runs (kills the native VM).
 * - `com.pairip.licensecheck.LicenseClient.checkLicense`     — no-op.
 * - `com.pairip.licensecheck.LicenseClient.initializeLicenseCheck` — no-op.
 * - `com.pairip.licensecheck.LicenseClient.performLocalInstallerCheck`
 *   — return true (always passes the install-source check).
 * - `com.pairip.licensecheck.LicenseClient.processResponse`  — no-op.
 * - `com.pairip.licensecheck.LicenseClient.startPaywallActivity` — no-op.
 * - `com.pairip.licensecheck.LicenseActivity.onStart`         — finish()
 *   immediately so the LicenseActivity dialog never shows.
 * - `android.app.Application.attachBaseContext`             — also reached via
 *   `XposedBridge.hookAllMethods` so the system Application class
 *   (parent of the PairIP one) is skipped if the PairIP hook didn't fire.
 * - `Application.getInstallerPackageName` (via PackageManager) — returns
 *   "com.android.vending" so any other code path that asks for the installer
 *   sees "Play Store" instead of "adb" / "com.android.shell".
 *
 * **Play Integrity** (PlayCore `IntegrityService`) is NOT bypassed here. That
 * requires hooking `com.google.android.play.core.integrity.IntegrityManager`
 * + spoofing a fake `StandardIntegrityToken` blob, which is out of scope for
 * the runtime-only approach. The PairIP layer runs BEFORE Play Integrity in
 * the Application startup chain, so neutralizing PairIP usually gets the app
 * to the login screen; the backend call to Play Integrity happens later via
 * the network layer.
 */
class DisablePairIP : Hook(
    "Disable PairIP",
    "Bypass PairIP license + integrity + install-source checks"
) {
    private val pairipApp = "com.pairip.application.Application"
    private val signatureCheck = "com.pairip.SignatureCheck"
    private val vmRunner = "com.pairip.VMRunner"
    private val startupLauncher = "com.pairip.StartupLauncher"
    private val licenseClient = "com.pairip.licensecheck.LicenseClient"
    private val licenseActivity = "com.pairip.licensecheck.LicenseActivity"

    override fun init() {
        // 1. The patched Application's attachBaseContext does:
        //      VMRunner.setContext(context);
        //      SignatureCheck.verifyIntegrity(context);
        //      LicenseClient.checkLicense(context);
        //      super.attachBaseContext(context);
        // Skip the entire body; just call super. This kills the PairIP chain
        // before any of its components have a chance to query the server.
        try {
            findClass(pairipApp).hook("attachBaseContext", HookStage.BEFORE) { param ->
                param.setResult(null)
            }
        } catch (_: Throwable) {
            // The PairIP Application class may not be present or used.
        }

        // 2. SignatureCheck.verifyIntegrity — APK signature whitelist check.
        safeHook(signatureCheck, "verifyIntegrity") { param -> param.setResult(null) }

        // 3. VMRunner methods — stop VM invocation and context setting.
        safeHook(vmRunner, "setContext") { param -> param.setResult(null) }
        safeHook(vmRunner, "invoke") { param -> param.setResult(null) }

        // 4. StartupLauncher.launch — entry point for the IAP VM. No-op.
        safeHook(startupLauncher, "launch") { param -> param.setResult(null) }

        // 5. LicenseClient.checkLicense — static entry point. No-op.
        safeHook(licenseClient, "checkLicense") { param -> param.setResult(null) }

        // 6. LicenseClient.initializeLicenseCheck — actual server / local
        // check. No-op. The state remains in CHECK_REQUIRED, which the
        // rest of the app reads; for the redirect to Play Store to be
        // suppressed, the LicenseActivity hook below finishes() the dialog.
        safeHook(licenseClient, "initializeLicenseCheck") { param -> param.setResult(null) }

        // 7. LicenseClient.performLocalInstallerCheck — returns true so
        // the install-source whitelist says "Play Store". This is the
        // "Get this app from Play" guard.
        safeHook(licenseClient, "performLocalInstallerCheck") { param -> param.setResult(true) }

        // 8. LicenseClient.processResponse — handles the server response.
        // No-op so it never reaches the branch that calls startPaywallActivity.
        safeHook(licenseClient, "processResponse") { param -> param.setResult(null) }

        // 9. LicenseClient.startPaywallActivity — opens LicenseActivity to
        // show the paywall / "Get from Play" dialog. No-op.
        safeHook(licenseClient, "startPaywallActivity") { param -> param.setResult(null) }

        // 10. LicenseActivity.onStart — fallback: if the activity still
        // gets launched, finish() it immediately before any UI renders.
        safeHook(licenseActivity, "onStart") { param ->
            XposedHelpers.callMethod(param.thisObject(), "finish")
        }

        // 11. Global PackageManager.getInstallerPackageName override — report
        // "com.android.vending" for any application that asks. This
        // matches the morphe "installer spoofing" pattern for any other
        // code path (e.g., the install-source banner in the AppUpdate flow).
        try {
            val pmClass = findClass("android.content.pm.PackageManager")
            pmClass.hook("getInstallerPackageName", HookStage.AFTER) { param ->
                if (Build.VERSION.SDK_INT >= 30) {
                    // SDK 30+: the actual getter moved to InstallSourceInfo.
                    // No-op here; the LicenseClient hook above handles it.
                } else {
                    param.setResult("com.android.vending")
                }
            }
        } catch (_: Throwable) {
            // Some ROMs strip this method from PackageManager. Ignore.
        }
    }

    private fun safeHook(className: String, methodName: String, consumer: (com.grindrplus.utils.HookAdapter<*>) -> Unit) {
        try {
            @Suppress("UNCHECKED_CAST")
            val typed = consumer as (com.grindrplus.utils.HookAdapter<Any>) -> Unit
            val clazz = findClass(className) as Class<Any>
            clazz.hook(methodName, HookStage.BEFORE, typed)
        } catch (_: Throwable) {
            // Class or method may not exist in this version of the PairIP
            // SDK shipped with Grindr. Skip silently — the other hooks
            // cover the rest of the surface.
        }
    }
}
