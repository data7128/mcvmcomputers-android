#!/bin/sh
# =============================================================================
# 在 Alpine linux/arm64 容器内静态交叉构建 QEMU 8.2.4
# 产物：静态 aarch64 Linux ELF（可直接在 Android/Linux 上运行，无 bionic 兼容问题）
#
# 运行方式（由 CI 调用）：
#   docker run --rm --platform linux/arm64 -v "$PWD":/build \
#     -e QEMU_VERSION=8.2.4 alpine:3.20 /bin/sh /build/.github/workflows/qemu-alpine-build.sh
#
# 依赖：Alpine 3.20 main 自带 glib-static / pixman-static / zlib-static
# =============================================================================
set -e

apk add --no-cache \
  build-base bash pkgconfig python3 ninja bison flex perl gettext git \
  glib-dev glib-static pixman-dev pixman-static zlib-dev zlib-static \
  libffi-dev pcre2-dev util-linux-dev util-linux-static linux-headers

QEMU_VERSION="${QEMU_VERSION:-8.2.4}"
OUT=/build

cd /tmp
echo "==> downloading qemu-${QEMU_VERSION}"
wget -q "https://download.qemu.org/qemu-${QEMU_VERSION}.tar.xz"
tar -xf "qemu-${QEMU_VERSION}.tar.xz"
cd "qemu-${QEMU_VERSION}"

echo "==> configuring"
./configure \
  --target-list=x86_64-softmmu \
  --static \
  --enable-vnc \
  --enable-tools \
  --disable-sdl --disable-gtk --disable-curses \
  --disable-docs --disable-gnutls \
  --disable-nettle --disable-vde --disable-netmap \
  --disable-linux-aio --disable-cap-ng --disable-vhost-net \
  --disable-vhost-user --disable-vhost-crypto --disable-vhost-kernel \
  --disable-pie --disable-werror

echo "==> building"
make -j"$(nproc)"

echo "==> artifacts"
ls -lh build/x86_64-softmmu/qemu-system-x86_64 build/qemu-img
cp -f build/x86_64-softmmu/qemu-system-x86_64 "$OUT/qemu-system-x86_64"
cp -f build/qemu-img "$OUT/qemu-img"
chmod +x "$OUT/qemu-system-x86_64" "$OUT/qemu-img"
echo "==> DONE"
