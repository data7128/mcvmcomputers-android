package mcvmcomputers.vm;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * 极简 RFB 3.3（VNC）客户端，专门面向 QEMU 的内置 VNC server。
 *
 * 能力范围：
 *  - 握手 + 免密认证（QEMU -vnc ...,password=off）
 *  - 强制 32bpp truecolor 像素格式（R<<16 | G<<8 | B<<0，内存字节序 B,G,R,X）
 *  - 只使用 Raw 编码（编码号 0），QEMU 会按客户端列表只发 Raw
 *  - 读取 FramebufferUpdate，把画面解码进紧凑 RGB 缓冲区
 *  - 发送键盘事件（X11 keysym）与鼠标指针事件（绝对坐标 + 按键位掩码）
 *
 * 注意：Android 的 ART 运行时没有 java.desktop，因此全程不用 AWT/ImageIO。
 */
public final class VncClient implements AutoCloseable {

    private static final String PROTOCOL = "RFB 003.003\n";

    // 编码类型
    private static final int ENC_RAW = 0;

    // 消息类型（client -> server）
    private static final int MSG_SET_PIXEL_FORMAT = 0;
    private static final int MSG_SET_ENCODINGS = 2;
    private static final int MSG_FB_UPDATE_REQUEST = 3;
    private static final int MSG_KEY_EVENT = 4;
    private static final int MSG_POINTER_EVENT = 5;

    private Socket socket;
    private InputStream in;
    private OutputStream out;

    private int width;
    private int height;
    private int bitsPerPixel;
    private boolean bigEndian;
    private int redShift, greenShift, blueShift;

    /** 解码后的紧凑 RGB 缓冲区（width*height*3），每帧覆盖。 */
    private byte[] rgbBuffer;

    /** 帧是否完整更新过。 */
    private boolean hasFrame;

    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public byte[] getRgbBuffer() { return rgbBuffer; }
    public boolean hasFrame() { return hasFrame; }

    public boolean isConnected() {
        return socket != null && socket.isConnected() && !socket.isClosed();
    }

    /** 连接 QEMU VNC。display 编号 0 -> 端口 5900。 */
    public void connectDisplay(String host, int display) throws IOException {
        connect(host, 5900 + display);
    }

    public void connect(String host, int port) throws IOException {
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), 5000);
        socket.setSoTimeout(10000);
        in = new DataInputStream(socket.getInputStream());
        out = new DataOutputStream(socket.getOutputStream());

        // 1) 版本握手
        byte[] serverVersion = new byte[12];
        readFully(in, serverVersion);
        String sv = new String(serverVersion, "US-ASCII");
        if (!sv.startsWith("RFB ")) {
            throw new IOException("Not a VNC server: " + sv);
        }
        out.write(PROTOCOL.getBytes("US-ASCII"));
        out.flush();

        // 2) 认证（RFB 3.3：4 字节：0=失败 1=无认证 2=VNC 认证）
        int security = readIntBE(in);
        if (security == 2) {
            // QEMU password=off 时不会走到这里；若走到则拒绝
            throw new IOException("VNC auth requested but no password support in this client");
        } else if (security != 1) {
            throw new IOException("VNC security handshake failed: " + security);
        }
        // 3) client 回 1 字节 0 表示接受认证结果
        out.write(0);
        out.flush();

        // 4) ServerInit：宽(2) 高(2) pixelformat(16) namelen(4) name
        width = readShortBE(in);
        height = readShortBE(in);
        if (width <= 0 || height <= 0 || width > 8192 || height > 8192) {
            throw new IOException("bad framebuffer size " + width + "x" + height);
        }
        int bpp = in.read();            // bits-per-pixel
        in.read();                      // depth
        int bigEndianFlag = in.read();
        int trueColor = in.read();
        skipFully(in, 2);                   // red max
        skipFully(in, 2);                   // green max
        skipFully(in, 2);                   // blue max
        redShift = in.read();
        greenShift = in.read();
        blueShift = in.read();
        skipFully(in, 3);                   // padding
        long nameLen = readIntBE(in) & 0xFFFFFFFFL;
        if (nameLen > 0 && nameLen < 4096) {
            skipFully(in, (int) nameLen);
        }
        if (trueColor != 1) {
            throw new IOException("server is not truecolor");
        }
        this.bitsPerPixel = bpp;
        this.bigEndian = bigEndianFlag != 0;

        // 5) 强制我们需要的像素格式：32bpp、little-endian、RGB
        sendSetPixelFormat();

        // 6) 只请求 Raw 编码
        sendSetEncodings(new int[]{ENC_RAW});

        rgbBuffer = new byte[width * height * 3];
    }

    private void sendSetPixelFormat() throws IOException {
        byte[] msg = new byte[20];
        msg[0] = MSG_SET_PIXEL_FORMAT;
        // padding 1..3 为 0
        msg[4] = 32;                    // bits-per-pixel
        msg[5] = 24;                    // depth
        msg[6] = 0;                     // big-endian flag = 0 (little)
        msg[7] = 1;                     // true-color
        putShortBE(msg, 8, 255);        // red max
        putShortBE(msg, 10, 255);       // green max
        putShortBE(msg, 12, 255);       // blue max
        msg[14] = 16;                   // red shift
        msg[15] = 8;                    // green shift
        msg[16] = 0;                    // blue shift
        // padding 17..19 = 0
        out.write(msg);
        out.flush();
    }

    private void sendSetEncodings(int[] encodings) throws IOException {
        ByteArrayWriter w = new ByteArrayWriter();
        w.writeByte(MSG_SET_ENCODINGS);
        w.writeByte(0); // padding
        w.writeShortBE(encodings.length);
        for (int e : encodings) {
            w.writeIntBE(e);
        }
        out.write(w.toByteArray());
        out.flush();
    }

    /**
     * 请求一次 framebuffer 更新。
     * @param incremental true=只发变化区域；false=全量（首帧必须为全量）
     */
    public void requestFramebufferUpdate(boolean incremental) throws IOException {
        byte[] msg = new byte[10];
        msg[0] = MSG_FB_UPDATE_REQUEST;
        msg[1] = (byte) (incremental ? 1 : 0);
        putShortBE(msg, 2, 0); // x
        putShortBE(msg, 4, 0); // y
        putShortBE(msg, 6, width);
        putShortBE(msg, 8, height);
        out.write(msg);
        out.flush();
    }

    /**
     * 读取一次 FramebufferUpdate 并解码所有 Raw 矩形到 rgbBuffer。
     * @return 是否有矩形被绘制
     */
    public boolean readFramebufferUpdate() throws IOException {
        int type = in.read();
        if (type != 0) {
            throw new IOException("unexpected server message type " + type);
        }
        in.read(); // padding
        int numRects = readShortBE(in);
        boolean drawn = false;
        for (int i = 0; i < numRects; i++) {
            int x = readShortBE(in);
            int y = readShortBE(in);
            int w = readShortBE(in);
            int h = readShortBE(in);
            int encoding = readIntBE(in);
            if (encoding == ENC_RAW) {
                decodeRaw(x, y, w, h);
                drawn = true;
            } else {
                // 理论上不会发生（我们只请求了 Raw），发生则跳过未知数据
                throw new IOException("unsupported encoding " + encoding);
            }
        }
        return drawn;
    }

    private void decodeRaw(int x, int y, int w, int h) throws IOException {
        if (x < 0 || y < 0 || w <= 0 || h <= 0 || x + w > width || y + h > height) {
            throw new IOException("raw rect out of bounds " + x + "," + y + "," + w + "," + h);
        }
        int bytesPerPixel = bitsPerPixel / 8;
        byte[] row = new byte[w * bytesPerPixel];
        boolean bgr = (blueShift == 0 && redShift == 16 && greenShift == 8);
        boolean rgb = (redShift == 0 && greenShift == 8 && blueShift == 16);
        for (int yy = 0; yy < h; yy++) {
            readFully(in, row);
            int dstRow = (y + yy) * width * 3;
            if (bigEndian) {
                // 大端 32bpp：内存顺序 R,G,B,X 或 B,G,R,X 取决于 shift；按 32 位解析
                for (int xx = 0; xx < w; xx++) {
                    int v = ((row[xx * bytesPerPixel] & 0xFF) << 24)
                          | ((row[xx * bytesPerPixel + 1] & 0xFF) << 16)
                          | ((row[xx * bytesPerPixel + 2] & 0xFF) << 8)
                          | (row[xx * bytesPerPixel + 3] & 0xFF);
                    int r = (v >>> redShift) & 0xFF;
                    int g = (v >>> greenShift) & 0xFF;
                    int b = (v >>> blueShift) & 0xFF;
                    int off = (dstRow + (x + xx) * 3);
                    rgbBuffer[off] = (byte) r;
                    rgbBuffer[off + 1] = (byte) g;
                    rgbBuffer[off + 2] = (byte) b;
                }
            } else if (bytesPerPixel == 4) {
                // 小端 32bpp：内存字节序 [B,G,R,X]（red shift 16）或 [R,G,B,X]（red shift 0）
                for (int xx = 0; xx < w; xx++) {
                    int base = xx * 4;
                    int off = (dstRow + (x + xx) * 3);
                    if (bgr) {
                        rgbBuffer[off] = row[base + 2];
                        rgbBuffer[off + 1] = row[base + 1];
                        rgbBuffer[off + 2] = row[base];
                    } else if (rgb) {
                        rgbBuffer[off] = row[base];
                        rgbBuffer[off + 1] = row[base + 1];
                        rgbBuffer[off + 2] = row[base + 2];
                    } else {
                        int v = (row[base] & 0xFF)
                              | ((row[base + 1] & 0xFF) << 8)
                              | ((row[base + 2] & 0xFF) << 16)
                              | ((row[base + 3] & 0xFF) << 24);
                        rgbBuffer[off] = (byte) ((v >>> redShift) & 0xFF);
                        rgbBuffer[off + 1] = (byte) ((v >>> greenShift) & 0xFF);
                        rgbBuffer[off + 2] = (byte) ((v >>> blueShift) & 0xFF);
                    }
                }
            } else {
                // 16bpp 兜底
                for (int xx = 0; xx < w; xx++) {
                    int v = ((row[xx * 2] & 0xFF) | ((row[xx * 2 + 1] & 0xFF) << 8));
                    int off = (dstRow + (x + xx) * 3);
                    rgbBuffer[off] = (byte) ((v >>> redShift) & 0xFF);
                    rgbBuffer[off + 1] = (byte) ((v >>> greenShift) & 0xFF);
                    rgbBuffer[off + 2] = (byte) ((v >>> blueShift) & 0xFF);
                }
            }
        }
        hasFrame = true;
    }

    /** 发送键盘事件。keysym 为 X11 keysym，down=true 按下。 */
    public void sendKeyEvent(int keysym, boolean down) throws IOException {
        byte[] msg = new byte[8];
        msg[0] = MSG_KEY_EVENT;
        msg[1] = (byte) (down ? 1 : 0);
        putShortBE(msg, 2, 0);
        putIntBE(msg, 4, keysym);
        out.write(msg);
        out.flush();
    }

    /** 发送鼠标指针事件（绝对坐标 + 按键位掩码：1=左 2=中 4=右）。 */
    public void sendPointerEvent(int x, int y, int buttons) throws IOException {
        if (x < 0) x = 0;
        if (y < 0) y = 0;
        if (x > width) x = width;
        if (y > height) y = height;
        byte[] msg = new byte[6];
        msg[0] = MSG_POINTER_EVENT;
        msg[1] = (byte) buttons;
        putShortBE(msg, 2, x);
        putShortBE(msg, 4, y);
        out.write(msg);
        out.flush();
    }

    @Override
    public void close() {
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
        socket = null;
    }

    // ---- helpers ----

    private static void readFully(InputStream in, byte[] b) throws IOException {
        int off = 0;
        while (off < b.length) {
            int n = in.read(b, off, b.length - off);
            if (n < 0) throw new IOException("unexpected EOF");
            off += n;
        }
    }

    private static void skipFully(InputStream in, int n) throws IOException {
        long remaining = n;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped <= 0) {
                if (in.read() == -1) throw new IOException("unexpected EOF");
                remaining--;
            } else {
                remaining -= skipped;
            }
        }
    }

    private static int readShortBE(InputStream in) throws IOException {
        int hi = in.read();
        int lo = in.read();
        if (hi < 0 || lo < 0) throw new IOException("unexpected EOF");
        return (hi << 8) | lo;
    }

    private static int readIntBE(InputStream in) throws IOException {
        int b0 = in.read(), b1 = in.read(), b2 = in.read(), b3 = in.read();
        if ((b0 | b1 | b2 | b3) < 0) throw new IOException("unexpected EOF");
        return (b0 << 24) | (b1 << 16) | (b2 << 8) | b3;
    }

    private static void putShortBE(byte[] arr, int off, int v) {
        arr[off] = (byte) ((v >>> 8) & 0xFF);
        arr[off + 1] = (byte) (v & 0xFF);
    }

    private static void putIntBE(byte[] arr, int off, int v) {
        arr[off] = (byte) ((v >>> 24) & 0xFF);
        arr[off + 1] = (byte) ((v >>> 16) & 0xFF);
        arr[off + 2] = (byte) ((v >>> 8) & 0xFF);
        arr[off + 3] = (byte) (v & 0xFF);
    }

    private static final class ByteArrayWriter {
        private final java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();

        void writeByte(int b) {
            bos.write(b);
        }

        void writeShortBE(int v) {
            bos.write((v >>> 8) & 0xFF);
            bos.write(v & 0xFF);
        }

        void writeIntBE(int v) {
            bos.write((v >>> 24) & 0xFF);
            bos.write((v >>> 16) & 0xFF);
            bos.write((v >>> 8) & 0xFF);
            bos.write(v & 0xFF);
        }

        byte[] toByteArray() {
            return bos.toByteArray();
        }
    }
}
