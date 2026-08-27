#!/data/data/com.termux/files/usr/bin/bash
# =============================================================================
# termux_precheck.sh — 在手机 Termux 里预检「QEMU 能不能跑、VNC 能不能出画面」
#
# 为什么先做这步：Android 手机 + QEMU 软件模拟最大的不确定性是「性能/能否跑」。
# 在动 FCL 和 mod 之前，先在 Termux 里用系统自带 QEMU 验证整条硬件链路，
# 能跑通再上 mod，能省掉大量排障时间。
#
# 用法（Termux 内）：
#   bash termux_precheck.sh
# =============================================================================
set -e

echo "========== 1/6 检查/安装 QEMU =========="
command -v qemu-system-x86_64 >/dev/null 2>&1 || {
  echo "未安装，开始安装（约几十 MB）..."
  pkg update -y
  pkg install -y qemu-system-x86-64-headless python
}
qemu-system-x86_64 --version
qemu-img --version

echo
echo "========== 2/6 创建测试磁盘（qcow2） =========="
WORK="$HOME/vm_precheck"
mkdir -p "$WORK"
qemu-img create -f qcow2 "$WORK/test.qcow2" 512M

echo
echo "========== 3/6 准备启动介质 =========="
ISO="$WORK/tinycore.iso"
if [ ! -f "$ISO" ]; then
  echo "下载 Tiny Core Linux（~25MB，CLI 即可，出帧验证用）..."
  curl -L -o "$ISO" "http://tinycorelinux.net/15.x/x86/release/Core-15.0.iso"
fi
ls -lh "$ISO"

echo
echo "========== 4/6 启动 QEMU（后台，VNC :0） =========="
qemu-system-x86_64 \
  -m 512 -smp 1 \
  -vga std \
  -vnc 127.0.0.1:0,password=off \
  -display none \
  -cdrom "$ISO" \
  -drive file="$WORK/test.qcow2,format=qcow2,if=ide,index=0,media=disk" \
  -no-reboot \
  > "$WORK/qemu.log" 2>&1 &
QPID=$!
echo "QEMU PID=$QPID"

echo
echo "========== 5/6 等 20s，然后验证 VNC 端口 =========="
sleep 20
python - <<'PY'
import socket, sys
try:
    s = socket.create_connection(("127.0.0.1", 5900), timeout=5)
    banner = s.recv(12)
    print("VNC 端口 5900 已监听，协议头:", banner.decode(errors="replace"))
    s.close()
    sys.exit(0)
except Exception as e:
    print("VNC 端口连接失败:", e)
    print("—— 看 qemu.log 判断是否还在启动，或是否 CPU 太慢")
    sys.exit(1)
PY

echo
echo "========== 6/6 性能估算 =========="
TOP=$(top -b -n1 | grep -i qemu | head -2 || true)
echo "QEMU 进程 CPU 占用："
echo "$TOP" || echo "(top 无输出，忽略)"
echo ""
echo "提示："
echo "  - 如果想看画面，另开一个 Termux 会话装 tigervnc：pkg install tigervnc && vncviewer 127.0.0.1:5900"
echo "  - 验证完关掉：kill $QPID"
echo "  - 如果这里跑不动/极卡，说明手机纯软件模拟 x86 太吃力，建议换 Win95/98/XP 或更轻的系统"
