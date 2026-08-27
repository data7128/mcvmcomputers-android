package mcvmcomputers.vm;

import java.util.List;

/**
 * 把模组现有的 PS/2 扫描码事件流（mcvmcomputers.KeyConverter 生成，
 * 保存在 ClientMod.vmKeyboardScancodes）转换为 VNC 需要的 X11 keysym 事件。
 *
 * 扫描码流格式（来自 KeyConverter.toVBKey）：
 *  - 普通键：单字节 sc
 *  - 扩展键：0xe0 前缀 + 第二字节
 *  - 松开：sc + 0x80（0xe0 保持 0xe0，第二字节 + 0x80）
 */
public final class X11Keysym {

    private X11Keysym() {}

    public interface Sink {
        void onKey(int keysym, boolean down);
    }

    /** 解析扁平扫描码流，逐个回调键事件。 */
    public static void parse(List<Integer> scancodes, Sink sink) {
        int i = 0;
        while (i < scancodes.size()) {
            int sc = scancodes.get(i);
            if (sc == 0xe0 && i + 1 < scancodes.size()) {
                int ext = scancodes.get(i + 1);
                boolean down = ext < 0x80;
                int code = down ? ext : (ext - 0x80);
                int ks = extendedKeysym(code);
                if (ks != 0) {
                    sink.onKey(ks, down);
                }
                i += 2;
            } else {
                boolean down = sc < 0x80;
                int code = down ? sc : (sc - 0x80);
                int ks = baseKeysym(code);
                if (ks != 0) {
                    sink.onKey(ks, down);
                }
                i += 1;
            }
        }
    }

    private static int baseKeysym(int sc) {
        switch (sc) {
            case 0x01: return 0xff1b; // Esc
            case 0x02: return '1';
            case 0x03: return '2';
            case 0x04: return '3';
            case 0x05: return '4';
            case 0x06: return '5';
            case 0x07: return '6';
            case 0x08: return '7';
            case 0x09: return '8';
            case 0x0a: return '9';
            case 0x0b: return '0';
            case 0x0c: return '-';
            case 0x0d: return '=';
            case 0x0e: return 0xff08; // BackSpace
            case 0x0f: return 0xff09; // Tab
            case 0x10: return 'q';
            case 0x11: return 'w';
            case 0x12: return 'e';
            case 0x13: return 'r';
            case 0x14: return 't';
            case 0x15: return 'y';
            case 0x16: return 'u';
            case 0x17: return 'i';
            case 0x18: return 'o';
            case 0x19: return 'p';
            case 0x1a: return '[';
            case 0x1b: return ']';
            case 0x1c: return 0xff0d; // Enter
            case 0x1d: return 0xffe3; // LCtrl
            case 0x1e: return 'a';
            case 0x1f: return 's';
            case 0x20: return 'd';
            case 0x21: return 'f';
            case 0x22: return 'g';
            case 0x23: return 'h';
            case 0x24: return 'j';
            case 0x25: return 'k';
            case 0x26: return 'l';
            case 0x27: return ';';
            case 0x28: return '\'';
            case 0x29: return '`';
            case 0x2a: return 0xffe1; // LShift
            case 0x2b: return '\\';
            case 0x2c: return 'z';
            case 0x2d: return 'x';
            case 0x2e: return 'c';
            case 0x2f: return 'v';
            case 0x30: return 'b';
            case 0x31: return 'n';
            case 0x32: return 'm';
            case 0x33: return ',';
            case 0x34: return '.';
            case 0x35: return '/';
            case 0x36: return 0xffe2; // RShift
            case 0x37: return 0xffaa; // KP Multiply
            case 0x38: return 0xffe9; // LAlt
            case 0x39: return ' ';
            case 0x3a: return 0xffe5; // CapsLock
            case 0x3b: return 0xffbe; // F1
            case 0x3c: return 0xffbf; // F2
            case 0x3d: return 0xffc0; // F3
            case 0x3e: return 0xffc1; // F4
            case 0x3f: return 0xffc2; // F5
            case 0x40: return 0xffc3; // F6
            case 0x41: return 0xffc4; // F7
            case 0x42: return 0xffc5; // F8
            case 0x43: return 0xffc6; // F9
            case 0x44: return 0xffc7; // F10
            case 0x45: return 0xff7f; // NumLock
            case 0x46: return 0xff14; // ScrollLock
            case 0x47: return 0xffb0; // KP 7
            case 0x48: return 0xffb1; // KP 8
            case 0x49: return 0xffb2; // KP 9
            case 0x4a: return 0xffad; // KP -
            case 0x4b: return 0xffb3; // KP 4
            case 0x4c: return 0xffb4; // KP 5
            case 0x4d: return 0xffb5; // KP 6
            case 0x4e: return 0xffab; // KP +
            case 0x4f: return 0xffb6; // KP 1
            case 0x50: return 0xffb7; // KP 2
            case 0x51: return 0xffb8; // KP 3
            case 0x52: return 0xffb9; // KP 0
            case 0x53: return 0xffae; // KP .
            case 0x57: return 0xffc8; // F11
            case 0x58: return 0xffc9; // F12
            default: return 0;
        }
    }

    private static int extendedKeysym(int sc) {
        switch (sc) {
            case 0x50: return 0xff54; // Down
            case 0x48: return 0xff52; // Up
            case 0x4b: return 0xff51; // Left
            case 0x4d: return 0xff53; // Right
            case 0x38: return 0xffea; // RAlt
            case 0x1c: return 0xff8d; // KP Enter
            case 0x1d: return 0xffe4; // RCtrl
            case 0x35: return 0xffaf; // KP /
            case 0x47: return 0xff50; // Home
            case 0x49: return 0xff55; // PgUp
            case 0x4f: return 0xff57; // End
            case 0x51: return 0xff56; // PgDn
            case 0x52: return 0xff63; // Insert
            case 0x53: return 0xffff; // Delete
            case 0x5d: return 0xff67; // Menu
            default: return 0;
        }
    }
}
