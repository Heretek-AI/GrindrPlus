package com.grindrplus.hooks

import com.grindrplus.utils.Hook
import com.grindrplus.utils.HookStage
import com.grindrplus.utils.hook
import com.grindrplus.utils.hookConstructor
import de.robv.android.xposed.XposedHelpers.newInstance
import de.robv.android.xposed.XposedHelpers.setObjectField
/**
 * Disable boosting.
 *
 * Get rid of all upsells related to boosting.
 *
 * Hooks `com.grindrapp.android` to add/modify this feature. See
 * `docs/patches/disable_boosting.md` for design notes and version-port history.
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

class DisableBoosting : Hook(
    "Disable boosting",
    "Get rid of all upsells related to boosting"
) {
    private val drawerProfileUiState = "com.grindrapp.android.ui.drawer.model.DrawerProfileUiState" // search for 'DrawerProfileUiState(showBoostMeButton='
    private val radarUiModel = "tq8" // search for 'RadarUiModel(boostButton='
    private val fabUiModel = "com.grindrapp.android.boost2.presentation.model.FabUiModel"
    private val rightNowMicrosFabUiModel =
        "com.grindrapp.android.rightnow.presentation.model.RightNowMicrosFabUiModel"

    private val boostStateClass =
        "com.grindrapp.android.ui.drawer.model.MicrosDrawerItemState\$Unavailable"

	private val navbarClass = "com.grindrapp.android.home.presentation.model.HomeScreenBottomNavigationUiModel"
	private val smallPersistentVector = "kotlinx.collections.immutable.implementations.immutableList.SmallPersistentVector"

    override fun init() {
        findClass(drawerProfileUiState).hookConstructor(HookStage.AFTER) { param ->
            setObjectField(param.thisObject(), "showBoostMeButton", false)
            setObjectField(
                param.thisObject(),
                "boostButtonState",
                newInstance(findClass(boostStateClass))
            )
            setObjectField(
                param.thisObject(),
                "roamButtonState",
                newInstance(findClass(boostStateClass))
            )
            setObjectField(param.thisObject(), "showRNBoostCard", false)
            setObjectField(param.thisObject(), "showDayPassItem", null)
            setObjectField(param.thisObject(), "unlimitedWeeklySubscriptionItem", null)
            setObjectField(param.thisObject(), "isRightNowAvailable", false)
            setObjectField(param.thisObject(), "showBoostDrawerFtux", false)
        }

        findClass(radarUiModel).hookConstructor(HookStage.AFTER) { param ->
            setObjectField(param.thisObject(), "a", null) // roamButton
            setObjectField(param.thisObject(), "b", null) // activeMicroSessionUi
        }

        findClass(fabUiModel).hookConstructor(HookStage.AFTER) { param ->
            setObjectField(param.thisObject(), "isVisible", false) // isVisible
        }

        findClass(rightNowMicrosFabUiModel).hookConstructor(HookStage.AFTER) { param ->
            setObjectField(param.thisObject(), "isBoostFabVisible", false) // isBoostFabVisible
            setObjectField(param.thisObject(), "isClickEnabled", false) // isClickEnabled
            setObjectField(param.thisObject(), "isFabVisible", false) // isFabVisible
        }

        val spvConstructor = findClass(smallPersistentVector).constructors[0]

		findClass(navbarClass).hookConstructor(HookStage.BEFORE) { param ->
			val routeList = param.args()[2] as List<*>
			val newRouteArray =	routeList.filter { it?.javaClass?.simpleName != "Store" }.toTypedArray()
			val newRouteList = spvConstructor.newInstance(newRouteArray)

			param.setArg(2, newRouteList)
		}
    }
}