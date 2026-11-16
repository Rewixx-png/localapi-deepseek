#!/usr/bin/env bash
# Usage: ./build.sh [debug|release]
set -euo pipefail

MODE="${1:-debug}"
if [ "$MODE" != "debug" ] && [ "$MODE" != "release" ]; then
  echo "Usage: $0 [debug|release]" >&2
  exit 1
fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$ANDROID_HOME" ]; then
  for candidate in "$HOME/Android/Sdk" "/usr/local/lib/android/sdk" "/opt/android-sdk"; do
    if [ -d "$candidate" ]; then
      ANDROID_HOME="$candidate"
      break
    fi
  done
fi
: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK}"
export ANDROID_HOME
export ANDROID_SDK_ROOT="$ANDROID_HOME"

NDK="${ANDROID_NDK_HOME:-}"
if [ -z "$NDK" ]; then
  NDK="$(ls -d "$ANDROID_HOME"/ndk/* 2>/dev/null | sort -V | tail -1 || true)"
fi
[ -n "$NDK" ] && [ -d "$NDK" ] || {
  echo "Android NDK not found. Install it with: sdkmanager 'ndk;27.0.12077973'" >&2
  exit 1
}

CLOUDFLARED_VERSION="2026.10.7"
CLOUDFLARED_URL="https://github.com/cloudflare/cloudflared/releases/download/${CLOUDFLARED_VERSION}/cloudflared-linux-arm64"
CLOUDFLARED_SHA256="3df43aedc9ff291d1735b5cb576cb97e0b157488796db2e32b28aecabb445775"

QEMU_LIBS_URL="https://github.com/Rewixx-png/localapi-deepseek/releases/download/native-deps-v1/qemu-arm64-android-libs-v1.tar.xz"
QEMU_LIBS_SHA256="c489ab54b81827b60db8cab84464bea9dd3cc721b778873795a7554f919b80ba"

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

download_verified() {
  local url="$1" expected="$2" dest="$3"
  local tmp="${dest}.tmp.$$"
  rm -f "$tmp"
  curl -fsSL --retry 3 -o "$tmp" "$url"
  local actual
  actual="$(sha256_file "$tmp")"
  if [ "$actual" != "$expected" ]; then
    rm -f "$tmp"
    echo "SHA-256 mismatch for $url: expected $expected, got $actual" >&2
    exit 1
  fi
  mv "$tmp" "$dest"
}

ensure_native_deps() {
  local jni_arm64="$ROOT/app/app/src/main/jniLibs/arm64-v8a"
  mkdir -p "$jni_arm64"
  local cf_bin="$jni_arm64/libcloudflared.so"
  if [ ! -f "$cf_bin" ] || [ "$(sha256_file "$cf_bin")" != "$CLOUDFLARED_SHA256" ]; then
    echo "==> Fetching cloudflared ${CLOUDFLARED_VERSION} (arm64)"
    download_verified "$CLOUDFLARED_URL" "$CLOUDFLARED_SHA256" "$cf_bin"
    chmod 755 "$cf_bin"
  fi
  local qemu_stamp="$jni_arm64/.qemu-libs.sha256"
  if [ ! -f "$jni_arm64/libqemu-system-aarch64.so" ] || [ ! -f "$qemu_stamp" ] || [ "$(cat "$qemu_stamp")" != "$QEMU_LIBS_SHA256" ]; then
    echo "==> Fetching QEMU arm64 libs (native-deps-v1)"
    local tmp_tar="$jni_arm64/.qemu-libs.tar.xz"
    download_verified "$QEMU_LIBS_URL" "$QEMU_LIBS_SHA256" "$tmp_tar"
    tar -C "$jni_arm64" -xJf "$tmp_tar"
    rm -f "$tmp_tar"
    chmod 755 "$jni_arm64/libqemu-system-aarch64.so"
    printf '%s\n' "$QEMU_LIBS_SHA256" > "$qemu_stamp"
  fi
}

ensure_native_deps

TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
API=26

build_abi() {
  local triple="$1" abi="$2" cc_prefix="$3"
  local cc="$TOOLCHAIN/${cc_prefix}${API}-clang"
  local underscore="${triple//-/_}"
  local upper
  upper="$(echo "$triple" | tr 'a-z' 'A-Z' | tr '-' '_')"
  [ -x "$cc" ] || { echo "missing compiler: $cc" >&2; exit 1; }
  echo "==> $abi"
  export "CC_${underscore}=$cc"
  export "AR_${underscore}=$TOOLCHAIN/llvm-ar"
  export "RANLIB_${underscore}=$TOOLCHAIN/llvm-ranlib"
  export "CARGO_TARGET_${upper}_LINKER=$cc"
  cargo build --release --target "$triple" -p localapi-android

  mkdir -p "$ROOT/app/app/src/main/jniLibs/$abi"
  cp "$ROOT/target/$triple/release/liblocalapi.so" \
     "$ROOT/app/app/src/main/jniLibs/$abi/liblocalapi.so"
}

build_abi aarch64-linux-android arm64-v8a aarch64-linux-android
build_abi x86_64-linux-android x86_64 x86_64-linux-android

echo "==> gradle ($MODE)"
cd "$ROOT/app"
if [ "$MODE" = "release" ]; then
  if [ -z "${ANDROID_KEYSTORE_PATH:-}" ] && [ -f "$HOME/.android/localapi-signing.env" ]; then
    # shellcheck disable=SC1091
    source "$HOME/.android/localapi-signing.env"
  fi
  : "${ANDROID_KEYSTORE_PATH:?Set ANDROID_KEYSTORE_PATH for release signing}"
  [ -f "$ANDROID_KEYSTORE_PATH" ] || { echo "Keystore not found: $ANDROID_KEYSTORE_PATH" >&2; exit 1; }
  ./gradlew --no-daemon assembleRelease
  APK="$ROOT/app/app/build/outputs/apk/release/app-release.apk"
else
  ./gradlew --no-daemon assembleDebug
  APK="$ROOT/app/app/build/outputs/apk/debug/app-debug.apk"
fi

echo "APK: $APK"
ls -lh "$APK"
echo "SHA256: $(sha256_file "$APK")"
