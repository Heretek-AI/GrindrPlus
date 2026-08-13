#!/usr/bin/env python3
"""
patch-grindr-smali.py — bytecode-level neutralisation of the PairIP SDK, mirrors
morphe-patches' `killPairIpFull` + `pairIPManifestPatch`.

Usage:
  python3 patch-grindr-smali.py <base.apk> <output.apk>

What it does (per morphe-patches killPairIpFull):
  - VMRunner.<clinit>            → return-void       (stops loadLibrary("pairipcore"))
  - VMRunner.invoke              → return-void       (the IAP VM entry)
  - SignatureCheck.verifyIntegrity → return-void    (signature whitelist)
  - LicenseClient.checkLicense   → return-void       (static entry)
  - LicenseClient.initializeLicenseCheck → return-void
  - LicenseClient.processResponse → return-void
  - LicenseClient.startPaywallActivity → return-void
  - LicenseClient.performLocalInstallerCheck → return true (passes Play Store check)
  - LicenseClient.checkLicenseInternal → return-void
  - StartupLauncher.launch        → return-void

Limitations:
  - Uses apktool + baksmali/smali. This corrupts Kotlin metadata in classes
    heavily using `kotlinx.metadata` (kotlinx.coroutines, Firebase Sessions),
    causing crashes at app startup with `NullPointerException: key can't be null`.
  - The GPlus manager's `PatchApkStep` solves this by using `com.reandroid.apk`
    (arsclib) directly to manipulate the DEX without going through smali,
    preserving Kotlin metadata. Use that path for production deployments.

This script is a quick-and-dirty research tool — useful for understanding
PairIP's footprint and for experimenting with hooks, but NOT a production
patcher. Pair it with the GPlus manager's LSPatch flow for a complete
production deployment.
"""
import re
import subprocess
import sys
import tempfile
from pathlib import Path

SMALI_DIR_NAME = "smali_classes2"
SMALI_FILES = {
    "LicenseClient": "com/pairip/licensecheck/LicenseClient.smali",
    "SignatureCheck": "com/pairip/SignatureCheck.smali",
    "VMRunner": "com/pairip/VMRunner.smali",
    "StartupLauncher": "com/pairip/StartupLauncher.smali",
}

# (file key, method-header regex, replacement body lines)
PATCHES = [
    ("LicenseClient",
     r"^\.method public static checkLicense\(Landroid/content/Context;\)V",
     ["    return-void"]),
    ("LicenseClient",
     r"^\.method private checkLicenseInternal\(Landroid/os/IBinder;\)V",
     ["    return-void"]),
    ("LicenseClient",
     r"^\.method public initializeLicenseCheck\(\)V",
     ["    return-void"]),
    ("LicenseClient",
     r"^\.method private processResponse\(ILandroid/os/Bundle;\)V",
     ["    return-void"]),
    ("LicenseClient",
     r"^\.method private startPaywallActivity\(Landroid/app/PendingIntent;\)V",
     ["    return-void"]),
    ("LicenseClient",
     r"^\.method private performLocalInstallerCheck\(\)Z",
     ["    const/4 v0, 0x1", "    return v0"]),
    ("VMRunner",
     r"^\.method static constructor <clinit>\(\)V",
     ["    return-void"]),
    ("VMRunner",
     r"^\.method public static invoke\(\[B\[Ljava/lang/Object;\)Ljava/lang/Object;",
     ["    const/4 v0, 0x0", "    return-object v0"]),
    ("SignatureCheck",
     r"^\.method public static verifyIntegrity\(Landroid/content/Context;\)V",
     ["    return-void"]),
    ("StartupLauncher",
     r"^\.method public static declared-synchronized launch\(\)V",
     ["    return-void"]),
]


def patch_smali(path: Path, header_regex: str, new_body: list[str]) -> bool:
    rx = re.compile(header_regex)
    lines = path.read_text().splitlines()
    for i, line in enumerate(lines):
        if not rx.match(line):
            continue
        # Match .end method (handling nested .annotation blocks)
        j = i + 1
        depth = 0
        while j < len(lines):
            s = lines[j].lstrip()
            if s.startswith(".annotation"):
                depth += 1
            elif s.startswith(".end annotation"):
                depth -= 1
            elif depth == 0 and lines[j].startswith(".end method"):
                break
            j += 1
        if j >= len(lines):
            return False
        # Walk forward preserving .registers/.locals/.annotation/.param/.line
        last_kept = i
        depth = 0
        for k in range(i + 1, j):
            s = lines[k].lstrip()
            if s.startswith(".annotation"):
                depth += 1
                last_kept = k
            elif s.startswith(".end annotation"):
                depth -= 1
                last_kept = k
            elif depth == 0:
                if s.startswith((".registers", ".locals", ".param", ".end param", ".line", ".prologue")):
                    last_kept = k
                elif s == "":
                    pass
                else:
                    break
        body_start = last_kept + 1
        while body_start < j and lines[body_start].strip() == "":
            body_start += 1
        new_lines = lines[:body_start] + new_body + lines[j:]
        path.write_text("\n".join(new_lines) + "\n")
        return True
    return False


def patch_apk(src: Path, dst: Path) -> int:
    with tempfile.TemporaryDirectory() as td:
        work = Path(td) / "work"
        work.mkdir()
        subprocess.run(
            ["apktool", "d", "-f", "-o", str(work), str(src)],
            check=True, capture_output=True,
        )
        applied = 0
        for file_key, header_regex, new_body in PATCHES:
            smali_path = work / SMALI_DIR_NAME / SMALI_FILES[file_key]
            if patch_smali(smali_path, header_regex, new_body):
                applied += 1
        subprocess.run(
            ["apktool", "b", "-o", str(dst), str(work)],
            check=True, capture_output=True,
        )
        return applied


def main() -> int:
    if len(sys.argv) != 3:
        print(__doc__)
        return 2
    src = Path(sys.argv[1])
    dst = Path(sys.argv[2])
    if not src.exists():
        print(f"input not found: {src}", file=sys.stderr)
        return 1
    n = patch_apk(src, dst)
    print(f"{n}/${len(PATCHES)} patches applied; output: {dst}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
