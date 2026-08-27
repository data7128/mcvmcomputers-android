# FCL / Android 调试指南（DEBUG.md）

> 面向实机调试：FCL 里 mod 起不来 / 黑屏 / 崩溃时，按这个流程定位。
> 所有关键日志都以 `[vmcomputers]` 前缀输出到 **latest.log**（就是游戏日志）。

---

## 1. 找到 FCL 的游戏日志

FCL（FoldCraftLauncher）基于 PojavLauncher，游戏目录在应用私有存储，但 **latest.log 通常可见**：

| 情况 | 日志路径 |
|---|---|
| FCL 默认 | `Android/data/com.tungsten.fclauncher/files/.minecraft/logs/latest.log` |
| FCL 自定义游戏目录 | `<你的目录>/.minecraft/logs/latest.log` |
| 部分机型文件管理器看不到 Android/data | 用「文件」App 的显示隐藏文件；或用 Termux：`ls /sdcard/Android/data/com.tungsten.fclauncher/files/.minecraft/logs/` |
| root 手机 | `/data/data/com.tungsten.fclauncher/files/.minecraft/logs/latest.log` |

> 如果你设置了 FCL「版本隔离」，路径是 `.../.minecraft/versions/<版本名>/logs/latest.log`。

崩溃时还会生成 `crash-reports/crash-*.txt`，一并收集。

---

## 2. 怎么把日志发出来（脚本）

`scripts/collect_fcl_logs.sh` 一键打包日志（Termux 里跑，或下载到本地跑）：
```bash
bash collect_fcl_logs.sh
# 产出 fcl-debug-logs.zip，把它发给我即可
```

---

## 3. 按症状排查

### 3.1 进游戏后设置向导直接失败 / 卡「startingStatus」
看日志有没有：
- `[vmcomputers] missing bundled binary resource: assets/qemu/qemu-system-x86_64`
  → jar 里没打进 QEMU 二进制。**必须用 CI 构建的 jar**（android-build.yml 会打进），不是本地直接 `./gradlew build` 的裸 jar。
- `[vmcomputers] qemu binaries not executable ...`
  → 解压到 `<gameDir>/vm_computers/bin/` 后没执行权限。确认 FCL 游戏目录在应用私有存储（Android/data 内），不要在 Download 里手动建。

### 3.2 开机后方块黑屏
- 日志里没有 `[vmcomputers] starting QEMU ...` → `turnOnPC` 没走到 QEMU 分支。确认 `useQemuBackend=true`（Android 自动开）。
- 有 `starting QEMU` 但没有 `VNC connected: WxH` → QEMU 进程没起来或 VNC 没监听。看完整日志里 QEMU 自身的报错（启动参数、缺组件）。
- 有 `VNC connected` 但没 `first frame received` → VNC 连上了但一直没帧。可能是分辨率/编码问题，抓完整日志。
- 有 `first frame received` 但屏幕黑 → 渲染链路问题（mod 侧），带上 `latest.log` + 截图。

### 3.3 卡死 / 掉帧 / 极慢
- Android 无 KVM，QEMU 纯软件模拟 x86，性能上限就是低。先跑 `scripts/termux_precheck.sh` 验证你手机能不能跑动 QEMU。
- 内存：FCL 设置里给游戏的 RAM 要 ≥ 1GB 给 mod 用（QEMU 默认 512MB+ 留给 MC）。

### 3.4 开机报 `failed_to_start`
- 日志会带异常栈。常见：`qemu` 二进制缺依赖（应该没有，是静态的）→ 反而更像权限/路径问题。

---

## 4. 强制打开更详细的 QEMU 日志

在 FCL 的 JVM 参数里加（可选）：
```
-Dmcvmcomputers.debug=true
```
当前 mod 版本中，QEMU 子进程的 stdout 会被丢弃（防阻塞）。调试时可临时把
`QemuProcess` 里 `pump` 线程改为打印到 `System.err`，或直接看游戏日志中
`[vmcomputers]` 行。

---

## 5. 收集什么给我

- `latest.log`（最关键的几行：`[vmcomputers]` 开头、`Exception`、`Caused by`）
- `crash-reports/` 里的崩溃报告
- 手机型号 + 安卓版本 + 内存
- 屏幕截图（黑屏/报错画面）
- 你用的 jar 是 CI 产物还是本地构建的
