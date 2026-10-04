package com.limbo.emu;

import android.os.Bundle;

import com.limbo.emu.main.ArchManager;
import com.limbo.emu.main.Config;
import com.limbo.emu.main.LimboActivity;
import com.limbo.emu.main.LimboApplication;

public class MainActivity extends LimboActivity {
    @Override
    public void onCreate(Bundle bundle) {
        // 1) 取用已保存（或自动探测）的模拟架构
        Config.Arch arch = LimboApplication.arch;
        if (arch == null)
            arch = ArchManager.resolveStartupArch(this);
        if (arch == null)
            throw new RuntimeException("Libs has not compiled into app");
        LimboApplication.arch = arch;

        // 2) 加载该架构对应的 QEMU 引擎（JNI 层常驻，切换架构必须重启进程）
        loadQEMULibrary(arch);

        Config.clientClass = this.getClass();

        // 3) 公共部分
        Config.enableMTTCG = LimboApplication.isHost64Bit() && Config.enableMTTCG;

        // 4) 按架构做差异化配置（机器目录 / 加速器 / 日志）
        ArchManager.applyArchConfig(arch);

        super.onCreate(bundle);
        // TODO: 日志/目录改到用户可访问位置
    }

    /** 探测成功后加载的库名，供后续使用 */
    private static String loadedLibName = null;

    /**
     * 加载架构对应的 QEMU 共享库。
     * 注意：一旦成功 loadLibrary，JNI 层就常驻了，不能"卸载再换一个"，因此
     * 切换模拟架构时必须重启应用进程（见 {@link ArchManager#restartApp}）。
     */
    private static void loadQEMULibrary(Config.Arch arch) {
        String library = ArchManager.libraryName(arch);
        System.loadLibrary(library);
        loadedLibName = library;
    }

    public static String getLoadedLibName() {
        return loadedLibName;
    }
}
