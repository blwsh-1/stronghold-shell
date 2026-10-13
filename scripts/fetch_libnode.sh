#!/usr/bin/env bash
# 准备 jniLibs 下的原生库：
#   - libnode.so        从 nodejs-mobile 预编译包下载（60MB，不入库）
#   - libc++_shared.so  libnode 的 C++ 运行时依赖，随仓库 prebuilt 提供（6.7MB）
set -eu

NJM_VERSION="${NJM_VERSION:-v18.20.4}"
ABI="${ABI:-arm64-v8a}"
DEST="app/src/main/jniLibs/$ABI"
NJM_URL="https://github.com/nodejs-mobile/nodejs-mobile/releases/download/${NJM_VERSION}/nodejs-mobile-${NJM_VERSION}-android.zip"
PREBUILT="prebuilt/$ABI"

mkdir -p "$DEST"

# ---- 1. libnode.so ----
if [ -f "$DEST/libnode.so" ]; then
  echo "==> libnode.so 已存在，跳过下载"
else
  TMP="$(mktemp -d)"
  echo "==> 下载 $NJM_URL"
  curl -L --fail --retry 5 --retry-delay 3 -o "$TMP/njm.zip" "$NJM_URL"
  echo "==> 解压"
  unzip -q -o "$TMP/njm.zip" -d "$TMP/njm"
  echo "==> 复制 libnode.so ($ABI)"
  cp "$TMP/njm/bin/$ABI/libnode.so" "$DEST/libnode.so"
  ls -lh "$DEST/libnode.so"
  rm -rf "$TMP"
fi

# ---- 2. libc++_shared.so（libnode 运行时依赖，必需）----
echo "==> 准备 libc++_shared.so ($ABI)"
if [ -f "$DEST/libc++_shared.so" ]; then
  echo "    已存在，跳过"
elif [ -f "$PREBUILT/libc++_shared.so" ]; then
  echo "    从仓库 prebuilt 复制"
  cp "$PREBUILT/libc++_shared.so" "$DEST/libc++_shared.so"
else
  echo "!! prebuilt 中未找到，尝试 Android NDK"
  CPP_SO="$(find "${ANDROID_HOME:-/usr/local/lib/android/sdk}" \
              -path "*arm64-v8a/libc++_shared.so" 2>/dev/null | head -n1 || true)"
  if [ -n "$CPP_SO" ]; then
    cp "$CPP_SO" "$DEST/libc++_shared.so"
  else
    echo "!! 仍然找不到 libc++_shared.so，构建产将无法运行，主动失败"
    exit 1
  fi
fi
ls -lh "$DEST/libc++_shared.so"

echo "==> 完成，jniLibs 内容："
ls -lh "$DEST"