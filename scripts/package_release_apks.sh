#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
ABIS="${ABIS:-arm64-v8a armeabi-v7a}"
SIGNING_PROPERTIES="${SIGNING_PROPERTIES:-$PROJECT_ROOT/temp/signing/signing.properties}"
BUILD_TOOLS_DIR="${BUILD_TOOLS_DIR:-/home/easul/software/android/sdk/build-tools/36.0.0}"
OUTPUT_DIR="${OUTPUT_DIR:-$PROJECT_ROOT/app/release}"

require_file() {
  [ -f "$1" ] || {
    echo "Missing file: $1" >&2
    exit 1
  }
}

require_cmd_file() {
  [ -x "$1" ] || {
    echo "Missing executable: $1" >&2
    exit 1
  }
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "Missing command: $1" >&2
    exit 1
  }
}

property() {
  local key="$1"
  local value
  value="$(grep -E "^${key}=" "$SIGNING_PROPERTIES" | tail -n 1 | cut -d= -f2-)"
  if [ -z "$value" ]; then
    echo "Missing signing property: $key" >&2
    exit 1
  fi
  printf '%s' "$value"
}

require_file "$SIGNING_PROPERTIES"
require_cmd_file "$BUILD_TOOLS_DIR/zipalign"
require_cmd_file "$BUILD_TOOLS_DIR/apksigner"
require_cmd git

if [ -z "${BUILD_VERSION_CODE:-}" ]; then
  if git -C "$PROJECT_ROOT" rev-parse --verify origin/main >/dev/null 2>&1; then
    MAIN_COMMIT_COUNT="$(git -C "$PROJECT_ROOT" rev-list --count origin/main)"
  else
    MAIN_COMMIT_COUNT="$(git -C "$PROJECT_ROOT" rev-list --count HEAD)"
  fi
  BUILD_VERSION_CODE="$((5000 + MAIN_COMMIT_COUNT))"
fi

if [ -z "${BUILD_VERSION_NAME:-}" ]; then
  COMMIT_HASH="$(git -C "$PROJECT_ROOT" rev-parse HEAD | cut -c1-6)"
  VERSION_TAG="$(git -C "$PROJECT_ROOT" tag --points-at HEAD --list 'v*' | sort -V | tail -n 1)"
  if [ -z "$VERSION_TAG" ]; then
    VERSION_TAG="v0.0.0"
  fi
  BUILD_VERSION_NAME="${VERSION_TAG}+${COMMIT_HASH}"
fi

export BUILD_VERSION_CODE
export BUILD_VERSION_NAME

echo "==> Version code: $BUILD_VERSION_CODE"
echo "==> Version name: $BUILD_VERSION_NAME"

STORE_FILE="$(property storeFile)"
STORE_PASSWORD="$(property storePassword)"
KEY_ALIAS="$(property keyAlias)"
KEY_PASSWORD="$(property keyPassword)"

case "$STORE_FILE" in
  /*) KEYSTORE="$STORE_FILE" ;;
  *) KEYSTORE="$PROJECT_ROOT/$STORE_FILE" ;;
esac
require_file "$KEYSTORE"

mkdir -p "$OUTPUT_DIR"

for ABI in $ABIS; do
  echo "==> Building release APK for $ABI"
  (cd "$PROJECT_ROOT" && ./gradlew :app:clean :app:assembleRelease -PabiFilters="$ABI")

  UNSIGNED_APK="$PROJECT_ROOT/app/build/outputs/apk/release/app-release-unsigned.apk"
  ALIGNED_APK="$OUTPUT_DIR/app-release-$ABI-aligned.apk"
  SIGNED_APK="$OUTPUT_DIR/app-release-$ABI-signed.apk"

  require_file "$UNSIGNED_APK"
  rm -f "$ALIGNED_APK" "$SIGNED_APK"

  echo "==> Aligning $ABI APK"
  "$BUILD_TOOLS_DIR/zipalign" -p -f 4 "$UNSIGNED_APK" "$ALIGNED_APK"

  echo "==> Signing $ABI APK"
  "$BUILD_TOOLS_DIR/apksigner" sign \
    --ks "$KEYSTORE" \
    --ks-pass "pass:$STORE_PASSWORD" \
    --ks-key-alias "$KEY_ALIAS" \
    --key-pass "pass:$KEY_PASSWORD" \
    --out "$SIGNED_APK" \
    "$ALIGNED_APK"

  echo "==> Verifying $ABI APK"
  "$BUILD_TOOLS_DIR/apksigner" verify --verbose "$SIGNED_APK"
  if [ -x "$BUILD_TOOLS_DIR/aapt" ]; then
    "$BUILD_TOOLS_DIR/aapt" dump badging "$SIGNED_APK" | grep "native-code:.*'$ABI'" >/dev/null || {
      echo "APK native-code metadata does not contain expected ABI: $ABI" >&2
      exit 1
    }
  fi

  rm -f "$ALIGNED_APK"
  echo "APK: $SIGNED_APK"
done
