# Limbo Emulator (QEMU for Android)

Limbo 是一款运行在 Android 上的虚拟机模拟器，基于 [QEMU](https://www.qemu.org/) 深度定制，可模拟多种 CPU/系统架构，让手机也能运行完整的 x86、ARM 甚至 IA-64 操作系统（如 Windows IA-64、嵌入式系统等）。

本项目在此前的 Limbo（原作者 limboemu）基础上，扩展了 **IA-64** 架构模拟支持，并通过 GTK 4 图形栈在 Android 上提供接近原生的窗口化渲染体验。

## 特别感谢
引用组件
 * [limboemu/limbo](https://github.com/limboemu/limbo)（原版）
 * [GNOME/gtk](https://github.com/GNOME/gtk)（实现 GTK 显示）
 * [syunnPC/qemu-system-ia64](https://github.com/syunnPC/qemu-system-ia64)（IA-64 架构模拟支持）
 * [AnyLaySys/qemu-gunyah](https://github.com/AnyLaySys/qemu-gunyah)（gunyah 加速来源）
 * [AnyLaySys/qemu-gzvm](https://github.com/AnyLaySys/qemu-gzvm)（gzvm 加速来源）
 * [ruvolof/nc-for-android](https://github.com/ruvolof/nc-for-android)（实现 QEMU Console）
 * [rosuH/AndroidFilePicker](https://github.com/rosuH/AndroidFilePicker)（文件选取器）
 * [getActivity/XXPermissions](https://github.com/getActivity/XXPermissions)（权限请求器）

仓库维护者
 * [FreshingAir](https://github.com/FreshingAir)
 * <img width="32" height="32" alt="深度求索" src=".github/deepseek-color.svg" />

## License

本项目沿用 Limbo/QEMU 的相关开源 License，见 [LICENSE](LICENSE) 与 [NOTICE](NOTICE)。