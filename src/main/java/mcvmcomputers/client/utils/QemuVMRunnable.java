package mcvmcomputers.client.utils;

import static mcvmcomputers.client.ClientMod.leftMouseButton;
import static mcvmcomputers.client.ClientMod.middleMouseButton;
import static mcvmcomputers.client.ClientMod.mouseCurX;
import static mcvmcomputers.client.ClientMod.mouseCurY;
import static mcvmcomputers.client.ClientMod.mouseDeltaScroll;
import static mcvmcomputers.client.ClientMod.mouseLastX;
import static mcvmcomputers.client.ClientMod.mouseLastY;
import static mcvmcomputers.client.ClientMod.releaseKeys;
import static mcvmcomputers.client.ClientMod.rightMouseButton;
import static mcvmcomputers.client.ClientMod.vmKeyboardScancodes;
import static mcvmcomputers.client.ClientMod.vmTextureBytes;
import static mcvmcomputers.client.ClientMod.vmTextureBytesSize;

import java.util.ArrayList;
import java.util.List;

import mcvmcomputers.vm.QemuBackend;

/**
 * QEMU 后端的 VM 更新循环，与 VMRunnable（VirtualBox 版）职责等价：
 * 每帧把 ClientMod 收集的鼠标/键盘输入喂给后端，并把后端最新 PNG 帧写回
 * vmTextureBytes，供 generatePCScreen() 与渲染链路使用。
 */
public class QemuVMRunnable implements Runnable {

    @Override
    public void run() {
        while (true) {
            try {
                // 鼠标相对位移
                double deltaX = mouseCurX - mouseLastX;
                double deltaY = mouseCurY - mouseLastY;
                mouseLastX = mouseCurX;
                mouseLastY = mouseCurY;

                // 按键掩码（与 VMRunnable 相同约定：左1 中4 右2）
                int buttons = 0x00;
                if (leftMouseButton) buttons += 0x01;
                if (middleMouseButton) buttons += 0x04;
                if (rightMouseButton) buttons += 0x02;

                // 键盘扫描码
                List<Integer> scancodes;
                boolean rel;
                synchronized (vmKeyboardScancodes) {
                    scancodes = new ArrayList<>(vmKeyboardScancodes);
                    vmKeyboardScancodes.clear();
                    rel = releaseKeys;
                    releaseKeys = false;
                }

                QemuBackend backend = QemuBackend.get();
                backend.tick(deltaX, deltaY, mouseDeltaScroll, buttons, scancodes, rel);

                byte[] png = backend.takeFrame();
                if (png != null) {
                    vmTextureBytes = png;
                    vmTextureBytesSize = png.length;
                }

                // ~30 FPS 上限，避免在手机上空转耗电
                Thread.sleep(33);
            } catch (Exception ex) {
                // 断连/超时等都不致命，下轮重试
            }
        }
    }
}
