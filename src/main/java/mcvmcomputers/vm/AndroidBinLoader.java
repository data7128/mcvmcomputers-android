package mcvmcomputers.vm;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Android 二进制加载器。
 *
 * 背景：Android 应用（含 FCL/PojavLauncher 的游戏实例）不允许直接执行
 * jar 内的文件，也不能执行公共存储里的可执行文件。必须把打包进 jar 资源的
 * 静态 qemu 二进制解压到应用私有目录，并 chmod 755。
 *
 * 在 FCL 中，"游戏目录"（minecraft.runDirectory）本身就在应用私有存储下，
 * 因此解压到 <gameDir>/vm_computers/bin/ 即可直接执行。
 */
public final class AndroidBinLoader {

    private AndroidBinLoader() {}

    /**
     * 从 classpath 资源解压二进制到目标文件并 chmod 755。
     *
     * @param resourcePath 例如 "assets/qemu/qemu-system-x86_64"
     * @param dest         目标文件（父目录需已存在）
     */
    public static boolean extractAndChmod(String resourcePath, File dest) {
        try (InputStream is = AndroidBinLoader.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (is == null) {
                System.err.println("[vmcomputers] missing bundled binary resource: " + resourcePath);
                return false;
            }
            File tmp = new File(dest.getParentFile(), dest.getName() + ".tmp");
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = is.read(buf)) != -1) {
                    fos.write(buf, 0, n);
                }
                fos.flush();
                // FileOutputStream 在 try-with-resources 中已关闭
            }
            chmod(tmp, "755");
            if (dest.exists()) {
                dest.delete();
            }
            if (!tmp.renameTo(dest)) {
                throw new IOException("rename failed: " + tmp + " -> " + dest);
            }
            return true;
        } catch (IOException e) {
            System.err.println("[vmcomputers] failed to extract " + resourcePath + ": " + e);
            return false;
        }
    }

    private static void chmod(File f, String mode) {
        try {
            Process p = new ProcessBuilder("chmod", mode, f.getAbsolutePath()).start();
            p.waitFor();
        } catch (IOException | InterruptedException e) {
            // 某些沙箱环境没有 chmod；退回到 Java 自身可执行位设置（Android 上通常无效）
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            f.setExecutable(true, false);
        }
    }
}
