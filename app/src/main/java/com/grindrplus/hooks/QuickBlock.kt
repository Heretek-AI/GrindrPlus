package com.grindrplus.hooks

import android.view.Menu
import android.view.MenuItem
import com.grindrplus.GrindrPlus
import com.grindrplus.ui.Utils.getId
import com.grindrplus.utils.Hook
import com.grindrplus.utils.HookStage
import com.grindrplus.utils.hook
import de.robv.android.xposed.XposedHelpers.callMethod
import de.robv.android.xposed.XposedHelpers.getObjectField
/**
 * Quick block.
 *
 * Ability to block users quickly.
 *
 * Hooks `com.grindrapp.android` to add/modify this feature. See
 * `docs/patches/quick_block.md` for design notes and version-port history.
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

class QuickBlock : Hook(
    "Quick block",
    "Ability to block users quickly"
) {
    private val blockViewModel = "ms0" // search for '("STATUS_BLOCK_DIALOG_SHOWN", 1)'
    private val profileViewHolder = "com.grindrapp.android.ui.profileV2.g" // search for 'com.grindrapp.android.ui.profileV2.ProfileViewHolder$onBind$3'

    override fun init() {
        findClass(profileViewHolder).hook("y", HookStage.AFTER) { param ->
            val arg0 = param.arg(0) as Any
            val profileId = param.args().getOrNull(1) ?: return@hook
            val viewBinding = getObjectField(arg0, "b")
            val profileToolbar = getObjectField(viewBinding, "p")
            val toolbarMenu = callMethod(profileToolbar, "getMenu") as Menu
            val menuActions = getId("menu_actions", "id", GrindrPlus.context)
            val actionsMenuItem = callMethod(toolbarMenu, "findItem", menuActions) as MenuItem
            actionsMenuItem.setOnMenuItemClickListener { GrindrPlus.httpClient.blockUser(profileId as String); true }
        }

        findClass(blockViewModel).hook("N", HookStage.BEFORE) { param ->
            val profileId = param.thisObject().javaClass.declaredFields
                .asSequence()
                .filter { it.type == String::class.java }
                .mapNotNull { field ->
                    try {
                        field.isAccessible = true
                        field.get(param.thisObject()) as? String
                    } catch (e: Exception) { null }
                }
                .firstOrNull { it.isNotEmpty() && it.all { char -> char.isDigit() } }
            GrindrPlus.httpClient.blockUser(profileId as String)
            param.setResult(null)
        }
    }
}