package com.grindrplus.utils

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Member
import java.util.function.Consumer

/**
 * Type-safe wrapper around [XC_MethodHook.MethodHookParam], passed to every
 * hook consumer registered via [Hooker] / the `Class<T>.hook(...)` extension
 * functions.
 *
 * The adapter hides the raw `args[]` array and unchecked casts in favor of
 * typed helpers: [arg], [argNullable], [setArg], [setResult], and the
 * original-method invokers [invokeOriginal] / [invokeOriginalSafe].
 *
 * @param Clazz The declared type of `this` on the hooked method (or `Any`
 *              if the hook doesn't care). Used by [thisObject] /
 *              [nullableThisObject] to give a typed view of the receiver.
 */
@Suppress("UNCHECKED_CAST")
class HookAdapter<Clazz>(
    private val methodHookParam: XC_MethodHook.MethodHookParam<*>
) {
    /**
     * `this` reference on the hooked method, cast to [Clazz].
     * @throws ClassCastException if the receiver is not a [Clazz].
     */
    fun thisObject(): Clazz {
        return methodHookParam.thisObject as Clazz
    }

    /**
     * Nullable variant of [thisObject]. Returns `null` when the hooked
     * method was a static call (`thisObject` is `null`).
     */
    fun nullableThisObject(): Clazz? {
        return methodHookParam.thisObject as Clazz?
    }

    /**
     * The reflective [Member] (method or constructor) that was hooked.
     */
    fun method(): Member {
        return methodHookParam.method
    }

    /**
     * Read an argument by index with an unchecked cast. Use [arg] overload
     * with a [Class] token when you want type-coercion fallbacks.
     *
     * @throws IndexOutOfBoundsException if [index] is out of range.
     */
    fun <T : Any> arg(index: Int): T {
        return methodHookParam.args[index] as T
    }

    /**
     * Read an argument by index with type coercion. If the raw value is not
     * already of type [clazz], the adapter tries a best-effort conversion
     * (calling `toString`/`toInt`/`toDouble`/...) and falls back to the
     * primitive zero value (`0`, `0.0`, `false`, ...) for primitives.
     *
     * Returns `null` if the value cannot be coerced.
     */
    fun <T : Any> arg(index: Int, clazz: Class<T>): T? {
        val argValue = methodHookParam.args[index]
        return try {
            clazz.cast(argValue)
        } catch (e: ClassCastException) {
            convertToType(argValue, clazz) ?: handlePrimitiveDefaults(clazz)
        }
    }

    /**
     * Like [arg] but returns `null` instead of throwing when [index] is
     * out of range.
     */
    fun <T : Any> argNullable(index: Int): T? {
        return methodHookParam.args.getOrNull(index) as T?
    }

    /**
     * Replace an argument by index. No-op if [index] is out of range.
     * Use this in `HookStage.BEFORE` to mutate inputs before the original
     * method runs.
     */
    fun setArg(index: Int, value: Any?) {
        if (index < 0 || index >= methodHookParam.args.size) return
        methodHookParam.args[index] = value
    }

    /**
     * Raw args array. Use this when you need the whole array at once
     * (e.g. for `invokeOriginal(args)`) or when you don't know the type.
     */
    fun args(): Array<Any?> {
        return methodHookParam.args
    }

    /**
     * Read the original method's return value. Only meaningful in
     * `HookStage.AFTER` callbacks.
     */
    fun getResult(): Any? {
        return methodHookParam.result
    }

    /**
     * Replace the original method's return value. In `HookStage.BEFORE`
     * this short-circuits the call entirely (the original is skipped).
     * In `HookStage.AFTER` this overrides the value the original returned.
     */
    fun setResult(result: Any?) {
        methodHookParam.result = result
    }

    /**
     * Make the original call throw [throwable] instead of returning.
     */
    fun setThrowable(throwable: Throwable) {
        methodHookParam.throwable = throwable
    }

    /**
     * Throwable the original method threw, if any. Only meaningful in
     * `HookStage.AFTER`.
     */
    fun throwable(): Throwable? {
        return methodHookParam.throwable
    }

    /**
     * Invoke the original (un-hooked) method using the current `this` and
     * args, and return its result. Useful when a `HookStage.AFTER` callback
     * needs to re-run the original with a tweaked argument set.
     *
     * @throws Throwable whatever the original method threw.
     */
    fun invokeOriginal(): Any? {
        return XposedBridge.invokeOriginalMethod(method(), thisObject(), args())
    }

    /**
     * Invoke the original method with a custom args array, return its result.
     */
    fun invokeOriginal(args: Array<Any?>): Any? {
        return XposedBridge.invokeOriginalMethod(method(), thisObject(), args)
    }

    /**
     * Invoke the original method, swallowing any thrown exception and
     * forwarding it to [errorCallback]. Sets the result via [setResult]
     * on success.
     */
    fun invokeOriginalSafe(errorCallback: Consumer<Throwable>) {
        invokeOriginalSafe(args(), errorCallback)
    }

    /**
     * Invoke the original method with custom [args], swallowing any thrown
     * exception and forwarding it to [errorCallback]. Sets the result via
     * [setResult] on success.
     */
    fun invokeOriginalSafe(args: Array<Any?>, errorCallback: Consumer<Throwable>) {
        runCatching {
            setResult(XposedBridge.invokeOriginalMethod(method(), thisObject(), args))
        }.onFailure {
            errorCallback.accept(it)
        }
    }

    private fun invokeMethodSafe(obj: Any, methodName: String): Any? {
        return try {
            obj::class.java.getMethod(methodName).invoke(obj)
        } catch (e: NoSuchMethodException) {
            null
        }
    }

    private fun <T : Any> handlePrimitiveDefaults(clazz: Class<T>): T? {
        return when (clazz) {
            Int::class.java -> 0 as T
            Double::class.java -> 0.0 as T
            Float::class.java -> 0f as T
            Long::class.java -> 0L as T
            Boolean::class.java -> false as T
            else -> null
        }
    }

    /**
     * Best-effort coercion of [arg] to [clazz] via the standard
     * `toString` / `toInt` / `toDouble` / `toFloat` / `toLong` / `toBoolean`
     * reflective call. Returns `null` if coercion fails.
     */
    fun <T : Any> convertToType(arg: Any, clazz: Class<T>): T? {
        return try {
            when (clazz) {
                String::class.java -> invokeMethodSafe(arg, "toString") as T
                Int::class.java -> invokeMethodSafe(arg, "toInt") as T
                Double::class.java -> invokeMethodSafe(arg, "toDouble") as T
                Float::class.java -> invokeMethodSafe(arg, "toFloat") as T
                Long::class.java -> invokeMethodSafe(arg, "toLong") as T
                Boolean::class.java -> invokeMethodSafe(arg, "toBoolean") as T
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }
}