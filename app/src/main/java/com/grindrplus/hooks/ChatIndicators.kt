package com.grindrplus.hooks

import com.grindrplus.utils.Hook
import com.grindrplus.utils.HookStage
import com.grindrplus.utils.RetrofitUtils
import com.grindrplus.utils.RetrofitUtils.RETROFIT_NAME
import com.grindrplus.utils.RetrofitUtils.createServiceProxy
import com.grindrplus.utils.hook
/**
 * Chat indicators.
 *
 * Don't show chat markers / indicators to others.
 *
 * Hooks `com.grindrapp.android` to add/modify this feature. See
 * `docs/patches/chat_indicators.md` for design notes and version-port history.
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

class ChatIndicators : Hook(
    "Chat indicators",
    "Don't show chat markers / indicators to others"
) {
    private val chatRestService = "com.grindrapp.android.chat.data.datasource.api.service.ChatRestService" // search for '"(Lcom/grindrapp/android/chat/data/datasource/api/model/MessageRateResponseRequest;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;"'
    private val blacklistedPaths = setOf(
        "v4/chatstatus/typing"
    )

    override fun init() {
        val chatRestServiceClass = findClass(chatRestService)

        val methodBlacklist = blacklistedPaths.mapNotNull {
            RetrofitUtils.findPOSTMethod(chatRestServiceClass, it)?.name
        }

        findClass(RETROFIT_NAME)
            .hook("create", HookStage.AFTER) { param ->
                val service = param.getResult()
                if (service != null && chatRestServiceClass.isAssignableFrom(service.javaClass)) {
                    param.setResult(createServiceProxy(
                        service,
                        chatRestServiceClass,
                        methodBlacklist.toTypedArray()
                    ))
                }
            }
    }
}