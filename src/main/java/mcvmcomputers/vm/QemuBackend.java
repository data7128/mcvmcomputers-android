package mcvmcomputers.vm;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * QEMU 后端门面：对 MC 侧（client.utils.QemuVMRunnable / GuiPCEditing 等）暴露
 * 与 VirtualBox 后端等价的语义：启动/停止虚拟机、喂入输入、取一帧 PNG。
 *
 * 线程模型：
 *  - MC 渲染线程调用 tick() 发送输入并抓帧（与 VMRunnable 的循环节奏一致）
 *  - QEMU 子进程与 VNC 连接失败时自动重连
 */
public final class QemuBackend {

    private static QemuBackend instance;

    public static QemuBackend get() {
        if (instance == null) {
            instance = new QemuBackend();
        }
        return instance;
    }

    public static boolean isAndroidEnv() {
        String arch = System.getProperty("os.arch", "");
        String name = System.getProperty("os.name", "");
        return arch.toLowerCase().contains("aarch64") || arch.toLowerCase().contains("arm")
                || name.toLowerCase().contains("android");
    }

    // ---- 二进制与目录 ----
    private File gameDir;
    private File binDir;
    private File qemuBinary;
    private File qemuImgBinary;
    private boolean available;

    // ---- 运行态 ----
    private final QemuProcess proc = new QemuProcess(null, null);
    private volatile boolean running;
    private VncClient vnc;
    private volatile boolean connected;

    // ---- 画面 ----
    private volatile byte[] latestPng;
    private volatile int frameWidth;
    private volatile int frameHeight;

    // ---- 输入 ----
    private int cursorX;
    private int cursorY;
    private boolean frameReceived;

    public File getQemuImgBinary() { return qemuImgBinary; }

    public boolean isAvailable() { return available; }

    /**
     * 初始化：确保 qemu-system-x86_64 与 qemu-img 静态二进制存在于
     * <gameDir>/vm_computers/bin/ 下（缺失则从 jar 资源解压）。
     */
    public void init(File gameDir) {
        this.gameDir = gameDir;
        this.binDir = new File(gameDir, "vm_computers/bin");
        if (!binDir.exists() && !binDir.mkdirs()) {
            System.err.println("[vmcomputers] cannot create bin dir " + binDir);
            return;
        }
        qemuBinary = new File(binDir, "qemu-system-x86_64");
        qemuImgBinary = new File(binDir, "qemu-img");
        if (!qemuBinary.exists()) {
            AndroidBinLoader.extractAndChmod("assets/qemu/qemu-system-x86_64", qemuBinary);
        }
        if (!qemuImgBinary.exists()) {
            AndroidBinLoader.extractAndChmod("assets/qemu/qemu-img", qemuImgBinary);
        }
        if (!qemuBinary.canExecute() || !qemuImgBinary.canExecute()) {
            System.err.println("[vmcomputers] qemu binaries not executable: "
                    + qemuBinary.canExecute() + " " + qemuImgBinary.canExecute());
            return;
        }
        available = true;
    }

    public void startVm(int ramMb, int cpuCount, int vramMb, boolean x64, File disk, File iso) throws IOException {
        stopVm();
        if (!available) {
            throw new IOException("QEMU backend not initialized");
        }
        proc.setBinary(qemuBinary);
        proc.start(ramMb, Math.max(1, cpuCount), vramMb, x64, disk, iso, 0);
        running = true;
        connected = false;
        frameReceived = false;
        cursorX = 0;
        cursorY = 0;
    }

    public void stopVm() {
        running = false;
        closeVnc();
        proc.stop();
        synchronized (this) {
            latestPng = null;
        }
    }

    public boolean isRunning() {
        return running && proc.isRunning();
    }

    public boolean isConnected() {
        return connected && vnc != null && vnc.isConnected();
    }

    /**
     * 主循环单步：发送鼠标/键盘输入，然后请求并读取一帧画面。
     *
     * @param deltaX      鼠标相对位移 X
     * @param deltaY      鼠标相对位移 Y
     * @param scroll      滚轮增量
     * @param mouseMask   模组按键掩码：1=左 2=右 4=中
     * @param scancodes   本轮键盘扫描码流（KeyConverter 产物）
     * @param releaseAll  释放全部修饰键（unfocus 时）
     */
    public void tick(double deltaX, double deltaY, int scroll, int mouseMask,
                     List<Integer> scancodes, boolean releaseAll) {
        if (!running) {
            return;
        }
        ensureConnected();
        if (!isConnected()) {
            return;
        }
        try {
            // 鼠标（相对 -> 绝对，VNC 要求绝对坐标）
            cursorX = clamp(cursorX + (int) deltaX, 0, vnc.getWidth());
            cursorY = clamp(cursorY + (int) deltaY, 0, vnc.getHeight());
            int vncButtons = toVncButtons(mouseMask);
            vnc.sendPointerEvent(cursorX, cursorY, vncButtons);

            // 滚轮
            if (scroll != 0) {
                int wheelBtn = scroll > 0 ? 5 : 4;
                vnc.sendPointerEvent(cursorX, cursorY, vncButtons | wheelBtn);
                vnc.sendPointerEvent(cursorX, cursorY, vncButtons);
            }

            // 键盘
            List<Integer> keys = new ArrayList<>();
            if (releaseAll) {
                keys.add(0x9d);
                keys.add(0xe0);
                keys.add(0x9d);
                keys.add(0x8e);
            } else if (scancodes != null) {
                keys.addAll(scancodes);
            }
            X11Keysym.parse(keys, (ks, down) -> {
                try {
                    vnc.sendKeyEvent(ks, down);
                } catch (IOException ignored) {
                }
            });

            // 抓帧：首帧请求全量，之后请求增量；每帧都尝试读取一帧
            vnc.requestFramebufferUpdate(frameReceived);
            if (vnc.readFramebufferUpdate()) {
                frameReceived = true;
                byte[] png = PngEncoder.encodeRGB(vnc.getRgbBuffer(), vnc.getWidth(), vnc.getHeight());
                synchronized (this) {
                    latestPng = png;
                    frameWidth = vnc.getWidth();
                    frameHeight = vnc.getHeight();
                }
            }
        } catch (IOException e) {
            // 连接断：下次 tick 重连
            closeVnc();
        }
    }

    /** 取最新一帧 PNG（null 表示尚无画面）。 */
    public byte[] takeFrame() {
        synchronized (this) {
            return latestPng;
        }
    }

    public int getFrameWidth() {
        return frameWidth;
    }

    public int getFrameHeight() {
        return frameHeight;
    }

    // ---- private ----

    private void ensureConnected() {
        if (connected && vnc != null && vnc.isConnected()) {
            return;
        }
        closeVnc();
        VncClient c = new VncClient();
        try {
            // 等待 QEMU VNC 端口就绪（最长 ~8s）
            long deadline = System.currentTimeMillis() + 8000;
            while (System.currentTimeMillis() < deadline) {
                try {
                    c.connect("127.0.0.1", 0);
                    break;
                } catch (IOException e) {
                    Thread.sleep(200);
                }
            }
            if (c.isConnected()) {
                vnc = c;
                connected = true;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            c.close();
        }
    }

    private void closeVnc() {
        if (vnc != null) {
            vnc.close();
            vnc = null;
        }
        connected = false;
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : Math.min(v, hi);
    }

    private static int toVncButtons(int mcMask) {
        int b = 0;
        if ((mcMask & 0x01) != 0) b |= 1;  // left
        if ((mcMask & 0x02) != 0) b |= 4;  // right -> VNC 4
        if ((mcMask & 0x04) != 0) b |= 2;  // middle -> VNC 2
        return b;
    }
}
