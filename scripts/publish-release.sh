#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

fail() {
  echo "Error: $*" >&2
  exit 1
}

require_file() {
  local path="$1"
  [[ -f "$path" ]] || fail "Missing required file: $path"
}

extract_first_match() {
  local pattern="$1"
  local file="$2"
  sed -n "s/${pattern}/\\1/p" "$file" | head -n 1
}

normalize_release_label() {
  local raw="$1"
  case "$raw" in
    *.*.*) printf '%s\n' "$raw" ;;
    *.*) printf '%s.0\n' "$raw" ;;
    *) printf '%s.0.0\n' "$raw" ;;
  esac
}

write_sha256_file() {
  local source_file="$1"
  local output_file="$2"
  local file_name
  file_name="$(basename "$source_file")"
  shasum -a 256 "$source_file" | awk -v name="$file_name" '{print $1 "  " name}' > "$output_file"
}

require_file "$ROOT_DIR/key.properties"
require_file "$ROOT_DIR/local.properties"

GRADLE_USER_HOME="${GRADLE_USER_HOME:-$ROOT_DIR/.gradle-publish}"
export GRADLE_USER_HOME

if [[ ! -d "$GRADLE_USER_HOME/wrapper" && -d "$HOME/.gradle/wrapper" ]]; then
  mkdir -p "$GRADLE_USER_HOME"
  cp -R "$HOME/.gradle/wrapper" "$GRADLE_USER_HOME/"
fi

VERSION_NAME="$(extract_first_match '.*versionName = "\([^"]*\)".*' "$ROOT_DIR/app/build.gradle")"
[[ -n "$VERSION_NAME" ]] || fail "Unable to parse versionName from app/build.gradle"

RELEASE_LABEL="$(normalize_release_label "$VERSION_NAME")"
APK_PATH="$ROOT_DIR/app/build/outputs/apk/release/app-release.apk"
LATEST_DIR="$ROOT_DIR/downloads/latest"
LATEST_APK="$LATEST_DIR/ZenSee-android-latest.apk"

SDK_DIR="$(extract_first_match '^sdk.dir=\(.*\)$' "$ROOT_DIR/local.properties")"
[[ -n "$SDK_DIR" ]] || fail "Unable to parse sdk.dir from local.properties"
[[ -d "$SDK_DIR/build-tools" ]] || fail "Android build-tools directory not found: $SDK_DIR/build-tools"

BUILD_TOOLS_VERSION="$(
  find "$SDK_DIR/build-tools" -mindepth 1 -maxdepth 1 -type d -exec basename {} \; |
    sort -t. -k1,1n -k2,2n -k3,3n |
    tail -n 1
)"
[[ -n "$BUILD_TOOLS_VERSION" ]] || fail "Unable to locate an Android build-tools version"

APKSIGNER="$SDK_DIR/build-tools/$BUILD_TOOLS_VERSION/apksigner"
[[ -x "$APKSIGNER" ]] || fail "apksigner not found: $APKSIGNER"

echo "Building release APK..."
./gradlew assembleRelease

[[ -f "$APK_PATH" ]] || fail "Release APK not found: $APK_PATH"

echo "Verifying APK signature..."
"$APKSIGNER" verify --verbose "$APK_PATH" >/dev/null

mkdir -p "$LATEST_DIR"

cp "$APK_PATH" "$LATEST_APK"
write_sha256_file "$LATEST_APK" "$LATEST_DIR/SHA256.txt"
cat > "$LATEST_DIR/README.md" <<EOF
# ZenSee Android Latest

- APK: \`$(basename "$LATEST_APK")\`
- Current release: v$RELEASE_LABEL
- SHA-256: see \`SHA256.txt\`

This directory provides the stable download URL used by the web download page and the app share entry.
When you publish a new Android release, re-run \`./scripts/publish-release.sh\` and commit the updated files.
EOF

echo
echo "Release artifacts updated:"
echo "  Stable APK:   $LATEST_APK"
echo "  Release label: v$RELEASE_LABEL"
echo "  SHA-256:      $(cat "$LATEST_DIR/SHA256.txt")"
echo
echo "Next step:"
echo "  git add downloads/latest"
echo "  git commit -m \"Publish Android v$RELEASE_LABEL\""
echo "  git push"
