package com.grindrplus.utils

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Member
import java.lang.reflect.Method

/**
 * Timing of a hook callback relative to the hooked method.
 *
 * - [BEFORE] — the callback runs *before* the original method. Use this to
 *   inspect / modify arguments via [HookAdapter.setArg] or short-circuit the
 *   call via [HookAdapter.setResult].
 * - [AFTER] — the callback runs *after* the original method. Use this to
 *   patch the return value via [HookAdapter.setResult] or trigger side
 *   effects (notifications, logging).
 */
enum class HookStage {
    BEFORE,
    AFTER
}

/**
 * Thin wrapper around `XposedBridge.hookAllMethods` / `hookAllConstructors`
 * / `hookMethod` that hands the consumer a type-safe [HookAdapter].
 *
 * Most hook authors never call [Hooker] directly — they use the extension
 * functions at the bottom of this file:
 *
 * ```kotlin
 * findClass("ka8").hook("d", HookStage.BEFORE) { param ->
 *     param.setResult(System.currentTimeMillis() - param.arg<Long>(0) <= savedDurationMillis)
 * }
 * ```
 *
 * The `hookObjectMethod` / `ephemeralHook*` overloads exist for hooks that
 * must fire only for a specific object instance or fire exactly once.
 *
 * @see HookAdapter for the type-safe wrapper passed to consumers.
 */
object Hooker {
    /**
     * Build an [XC_MethodHook] that dispatches to [consumer] wrapped in a
     * [HookAdapter]. Internal — exposed for the inline extension functions.
     */
    inline fun <T> newMethodHook(
        stage: HookStage,
        crossinline consumer: (HookAdapter<T>) -> Unit,
        crossinline filter: ((HookAdapter<T>) -> Boolean) = { true }
    ): XC_MethodHook {
        return when (stage) {
            HookStage.BEFORE -> object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam<*>) {
                    HookAdapter<T>(param).takeIf(filter)?.also(consumer)
                }
            }
            HookStage.AFTER -> object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam<*>) {
                    HookAdapter<T>(param).takeIf(filter)?.also(consumer)
                }
            }
        }
    }

    /**
     * Hook every method named [methodName] on [clazz]. The [filter] gates
     * which calls actually invoke [consumer].
     */
    inline fun <T> hook(
        clazz: Class<T>,
        methodName: String,
        stage: HookStage,
        crossinline filter: (HookAdapter<T>) -> Boolean,
        noinline consumer: (HookAdapter<T>) -> Unit
    ): Set<XC_MethodHook.Unhook> = XposedBridge.hookAllMethods(clazz, methodName, newMethodHook(stage, consumer, filter))

    /**
     * Hook a specific reflective [member] (method or constructor) directly,
     * without going through `hookAllMethods`. Use when you have already
     * resolved a `Member` (e.g. via `getDeclaredMethods`).
     */
    inline fun <T> hook(
        member: Member,
        stage: HookStage,
        crossinline filter: ((HookAdapter<T>) -> Boolean),
        crossinline consumer: (HookAdapter<T>) -> Unit
    ): XC_MethodHook.Unhook {
        return XposedBridge.hookMethod(member, newMethodHook(stage, consumer, filter))
    }

    /**
     * Hook every method named [methodName] on [clazz]. Shorthand for the
     * filter overload with `filter = { true }`.
     */
    fun <T> hook(
        clazz: Class<T>,
        methodName: String,
        stage: HookStage,
        consumer: (HookAdapter<T>) -> Unit
    ): Set<XC_MethodHook.Unhook> = hook(clazz, methodName, stage, { true }, consumer)

    /**
     * Hook a reflective [member] directly. Shorthand for the filter overload
     * with `filter = { true }`.
     */
    fun <T> hook(
        member: Member,
        stage: HookStage,
        consumer: (HookAdapter<T>) -> Unit
    ): XC_MethodHook.Unhook {
        return hook(member, stage, { true }, consumer)
    }

    /**
     * Hook every constructor on [clazz]. Shorthand for the filter overload
     * with `filter = { true }`.
     */
    fun <T> hookConstructor(
        clazz: Class<T>,
        stage: HookStage,
        consumer: (HookAdapter<T>) -> Unit
    ): Set<XC_MethodHook.Unhook> = XposedBridge.hookAllConstructors(clazz, newMethodHook(stage, consumer))

    /**
     * Hook every constructor on [clazz], gating with [filter].
     */
    fun <T> hookConstructor(
        clazz: Class<T>,
        stage: HookStage,
        filter: ((HookAdapter<T>) -> Boolean),
        consumer: (HookAdapter<T>) -> Unit
    ) {
        XposedBridge.hookAllConstructors(clazz, newMethodHook(stage, consumer, filter))
    }

    /**
     * Hook [methodName] on [clazz] but fire [hookConsumer] only when the
     * `this` object equals [instance]. The hook automatically unhooks itself
     * once it fires (or once the instance is GC'd).
     */
    inline fun <T> hookObjectMethod(
        clazz: Class<T>,
        instance: Any,
        methodName: String,
        stage: HookStage,
        crossinline hookConsumer: (HookAdapter<T>) -> Unit
    ): List<() -> Unit> {
        val unhooks = mutableSetOf<XC_MethodHook.Unhook>()
        hook(clazz, methodName, stage) { param->
            if (param.nullableThisObject().let {
                    if (it == null) unhooks.forEach { u -> u.unhook() }
                    it != instance
                }) return@hook
            hookConsumer(param)
        }.also { unhooks.addAll(it) }
        return unhooks.map {
            { it.unhook() }
        }
    }

    /**
     * Hook [methodName] on [clazz] exactly once: the hook fires the first
     * time [hookConsumer] is invoked, then automatically unhooks itself.
     */
    inline fun <T> ephemeralHook(
        clazz: Class<T>,
        methodName: String,
        stage: HookStage,
        crossinline hookConsumer: (HookAdapter<T>) -> Unit
    ) {
        val unhooks: MutableSet<XC_MethodHook.Unhook> = HashSet()
        hook(clazz, methodName, stage) { param->
            hookConsumer(param)
            unhooks.forEach{ it.unhook() }
        }.also { unhooks.addAll(it) }
    }

    /**
     * Hook [methodName] on [clazz], scoped to [instance], exactly once.
     * Combines [hookObjectMethod] + [ephemeralHook].
     */
    inline fun <T> ephemeralHookObjectMethod(
        clazz: Class<T>,
        instance: Any,
        methodName: String,
        stage: HookStage,
        crossinline hookConsumer: (HookAdapter<T>) -> Unit
    ) {
        val unhooks: MutableSet<XC_MethodHook.Unhook> = HashSet()
        hook(clazz, methodName, stage) { param->
            if (param.nullableThisObject() != instance) return@hook
            unhooks.forEach { it.unhook() }
            hookConsumer(param)
        }.also { unhooks.addAll(it) }
    }

    /**
     * Hook every constructor on [clazz] exactly once. The hook fires the
     * first time [hookConsumer] is invoked, then automatically unhooks
     * itself.
     */
    inline fun <T> ephemeralHookConstructor(
        clazz: Class<T>,
        stage: HookStage,
        crossinline hookConsumer: (HookAdapter<T>) -> Unit
    ) {
        val unhooks: MutableSet<XC_MethodHook.Unhook> = HashSet()
        hookConstructor(clazz, stage) { param->
            hookConsumer(param)
            unhooks.forEach{ it.unhook() }
        }.also { unhooks.addAll(it) }
    }
}

/**
 * Sugar: `findClass("Foo").hookConstructor(HookStage.AFTER) { ... }`.
 */
fun <T> Class<T>.hookConstructor(
    stage: HookStage,
    consumer: (HookAdapter<T>) -> Unit
) = Hooker.hookConstructor(this, stage, consumer)

/**
 * Sugar: `findClass("Foo").hookConstructor(HookStage.AFTER, filter = ...) { ... }`.
 */
fun <T> Class<T>.hookConstructor(
    stage: HookStage,
    filter: ((HookAdapter<T>) -> Boolean),
    consumer: (HookAdapter<T>) -> Unit
) = Hooker.hookConstructor(this, stage, filter, consumer)

/**
 * Sugar: `findClass("Foo").hook("bar", HookStage.BEFORE) { ... }`.
 */
fun <T> Class<T>.hook(
    methodName: String,
    stage: HookStage,
    consumer: (HookAdapter<T>) -> Unit
): Set<XC_MethodHook.Unhook> = Hooker.hook(this, methodName, stage, consumer)

/**
 * Sugar: `findClass("Foo").hook("bar", HookStage.BEFORE, filter = ...) { ... }`.
 */
fun <T> Class<T>.hook(
    methodName: String,
    stage: HookStage,
    filter: (HookAdapter<T>) -> Boolean,
    consumer: (HookAdapter<T>) -> Unit
): Set<XC_MethodHook.Unhook> = Hooker.hook(this, methodName, stage, filter, consumer)

/**
 * Sugar: hook a reflective [Member] (method or constructor).
 */
fun Member.hook(
    stage: HookStage,
    consumer: (HookAdapter<Any>) -> Unit
): XC_MethodHook.Unhook = Hooker.hook(this, stage, consumer)

/**
 * Sugar: hook a reflective [Member] with a filter.
 */
fun Member.hook(
    stage: HookStage,
    filter: ((HookAdapter<Any>) -> Boolean),
    consumer: (HookAdapter<Any>) -> Unit
): XC_MethodHook.Unhook = Hooker.hook(this, stage, filter, consumer)

/**
 * Bulk-hook an array of [Method]s. Skips methods declared on [Object] to
 * avoid re-hooking `toString`, `hashCode`, etc. across the entire class
 * hierarchy.
 */
fun Array<Method>.hookAll(stage: HookStage, param: (HookAdapter<Any>) -> Unit) {
    filter { it.declaringClass != Object::class.java }.forEach {
        it.hook(stage, param)
    }
}