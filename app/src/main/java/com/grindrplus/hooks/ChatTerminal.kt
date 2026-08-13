package com.grindrplus.hooks

import com.grindrplus.commands.CommandHandler
import com.grindrplus.core.Config
import com.grindrplus.utils.Hook
import com.grindrplus.utils.HookStage
import com.grindrplus.utils.hook
import de.robv.android.xposed.XposedHelpers.callMethod
import de.robv.android.xposed.XposedHelpers.getObjectField
/**
 * Chat terminal.
 *
 * Create a chat terminal to execute commands.
 *
 * Hooks `com.grindrapp.android` to add/modify this feature. See
 * `docs/patches/chat_terminal.md` for design notes and version-port history.
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

class ChatTerminal : Hook(
    "Chat terminal",
    "Create a chat terminal to execute commands"
) {
    private val chatMessageSenderService = "com.grindrapp.android.chat.service.ChatMessageSenderService"

    override fun init() {
        findClass(chatMessageSenderService).hook("c", HookStage.BEFORE) { param ->
            val wrapper = param.arg(1) ?: return@hook
            val message = callMethod(wrapper, "getMessage") ?: return@hook
            val body = callMethod(wrapper, "getBody") ?: return@hook
            val text = getObjectField(body, "text") as? String ?: return@hook
            val sender = getObjectField(message, "senderId").toString()
            val recipient = getObjectField(message, "recipientId").toString()

            val commandPrefix = (Config.get("command_prefix", "/") as String)
            if (text.startsWith(commandPrefix)) {
                param.setResult(null) // Don't send the command to the chat
                CommandHandler(sender, recipient).handle(text.substring(commandPrefix.length))
            }
        }
    }
}