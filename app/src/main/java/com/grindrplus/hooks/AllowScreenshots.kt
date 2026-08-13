package com.grindrplus.hooks

import android.app.Activity
import android.content.ContentResolver
import android.database.ContentObserver
import android.net.Uri
import android.view.Window
import android.view.WindowManager.LayoutParams.FLAG_SECURE
import com.grindrplus.utils.Hook
import com.grindrplus.utils.HookStage
import com.grindrplus.utils.hook
/**
 * Allow screenshots.
 *
 * Allow screenshots everywhere in the app.
 *
 * Hooks `com.grindrapp.android` to add/modify this feature. See
 * `docs/patches/allow_screenshots.md` for design notes and version-port history.
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

class AllowScreenshots : Hook(
    "Allow screenshots",
    "Allow screenshots everywhere in the app"
) {
    override fun init() {
        Window::class.java.hook("setFlags", HookStage.BEFORE) { param ->
            var flags = param.arg<Int>(0)
            flags = flags and FLAG_SECURE.inv()
            param.setArg(0, flags)
        }

        Activity::class.java.hook("registerScreenCaptureCallback", HookStage.BEFORE) { param ->
            param.setResult(null)
        }

    ContentResolver::class.java.methods.first {
        it.name == "registerContentObserver" &&
                it.parameterTypes.contentEquals(arrayOf(Uri::class.java,
                    Boolean::class.javaPrimitiveType, ContentObserver::class.java))
        }.hook(HookStage.BEFORE) { param ->
            val uri = param.arg<Uri>(0)
            if (uri.host != "media") return@hook
            param.setResult(null)
        }
    }
}