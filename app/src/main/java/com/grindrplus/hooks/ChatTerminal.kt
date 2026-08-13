package com.grindrplus.hooks

import com.grindrplus.commands.CommandHandler
import com.grindrplus.core.Config
import com.grindrplus.utils.Hook
import com.grindrplus.utils.HookStage
import com.grindrplus.utils.hook
import de.robv.android.xposed.XposedHelpers.callMethod
import de.robv.android.xposed.XposedHelpers.getObjectField

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