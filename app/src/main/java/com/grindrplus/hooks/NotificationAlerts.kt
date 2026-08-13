package com.grindrplus.hooks

import com.grindrplus.utils.Hook
import com.grindrplus.utils.HookStage
import com.grindrplus.utils.hook
/**
 * Notification Alerts.
 *
 * Disable all Grindr warnings related to notifications.
 *
 * Hooks `com.grindrapp.android` to add/modify this feature. See
 * `docs/patches/notification_alerts.md` for design notes and version-port history.
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

class NotificationAlerts : Hook(
    "Notification Alerts",
    "Disable all Grindr warnings related to notifications"
) {
    private val notificationManager = "cl5" // search for '0L, "notification_reminder_time"'

    override fun init() {
        findClass(notificationManager)
            .hook("a", HookStage.BEFORE) { param ->
                param.setResult(null)
            }
    }
}