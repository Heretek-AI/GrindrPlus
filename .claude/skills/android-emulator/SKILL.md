---
name: android-emulator
description: Launch the local Android Virtual Device `Pixel_10_Pro_XL` on this machine (AVD lives under the Flatpak-managed Android Studio's config; SDK at /home/john/Android/Sdk). Use when the user says "boot the emulator", "start an Android device", "run on emulator", or whenever you need a live Android device to install + smoke-test GrindrPlus.
metadata:
  type: project
---

# android-emulator

Boot the local AVD named `Pixel_10_Pro_XL` and confirm it's reachable
via `adb`. The emulator binary is the standalone SDK one (not bundled
with Android Studio); the AVD itself was created by the Flatpak
Android Studio and lives outside the default `~/.android/avd` path,
which is why the env override is required.

## When to use

- The user wants to launch the Android emulator.
- You need a live Android device to install + smoke-test GrindrPlus
  (e.g. after `verify-build`).
- An LSPosed / hook integration test needs a fresh device.

Do **not** use this skill if the user only wants to compile the module
(that's `verify-build`).

## Prerequisites

- Android SDK installed at `/home/john/Android/Sdk/`.
- AVD `Pixel_10_Pro_XL` registered. It lives at
  `~/.var/app/com.google.AndroidStudio/config/.android/avd/Pixel_10_Pro_XL.avd/`
  — the `ANDROID_AVD_HOME` env var in the launch command below points
  the SDK's `emulator` at that path.
- `adb` on `$PATH` (it's in `/home/john/Android/Sdk/platform-tools/`).
- KVM acceleration enabled in BIOS. The Pixel_10_Pro_XL image is x86_64
  and will be unusable without KVM.

## Launch command

```bash
cd /home/john/Android/Sdk/emulator \
  && ANDROID_AVD_HOME=~/.var/app/com.google.AndroidStudio/config/.android/avd \
     ./emulator -avd Pixel_10_Pro_XL -verbose
```

`-verbose` floods stderr with emulator init detail — useful when the
boot stalls and you need to know which subsystem is hanging. Drop it
for routine launches.

The emulator runs as a foreground process. To leave it running and
return to the shell, launch it in the background:

```bash
cd /home/john/Android/Sdk/emulator \
  && ANDROID_AVD_HOME=~/.var/app/com.google.AndroidStudio/config/.android/avd \
     nohup ./emulator -avd Pixel_10_Pro_XL > /tmp/avd.log 2>&1 &
```

## Verify it actually booted

`adb devices` should show `emulator-5554  device` (the trailing
`device` is the important part — `offline` means boot is still in
progress):

```bash
adb devices
# List of devices attached
# emulator-5554  device
```

The first boot after a fresh AVD is slow (1–2 min). Subsequent boots
are ~15s. If `adb devices` shows `offline` for > 60s, see
**Troubleshooting** below.

Once the emulator is `device`, sanity-check the API level matches what
GrindrPlus targets:

```bash
adb shell getprop ro.build.version.sdk
# Should be ≥ 26 (the project's minSdk in app/build.gradle.kts)
```

## Install GrindrPlus on the emulator

```bash
# Debug APK from ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/GPlus_v*-debug.apk
```

If `adb install` returns `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, an older
GPlus is already installed under a different signing key — uninstall
first:

```bash
adb uninstall com.grindrplus
adb install -r app/build/outputs/apk/debug/GPlus_v*-debug.apk
```

## Useful follow-up commands

| What you want | Command |
|---|---|
| Tail Grindr / Gplus logs | `adb logcat -v threadtime \| grep -E 'grindrplus\|com.grindrapp.android\|AndroidRuntime'` |
| Open the manager UI | `adb shell am start -n com.grindrplus/.manager.MainActivity` |
| Forward LSPosed's port to the host | (LSPosed uses in-process hooks; no port-forward needed) |
| Reboot the emulator | `adb reboot` |
| Power off the emulator | `adb emu kill` |
| Wipe userdata (clean boot) | `adb shell pm clear com.grindrapp.android` |
| Install the target Grindr APK | `adb install <grindr-base.apk>` then open the manager app and click Install |
| Take a screenshot | `adb exec-out screencap -p > screenshot.png` |

## Troubleshooting

### `PANIC: Could not find AVD: Pixel_10_Pro_XL.ini`

Two possible causes:

1. AVD name typo (case-sensitive). Run
   `ANDROID_AVD_HOME=~/.var/app/com.google.AndroidStudio/config/.android/avd ls ~/.var/app/com.google.AndroidStudio/config/.android/avd/`
   to list available AVD names.

2. `ANDROID_AVD_HOME` not exported. The launch command above sets it
   inline for that one process — confirm the env line is still there.

### Boot stalls at "Starting emulator for AVD 'Pixel_10_Pro_XL'…"

Likely KVM not available. Check:

```bash
ls -l /dev/kvm          # should exist and be owned by your user or kvm group
egrep -c '(vmx|svm)' /proc/cpuinfo   # should be > 0
```

If `/dev/kvm` is missing or `vmx/svm` count is 0, KVM isn't enabled —
no amount of flag-tweaking will fix that; BIOS needs `VT-x` (Intel) or
`SVM` (AMD) on.

### `adb devices` shows `emulator-5554  offline` for > 60s

- First boot is slow. Give it another 30–60s.
- If persistent, the AVD may be wedged: `adb emu kill` then relaunch.
- If it recurs every boot, the userdata image may be corrupt. Wipe with
  `ANDROID_AVD_HOME=~/.var/app/com.google.AndroidStudio/config/.android/avd ./emulator -avd Pixel_10_Pro_XL -wipe-data`.

### Emulator boots but `adb` never connects

Something else already grabbed port 5554. Check:

```bash
ss -tlnp | grep 5554
# or
lsof -i :5554
```

If another process is on the port, kill it and relaunch.

## See also

- `.claude/skills/verify-build/` — build the APK before installing it
  on the emulator.
- `.claude/skills/lspatch-build-workflow/` — the full gradle
  setupLSPatch + assembleDebug + assembleRelease flow.
- `docs/env_setup.md` — full dev environment setup (mitmproxy,
  JADX, LSPosed/LSPatch on real devices).
