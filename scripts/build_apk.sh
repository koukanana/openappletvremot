#!/usr/bin/env bash
# 不用 Gradle，直接用 aapt2 + javac + d8 + zipalign + apksigner 编译一个最小 APK。
# 首次运行会自动下载 build-tools 与 Android 9 platform（约 150MB，只需一次）。
#
# 用法: build_apk.sh <项目目录> [输出apk]
# 项目目录需包含: AndroidManifest.xml, src/**, 可选 res/
set -euo pipefail

PROJ="$(cd "${1:?用法: build_apk.sh <项目目录> [输出apk]}" && pwd)"
OUT="${2:-$PROJ/build/app.apk}"
SDK="${ANDROID_SDK_ROOT:-$HOME/.androidtv/sdk}"
BT_URL="https://dl.google.com/android/repository/build-tools_r34-macosx.zip"
PLATFORM_URL="https://dl.google.com/android/repository/platform-28_r06.zip"
MIRROR_PREFIX="${SDK_MIRROR:-}"   # 可选：如 https://mirror.example.com/android

mkdir -p "$SDK"
cd "$SDK"

dl() {  # dl <url> <file>
  [ -f "$2" ] && return 0
  echo "== 下载 $(basename "$2")"
  curl -fL --retry 3 -m 600 -o "$2" "${MIRROR_PREFIX}$1"
}

find_tool() { find "$SDK" -maxdepth 4 -name "$1" -type f 2>/dev/null | head -1; }

if [ -z "$(find_tool aapt2)" ]; then
  dl "$BT_URL" build-tools.zip
  unzip -q -o build-tools.zip -d "$SDK"
fi
if ! find "$SDK" -maxdepth 3 -name android.jar -type f | grep -q .; then
  dl "$PLATFORM_URL" platform.zip
  unzip -q -o platform.zip -d "$SDK"
fi

AAPT2="$(find_tool aapt2)"
AJ="$(find "$SDK" -maxdepth 3 -name android.jar -type f | head -1)"
BTDIR="$(dirname "$AAPT2")"
D8JAR="$(find "$SDK" -maxdepth 4 -name d8.jar -type f | head -1)"
ZIPALIGN="$(find_tool zipalign)"
APKSIGNER="$(find_tool apksigner)"
KEYSTORE="$SDK/debug.keystore"

[ -n "$AAPT2" ] && [ -n "$AJ" ] && [ -n "$D8JAR" ] || { echo "SDK 不完整: $SDK"; exit 1; }

if [ ! -f "$KEYSTORE" ]; then
  keytool -genkeypair -keystore "$KEYSTORE" -alias androiddebugkey \
    -storepass android -keypass android -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
fi

cd "$PROJ"
rm -rf build && mkdir -p build/classes build/dex
OUT_DIR="$(dirname "$OUT")"; mkdir -p "$OUT_DIR"

LINK_ARGS=(-o build/app.unaligned.apk -I "$AJ" --manifest AndroidManifest.xml
           --min-sdk-version 28 --target-sdk-version 28)
if [ -d res ]; then
  echo "[1/6] aapt2 compile res"
  "$AAPT2" compile --dir res -o build/res.zip
  LINK_ARGS+=(-R build/res.zip --auto-add-overlay)
fi
echo "[2/6] aapt2 link"
"$AAPT2" link "${LINK_ARGS[@]}"

echo "[3/6] javac"
javac --release 8 -Xlint:-options -cp "$AJ" -d build/classes $(find src -name '*.java') 2>&1 \
  | grep -vE '^Note:' || true

echo "[4/6] d8"
java -cp "$D8JAR" com.android.tools.r8.D8 --lib "$AJ" --min-api 28 \
  --output build/dex $(find build/classes -name '*.class')

echo "[5/6] zip + zipalign"
( cd build/dex && zip -q -X ../app.unaligned.apk classes.dex && cd ../.. )
"$ZIPALIGN" -f 4 build/app.unaligned.apk build/app.aligned.apk

echo "[6/6] apksigner"
"$APKSIGNER" sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
  --v1-signing-enabled true --v2-signing-enabled true \
  --out "$OUT" build/app.aligned.apk

"$APKSIGNER" verify --print-certs "$OUT" | head -3
ls -lh "$OUT"
