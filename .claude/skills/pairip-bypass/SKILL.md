---
name: pairip-bypass
description: Neutralize PairIP DRM protection on Grindr via manifest repointing, native JNI stub injection, and runtime hook safeguards. Use when dealing with PairIP crashes, SIGSEGV in libpairipcore.so, or startup integrity checks on Grindr 26.13.0+.
metadata:
  type: project
---

# pairip-bypass

Workflow and instructions for neutralizing Google's PairIP DRM / App Integrity system in Grindr APKs and GrindrPlus.

## Architecture

PairIP protects Grindr across 3 layers:
1. **Manifest Wrapper**: `<application android:name="com.pairip.application.Application">`
2. **Deep Class Initializers**: `RealApplication` extends `vt5` -> `hf5`. In `hf5.<clinit>`, `StartupLauncher.launch()` calls `VMRunner.invoke()` -> `System.loadLibrary("pairipcore")` -> native `executeVM`.
3. **Native Scanners**: `libpairipcore.so` checks APK integrity / memory signatures and crashes with `SIGSEGV` (`SEGV_ACCERR`) if tampered.

## Neutralization Strategy

### 1. Manifest Neutralization (arsclib / ApkModule)
In `PatchApkStep.kt`, before patching with LSPatch, load `base.apk` with `ApkModule` and change the application name:
```kotlin
val appElement = apkModule.androidManifest.applicationElement
val appNameAttr = appElement.searchAttributeByName("name")
if (appNameAttr != null && appNameAttr.valueString == "com.pairip.application.Application") {
    appNameAttr.setValueAsString(StyleDocument.parseStyledString("com.grindrapp.android.RealApplication"))
    apkModule.writeApk(baseApk)
}
```

### 2. Native Stub Replacement (`libpairipcore.so`)
Do **NOT** try to remove `StartupLauncher.launch()` via `apktool`/`smali` (which breaks `kotlinx.metadata` annotations and crashes coroutines). Instead, substitute `libpairipcore.so` in split APKs with an ABI-compatible, 16KB-aligned C stub:

Stub implementation (`pairip_stub.c`):
```c
#include <jni.h>
#include <stddef.h>

static jobject JNICALL native_executeVM(JNIEnv *env, jclass clazz, jobjectArray args) {
    return NULL;
}

void ExecuteProgram(void) {}

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    JNIEnv *env = NULL;
    if ((*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass clazz = (*env)->FindClass(env, "com/pairip/VMRunner");
    if (clazz != NULL) {
        JNINativeMethod methods[] = {
            {"executeVM", "([Ljava/lang/Object;)Ljava/lang/Object;", (void*)&native_executeVM}
        };
        (*env)->RegisterNatives(env, clazz, methods, 1);
        (*env)->DeleteLocalRef(env, clazz);
    }
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNI_OnUnload(JavaVM *vm, void *reserved) {}
```

Compile for all architectures with 16KB alignment:
```bash
$NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/x86_64-linux-android35-clang -shared -fPIC -Wl,-z,max-page-size=16384 pairip_stub.c -o libpairipcore_x86_64.so
$NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android35-clang -shared -fPIC -Wl,-z,max-page-size=16384 pairip_stub.c -o libpairipcore_arm64_v8a.so
$NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/armv7a-linux-androideabi35-clang -shared -fPIC -Wl,-z,max-page-size=16384 pairip_stub.c -o libpairipcore_armeabi_v7a.so
$NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/i686-linux-android35-clang -shared -fPIC -Wl,-z,max-page-size=16384 pairip_stub.c -o libpairipcore_x86.so
```
Place the compiled stubs in `app/src/main/assets/pairip/`.

### 3. Automated Injection in `PatchApkStep.kt`
`PatchApkStep.replacePairIpNativeLibs` inspects all split APKs before LSPatch embedding, and replaces any `libpairipcore.so` entries with the corresponding asset.

### 4. Runtime Fallback Hook (`DisablePairIP.kt`)
Hooks `VMRunner.invoke`, `SignatureCheck.verifyIntegrity`, and `LicenseActivity` to neutralize any secondary checks or paywall dialogs.

## Verification
1. Launch patched Grindr:
   ```bash
   adb shell monkey -p com.grindrapp.android -c android.intent.category.LAUNCHER 1
   ```
2. Confirm process is active:
   ```bash
   adb shell ps -A | grep com.grindrapp.android
   ```
3. Confirm logcat is free of `SIGSEGV` or `SEGV_ACCERR`.
