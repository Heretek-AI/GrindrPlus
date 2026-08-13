---
name: lsposed-hooking
description: How to write or modify a hook in app/src/main/java/com/grindrplus/hooks/. Use when adding a new feature patch, fixing a broken hook after a Grindr update, or porting an existing hook to a new Grindr version. Always pair with the obfuscation-marker convention from AGENTS.md § 3.
metadata:
  type: project
---

# LSPosed & Xposed Hooking in GrindrPlus

This skill teaches the **GrindrPlus-specific** way to write a hook. The
project wraps the raw Xposed API in a Kotlin DSL — use that DSL, not the
raw `XposedHelpers` calls.

## When to use

- Adding a new hook (new feature / bypass) for the first time
- Editing an existing hook after a Grindr update broke the captured symbol
- Reviewing a PR that touches `app/src/main/java/com/grindrplus/hooks/`

## Step-by-step

### 1. Find the host-app target

1. Decompile `com.grindrapp.android` (target version from `version.json`)
   with JADX (see the `jadx` skill in this directory).
2. Locate the obfuscated class + method that does what you want to change.
   Pick a stable literal snippet you can later search against — see
   `AGENTS.md` § 3 for marker rules.

### 2. Write the hook file

Use `Hook` as the abstract base; **never** call
`XposedHelpers.findAndHookMethod` directly from a hook file. The project
DSL is documented in `app/src/main/java/com/grindrplus/utils/Hooker.kt`.

```kotlin
package com.grindrplus.hooks

import com.grindrplus.utils.Hook
import com.grindrplus.utils.HookStage
import com.grindrplus.utils.hook

class MyFeature : Hook(
    "My feature",                       // shown in the in-app toggle list
    "What this hook does in one line"   // shown as a subtitle
) {
    // Every captured obfuscated symbol carries a `// search for` marker.
    private val targetClass = "ka8" // search for ' <= 600000;'

    override fun init() {
        findClass(targetClass) // <-- comes from the Hook base class
            .hook("d", HookStage.BEFORE) { param ->
                // arg<T>(i) is a typed accessor; setArg(i, v) mutates.
                val lastSeen = param.arg<Long>(0)
                param.setResult(System.currentTimeMillis() - lastSeen <= savedDurationMillis)
            }
    }

    // cleanup() is required by the Hook base class. Override when you start
    // coroutines or hold reflection-cached resources. Empty body is fine
    // for pure stateless hooks — most existing hooks omit the override.
    override fun cleanup() {}
}
```

### 3. Use the project's logging, not `XposedBridge.log`

The repo wraps `XposedBridge.log` in `core/Logger.kt`:

```kotlin
import com.grindrplus.core.logd
import com.grindrplus.core.loge

logd("Online indicator refreshed: ${count}")              // debug-level
loge("Hook failed: ${e.message}"); Logger.writeRaw(e.stackTraceToString())  // error + stack
```

`XposedBridge.log(...)` will leak into the host-app's logcat under the
wrong tag. Don't use it from this repo.

### 4. Use `Config` for user-tunable settings

```kotlin
import com.grindrplus.core.Config

val savedMinutes = Config.get("my_feature_duration", 3).toString().toInt()
```

`Config.initHookSettings(...)` is called automatically by
`HookManager.registerHooks()` — you don't need to register anything manually.

### 5. Register the hook

Edit `app/src/main/java/com/grindrplus/utils/HookManager.kt` and add the
new class to the `hookList` literal in `registerHooks()`. CI will fail
the build if you forget the matching `docs/patches/<kebab>.md` — use the
`patch-doc-coverage` skill to author it.

## Do not

- Don't use `XposedHelpers.findAndHookMethod` / `XposedBridge.hookAllMethods`
  directly from a hook file. Use the `Hook` base class + `findClass(...).hook(...)`
  DSL. Raw Xposed calls bypass the project's type-safe `HookAdapter` and
  bypass the `Config` registration.
- Don't write a central `Hooks.kt` mapping object. The convention is
  inline `val x = "y" // search for '...'` markers per hook file. That's
  what the obfuscation-marker scanner (`scripts/check-obfuscated-symbols.py`)
  parses.
- Don't use `try { ... } catch (e: Throwable)` swallowing patterns without
  logging via `Logger.writeRaw(e.stackTraceToString())`. Host-app crashes
  are much harder to diagnose without the stack.
- Don't forget to wire up `Config.isHookEnabled(...)` gates if the hook
  does expensive work or has side effects that would surprise the user
  when first installed.

## Failure modes

- **`ClassNotFoundException` at runtime**: the obfuscated symbol changed
  in the new Grindr release. Re-decompile, find the new class/method
  letter, and replace the `val x = "..."` capture. Update the matching
  `// search for '...'` snippet too.
- **Hook body throws but host app survives**: the Xposed framework catches
  hook exceptions and logs them. Find them in the logcat — they show up
  under the GrindrPlus log source, not the host-app source.
- **Hook never fires**: verify the captured symbol matches by adding a
  one-off `logd("hook entered")` at the top of `init`. If you see it on
  every app launch, the symbol is correct; if not, it's stale.

## Verification

After any change to a hook file, run the `verify-build` skill to confirm
the module still assembles, then run the `update-obfuscated-symbols`
skill against a JADX dump of the target Grindr APK to verify the marker
isn't STALE.
