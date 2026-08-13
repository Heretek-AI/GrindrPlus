package com.grindrplus.hooks

import com.grindrplus.utils.Hook
import com.grindrplus.utils.HookStage
import com.grindrplus.utils.RetrofitUtils.RETROFIT_NAME
import com.grindrplus.utils.RetrofitUtils.createServiceProxy
import com.grindrplus.utils.RetrofitUtils.findPOSTMethod
import com.grindrplus.utils.hook
/**
 * Profile views.
 *
 * Don't let others know you viewed their profile.
 *
 * Hooks `com.grindrapp.android` to add/modify this feature. See
 * `docs/patches/profile_views.md` for design notes and version-port history.
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

class ProfileViews : Hook(
	"Profile views",
	"Don't let others know you viewed their profile"
) {
    private val profileRestService = "com.grindrapp.android.api.ProfileRestService"
    private val blacklistedPaths = setOf(
        "v5/views/{profileId}"
    )

    override fun init() {
        val profileRestServiceClass = findClass(profileRestService)

        val methodBlacklist =
            blacklistedPaths.mapNotNull { findPOSTMethod(profileRestServiceClass, it)?.name }

        findClass(RETROFIT_NAME).hook("create", HookStage.AFTER) { param ->
            val service = param.getResult()
            if (service != null && profileRestServiceClass.isAssignableFrom(service.javaClass)) {
                param.setResult(
                    createServiceProxy(
                        service,
                        profileRestServiceClass,
                        methodBlacklist.toTypedArray()
                    )
                )
            }
        }
    }
}
