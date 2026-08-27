# QEMU Android ARM64 二进制（自动构建产物）
此目录在 GitHub Actions 构建时由 `android-build.yml` 自动填充：
- `qemu-system-x86_64` — 静态 aarch64 ELF（TCG 软件模拟 x86_64），Android 可直接运行
- `qemu-img` — 同平台静态二进制（创建 qcow2 虚拟磁盘）
## 手动构建（推荐：Alpine arm64 容器，静态 musl，规避 bionic 兼容问题）
```bash
# 方式一：一键脚本
./scripts/build_qemu_android.sh 8.2.4

# 方式二：直接进容器构建（与 CI 完全一致）
docker run --rm --privileged multiarch/qemu-user-static --reset -p yes
docker run --rm --platform linux/arm64 \
  -v "$PWD":/build -e QEMU_VERSION=8.2.4 \
  alpine:3.20 /bin/sh /build/.github/workflows/qemu-alpine-build.sh
```
容器内实际执行的构建参数（详见 `.github/workflows/qemu-alpine-build.sh`）：
```
./configure --target-list=x86_64-softmmu --static --enable-vnc --enable-tools \
  --disable-sdl --disable-gtk --disable-curses --disable-docs --disable-gnutls ... \
  --disable-pie --disable-werror
```
> 说明：早期方案用 Android NDK 交叉编译 bionic 静态 QEMU，但需要补 glib/pixman 交叉依赖
> 与大量 bionic 兼容补丁（shm_open/timespec_get/setjmp…）。改为静态 musl 构建后这些问题全部规避。
## 验证
在 Termux（aarch64）或已 root 的 Android 终端：
```bash
chmod 755 qemu-system-x86_64 qemu-img
./qemu-system-x86_64 --version
./qemu-img --version
```
> 注意：Android 上运行无 KVM 硬件加速，纯 TCG 软件模拟 x86_64，性能有限。
> 建议目标系统为 Windows 95/98/XP（32 位）或轻量 Linux（如 Tiny Core）。
> 若捆绑二进制在你设备上异常，可安装 Termux 的 `qemu-system-x86-64-headless` 作兜底，
> mod 会自动检测并使用（见 QemuBackend 的 Termux 路径探测）。
