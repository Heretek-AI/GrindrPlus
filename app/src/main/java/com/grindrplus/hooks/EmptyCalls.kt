package com.grindrplus.hooks

import com.grindrplus.utils.Hook
import com.grindrplus.utils.HookStage
import com.grindrplus.utils.hook

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