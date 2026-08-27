package mcvmcomputers.vm;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * qemu-system-x86_64 子进程封装：启动 / 停止 / 存活检测 / 日志。
 *
 * 关键设计：
 *  - -display none + -vnc 127.0.0.1:0：画面由 mod 内部 VNC 客户端读取
 *  - 纯软件模拟（TCG），Android 上无 KVM 加速，性能有限
 *  - ISO 通过 -cdrom 挂到 IDE 次通道，与硬盘（IDE 主通道）分离
 */
public final class QemuProcess {

    private Process process;
    private File qemuBinary;
    private File workDir;

    public QemuProcess(File qemuBinary, File workDir) {
        this.qemuBinary = qemuBinary;
        this.workDir = workDir;
    }

    public void setBinary(File qemuBinary) {
        this.qemuBinary = qemuBinary;
    }

    public void setWorkDir(File workDir) {
        this.workDir = workDir;
    }

    public boolean isRunning() {
        return process != null && process.isAlive();
    }

    /**
     * 启动虚拟机。
     *
     * @param ramMb        内存 MB
     * @param cpuCount     CPU 核数
     * @param vramMb       显存 MB（QEMU 下映射为 -vga std 显存，主要影响分辨率上限）
     * @param x64          是否 64 位固件（决定 qemu 用 i440fx 默认即可，仅影响机型选择）
     * @param disk         硬盘镜像（qcow2），可为 null
     * @param iso          ISO 光盘，可为 null
     * @param vncDisplay   VNC display 编号（0 -> 端口 5900）
     */
    public void start(int ramMb, int cpuCount, int vramMb, boolean x64,
                      File disk, File iso, int vncDisplay) throws IOException {
        stop();

        List<String> cmd = new ArrayList<>();
        cmd.add(qemuBinary.getAbsolutePath());
        cmd.add("-m"); cmd.add(String.valueOf(ramMb));
        cmd.add("-smp"); cmd.add(String.valueOf(Math.max(1, cpuCount)));
        cmd.add("-vga"); cmd.add("std");
        cmd.add("-vnc"); cmd.add("127.0.0.1:" + vncDisplay + ",password=off");
        cmd.add("-display"); cmd.add("none");
        cmd.add("-rtc"); cmd.add("base=localtime");
        cmd.add("-no-reboot");

        if (disk != null) {
            cmd.add("-drive");
            cmd.add("file=" + disk.getAbsolutePath() + ",format=qcow2,if=ide,index=0,media=disk");
        }
        if (iso != null && iso.exists()) {
            cmd.add("-drive");
            cmd.add("file=" + iso.getAbsolutePath() + ",format=raw,if=ide,index=1,media=cdrom");
        }

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(workDir);
        pb.redirectErrorStream(true);
        // 后台持续读取 QEMU 输出，防止管道阻塞导致 QEMU 卡死
        Process proc = pb.start();
        this.process = proc;
        Thread pump = new Thread(() -> {
            try {
                byte[] buf = new byte[4096];
                int n;
                while ((n = proc.getInputStream().read(buf)) != -1) {
                    // 丢弃输出（或在此接日志钩子）
                }
            } catch (IOException ignored) {
            }
        }, "QEMU stdout pump");
        pump.setDaemon(true);
        pump.start();
    }

    public void stop() {
        if (process != null) {
            process.destroy();
            try {
                if (!process.waitFor(3000, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
            process = null;
        }
    }

    /** 完整命令行（用于日志/调试）。 */
    public String describe() {
        return qemuBinary.getAbsolutePath() + " ...";
    }
}
