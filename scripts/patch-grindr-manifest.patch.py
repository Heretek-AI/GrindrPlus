#!/usr/bin/env python3
"""
patch-grindr-manifest.py — neutralise the PairIP / install-source guards at the
manifest layer. Applies the morphe-patches `pairIPManifestPatch` technique
without going through smali.

Usage:
  python3 patch-grindr-manifest.py <base.apk> <output.apk>

What it does:
  1. If `<application android:name="com.pairip.application.Application">` is
     present, replace with `com.grindrapp.android.RealApplication` (the real
     app class per the JADX decompile).
  2. Remove the `<activity android:name="com.pairip.licensecheck.LicenseActivity">`
     declaration (defense-in-depth — the activity is also unreachable once
     Application.attachBaseContext returns without invoking the chain).
  3. Remove the `<uses-permission android:name="com.android.vending.CHECK_LICENSE">`
     permission.

This is the same set of edits morphe-patches applies via
`pairIPManifestPatch()`. After this, the bytecode-level PairIP checks
(`VMRunner.<clinit>`, `SignatureCheck.verifyIntegrity`, etc.) are still
present in the dex but never invoked because the wrapper class that calls
them is no longer referenced from the manifest.

For a complete bypass (also killing the native VM and the periodic
re-checks), pair this with `patch-grindr-smali.py` (companion script) or
use the GPlus manager's LSPatch flow which embeds the GPlus dex and
rewrites the bytecode in one step.
"""
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

REPLACEMENTS = [
    (
        'android:name="com.pairip.application.Application"',
        'android:name="com.grindrapp.android.RealApplication"',
    ),
]

REMOVE_PATTERNS = [
    re.compile(
        r'<activity\s+[^>]*android:name="com\.pairip\.licensecheck\.LicenseActivity"[^>]*/>'
    ),
    re.compile(
        r'<uses-permission\s+[^>]*android:name="com\.android\.vending\.CHECK_LICENSE"[^>]*/>'
    ),
]


def patch_manifest_text(manifest: str) -> str:
    out = manifest
    for old, new in REPLACEMENTS:
        out = out.replace(old, new)
    for rx in REMOVE_PATTERNS:
        out = rx.sub('', out)
    return out


def patch_apk(src: Path, dst: Path) -> None:
    with tempfile.TemporaryDirectory() as td:
        # apktool round-trip: decompile, edit, rebuild
        work = Path(td) / "work"
        work.mkdir()
        subprocess.run(
            ["apktool", "d", "-f", "-s", "-o", str(work), str(src)],
            check=True, capture_output=True,
        )
        am_path = work / "AndroidManifest.xml"
        am_path.write_text(patch_manifest_text(am_path.read_text()), encoding="utf-8")
        subprocess.run(
            ["apktool", "b", "-o", str(dst), str(work)],
            check=True, capture_output=True,
        )


def main() -> int:
    if len(sys.argv) != 3:
        print(__doc__)
        return 2
    src = Path(sys.argv[1])
    dst = Path(sys.argv[2])
    if not src.exists():
        print(f"input not found: {src}", file=sys.stderr)
        return 1
    patch_apk(src, dst)
    print(f"patched manifest written to {dst}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
