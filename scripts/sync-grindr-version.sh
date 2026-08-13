#!/usr/bin/env bash
#
# sync-grindr-version.sh — refresh version.json + patch the two listOf(...) lines
# in app/build.gradle.kts in one pass.
#
# Usage:
#   bash scripts/sync-grindr-version.sh                  # apply for real
#   bash scripts/sync-grindr-version.sh --dry-run        # print the diff, don't write
#   bash scripts/sync-grindr-version.sh --gradle-kts path/to/build.gradle.kts
#
# Exit codes:
#   0 — success (or "already up to date")
#   1 — APKMirror unreachable / fetch_version.py failed
#   2 — could not parse fetched version
#   3 — gradle-kts file not found
#
set -euo pipefail

GRADLE_KTS="app/build.gradle.kts"
DRY_RUN="false"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --dry-run)
            DRY_RUN="true"
            shift
            ;;
        --gradle-kts)
            GRADLE_KTS="$2"
            shift 2
            ;;
        -h|--help)
            sed -n '2,18p' "$0"
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

if [[ ! -f "$GRADLE_KTS" ]]; then
    echo "::error::Gradle build file not found: $GRADLE_KTS" >&2
    exit 3
fi

VERSION_JSON="version.json"
TMP_JSON="version.json.tmp"

echo ">>> Fetching latest Grindr version via fetch_version.py"
if ! python3 fetch_version.py -o "$TMP_JSON"; then
    echo "::error::fetch_version.py failed (APKMirror unreachable?)" >&2
    rm -f "$TMP_JSON"
    exit 1
fi

if ! command -v jq >/dev/null 2>&1; then
    echo "::error::jq is required to parse version.json" >&2
    rm -f "$TMP_JSON"
    exit 2
fi

NEW_NAME="$(jq -r '.versionName' "$TMP_JSON")"
NEW_CODE="$(jq -r '.versionCode' "$TMP_JSON")"

if [[ -z "$NEW_NAME" || -z "$NEW_CODE" || "$NEW_NAME" == "null" || "$NEW_CODE" == "null" ]]; then
    echo "::error::Could not parse versionName/versionCode from fetch output" >&2
    rm -f "$TMP_JSON"
    exit 2
fi

CURRENT_NAME="$(grep -E '^[[:space:]]*val grindrVersionName' "$GRADLE_KTS" \
    | sed -E 's/.*listOf\("([^"]+)".*/\1/' | head -n1)"
CURRENT_CODE="$(grep -E '^[[:space:]]*val grindrVersionCode' "$GRADLE_KTS" \
    | sed -E 's/.*listOf\(([0-9]+).*/\1/' | head -n1)"

if [[ "$CURRENT_NAME" == "$NEW_NAME" && "$CURRENT_CODE" == "$NEW_CODE" ]]; then
    echo "Already up to date: versionName=$NEW_NAME versionCode=$NEW_CODE"
    cp "$TMP_JSON" "$VERSION_JSON"
    rm -f "$TMP_JSON" "${GRADLE_KTS}.bak"
    exit 0
fi

echo "Bumping: versionName $CURRENT_NAME -> $NEW_NAME; versionCode $CURRENT_CODE -> $NEW_CODE"

if [[ "$DRY_RUN" == "true" ]]; then
    echo "--- diff (dry-run; nothing written) ---"
    echo "--- version.json ---"
    diff -u "$VERSION_JSON" "$TMP_JSON" || true
    echo "--- $GRADLE_KTS ---"
    sed -E \
        -e "s/(grindrVersionName = listOf\\(\")[^\"]+(\"\\))/\1${NEW_NAME}\2/" \
        -e "s/(grindrVersionCode = listOf\\()[0-9]+(\\))/\1${NEW_CODE}\2/" \
        "$GRADLE_KTS" \
        | diff -u "$GRADLE_KTS" - || true
    rm -f "$TMP_JSON"
    exit 0
fi

# Apply version.json
cp "$TMP_JSON" "$VERSION_JSON"
rm -f "$TMP_JSON"

# Patch the two listOf(...) lines in build.gradle.kts
sed -i.bak -E \
    -e "s/(grindrVersionName = listOf\\(\")[^\"]+(\"\\))/\1${NEW_NAME}\2/" \
    -e "s/(grindrVersionCode = listOf\\()[0-9]+(\\))/\1${NEW_CODE}\2/" \
    "$GRADLE_KTS"

echo "Wrote $VERSION_JSON and patched $GRADLE_KTS"
echo "Verify with: ./gradlew printVersionInfo --quiet"
