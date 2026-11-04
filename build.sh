#!/usr/bin/env bash
# Builds liblocalapi.so for both Android ABIs and assembles app-debug.apk.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK (e.g. \$HOME/Android/Sdk)}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"

NDK="${ANDROID_NDK_HOME:-}"
if [ -z "$NDK" ]; then
  NDK="$(ls -d "$ANDROID_HOME"/ndk/* 2>/dev/null | sort -V | tail -1 || true)"
fi
[ -n "$NDK" ] && [ -d "$NDK" ] || {
  echo "Android NDK not found. Install it with: sdkmanager 'ndk;27.0.12077973'" >&2
  exit 1
}

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

echo "==> gradle"
cd "$ROOT/app"
./gradlew --no-daemon assembleDebug

APK="$ROOT/app/app/build/outputs/apk/debug/app-debug.apk"
echo "APK: $APK"
ls -lh "$APK"
