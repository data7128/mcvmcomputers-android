#!/usr/bin/env bash
# =============================================================================
# build_qemu_android.sh — 交叉编译 Android ARM64 静态 QEMU
#
# 用途：在 Linux PC / CI 上产出两个静态二进制：
#   out/qemu-system-x86_64   （aarch64-android，TCG 软件模拟 x86_64）
#   out/qemu-img             （创建 qcow2 虚拟磁盘）
#
# 依赖：Android NDK（r23+，推荐 r25/r26）、curl、make、python3
#
# 用法：
#   ./scripts/build_qemu_android.sh [NDK路径] [QEMU版本，默认8.2.4]
# 例：
#   ./scripts/build_qemu_android.sh ~/Android/Sdk/ndk/26.3.11579264 8.2.4
# =============================================================================
set -euo pipefail

NDK="${1:-${ANDROID_NDK_HOME:-}}"
QEMU_VER="${2:-8.2.4}"
API_LEVEL=24
ARCH=aarch64-linux-android

if [ -z "$NDK" ] || [ ! -d "$NDK" ]; then
  echo "ERROR: 未找到 Android NDK。请传入 NDK 路径，或设置 \$ANDROID_NDK_HOME" >&2
  exit 1
fi

TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
OUT="$(cd "$(dirname "$0")/.." && pwd)/out-android-qemu"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "==> NDK: $NDK"
echo "==> QEMU: $QEMU_VER"
echo "==> 输出目录: $OUT"
mkdir -p "$OUT"

echo "==> 下载 QEMU $QEMU_VER 源码 ..."
cd "$WORK"
curl -sL -o qemu.tar.xz "https://download.qemu.org/qemu-${QEMU_VER}.tar.xz"
tar -xf qemu.tar.xz
cd "qemu-${QEMU_VER}"

echo "==> configure（静态 / 仅 x86_64-softmmu / 仅 VNC）..."
export CC="$TC/${ARCH}${API_LEVEL}-clang"
export CXX="$TC/${ARCH}${API_LEVEL}-clang++"
export AR="$TC/llvm-ar"
export RANLIB="$TC/llvm-ranlib"
export STRIP="$TC/llvm-strip"
export LD="$TC/ld.lld"

./configure \
  --target-list=x86_64-softmmu \
  --static \
  --disable-sdl --disable-gtk --disable-curses \
  --enable-vnc \
  --disable-tools --disable-docs --disable-gnutls \
  --disable-nettle --disable-vde --disable-netmap \
  --disable-linux-aio --disable-cap-ng --disable-vhost-net \
  --disable-vhost-user --disable-vhost-crypto --disable-vhost-kernel \
  --disable-pie --disable-werror \
  --cross-prefix="$ARCH-"

echo "==> make -j$(nproc) ..."
make -j"$(nproc)"

echo "==> 收集产物 ..."
cp build/x86_64-softmmu/qemu-system-x86_64 "$OUT/qemu-system-x86_64"
cp build/qemu-img "$OUT/qemu-img"
"$STRIP" "$OUT/qemu-system-x86_64" "$OUT/qemu-img"
chmod +x "$OUT/qemu-system-x86_64" "$OUT/qemu-img"

echo "==> 完成。产物："
ls -lh "$OUT"
echo ""
echo "下一步：把这两个文件拷入 mod 资源目录，或推到 CI 由 android-build.yml 自动打包"
echo "  拷贝到本仓库:  mkdir -p src/main/resources/assets/qemu && cp $OUT/* src/main/resources/assets/qemu/ && chmod +x src/main/resources/assets/qemu/*"
