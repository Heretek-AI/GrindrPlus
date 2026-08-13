#!/usr/bin/env bash
#
# check-patch-docs.sh — every hook in --hooks-dir must have a corresponding
# docs/patches/<kebab>.md. Exits 1 and lists the missing docs if any are absent.
#
# Mapping: AntiBlock.kt -> anti_block.md (snake_case, lowercase, .md).
#
# Defaults assume the standard repo layout. Override with --hooks-dir / --docs-dir.
#
set -euo pipefail

HOOKS_DIR="app/src/main/java/com/grindrplus/hooks"
DOCS_DIR="docs/patches"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --hooks-dir)
            HOOKS_DIR="$2"
            shift 2
            ;;
        --docs-dir)
            DOCS_DIR="$2"
            shift 2
            ;;
        -h|--help)
            sed -n '2,12p' "$0"
            exit 0
            ;;
        *)
            echo "Unknown flag: $1" >&2
            exit 1
            ;;
    esac
done

REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
cd "$REPO_ROOT"

if [[ ! -d "$HOOKS_DIR" ]]; then
    echo "::error::hooks dir not found: $HOOKS_DIR" >&2
    exit 2
fi
if [[ ! -d "$DOCS_DIR" ]]; then
    echo "::error::docs dir not found: $DOCS_DIR" >&2
    exit 2
fi

# Convert PascalCase.kt -> snake_case.md via Python (more portable than bash).
# Handles acronyms gracefully: `SSLUnpinning` -> `ssl_unpinning`, not `s_s_l_unpinning`.
kebab() {
    python3 - "$1" <<'PY'
import re, sys
name = sys.argv[1]
stem = name[:-3] if name.endswith(".kt") else name
# Step 1: only insert an underscore when an uppercase letter follows a lowercase.
# (This is what most editors do for snake-case conversion.)
stem = re.sub(r'(?<=[a-z])(?=[A-Z])', '_', stem)
# Step 2: an all-caps cluster of length >= 2 followed by an uppercase + lowercase
# pair is treated as an acronym; insert the boundary underscore there.
# e.g. `SSLUnpinning` -> `SSL_Unpinning`.
stem = re.sub(r'([A-Z]+)([A-Z][a-z])', r'\1_\2', stem)
# Step 3: lowercase.
print(stem.lower())
PY
}

missing=()
present=()

shopt -s nullglob
for kt in "$HOOKS_DIR"/*.kt; do
    base="$(basename "$kt")"
    doc_name="$(kebab "$base").md"
    if [[ -f "$DOCS_DIR/$doc_name" ]]; then
        present+=("$doc_name")
    else
        missing+=("$base -> $doc_name")
    fi
done
shopt -u nullglob

echo "Hooks scanned: $(( ${#present[@]} + ${#missing[@]} ))"
echo "Docs present:  ${#present[@]}"
echo "Docs missing:  ${#missing[@]}"

if [[ ${#missing[@]} -gt 0 ]]; then
    echo ""
    echo "Missing patch docs:"
    for m in "${missing[@]}"; do
        echo "  - $m"
    done
    echo ""
    echo "::error::Add docs/patches/<kebab>.md for each hook above."
    echo "::error::See .claude/skills/patch-doc-coverage/ for the stub template."
    exit 1
fi

echo "All hooks have patch docs."
