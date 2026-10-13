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

# ⭐ libnode.so 依赖 C++ 标准库 libc++_shared.so，必须一并打包，否则
#    dlopen("libnode.so") 会报 "library libc++_shared.so not found"
echo "==> 查找并复制 libc++_shared.so ($ABI)"
CPP_SO="$(find "$TMP/njm" -name 'libc++_shared.so' | head -n1 || true)"
if [ -n "$CPP_SO" ]; then
  cp "$CPP_SO" "$DEST/libc++_shared.so"
  ls -lh "$DEST/libc++_shared.so"
else
  echo "!! njm 包中未找到，尝试从 Android NDK 取"
  CPP_SO="$(find "${ANDROID_HOME:-/usr/local/lib/android/sdk}/ndk" \
              -path "*arm64-v8a/libc++_shared.so" 2>/dev/null | head -n1 || true)"
  if [ -n "$CPP_SO" ]; then
    cp "$CPP_SO" "$DEST/libc++_shared.so"
    ls -lh "$DEST/libc++_shared.so"
  else
    echo "!! 仍然找不到 libc++_shared.so，构建产将无法运行，主动失败"
    exit 1
  fi
fi

rm -rf "$TMP"
echo "==> 完成"