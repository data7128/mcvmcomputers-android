#!/usr/bin/env bash
# =============================================================================
# build_qemu_android.sh — 构建 Android ARM64 可用的静态 QEMU
#
# 产物（静态 aarch64 ELF，可直接在 Android/Linux 上运行）：
#   out-android-qemu/qemu-system-x86_64
#   out-android-qemu/qemu-img
#
# 方式：在 alpine:3.20 (linux/arm64) 容器内构建。
#   静态 musl 二进制不依赖 bionic，规避 Android libc 兼容问题（无需 Termux 那套补丁）。
#   Alpine main 自带 glib-static / pixman-static / zlib-static，构建干净可复现。
#
# 依赖：docker（或 podman 支持 --platform）+ 能跑 arm64 容器（可用
#       multiarch/qemu-user-static 在 x86 上模拟 arm64）。
#
# 用法：
#   ./scripts/build_qemu_android.sh [QEMU版本，默认8.2.4]
# 例：
#   ./scripts/build_qemu_android.sh 8.2.4
#
# CI 中由 .github/workflows/qemu-alpine-build.sh 完成同样的容器构建。
# =============================================================================
set -euo pipefail
QEMU_VER="${1:-8.2.4}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/out-android-qemu"
mkdir -p "$OUT"

command -v docker >/dev/null 2>&1 || { echo "ERROR: 需要 docker" >&2; exit 1; }

echo "==> 注册 arm64 模拟（x86 主机需要）"
docker run --rm --privileged multiarch/qemu-user-static --reset -p yes

echo "==> 在 arm64 Alpine 容器内构建 QEMU $QEMU_VER"
docker run --rm --platform linux/arm64 \
  -v "$ROOT":/build \
  -e QEMU_VERSION="$QEMU_VER" \
  alpine:3.20 /bin/sh /build/.github/workflows/qemu-alpine-build.sh

echo "==> 产物校验"
file "$OUT/qemu-system-x86_64" "$OUT/qemu-img"
ls -lh "$OUT"

echo ""
echo "下一步：把这些文件拷入 mod 资源目录（或交给 CI 的 android-build.yml 自动打包）："
echo "  mkdir -p $ROOT/src/main/resources/assets/qemu && cp $OUT/* $ROOT/src/main/resources/assets/qemu/ && chmod +x $ROOT/src/main/resources/assets/qemu/*"
