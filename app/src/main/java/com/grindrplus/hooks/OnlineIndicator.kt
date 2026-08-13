package com.grindrplus.hooks

import com.grindrplus.core.Config
import com.grindrplus.utils.Hook
import com.grindrplus.utils.HookStage
import com.grindrplus.utils.hook
import kotlin.time.Duration.Companion.minutes
/**
 * Online indicator.
 *
 * Customize online indicator duration.
 *
 * Hooks `com.grindrapp.android` to add/modify this feature. See
 * `docs/patches/online_indicator.md` for design notes and version-port history.
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

class OnlineIndicator : Hook(
    "Online indicator",
    "Customize online indicator duration"
) {
    val utils = "ka8" // search for ' <= 600000;'
    val isFeatureFlagEnabled = "xh6" // search for 'implements IsFeatureFlagEnabled'

    override fun init() {
        val savedDurationMinutes = Config.get("online_indicator", 3).toString().toInt()
        val savedDurationMillis = savedDurationMinutes.minutes.inWholeMilliseconds

        findClass(utils)// shouldShowOnlineIndicator()
            .hook("d", HookStage.BEFORE) { param ->
                val lastSeen = param.arg<Long>(0)
                param.setResult(System.currentTimeMillis() - lastSeen <= savedDurationMillis)
            }

        findClass(isFeatureFlagEnabled)
            .hook("a", HookStage.BEFORE) { param ->
                val a = param.args()[0]
                val flagKey = a!!.javaClass.getMethod("getKey").invoke(a)

                if (flagKey == "online-until-updates")
                    param.setResult(false)
            }

        findClass("com.grindrapp.android.utils.ProfileUtilsV2") // search for 'R.string.profile_time_online_minutes_ago'
            .hook("b", HookStage.BEFORE) { param ->
                val onlineUntilThreshold = param.arg<Long>(1)
                if (onlineUntilThreshold == 600000L)
                    param.setArg(1, savedDurationMillis)
            }

    }
}