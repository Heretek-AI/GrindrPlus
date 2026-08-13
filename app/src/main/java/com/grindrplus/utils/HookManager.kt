package com.grindrplus.utils

import com.grindrplus.core.Config
import com.grindrplus.core.Logger
import com.grindrplus.hooks.AllowScreenshots
import com.grindrplus.hooks.AntiBlock
import com.grindrplus.hooks.AntiDetection
import com.grindrplus.hooks.BanManagement
import com.grindrplus.hooks.ChatIndicators
import com.grindrplus.hooks.ChatTerminal
import com.grindrplus.hooks.DisableAnalytics
import com.grindrplus.hooks.DisableBoosting
import com.grindrplus.hooks.DisablePairIP
import com.grindrplus.hooks.DisableShuffle
import com.grindrplus.hooks.DisableUpdates
import com.grindrplus.hooks.EmptyCalls
import com.grindrplus.hooks.EnableUnlimited
import com.grindrplus.hooks.ExpiringMedia
import com.grindrplus.hooks.FeatureGranting
import com.grindrplus.hooks.LocalSavedPhrases
import com.grindrplus.hooks.LocationSpoofer
import com.grindrplus.hooks.NotificationAlerts
import com.grindrplus.hooks.OnlineIndicator
import com.grindrplus.hooks.ProfileDetails
import com.grindrplus.hooks.ProfileViews
import com.grindrplus.hooks.QuickBlock
import com.grindrplus.hooks.StatusDialog
import com.grindrplus.hooks.TimberLogging
import com.grindrplus.hooks.UnlimitedAlbums
import com.grindrplus.hooks.UnlimitedProfiles
import com.grindrplus.hooks.UnlockExplorer
import com.grindrplus.hooks.WebSocketAlive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.reflect.KClass

/**
 * Owns the lifecycle of every [Hook] registered with the module.
 *
 * The `hookList` literal in [registerHooks] is the single source of truth
 * for which hooks ship in the module. **Every new hook** in
 * `com.grindrplus.hooks` must be added here (and, per `AGENTS.md` § 4, get
 * a matching `docs/patches/<kebab>.md`).
 *
 * The class is a singleton owned by [com.grindrplus.GrindrPlus.hookManager].
 */
class HookManager {
    /** Currently-registered hooks, keyed by their concrete [KClass]. */
    private var hooks = mutableMapOf<KClass<out Hook>, Hook>()

    /**
     * Build the [hookList], register each hook's settings with [Config],
     * and (when [init] is true) call [Hook.init] on every enabled hook.
     *
     * Pass `init = false` to only refresh the settings registry without
     * invoking hook bodies — useful for first-launch wiring where the host
     * app context is not yet available.
     *
     * @param init If true, call [Hook.init] on every hook whose setting is
     *             enabled in [Config]. Default `true`.
     */
    fun registerHooks(init: Boolean = true) {
        runBlocking(Dispatchers.IO) {
            val hookList = listOf(
                AllowScreenshots(),
                AntiBlock(),
                AntiDetection(),
                BanManagement(),
                ChatIndicators(),
                ChatTerminal(),
                DisableAnalytics(),
                DisableBoosting(),
                DisablePairIP(),
                DisableShuffle(),
                DisableUpdates(),
                EmptyCalls(),
                EnableUnlimited(),
                ExpiringMedia(),
                FeatureGranting(),
                LocalSavedPhrases(),
                LocationSpoofer(),
                NotificationAlerts(),
                OnlineIndicator(),
                ProfileDetails(),
                ProfileViews(),
                QuickBlock(),
                TimberLogging(),
                UnlimitedAlbums(),
                UnlimitedProfiles(),
                UnlockExplorer(),
                StatusDialog(),
//                WebSocketAlive()
            )

            hookList.forEach { hook ->
                Config.initHookSettings(
                    hook.hookName, hook.hookDesc, false
                )
            }

            if (!init) return@runBlocking

            hooks = hookList.associateBy { it::class }.toMutableMap()

            hooks.values.forEach { hook ->
                if (Config.isHookEnabled(hook.hookName)) {
                    hook.init()
                    Logger.s("Initialized hook: ${hook.hookName}")
                } else {
                    Logger.i("Hook ${hook.hookName} is disabled.")
                }
            }
        }
    }

    /**
     * Tear down every currently-registered hook (calling [Hook.cleanup])
     * and re-register the full [hookList] from scratch. Invoked from the
     * in-app settings screen when the user toggles hooks off/on, or from
     * any code path that needs to re-bind Xposed hooks (e.g. after a
     * module update).
     */
    fun reloadHooks() {
        runBlocking(Dispatchers.IO) {
            hooks.values.forEach { hook -> hook.cleanup() }
            hooks.clear()
            registerHooks()
            Logger.s("Reloaded hooks")
        }
    }

    /**
     * One-shot initialization called by [com.grindrplus.GrindrPlus.init] on
     * module load. Equivalent to `registerHooks(init = true)`.
     */
    fun init() {
        registerHooks()
    }
}