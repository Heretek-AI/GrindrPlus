package com.grindrplus.hooks

import com.grindrplus.utils.Hook
import com.grindrplus.utils.HookStage
import com.grindrplus.utils.hookConstructor
import de.robv.android.xposed.XposedHelpers.setObjectField
/**
 * Disable shuffle.
 *
 * Forcefully disable the shuffle feature.
 *
 * Hooks `com.grindrapp.android` to add/modify this feature. See
 * `docs/patches/disable_shuffle.md` for design notes and version-port history.
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

class DisableShuffle : Hook(
    "Disable shuffle",
    "Forcefully disable the shuffle feature"
) {
    private val viewState = "com.grindrapp.android.ui.browse.v\$j" // search for 'ViewState(isRefreshing='
    private val shuffleUiState = "com.grindrapp.android.ui.browse.v\$g" // search for 'ShuffleUiState(isShuffleEnabled='

    override fun init() {
        findClass(shuffleUiState).hookConstructor(HookStage.AFTER) { param ->
            setObjectField(param.thisObject(), "a", false) // shuffleEnabled
            setObjectField(param.thisObject(), "b", false) // isShuffled
            setObjectField(param.thisObject(), "c", false) // isShuffling
            setObjectField(param.thisObject(), "d", false) // showShuffleTooltip
            setObjectField(param.thisObject(), "f", false) // isShuffleTopBarVisible
            setObjectField(param.thisObject(), "g", false) // showShuffleUpsell
            setObjectField(param.thisObject(), "h", true)  // isDisabledByFavorites
            setObjectField(param.thisObject(), "i", true)  // isDisabledByRightNow
            setObjectField(param.thisObject(), "j", false) // reshowTopBarAfterTurningOffBlockingFilters
        }

        findClass(viewState).hookConstructor(HookStage.AFTER) { param ->
            setObjectField(param.thisObject(), "b", false) // isRightNowUpsellBannerVisible
            setObjectField(param.thisObject(), "d", false) // shouldShowFloatingRatingBanner
        }
    }
}