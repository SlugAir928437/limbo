# Limbo Emulator (QEMU for Android)

Limbo 是一款运行在 Android 上的虚拟机模拟器，基于 [QEMU](https://www.qemu.org/) 深度定制，可模拟多种 CPU/系统架构，让手机也能运行完整的 x86、ARM 甚至 IA-64 操作系统（如 Windows IA-64、嵌入式系统等）。

本项目在此前的 Limbo（原作者 limboemu）基础上，扩展了 **IA-64** 架构模拟支持，并通过 GTK 4 图形栈在 Android 上提供接近原生的窗口化渲染体验。

## 虚拟化设备清单

访客侧以 QEMU virtio 设备族作为核心设备，宿主机侧通过 Android 原生后端完成对接。

### 核心设备

| 核心设备 | 实现设备 | 技术职责 |
| --- | --- | --- |
| 存储 | virtio-blk-pci<br>异步 I/O 机制 AIO / IO_Uring<br>独立 I/O 线程 Object IOThread | 负责存储控制 |
| 显示 | virtio-gpu-gl-pci | 负责图形画面输出与 OpenGL/VirGL 图形渲染 |
| 输入 | virtio-tablet-pci<br>virtio-keyboard-pci | 负责基础交互，仿真绝对坐标平板与 PCI 键盘，传递键鼠指令 |
| 音频 | virtio-sound-pci | 负责音频流输入输出，将音频信号桥接至宿主机音频驱动 |
| 网络 | virtio-net-pci | 负责网络连接，将虚拟网卡桥接至宿主机以实现原生网络吞吐 |

### 🚀 高效后端对接

| 核心设备 | 对接实现 | 技术职责 |
| --- | --- | --- |
| 显示 | AGL | 通过 Android 原生窗口与 EGL/OpenGL 呈现 VirGL 画面 |
| 音频 | AAudio | 对接 Android Audio，将音频流直通硬件混音器 |
| 网络 | TAP | 采用 Linux 标准 tap0 虚拟网卡接口，在宿主机创建网络隧道以实现原生网络吞吐 |

> 构建提示：`virtio-gpu-gl-pci` 需要 VirGL 渲染后端，由 `USE_VIRGL=true`（默认）控制，构建时会交叉编译
> `virglrenderer`（EGL 平台）并以 `-Dopengl=enabled -Dvirglrenderer=enabled` 配置 QEMU；设为 `false` 可跳过。
> 其余 virtio 设备（blk/net/input/snd）在 QEMU Kconfig 中均为默认启用，无需额外开关。

## 特别感谢
引用组件
 * [limboemu/limbo](https://github.com/limboemu/limbo)
 * [syunnPC/qemu-system-ia64](https://github.com/syunnPC/qemu-system-ia64)
 * [ruvolof/nc-for-android](https://github.com/ruvolof/nc-for-android)
 * [rosuH/AndroidFilePicker](https://github.com/rosuH/AndroidFilePicker)
 * [getActivity/XXPermissions](https://github.com/getActivity/XXPermissions)

仓库维护者
 * [FreshingAir](https://github.com/FreshingAir)
 * <img width="32" height="32" alt="深度求索" src=".github/deepseek-color.svg" />
 * <img width="32" height="32" alt="商汤日日新" src=".github/sensenova-color.svg" />
 * <img width="32" height="32" alt="豆包" src=".github/doubao-color.svg" />
 * <img width="32" height="32" alt="月之暗面" src=".github/kimi-color.svg" />

## License

本项目沿用 Limbo/QEMU 的相关开源 License，见 [LICENSE](LICENSE) 与 [NOTICE](NOTICE)。