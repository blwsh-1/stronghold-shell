#!/usr/bin/env bash
# 下载 nodejs-mobile 的预编译 libnode.so 到 jniLibs
# 这样仓库里不需要存 60MB 的二进制
set -euo pipefail

NJM_VERSION="${NJM_VERSION:-v18.20.4}"
ABI="${ABI:-arm64-v8a}"
DEST="app/src/main/jniLibs/$ABI"

URL="https://github.com/nodejs-mobile/nodejs-mobile/releases/download/${NJM_VERSION}/nodejs-mobile-${NJM_VERSION}-android.zip"
TMP="$(mktemp -d)"

mkdir -p "$DEST"

if [ -f "$DEST/libnode.so" ]; then
  echo "==> libnode.so 已存在，跳过"
  exit 0
fi

echo "==> 下载 $URL"
curl -L --fail --retry 5 --retry-delay 3 -o "$TMP/njm.zip" "$URL"

echo "==> 解压"
unzip -q -o "$TMP/njm.zip" -d "$TMP/njm"

echo "==> 复制 libnode.so ($ABI)"
cp "$TMP/njm/bin/$ABI/libnode.so" "$DEST/libnode.so"
ls -lh "$DEST/libnode.so"

rm -rf "$TMP"
echo "==> 完成"