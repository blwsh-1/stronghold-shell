#!/usr/bin/env bash
# ============================================================
#  sync-upstream.sh —— 上游出新版时的一键同步脚本
#
#  作用：
#    1. 查询上游 sganggs/Stronghold-Protocol 的最新版本
#    2. 与你当前打包用的版本对比
#    3. 若上游更新了，触发 GitHub Actions 云端重新构建 APK
#
#  用法（在手机容器终端里跑）：
#    export GH_TOKEN=ghp_xxxx        # 你的 GitHub token（repo + workflow 权限）
#    bash scripts/sync-upstream.sh            # 只检查是否有新版
#    bash scripts/sync-upstream.sh --build    # 有新版就直接触发云端构建
#
#  说明：本脚本不会自动安装到手机。构建完成后的新 APK 需你手动下载安装。
# ============================================================
set -uo pipefail

UPSTREAM="sganggs/Stronghold-Protocol"
REPO="${SP_REPO:-huajixiaobai/stronghold-shell}"
TOKEN="${GH_TOKEN:-}"
DO_BUILD=0
[ "${1:-}" = "--build" ] && DO_BUILD=1

api() {  # api <url>
  if [ -n "$TOKEN" ]; then
    curl -s -H "Authorization: token $TOKEN" "$1"
  else
    curl -s "$1"
  fi
}

echo "==> 查询上游最新版本"
TAG=$(api "https://api.github.com/repos/$UPSTREAM/releases/latest" \
  | grep -o '"tag_name": *"[^"]*"' | head -1 | cut -d'"' -f4)
if [ -z "$TAG" ]; then
  echo "!! 无法获取上游版本（网络或限流），请稍后再试"
  exit 1
fi
LATEST="${TAG#v}"
echo "    上游最新: $LATEST"

echo "==> 读取本地打包版本"
LOCAL="(未知)"
if [ -f app/src/main/assets/bundle.sha256 ]; then
  LOCAL=$(sed 's/-patched$//' app/src/main/assets/bundle.sha256 | tr -d '\n')
fi
echo "    本地打包: $LOCAL"

if [ "$LOCAL" = "$LATEST" ]; then
  echo "==> 已是最新，无需重建。"
  exit 0
fi

echo "==> 发现新版：$LOCAL -> $LATEST"
if [ "$DO_BUILD" -eq 0 ]; then
  echo "    （未加 --build，仅报告。加 --build 可直接触发云端构建）"
  exit 0
fi

if [ -z "$TOKEN" ]; then
  echo "!! 触发构建需要 GH_TOKEN 环境变量（repo + workflow 权限）"
  exit 1
fi

echo "==> 触发 GitHub Actions 云端构建（version=$LATEST）"
CODE=$(curl -s -o /tmp/dispatch.out -w '%{http_code}' -X POST \
  -H "Authorization: token $TOKEN" \
  -H "Accept: application/vnd.github+json" \
  "https://api.github.com/repos/$REPO/actions/workflows/build.yml/dispatches" \
  -d "{\"ref\":\"main\",\"inputs\":{\"version\":\"$LATEST\"}}")

if [ "$CODE" = "204" ]; then
  echo "✅ 已触发构建。几分钟后到 Release 页面拿新 APK："
  echo "   https://github.com/$REPO/releases"
  echo "   注意：构建完成后仍需你手动下载安装，不会自动更新到手机。"
else
  echo "!! 触发失败 HTTP $CODE"
  cat /tmp/dispatch.out 2>/dev/null
  exit 1
fi
