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

extract_release_label_from_latest() {
  local file="$1"
  [[ -f "$file" ]] || return 0
  sed -n 's/^- Current release: v\(.*\)$/\1/p' "$file" | head -n 1
}

resolve_config_value() {
  local env_name="$1"
  local property_name="$2"
  local default_value="${3:-}"
  local value="${!env_name:-}"

  if [[ -z "$value" ]]; then
    value="$(extract_first_match "^${property_name}=\\(.*\\)$" "$ROOT_DIR/key.properties")"
  fi

  if [[ -z "$value" ]]; then
    value="$default_value"
  fi

  printf '%s' "$value"
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

json_read_field() {
  local json_file="$1"
  local field_name="$2"

  python3 - "$json_file" "$field_name" <<'PY'
import json
import sys

json_file, field_name = sys.argv[1], sys.argv[2]
with open(json_file, "r", encoding="utf-8") as handle:
    payload = json.load(handle)

data = payload.get("data") if isinstance(payload, dict) and isinstance(payload.get("data"), dict) else payload
value = data.get(field_name, "") if isinstance(data, dict) else ""
print("" if value is None else value)
PY
}

validate_pgyer_response() {
  local json_file="$1"

  python3 - "$json_file" <<'PY'
import json
import sys

with open(sys.argv[1], "r", encoding="utf-8") as handle:
    payload = json.load(handle)

if not isinstance(payload, dict):
    sys.stderr.write("Pgyer upload failed: invalid JSON response\n")
    sys.exit(1)

code = payload.get("code")
if code not in (None, 0, "0"):
    message = payload.get("message") or payload.get("msg") or payload.get("error") or "unknown error"
    sys.stderr.write(f"Pgyer upload failed ({code}): {message}\n")
    sys.exit(1)

data = payload.get("data") if isinstance(payload.get("data"), dict) else payload
if not isinstance(data, dict):
    sys.stderr.write("Pgyer upload failed: missing app payload\n")
    sys.exit(1)

if not any(data.get(key) for key in ("appKey", "appShortcutUrl", "appQRCodeURL")):
    sys.stderr.write("Pgyer upload failed: response missing app identifiers\n")
    sys.exit(1)
PY
}

upload_to_pgyer() {
  local apk_path="$1"
  local release_label="$2"
  local api_key
  local user_key
  local install_type
  local password
  local channel_shortcut
  local update_description
  local response_file
  local shortcut_url
  local qr_url
  local app_key
  local -a curl_args

  api_key="$(resolve_config_value "PGYER_API_KEY" "pgyerApiKey")"
  user_key="$(resolve_config_value "PGYER_USER_KEY" "pgyerUserKey")"

  if [[ -z "$api_key" || -z "$user_key" ]]; then
    echo "Pgyer upload skipped: credentials not configured."
    return 0
  fi

  install_type="$(resolve_config_value "PGYER_INSTALL_TYPE" "pgyerInstallType" "1")"
  password="$(resolve_config_value "PGYER_PASSWORD" "pgyerPassword")"
  channel_shortcut="$(resolve_config_value "PGYER_CHANNEL_SHORTCUT" "pgyerChannelShortcut")"
  update_description="$(resolve_config_value "PGYER_UPDATE_DESCRIPTION" "pgyerUpdateDescription" "ZenSee Android v${release_label}")"

  if [[ "$install_type" == "2" && -z "$password" ]]; then
    fail "Pgyer installType=2 requires PGYER_PASSWORD or pgyerPassword"
  fi

  response_file="$(mktemp "${TMPDIR:-/tmp}/zensee-pgyer-upload.XXXXXX.json")"
  curl_args=(
    --silent
    --show-error
    --fail
    -F "file=@${apk_path}"
    -F "uKey=${user_key}"
    -F "_api_key=${api_key}"
    -F "installType=${install_type}"
    -F "updateDescription=${update_description}"
  )

  if [[ -n "$password" ]]; then
    curl_args+=(-F "password=${password}")
  fi

  if [[ -n "$channel_shortcut" ]]; then
    curl_args+=(-F "channelShortcut=${channel_shortcut}")
  fi

  echo "Uploading release APK to Pgyer..."
  curl "${curl_args[@]}" "https://upload.pgyer.com/apiv1/app/upload" > "$response_file"

  validate_pgyer_response "$response_file"

  shortcut_url="$(json_read_field "$response_file" "appShortcutUrl")"
  qr_url="$(json_read_field "$response_file" "appQRCodeURL")"
  app_key="$(json_read_field "$response_file" "appKey")"

  rm -f "$response_file"

  echo
  echo "Pgyer upload completed:"
  [[ -n "$shortcut_url" ]] && echo "  Shortcut URL: https://www.pgyer.com/${shortcut_url}"
  [[ -n "$qr_url" ]] && echo "  QR Code URL:  ${qr_url}"
  [[ -n "$app_key" ]] && echo "  App Key:      ${app_key}"
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
VERSION_CODE="$(extract_first_match '.*versionCode = \([0-9][0-9]*\).*' "$ROOT_DIR/app/build.gradle")"
[[ -n "$VERSION_CODE" ]] || fail "Unable to parse versionCode from app/build.gradle"

RELEASE_LABEL="$(normalize_release_label "$VERSION_NAME")"
APK_PATH="$ROOT_DIR/app/build/outputs/apk/release/app-release.apk"
LATEST_DIR="$ROOT_DIR/downloads/latest"
LATEST_APK="$LATEST_DIR/ZenSee-android-latest.apk"
PREVIOUS_RELEASE_LABEL="$(extract_release_label_from_latest "$LATEST_DIR/README.md")"

if [[ -n "$PREVIOUS_RELEASE_LABEL" && "$PREVIOUS_RELEASE_LABEL" == "$RELEASE_LABEL" ]]; then
  fail "Current release label v${RELEASE_LABEL} already matches downloads/latest. Bump versionCode/versionName in app/build.gradle before publishing again."
fi

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

echo "Publishing ZenSee Android v$RELEASE_LABEL (versionCode $VERSION_CODE)"
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

upload_to_pgyer "$APK_PATH" "$RELEASE_LABEL"

echo
echo "Next step:"
echo "  git add downloads/latest"
echo "  git commit -m \"Publish Android v$RELEASE_LABEL\""
echo "  git push"
