#!/usr/bin/env bash
# =============================================================================
# collect_fcl_logs.sh — 一键收集 FCL 游戏日志与崩溃报告，打包成 zip 便于反馈
#
# 用法（Termux 内，或桌面把路径改成你的）：
#   bash collect_fcl_logs.sh [游戏目录，默认自动探测]
# 产出：./fcl-debug-logs-<时间戳>.zip
# =============================================================================
set -euo pipefail

OUT_ZIP="fcl-debug-logs-$(date +%Y%m%d-%H%M%S).zip"
TMP="$(mktemp -d)"

# 自动探测候选游戏目录
CANDIDATES=(
  "${1:-}"
  "/sdcard/Android/data/com.tungsten.fclauncher/files/.minecraft"
  "$HOME/Android/data/com.tungsten.fclauncher/files/.minecraft"
  "/storage/emulated/0/Android/data/com.tungsten.fclauncher/files/.minecraft"
  "$HOME/storage/shared/Android/data/com.tungsten.fclauncher/files/.minecraft"
)

GAME_DIR=""
for c in "${CANDIDATES[@]}"; do
  if [ -n "$c" ] && [ -d "$c" ]; then
    GAME_DIR="$c"
    break
  fi
done

if [ -z "$GAME_DIR" ]; then
  echo "未自动找到 FCL 游戏目录，请手动传参：bash collect_fcl_logs.sh <游戏目录>" >&2
  exit 1
fi

echo "==> 游戏目录: $GAME_DIR"

# 收集 logs 与 crash-reports（含版本隔离子目录）
mkdir -p "$TMP/logs"
find "$GAME_DIR" -maxdepth 4 -path '*/logs/*' -name '*.log' 2>/dev/null | while read -r f; do
  cp -v "$f" "$TMP/logs/" 2>/dev/null || true
done
find "$GAME_DIR" -maxdepth 4 -path '*/crash-reports/*' -name '*.txt' 2>/dev/null | while read -r f; do
  mkdir -p "$TMP/crash"
  cp -v "$f" "$TMP/crash/" 2>/dev/null || true
done

# mod 自己的运行目录（vhdnum / setup.json / bin 状态）
if [ -d "$GAME_DIR/vm_computers" ]; then
  mkdir -p "$TMP/vm_computers"
  cp -r "$GAME_DIR/vm_computers" "$TMP/" 2>/dev/null || true
fi

# 手机信息
{
  echo "== device info =="
  getprop ro.product.model 2>/dev/null || true
  getprop ro.build.version.release 2>/dev/null || true
  echo "RAM total:"; grep MemTotal /proc/meminfo 2>/dev/null || true
} > "$TMP/device.txt" || true

# 打包
cd "$TMP" && zip -r -q "$OLDPWD/$OUT_ZIP" . || {
  # zip 不存在时用 tar 兜底
  cd "$TMP" && tar -czf "$OLDPWD/$OUT_ZIP.tar.gz" . && echo "已打包: $PWD/$OUT_ZIP.tar.gz"
  exit 0
}
echo "==> 已打包: $PWD/$OUT_ZIP"
echo "把这个 zip 发给开发者即可。"
