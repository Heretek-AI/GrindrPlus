package com.grindrplus.utils

import com.grindrplus.GrindrPlus
import com.grindrplus.core.Config

/**
 * Abstract base class for every Grindr patch in `hooks/`.
 *
 * Each subclass represents one user-visible feature (Anti Block, Online
 * Indicator, Disable Boosting, …). The class is registered in
 * [HookManager.registerHooks]'s `hookList` and the display name surfaces
 * in the in-app toggle list.
 *
 * **Obfuscation marker convention.** When a hook captures an obfuscated
 * class or method name (`a`, `ka8`, `ps2`, …) it MUST be followed by a
 * `// search for '<unique snippet>'` marker on the same line. The snippet
 * is a stable literal in the decompiled source so the next maintainer can
 * locate the new obfuscated name in a fresh JADX-decompiled APK. See
 * `AGENTS.md` § 3 for the full convention.
 *
 * **Lifecycle.** [init] runs once on host-app load (after `Config` confirms
 * the hook is enabled). [cleanup] runs on `HookManager.reloadHooks()` and
 * MUST release any reflection-cached `Class` references, cancel coroutines
 * (`scope.cancel()`), stop background threads, and reset static state.
 *
 * @property hookName Display name shown in the in-app hook toggle list.
 *                    Also used as the key in the `Config` datastore.
 * @property hookDesc One-line user-facing description of what the hook does.
 *
 * @see HookManager for registration / reload semantics.
 * @see HookStage for the BEFORE / AFTER hook timing options.
 */
abstract class Hook(
    val hookName: String,
    val hookDesc: String = "",
) {
    /**
     * Hook-specific initialization. Override to wire up Xposed hooks,
     * start coroutines, register listeners, etc. Called once when the
     * host app is loaded if the hook is enabled in user settings.
     */
    open fun init() {}

    /**
     * Hook-specific cleanup. Override to cancel coroutines, stop threads,
     * unhook any reflection-cached resources, and reset static state.
     * Called when the user toggles the hook off/on or when
     * [HookManager.reloadHooks] is invoked.
     */
    open fun cleanup() {}

    /**
     * Returns whether this hook is currently enabled in the in-app settings.
     * Hook implementations should consult this before doing expensive work.
     */
    protected fun isHookEnabled(): Boolean {
        return Config.isHookEnabled(hookName)
    }

    /**
     * Resolve a class from the host app's class loader. Use this for both
     * obfuscated (`findClass("ka8")`) and fully-qualified (`findClass(
     * "com.grindrapp.android.chat.ChatDeleteConversationPlugin")`) names.
     *
     * @param name Either a fully-qualified class name or an obfuscated
     *             short name. The latter should always have a `// search for`
     *             marker as documentation (see class KDoc).
     * @return The [Class] object from the host app's class loader.
     * @throws ClassNotFoundException if the host APK no longer contains
     *             a matching class (typical after a Grindr update that
     *             renamed the symbol — port the marker via the
     *             `update-obfuscated-symbols` skill).
     */
    protected fun findClass(name: String): Class<*> {
        return GrindrPlus.loadClass(name)
    }

    /**
     * Look up an Android resource identifier by name + type, scoped to
     * the host app's package (Grindr). Returns `0` if no resource matches.
     *
     * @param name Resource entry name (without `@`, `@+`, `@drawable/`, etc.).
     * @param type Resource type (`string`, `drawable`, `layout`, …).
     */
    protected fun getResource(name: String, type: String): Int {
        return GrindrPlus.context.resources.getIdentifier(
            name, type, GrindrPlus.context.packageName
        )
    }

    /**
     * Look up an Android attribute identifier by name, scoped to the host
     * app's package. Returns `0` if no attribute matches.
     *
     * @param name Attribute name (e.g. `R.attr.someCustomAttr`).
     */
    protected fun getAttribute(name: String): Int {
        return GrindrPlus.context.resources.getIdentifier(name, "attr"
            , GrindrPlus.context.packageName)
    }
}