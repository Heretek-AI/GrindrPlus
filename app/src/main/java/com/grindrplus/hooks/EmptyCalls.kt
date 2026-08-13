package com.grindrplus.hooks

import com.grindrplus.utils.Hook
import com.grindrplus.utils.HookStage
import com.grindrplus.utils.hook
/**
 * Video calls.
 *
 * Allow video calls on empty chats.
 *
 * Hooks `com.grindrapp.android` to add/modify this feature. See
 * `docs/patches/empty_calls.md` for design notes and version-port history.
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

class EmptyCalls : Hook(
    "Video calls",
    "Allow video calls on empty chats"
) {
    private val checkChattedBefore = "x65" // search for 'VideoCallHasNotChattedException'

    override fun init() {
        findClass(checkChattedBefore)
            .hook("a", HookStage.BEFORE) { param ->
                param.setResult(true)
            }
    }
}