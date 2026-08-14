---
name: test-install-avd
description: Automate building, deploying, patching, and testing GrindrPlus on Android Virtual Devices (AVD) using ADB and Mobile-MCP. Use when testing GrindrPlus on an emulator, running the Manager UI installation flow, or debugging startup crashes.
metadata:
  type: project
---

# test-install-avd

End-to-end testing, patching, and debugging workflow for GrindrPlus on Android Virtual Devices (AVD).

## Prerequisites

- Android SDK installed (`$ANDROID_HOME`, `emulator`, `adb`)
- Target AVD configured (e.g. `Pixel_10_Pro_XL`)
- Mobile-MCP server active

## Workflow

### 1. Start AVD & Verify ADB Connectivity
```bash
# Check running AVDs
/home/john/Android/Sdk/emulator/emulator -list-avds

# Start emulator in background if not already running
/home/john/Android/Sdk/emulator/emulator -avd Pixel_10_Pro_XL -no-snapshot -gpu host &

# Wait for boot completion
adb wait-for-device
adb shell getprop sys.boot_completed  # must return 1
```

### 2. Build Debug GrindrPlus APK
```bash
JAVA_HOME=/home/linuxbrew/.linuxbrew/opt/openjdk@21 \
PATH=/home/linuxbrew/.linuxbrew/opt/openjdk@21/bin:$PATH \
ANDROID_HOME=/home/john/Android/Sdk \
./gradlew assembleDebug
```

### 3. Deploy Artifacts to Emulator
```bash
# Push target Grindr APKM bundle and mod APK to Download directory
adb push com.grindrapp.android_<version>.apkm /sdcard/Download/
adb push app/build/outputs/apk/debug/GPlus_*.apk /sdcard/Download/

# Install the GrindrPlus Manager APK
adb install -r app/build/outputs/apk/debug/GPlus_*.apk
```

### 4. Patching via GrindrPlus Manager UI
Using Mobile-MCP or ADB touch events:
1. Launch GrindrPlus: `mobile_launch_app` with `packageName: "com.grindrplus"`.
2. Tap **Install** tab (bottom left navigation).
3. Tap **Install** button to open the Custom Installation dialog.
4. Tap **Select Grindr Bundle** -> select the `.apkm` file from `/sdcard/Download/`.
5. Tap **Select Mod File** -> select the `GPlus_*.apk` file from `/sdcard/Download/`.
6. Tap **Select Files** to start the patching process.
7. Confirm the system package installer prompt ("Install this app? Grindr").

### 5. Launch & Verify Execution
```bash
# Launch Grindr
adb shell monkey -p com.grindrapp.android -c android.intent.category.LAUNCHER 1

# Check process status
adb shell ps -A | grep com.grindrapp.android

# Check logcat for errors / hooks
adb logcat -d | grep -E "PairIP|GrindrPlus|LSPatch|FATAL"
```

### 6. Troubleshooting
- **Dynamic Linker Unaligned Load Fault**: Ensure `lspatch.jar` is 16KB-patched and native stubs have `-Wl,-z,max-page-size=16384` (see `16kb-page-alignment` skill).
- **PairIP SIGSEGV**: Ensure `PatchApkStep.kt` repointed `AndroidManifest.xml` to `RealApplication` and replaced `libpairipcore.so` with the stub (see `pairip-bypass` skill).
- **Kotlin Coroutines NPE**: Do not use `apktool`/`smali` bytecode editing on `base.apk` (strips `kotlinx.metadata`).
