---
name: 16kb-page-alignment
description: Diagnose and fix 16KB memory page alignment compatibility issues for Android 15/16+ across LSPatch and native shared libraries (.so). Use when encountering "dlopen failed: empty/unaligned load segment" or running on 16KB kernel emulators (sdk_gphone16k_*).
metadata:
  type: project
---

# 16kb-page-alignment

Guide for ensuring 16KB memory page alignment compatibility on modern Android systems (Android 15 / 16 / API 35+ / `sdk_gphone16k_*`).

## Problem Overview

Traditional Android systems run with 4KB (4096-byte) memory pages. Android 15+ introduces support and requirements for 16KB (16384-byte / `0x4000`) page sizes.
Two major failure modes occur on 16KB kernels:

1. **ELF Load Segment Alignment**: If a `.so` binary was linked with 4KB `LOAD` segment alignment (e.g. `p_align = 0x1000`), the dynamic linker refuses to map it:
   ```
   dlopen failed: empty/unaligned load segment
   ```
2. **ZIP Entry Alignment in APKs**: Uncompressed shared libraries in APKs (`android:extractNativeLibs="false"`) must be aligned to 16KB boundaries inside the ZIP file (offset `% 16384 == 0`). Standard `zipalign -p 4` or standard `lspatch.jar` only align to 4096 bytes.

## Diagnostics

### 1. Check ELF Alignment
Use `readelf` or `llvm-readelf`:
```bash
readelf -l <path_to_so> | grep -A 1 LOAD
```
Look for `Align 0x4000` (16KB) vs `Align 0x1000` (4KB).

### 2. Check APK ZIP Entry Alignment
```bash
zipalign -c -v -p 16 <path_to_apk>
```
Or check byte offsets with `zipinfo -v <path_to_apk>`.

## Fixes

### 1. Patching `lspatch.jar` for 16KB Alignment
LSPatch uses `ApkZFileCreator` and `LSPatch.class` which hardcode `4096` (`0x1000`) as the alignment for `.so` files.
To patch `app/libs/lspatch.jar`:
- Decompile `LSPatch.class` and `ApkZFileCreator.class` (or patch bytecode constants `SIPUSH 4096` -> `SIPUSH 16384` / `0x4000`).
- Reassemble into `app/libs/lspatch.jar`.

### 2. Compiling Native Libraries with 16KB Alignment
When compiling with NDK clang:
```bash
$NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/<arch>-clang \
  -shared -fPIC \
  -Wl,-z,max-page-size=16384 \
  <sources> -o <output.so>
```
Verify with `readelf -l <output.so> | grep Align` to ensure `0x4000` is present.
