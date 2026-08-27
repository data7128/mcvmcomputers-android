# MCVmComputers → Android ARM64 (QEMU) 移植说明

> 目标：让原 VirtualBox 版的 VM Computers mod 在手机端 FCL（FoldCraftLauncher，
> PojavLauncher 分支，Android ARM64）上运行——用 QEMU 软件模拟替代 VirtualBox。

---

## 0. 结论先行（重要）

- **原版完全不支持 Android/ARM64**：它通过 `lib/vboxjws.jar` 以 XML-RPC 控制
  宿主机上的 `vboxwebsrv`，而 VirtualBox 没有 Android ARM64 版本。
- 本仓库新增 **QEMU 后端**，与原有 VirtualBox 后端**共存、按环境自动切换**：
  - 桌面 x86 环境：仍走 VirtualBox（原逻辑原封不动）
  - Android/ARM（FCL）：自动走 QEMU（新增逻辑）
  - 桌面调试：加 `-Dmcvmcomputers.forceqemu=true` 强制走 QEMU
- **渲染链路完全复用**：原版 `VMRunnable` 产出一帧 **PNG 字节**（`vmTextureBytes`），
  渲染层 `generatePCScreen()` → `NativeImage` → 动态纹理 → 屏幕实体。QEMU 后端
  同样产出 PNG 字节，渲染层零改动。

---

## 1. 架构

```
┌─────────────────────────── Minecraft 侧（原版，未动）──────────────────────────┐
│ 物品/实体/平板/网络/装配GUI    渲染: vmTextureBytes(PNG) → NativeImage → 屏幕纹理   │
└───────────────┬───────────────────────────────────────────────┬──────────────┘
                │ 启动/停止/硬盘/ISO/输入                      │ PNG 帧
┌───────────────▼───────────────────────────────────────────────▼──────────────┐
│                          QemuBackend（新，mcvmcomputers.vm）                  │
│  QemuProcess: qemu-system-x86_64 子进程 (-vnc 127.0.0.1:0, -display none)     │
│  VncClient:   RFB 3.3 协议，抓帧 + 键鼠注入（纯 Java，无 java.desktop 依赖）    │
│  PngEncoder:  自写 PNG 编码器（java.util.zip，Android 无 ImageIO）             │
│  X11Keysym:   PS/2 扫描码流 → VNC X11 keysym 事件                              │
│  AndroidBinLoader: 解压 jar 内静态 qemu 二进制 → 私有目录 + chmod 755          │
└──────────────────────────────────────────────────────────────────────────────┘
```

### 后端切换开关
- `ClientMod.useQemuBackend`：静态字段，运行时决定走哪个后端。
- 在 `GameloopMixin.run`（进游戏时）自动判定：
  `QemuBackend.isAndroidEnv() || Boolean.getBoolean("mcvmcomputers.forceqemu")`
  → 置 `useQemuBackend = true` 并 `QemuBackend.get().init(gameDir)`。

---

## 2. 改动文件清单

### 新增（纯 Java，不依赖 MC，可独立编译验证）
| 文件 | 说明 |
|---|---|
| `src/main/java/mcvmcomputers/vm/QemuBackend.java` | 后端门面：生命周期/输入/抓帧/PNG 产出 |
| `src/main/java/mcvmcomputers/vm/QemuProcess.java` | qemu-system-x86_64 子进程封装 |
| `src/main/java/mcvmcomputers/vm/VncClient.java` | RFB 3.3 客户端（握手/像素格式/Raw 解码/键鼠注入） |
| `src/main/java/mcvmcomputers/vm/PngEncoder.java` | 自写 PNG 编码器（Android 无 ImageIO） |
| `src/main/java/mcvmcomputers/vm/X11Keysym.java` | PS/2 扫描码流 → X11 keysym 事件 |
| `src/main/java/mcvmcomputers/vm/AndroidBinLoader.java` | jar 资源二进制解压 + chmod |

### 新增（MC 侧）
| 文件 | 说明 |
|---|---|
| `src/main/java/mcvmcomputers/client/utils/QemuVMRunnable.java` | QEMU 版更新循环（等价 VMRunnable） |
| `src/main/resources/assets/qemu/README.md` | qemu 二进制占位说明（CI 会覆盖为真实产物） |

### 修改（MC 侧，均为"加分支、不动原逻辑"）
| 文件 | 改动 |
|---|---|
| `client/ClientMod.java` | 新增 `useQemuBackend` 静态开关 |
| `mixins/GameloopMixin.java` | 进游戏判定后端并 init；更新线程按后端选择；退出/崩溃清理按后端分流 |
| `client/gui/setup/pages/SetupPageMaxValues.java` | confirmButton 增加 QEMU 分支（跳过 vboxwebsrv，初始化后端并写 setup.json） |
| `client/gui/GuiPCEditing.java` | turnOn/turnOff/insertISO/removeISO 增加 QEMU 分支 |
| `client/gui/GuiCreateHarddrive.java` | createNew 增加 QEMU 分支（qemu-img 创建 qcow2） |
| `lang/en_us.json`、`lang/zh_cn.json` | 新增 `iso_restart_needed` 文案 |

### 构建/CI
| 文件 | 说明 |
|---|---|
| `.github/workflows/android-build.yml` | NDK 交叉编译 QEMU(arm64-android 静态) → 打包进 mod → 构建 jar → Release |
| `build.gradle` | 无需改动（`src/main/resources` 默认进 jar） |

---

## 3. 关键实现细节

### 3.1 为什么画面用 VNC + 自写 PNG 编码器
- QEMU 用 `-vnc 127.0.0.1:0 -display none -net none` 在本地 5900 端口出画面；
- mod 内部 VNC 客户端请求 Raw 编码帧（强制 32bpp truecolor，内存序 B,G,R,X），
  解码成 RGB 紧凑缓冲；
- **Android 的 ART 运行时没有 `java.desktop`，`javax.imageio.ImageIO` 不可用**，
  因此自写 PNG 编码器（`java.util.zip.Deflater` + `CRC32`），产出 PNG 字节，
  与 `generatePCScreen()` 的 `NativeImage.read(ByteArrayInputStream)` 无缝衔接。

### 3.2 为什么必须解压二进制 + chmod
- Android 不允许直接执行 jar 内文件，也不允许执行公共存储里的可执行文件；
- FCL 的游戏目录（`minecraft.runDirectory`）位于应用私有存储，
  `AndroidBinLoader` 把 `assets/qemu/qemu-system-x86_64`、`qemu-img`
  解压到 `<gameDir>/vm_computers/bin/` 并 `chmod 755`。

### 3.3 输入链路
- 原版 `MouseMixin/KeyboardMixin` 已经把鼠标位移、按键掩码、PS/2 扫描码
  收集到 `ClientMod` 静态字段（与 VirtualBox 后端共用）；
- `QemuVMRunnable` 每帧读取 → `QemuBackend.tick()`：
  - 鼠标：相对位移累加为绝对坐标 → VNC PointerEvent
  - 滚轮：VNC 按钮 4/5 点击
  - 键盘：`X11Keysym.parse()` 把扫描码流转成 X11 keysym 事件对 → VNC KeyEvent

### 3.4 ISO 行为差异
- VirtualBox 可在运行中热挂载 ISO；QEMU 本实现里 ISO 在 `startVm` 时经
  `-cdrom` 挂载，运行中改 ISO 需重启 VM（已加 `iso_restart_needed` 提示）。

---

## 4. 构建

### 4.1 本地构建 mod jar（不含 QEMU 二进制，需先手动放入资源）
```bash
mkdir -p src/main/resources/assets/qemu
# 把交叉编译好的两个二进制拷入：
cp <qemu>/build/x86_64-softmmu/qemu-system-x86_64 src/main/resources/assets/qemu/
cp <qemu>/build/qemu-img                        src/main/resources/assets/qemu/
chmod +x src/main/resources/assets/qemu/*
./gradlew build
# 产物：build/libs/mcvmcomputers-<version>.jar
```

### 4.2 完整 CI 构建（推荐）
推到 `android-qemu-arm64` 分支，或手动触发
`.github/workflows/android-build.yml`，会自动：
1. 用 Android NDK 交叉编译 QEMU 8.2（aarch64-android24 静态、仅 x86_64-softmmu、仅 VNC）
2. 拷入资源 → `./gradlew build`
3. 上传 mod jar；打 tag 时自动创建 GitHub Release

### 4.3 QEMU 交叉编译要点
```bash
export NDK=/path/to/android-ndk-r26
export CC=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android24-clang
export CXX=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android24-clang++
./configure \
  --target-list=x86_64-softmmu --static \
  --disable-sdl --disable-gtk --disable-curses --enable-vnc \
  --disable-tools --disable-docs --disable-gnutls \
  --disable-nettle --disable-vde --disable-netmap \
  --disable-linux-aio --disable-cap-ng --disable-vhost-net \
  --disable-vhost-user --disable-vhost-crypto --disable-vhost-kernel \
  --disable-pie --disable-werror \
  --cross-prefix=aarch64-linux-android-
make -j$(nproc)
```
> 静态链接 + 只保留 VNC，去掉图形库，二进制体积可控（约 20~40MB）。

---

## 5. FCL 部署步骤（手机端）

1. 从 GitHub Release 下载 `mcvmcomputers-android-arm64.jar`
2. 手机文件管理器，把 jar 放入 FCL 对应 1.16.5 Fabric 实例的 `mods/` 文件夹
   （FCL 各实例 mods 路径：`Android/data/com.tungsten.fclauncher/files/...` 或
   你设置的版本隔离目录，以 FCL 内实际路径为准）
3. 启动 FCL → 进入游戏（需要 Fabric API 0.42.0+1.16）
4. 首次进入自动弹设置向导：QEMU 分支无需填 VirtualBox 目录，直接走
   MaxValues → 确认（此时会自动把 qemu 二进制解压到游戏目录并 chmod）
5. 正常玩法：订购零件 → 装配电脑 → 硬盘/ISO → 开机
6. 开机后屏幕方块显示 VM 画面，键鼠交互自动转发进虚拟机

> 若 QEMU 二进制解压失败，检查 `<gameDir>/vm_computers/bin/` 是否存在且可执行，
> 日志会打印 `[vmcomputers]` 前缀错误。

---

## 6. 已知限制与后续优化

1. **性能**：Android 无 KVM，QEMU 纯 TCG 软件模拟 x86_64。建议跑
   Windows 95/98/XP（32 位）或 Tiny Core Linux 等轻系统；Win7+ 基本不可用。
2. **分辨率/带宽**：目前只实现 VNC Raw 编码，帧率高会吃带宽/CPU。
   后续可加 ZRLE/Tight 编码 + 增量刷新降带宽。
3. **热插拔**：ISO 不支持运行中热插拔（需重启 VM）；QMP 可后续补齐
   `blockdev-change-medium` 实现热插拔。
4. **多核**：QEMU TCG 多核=多线程翻译，Android 上收益有限且更耗电，
   已限制 `smp` 按 CPU 除以板卡核数。
5. **快照/网络**：未实现 QEMU 快照；默认 `-net none`（省去网卡 ROM 依赖，交互全走 VNC），
   如需 VM 内联网可后续加 `-netdev user`。
6. **vboxjws 依赖**：PC 端仍依赖 `lib/vboxjws.jar`（VirtualBox 后端）。
   若未来要彻底去 VB，可加编译开关剔除。
