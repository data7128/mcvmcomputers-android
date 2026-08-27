package mcvmcomputers.vm;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * 极简 PNG 编码器（RGB / RGBA -> PNG）。
 *
 * 为什么不用 javax.imageio.ImageIO？
 * Android（FCL/PojavLauncher 的 ART 运行环境）不包含 java.desktop 模块，
 * ImageIO 在手机上不可用。这里仅依赖 java.util.zip + CRC32，Android 与 PC 通用。
 */
public final class PngEncoder {

    private PngEncoder() {}

    /** 输入 RGB 字节（每像素3字节，紧凑排列，无行对齐）。 */
    public static byte[] encodeRGB(byte[] rgb, int width, int height) throws IOException {
        return encode(rgb, width, height, 3);
    }

    /** 输入 RGBA 字节（每像素4字节）。 */
    public static byte[] encodeRGBA(byte[] rgba, int width, int height) throws IOException {
        return encode(rgba, width, height, 4);
    }

    private static byte[] encode(byte[] pixels, int width, int height, int bpp) throws IOException {
        if (pixels == null || pixels.length < width * height * bpp) {
            throw new IllegalArgumentException("pixel buffer too small");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 + width * height * (bpp + 1));

        // PNG signature
        out.write(new byte[]{(byte)0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A});

        // IHDR
        byte[] ihdr = new byte[13];
        putInt(ihdr, 0, width);
        putInt(ihdr, 4, height);
        ihdr[8] = 8;            // bit depth
        ihdr[9] = (byte)(bpp == 4 ? 6 : 2); // color type: 6=RGBA, 2=RGB
        ihdr[10] = 0;           // compression
        ihdr[11] = 0;           // filter
        ihdr[12] = 0;           // interlace
        writeChunk(out, "IHDR", ihdr);

        // IDAT: 每行前置 1 字节 filter(0=none)，然后 zlib deflate
        byte[] raw = new byte[height * (1 + width * bpp)];
        int idx = 0;
        for (int y = 0; y < height; y++) {
            raw[idx++] = 0; // filter none
            System.arraycopy(pixels, y * width * bpp, raw, idx, width * bpp);
            idx += width * bpp;
        }
        Deflater def = new Deflater(Deflater.BEST_SPEED);
        def.setInput(raw);
        def.finish();
        byte[] buf = new byte[raw.length + 4096];
        int compressedLen = def.deflate(buf);
        def.end();
        byte[] idat = new byte[compressedLen];
        System.arraycopy(buf, 0, idat, 0, compressedLen);
        writeChunk(out, "IDAT", idat);

        // IEND
        writeChunk(out, "IEND", new byte[0]);

        return out.toByteArray();
    }

    private static void writeChunk(ByteArrayOutputStream out, String type, byte[] data) throws IOException {
        int len = data.length;
        out.write((len >>> 24) & 0xFF);
        out.write((len >>> 16) & 0xFF);
        out.write((len >>> 8) & 0xFF);
        out.write(len & 0xFF);
        byte[] typeBytes = type.getBytes("US-ASCII");
        out.write(typeBytes);
        out.write(data);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        long c = crc.getValue();
        out.write((int)((c >>> 24) & 0xFF));
        out.write((int)((c >>> 16) & 0xFF));
        out.write((int)((c >>> 8) & 0xFF));
        out.write((int)(c & 0xFF));
    }

    private static void putInt(byte[] arr, int off, int v) {
        arr[off] = (byte)((v >>> 24) & 0xFF);
        arr[off + 1] = (byte)((v >>> 16) & 0xFF);
        arr[off + 2] = (byte)((v >>> 8) & 0xFF);
        arr[off + 3] = (byte)(v & 0xFF);
    }
}
