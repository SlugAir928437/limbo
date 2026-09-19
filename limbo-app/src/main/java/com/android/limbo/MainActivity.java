package com.android.limbo;

import android.os.Bundle;

import com.android.limbo.log.Logger;
import com.android.limbo.main.Config;
import com.android.limbo.main.LimboActivity;
import com.android.limbo.main.LimboApplication;

public class MainActivity extends LimboActivity {
    @Override
    public void onCreate(Bundle bundle) {
        // 1) 探测并拿到真实可用架构
        Config.Arch arch = checkQEMULib();
        LimboApplication.arch = arch;
        Config.clientClass = this.getClass();

        // 2) 公共部分
        Config.enableMTTCG = LimboApplication.isHost64Bit() && Config.enableMTTCG;

        // 3) 按探测出的架构做差异化配置
        applyArchConfig(arch);

        super.onCreate(bundle);
        // TODO: 日志/目录改到用户可访问位置
    }

    private void applyArchConfig(Config.Arch arch) {
        switch (arch) {
            case arm64:
                Config.enableKVM = true;
                Config.enableEmulatedFloppy = false;
                Config.enableEmulatedSDCard = true;
                Config.machineFolder = Config.machineFolder + "other/arm_machines/";
                Logger.setupLogFile("/limbo/limbo-arm-log.txt");
                break;

            case ia64:
                Config.enableKVM = false;
                Config.enableEmulatedFloppy = false;
                Config.enableEmulatedSDCard = true;
                Config.machineFolder = Config.machineFolder + "other/ia64_machines/";
                Logger.setupLogFile("/limbo/limbo-ia64-log.txt");
                break;

            case x86_64:
            default:
                Config.enableKVM = true;
                // 注意：x86 版本不改 machineFolder、不设 floppy/sdcard
                Logger.setupLogFile("/limbo/limbo-x86-log.txt");
                break;
        }
    }

    /** 探测成功后加载的库名，供后续使用 */
    private static String loadedLibName = null;

    /**
     * 探测并加载本机可用的 QEMU 库，返回对应架构。
     * 注意：一旦成功 loadLibrary，JNI 层就常驻了，不能"卸载再换一个"。
     */
    public static Config.Arch checkQEMULib() throws RuntimeException {
        // 库名 -> 架构，顺序即优先级
        String[][] candidates = {
                {"qemu-system-x86_64",  "x86_64"},
                {"qemu-system-i386",    "x86"},
                {"qemu-system-arm",     "arm"},
                {"qemu-system-aarch64", "arm64"},
                {"qemu-system-ia64",    "ia64"},
                {"qemu-system-ia64w",   "ia64w"},
        };

        for (String[] c : candidates) {
            try {
                System.loadLibrary(c[0]);
                loadedLibName = c[0];
                return Config.Arch.valueOf(c[1]);
            } catch (UnsatisfiedLinkError e) {
                // 该库没编进来 / ABI 不匹配，继续试下一个
            }
        }
        throw new RuntimeException("Libs has not compiled into app");
    }

    public static String getLoadedLibName() {
        return loadedLibName;
    }
}
