# QEMU Android ARM64 二进制（自动构建产物）

此目录在 GitHub Actions 构建时由 `android-build.yml` 自动填充：

- `qemu-system-x86_64` — aarch64-android 静态二进制（TCG 软件模拟 x86_64）
- `qemu-img` — 同平台静态二进制（创建 qcow2 虚拟磁盘）

## 手动构建（本地）

```bash
export NDK=/path/to/android-ndk-r26
export CC=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android24-clang
export CXX=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android24-clang++
./configure \
  --target-list=x86_64-softmmu \
  --static \
  --disable-sdl --disable-gtk --disable-curses \
  --enable-vnc \
  --disable-tools --disable-docs --disable-gnutls \
  --cross-prefix=aarch64-linux-android-
make -j$(nproc)
# 取 build/x86_64-softmmu/qemu-system-x86_64 与 build/qemu-img 放入本目录
```

## 验证

在 Termux（aarch64）或已 root 的 Android 终端：
```bash
chmod 755 qemu-system-x86_64 qemu-img
./qemu-system-x86_64 --version
./qemu-img --version
```

> 注意：Android 上运行无 KVM 硬件加速，纯 TCG 软件模拟 x86_64，性能有限。
> 建议目标系统为 Windows 95/98/XP（32 位）或轻量 Linux（如 Tiny Core）。
