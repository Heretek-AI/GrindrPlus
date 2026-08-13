package com.grindrplus.hooks

import com.grindrplus.GrindrPlus
import com.grindrplus.core.Config
import com.grindrplus.ui.Utils
import com.grindrplus.utils.Feature
import com.grindrplus.utils.FeatureManager
import com.grindrplus.utils.Hook
import com.grindrplus.utils.HookStage
import com.grindrplus.utils.hook
import com.grindrplus.utils.hookConstructor
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.XposedHelpers.callMethod
import de.robv.android.xposed.XposedHelpers.getObjectField
/**
 * Feature granting.
 *
 * Grant all Grindr features.
 *
 * Hooks `com.grindrapp.android` to add/modify this feature. See
 * `docs/patches/feature_granting.md` for design notes and version-port history.
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

class FeatureGranting : Hook(
    "Feature granting",
    "Grant all Grindr features"
) {
    private val isFeatureFlagEnabled = "xh6" // search for 'implements IsFeatureFlagEnabled {'
    private val upsellsV8Model = "com.grindrapp.android.model.UpsellsV8"
    private val insertsModel = "com.grindrapp.android.model.Inserts"
    private val settingDistanceVisibilityViewModel =
        "sfa" // search for 'UiState(distanceVisibility='
    private val featureModel = "com.grindrapp.android.usersession.model.Feature"
    private val tapModel = "com.grindrapp.android.taps.model.Tap"
    private val tapInboxModel = "com.grindrapp.android.taps.data.model.TapsInboxEntity"
    private val alertParams = "P" // search for 'AlertController.AlertParams' in androidx.appcompat.app.AlertDialog
    private val featureManager = FeatureManager()

    override fun init() {
        initFeatures()

		// search for 'Assignment.Flag'
        findClass(isFeatureFlagEnabled).hook("a", HookStage.BEFORE) { param ->
            val flagKey = callMethod(param.args()[0], "toString") as String
            if (featureManager.isManaged(flagKey)) {
                param.setResult(featureManager.isEnabled(flagKey))
            }
        }

        findClass(featureModel).hook("isGranted", HookStage.BEFORE) { param ->
            val disallowedFeatures = setOf("DisableScreenshot")
            val feature = callMethod(param.thisObject(), "toString") as String
            param.setResult(feature !in disallowedFeatures)
        }

        findClass(settingDistanceVisibilityViewModel)
            .hookConstructor(HookStage.BEFORE) { param ->
                param.setArg(4, false) // hidePreciseDistance
            }

        listOf(upsellsV8Model, insertsModel).forEach { model ->
            findClass(model)
                .hook("getMpuFree", HookStage.BEFORE) { param ->
                    param.setResult(0)
                }

            findClass(model)
                .hook("getMpuXtra", HookStage.BEFORE) { param ->
                    param.setResult(0)
                }
        }

        listOf(tapModel, tapInboxModel).forEach { model ->
            findClass(model).hook("isViewable", HookStage.BEFORE) { param ->
                param.setResult(true)
            }
        }

        val boostAlertStringId = Utils.getId(
            "incognito_while_boosting_confilct_warning_message",
            "string",
            GrindrPlus.context
        )

        val boostAlertString = GrindrPlus.context.resources.getString(boostAlertStringId)

        findClass("androidx.appcompat.app.AlertDialog\$Builder")
            .hook("show", HookStage.BEFORE) { param ->
                val builder = param.thisObject()
                val alertParams = getObjectField(builder, alertParams)
                val messageString = getObjectField(alertParams, "mMessage")

                if (messageString.equals(boostAlertString)) {
                    val dialog = callMethod(builder, "create")
                    val positiveButtonListener = getObjectField(alertParams, "mPositiveButtonListener")

                    val positiveButtonId = XposedHelpers.getStaticIntField(
                        findClass("android.content.DialogInterface"),
                        "BUTTON_POSITIVE"
                    )

                    callMethod(positiveButtonListener, "onClick", dialog, positiveButtonId)

                    param.setResult(dialog)
                }
        }
    }

    private fun initFeatures() {
        featureManager.add(Feature("PasswordComplexity", false))
        featureManager.add(Feature("TimedBans", false))
        featureManager.add(Feature("GenderFlag", true))
        featureManager.add(Feature("ForceApplovinOptOut", true))
        featureManager.add(Feature("RewardedAdViewedMeFeatureFlag", false))
        featureManager.add(Feature("ChatInterstitialFeatureFlag", false))
        featureManager.add(Feature("SideDrawerDeeplinkKillSwitch", true))
        featureManager.add(Feature("SponsoredRoamKillSwitch", true))
        featureManager.add(Feature("UnifiedProfileAvatarFeatureFlag", true))
        featureManager.add(Feature("ApproximateDistanceFeatureFlag", false))
        featureManager.add(Feature("DoxyPEP", true))
        featureManager.add(Feature("CascadeRewriteFeatureFlag", false))
        featureManager.add(Feature("AdsLogs", false))
        featureManager.add(Feature("NonChatEnvironmentAdBannerFeatureFlag", false))
        featureManager.add(Feature("PersistentAdBannerFeatureFlag", false))
        featureManager.add(Feature("ClientTelemetryTracking", false))
        featureManager.add(Feature("LTOAds", false))
        featureManager.add(Feature("SponsorProfileAds", false))
        featureManager.add(Feature("ConversationAds", false))
        featureManager.add(Feature("InboxNativeAds", false))
        featureManager.add(Feature("ReportingLagTime", false))
        featureManager.add(Feature("MrecNewFlow", false))
        featureManager.add(Feature("RunningOnEmulatorFeatureFlag", false))
        featureManager.add(Feature("BannerNewFlow", false))
        featureManager.add(Feature("CalendarUi", true))
        featureManager.add(Feature("CookieTap", Config.get("enable_cookie_tap", false, true) as Boolean))
        featureManager.add(Feature("VipFlag", Config.get("enable_vip_flag", false, true) as Boolean))
        featureManager.add(Feature("PositionFilter", true))
        featureManager.add(Feature("AgeFilter", true))
        featureManager.add(Feature("BanterFeatureGate", false))
        featureManager.add(Feature("TakenOnGrindrWatermarkFlag", false))
        featureManager.add(Feature("gender-filter", true))
        featureManager.add(Feature("enable-chat-summaries", true))
        featureManager.add(Feature("enable-mutual-taps-no-paywall", !(Config.get("enable_interest_section", true, true) as Boolean)))
    }
}