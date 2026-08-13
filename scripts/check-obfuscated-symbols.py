#!/usr/bin/env python3
"""
check-obfuscated-symbols.py — walk every hook file in --hooks, extract the
`// search for '<snippet>'` markers, and grep a JADX-decompiled APK for them.

Outputs a Markdown table to STDOUT:

    | Status | Marker | Old symbol | Candidates | Confidence |

Status ∈ {OK, AMBIGUOUS, STALE}.
- OK        — exactly one candidate file matched.
- AMBIGUOUS — multiple candidate files matched; human must disambiguate.
- STALE     — no candidate file matched; the snippet may be obsolete.

By default the script exits 0 even on STALE markers (grep-friendly).
Pass --strict to exit non-zero on STALE (useful in CI when you want
to force a maintainer to investigate before merging).

Pure stdlib — no third-party deps so it can run anywhere with Python 3.
"""
from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Iterable

MARKER_RE = re.compile(
    r"""val\s+(?P<name>\w+)\s*=\s*"(?P<symbol>[^"]+)"\s*//\s*search for\s+'(?P<snippet>[^']+)'""",
    re.MULTILINE,
)
# Variant for comments above hook calls — capture the next line's symbol
# and method body. We only extract snippet text in that case.
BODY_MARKER_RE = re.compile(
    r"""//\s*search for\s+'(?P<snippet>[^']+)'""",
)


@dataclass
class MarkerHit:
    """A single (snippet, old_symbol) tuple we found in a hook file."""
    file: Path
    line: int
    name: str | None  # val name, if the marker is on a val line
    symbol: str | None  # captured obfuscated name (val RHS)
    snippet: str


@dataclass
class Candidate:
    """A single JADX-output match for a snippet."""
    path: Path
    line_no: int


@dataclass
class Result:
    marker: MarkerHit
    candidates: list[Candidate] = field(default_factory=list)

    @property
    def status(self) -> str:
        if not self.candidates:
            return "STALE"
        if len(self.candidates) == 1:
            return "OK"
        return "AMBIGUOUS"

    @property
    def confidence(self) -> str:
        s = self.status
        if s == "OK":
            return "high"
        if s == "AMBIGUOUS":
            return "low"
        return "n/a"


def walk_hook_files(hooks_dir: Path) -> Iterable[Path]:
    for p in sorted(hooks_dir.glob("*.kt")):
        yield p


def extract_markers(kt_file: Path) -> list[MarkerHit]:
    """Return every `val X = "y" // search for 'snippet'` line in the file."""
    hits: list[MarkerHit] = []
    text = kt_file.read_text(encoding="utf-8", errors="replace")
    # First pass: val-line markers
    for m in MARKER_RE.finditer(text):
        line = text.count("\n", 0, m.start()) + 1
        hits.append(
            MarkerHit(
                file=kt_file,
                line=line,
                name=m.group("name"),
                symbol=m.group("symbol"),
                snippet=m.group("snippet"),
            )
        )
    # Second pass: body-comment markers (// search for '<snippet>' on its own line).
    # These don't carry an `old_symbol` because they comment a hook call site.
    for m in BODY_MARKER_RE.finditer(text):
        snippet = m.group("snippet")
        # Skip duplicates already captured by the val-line pass.
        if any(h.snippet == snippet and h.file == kt_file for h in hits):
            continue
        line = text.count("\n", 0, m.start()) + 1
        hits.append(
            MarkerHit(
                file=kt_file,
                line=line,
                name=None,
                symbol=None,
                snippet=snippet,
            )
        )
    return hits


def grep_snippet(jadx_root: Path, snippet: str) -> list[Candidate]:
    """grep -rF -- '<snippet>' <jadx_root>/sources"""
    sources = jadx_root / "sources"
    if not sources.is_dir():
        # Older JADX layouts just dump everything into the root.
        sources = jadx_root

    # Use subprocess so we don't have to read every file into Python.
    try:
        out = subprocess.run(
            ["grep", "-rF", "-n", "--", snippet, str(sources)],
            capture_output=True,
            text=True,
            timeout=30,
            check=False,
        )
    except subprocess.TimeoutExpired:
        return []

    candidates: list[Candidate] = []
    for raw in out.stdout.splitlines():
        if ":" not in raw:
            continue
        path_str, _, line_str = raw.partition(":")
        try:
            line_no = int(line_str.split(":", 1)[0])
        except ValueError:
            continue
        candidates.append(Candidate(path=Path(path_str), line_no=line_no))
    return candidates


def render_markdown(results: list[Result]) -> str:
    out = [
        "# Obfuscation-marker scan",
        "",
        f"Markers found: **{len(results)}**",
        "",
        "| Status | Marker | Old symbol | Candidates | Confidence | File |",
        "|--------|--------|------------|------------|------------|------|",
    ]
    for r in results:
        marker = r.marker
        # Truncate long snippets for the table.
        snip = marker.snippet if len(marker.snippet) <= 60 else marker.snippet[:57] + "…"
        sym = marker.symbol or "(body)"
        if not r.candidates:
            cands = "—"
        else:
            shown = []
            for c in r.candidates[:3]:
                rel = c.path
                # Show as JADX-rooted path so the maintainer can locate it.
                shown.append(f"`{rel}`:{c.line_no}")
            cands = "; ".join(shown)
            if len(r.candidates) > 3:
                cands += f" (+{len(r.candidates) - 3} more)"
        rel = marker.file.name
        out.append(
            f"| {r.status} | `{snip}` | `{sym}` | {cands} | {r.confidence} | `{rel}` |"
        )
    out.append("")
    n_stale = sum(1 for r in results if r.status == "STALE")
    n_ambig = sum(1 for r in results if r.status == "AMBIGUOUS")
    n_ok = sum(1 for r in results if r.status == "OK")
    out.append(
        f"Summary: **{n_ok} OK**, **{n_ambig} AMBIGUOUS**, **{n_stale} STALE**"
    )
    return "\n".join(out) + "\n"


def render_json(results: list[Result]) -> str:
    import json
    return json.dumps(
        [
            {
                "file": str(r.marker.file),
                "line": r.marker.line,
                "val_name": r.marker.name,
                "old_symbol": r.marker.symbol,
                "snippet": r.marker.snippet,
                "status": r.status,
                "confidence": r.confidence,
                "candidates": [
                    {"path": str(c.path), "line": c.line_no}
                    for c in r.candidates
                ],
            }
            for r in results
        ],
        indent=2,
    )


def main(argv: list[str]) -> int:
    p = argparse.ArgumentParser(
        description="Scan // search-for markers in hook files against a JADX-out dir."
    )
    p.add_argument(
        "--jadx",
        required=True,
        type=Path,
        help="Path to JADX decompilation output (the dir that contains sources/).",
    )
    p.add_argument(
        "--hooks",
        type=Path,
        default=Path("app/src/main/java/com/grindrplus/hooks"),
        help="Path to the hooks/ source directory.",
    )
    p.add_argument(
        "--format",
        choices=("markdown", "json"),
        default="markdown",
    )
    p.add_argument(
        "--strict",
        action="store_true",
        help="Exit non-zero if any STALE markers are found.",
    )
    args = p.parse_args(argv)

    if not args.hooks.is_dir():
        print(f"::error::hooks dir not found: {args.hooks}", file=sys.stderr)
        return 2
    if not args.jadx.exists():
        print(f"::error::JADX dir not found: {args.jadx}", file=sys.stderr)
        return 2

    results: list[Result] = []
    for kt in walk_hook_files(args.hooks):
        for marker in extract_markers(kt):
            cands = grep_snippet(args.jadx, marker.snippet)
            results.append(Result(marker=marker, candidates=cands))

    if args.format == "json":
        print(render_json(results))
    else:
        print(render_markdown(results))

    if args.strict and any(r.status == "STALE" for r in results):
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
