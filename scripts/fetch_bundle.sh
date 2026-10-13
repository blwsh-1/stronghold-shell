#!/usr/bin/env bash
# 下载上游最新游戏包，打成 assets/app_bundle.zip
# ⚠️ 仅在你拥有素材使用权的场合使用（自用）。不要提交产物到仓库！
set -euo pipefail

UPSTREAM="sganggs/Stronghold-Protocol"
ASSET_DIR="app/src/main/assets"
BUNDLE="$ASSET_DIR/app_bundle.zip"
MARKER="$ASSET_DIR/bundle.sha256"
TMP="$(mktemp -d)"

mkdir -p "$ASSET_DIR"

echo "==> 查询上游最新 release"
TAG=$(curl -s "https://api.github.com/repos/$UPSTREAM/releases/latest" | grep -o '"tag_name": *"[^"]*"' | cut -d'"' -f4)
echo "    最新版本: $TAG"

URL=$(curl -s "https://api.github.com/repos/$UPSTREAM/releases/tags/$TAG" \
  | grep -o '"browser_download_url": *"[^"]*\.zip"' | cut -d'"' -f4 \
  | grep -v -E 'lite|update' | head -1)
echo "    下载: $URL"

curl -L --fail -o "$TMP/full.zip" "$URL"
echo "==> 解压"
unzip -q -o "$TMP/full.zip" -d "$TMP/up"
# 剥掉唯一一层顶层目录
TOP=$(find "$TMP/up" -mindepth 1 -maxdepth 1 -type d | head -1)
if [ -n "$TOP" ] && [ "$(find "$TMP/up" -mindepth 1 -maxdepth 1 | wc -l)" -eq 1 ]; then
  mv "$TOP" "$TMP/root"
else
  mv "$TMP/up" "$TMP/root"
fi

echo "==> 注入 boot 回报补丁"
IDX="$TMP/root/public/index.html"
if [ -f "$IDX" ] && ! grep -q "AndroidNative" "$IDX"; then
  PATCH='</head>'
  sed -i "s#</head>#<script>window.addEventListener("load",function(){try{window.AndroidNative\&\&window.AndroidNative.reportClientState(JSON.stringify({booted:true}))}catch(e){}});</script></head>#" "$IDX"
fi

echo "==> 打包 app_bundle.zip（store 模式）"
( cd "$TMP/root" && zip -q -r -X -0 "$OLDPWD/$BUNDLE" . )
VER="${TAG#v}"
echo -n "${VER}-patched" > "$MARKER"

rm -rf "$TMP"
echo "==> 完成: $BUNDLE ($(du -h "$BUNDLE" | cut -f1))"
