# Limbo FA v1.0.0 更新日志（相对原版 limboemu/limbo）

## 一、新增功能

### 1. 架构与加速

- 新增 **IA-64** 架构模拟，接入 IA-64 QEMU fork，支持 `itanium-vpc`、`itanium2-vpc`、`ia64-vpc` 以及 `hp-i2000`、`hp-zx2000`、`hp-zx6000` 等机型。
- 新增 IA-64 专用 CPU 列表、网卡列表、`ia64-firmware.bin` 固件与 HP 工作站机型支持。
- 新增 **应用内架构切换**：一个 APK 打包多套 QEMU 引擎，切换架构后重启加载对应引擎；虚拟机数据库与状态目录按架构隔离，避免跨架构串机。
- 新增 **KVM root 启动**：通过 root 子进程加载引擎，补充 KVM 启动失败原因与退出状态回传。
- 新增 **Gunyah / GZVM** 加速支持（ARM64 宿主），配套 AArch64 专用 EDK2 固件。
- 新增 **AGL（Android Graphics Layer）** 显示后端，用于 root 加速 VM 的 Surface 直出。

### 2. 显示与图形

- 新增 **GTK4 Android 显示后端**：交叉编译 GTK4/GLib/Pango 栈，提供窗口化、全屏与等比缩放显示（Android 12/API 31 及以上可用）。
- 新增 **SPICE 显示输出**：集成 spice-server、spice-protocol、openssl、libjpeg 交叉编译链，支持 `-spice`、QXL 显卡、`gl=on`、egl-headless，以及外部连接与密码设置（默认端口 5930）。
- 新增 **VirGL / VirGL Venus**：支持 `virtio-gpu-gl-pci`，Venus 在 Android 上使用 `AHardwareBuffer` 承载 blob。
- 新增 **ANGLE GLES 渲染后端**，可在设置中切换系统 GLES / ANGLE；ANGLE 不可用时回退系统 GLES。
- 新增 **多显卡画面切换**：同时配置 `ramfb` 与 `virtio-gpu` 时，可在 SDL/GTK/AGL 菜单中切换当前输出画面。
- 新增显示缩放模式：**拉伸全屏 / 等比缩放 / 原始 1:1**，SDL 与 GTK 均支持。
- 新增 **可变刷新率（VRR）**：关闭、跟随屏幕、固定 60/90/120 Hz。
- 新增 **virtio-multitouch-pci** 多点触控与 `virtio-tablet-pci`、`virtio-keyboard-pci` 输入设备。
- 修复 SDL GL 配置为 8/8/8，避免 ANGLE 选中 RGB565 产生色带。

### 3. 固件与启动

- 新增 **EDK2 UEFI 固件**打包与选择：`edk2-i386-code.fd`、`edk2-x86_64-code.fd`、`edk2-arm-code.fd`、`edk2-aarch64-code.fd` 及 Gunyah/GZVM 专用 AArch64 固件。
- 新增 **BIOS 固件下拉选择**，`-bios` 参数扩展到 ARM 与 IA-64；固件按 APK 实际打包结果识别，不再依赖构建开关。
- 新增 IA-64 **NVRAM 自动管理**。
- APK 升级后强制刷新 `assets/roms`，避免旧固件残留。
- 新增 QEMU 命令行测试入口与原生 SIGABRT 崩溃回溯，便于定位启动问题。

### 4. 存储与外设

- 新增 **USB 控制器选择**：`qemu-xhci`、`nec-usb-xhci`、`ich9-usb-ehci1`、`piix3-usb-uhci`，IA-64 额外提供 `usb-kbd`。
- 新增独立 **存储设备编辑页**，统一使用 `-drive` 参数，支持每个磁盘的接口、镜像格式、缓存模式与镜像创建。
- 修复磁盘配置未持久化问题，磁盘设置可随虚拟机保存。
- 新增独立 **filepicker** 文件选择模块，并保留 `LimboFileManager` 兼容入口。

### 5. 界面与交互

- 主界面迁移到 **Jetpack Compose + Miuix（HyperOS/MIUI 设计体系）**：`LazyColumn` 卡片、分区折叠、下拉选择、状态指示与存储设备摘要。
- 新增 **ncat 控制台**（基于 nc-for-android），可通过 TCP 端口连接串口、QEMU monitor、并口，并支持分屏显示。
- 新增崩溃日志界面（`CrashApplication` / `CrashActivity`）。
- 新增基于 GitHub Releases API 的版本检查与更新提示。
- 增加中文界面与更新日志查看（Markwon 渲染 `Changelog.md`）。

### 6. 应用内部架构

- 新增 **AIDL/Binder 执行体**：`IVmExecutorService` / `IVmExecutorCallback`，App 与 JNI 执行逻辑通过 Binder 解耦，并保留独立 `:vm` 进程开关。
- 新增 `BuildFeatures` 构建期开关，按 APK 实际包含的 SDL/GTK/VirGL/Venus/SPICE/EDK2 能力动态显示选项。
- 新增 VNC、SPICE、QMP 的外部连接与密码设置项。
- 新增磁盘缓存、渲染后端、全屏、键位映射布局等设置项。

---

## 二、改进

- QEMU 从 5.1.0/2.9.1 升级到 11.0.0，并合入 Limbo 显示、音频、网络、IA-64 等补丁。
- 网络栈更新为独立 libslirp 交叉编译集成。
- 新增 `-bios`、统一 `-drive`、USB 控制器、NVRAM 等参数生成逻辑。
- x86 默认 CPU 调整以兼容 Windows XP/2003 安装，启用 `CMPXCHG16B`。
- x86_64 不再追加 `-tsc`，避免 64 位 Windows 蓝屏；`Disable TSC` 收敛为 32 位 x86 选项。
- 声卡废弃参数修复，AAudio 分支只处理播放。
- 引擎优先使用 APK 内置副本，避免设备上残留旧解压版本。
- 修正 AGL 显示参数顺序与后端检测，避免后端缺失时 QEMU 直接退出。
- GTK/SPICE `gl=on` 补丁与 QEMU 上游版本同步。
- SDL2 下载改用 GitHub Releases 主源，并增加 gzip 完整性校验与重试。
- 补充 AnyLaySys 等组件署名与许可证全文，完善 NOTICE/LICENSES。

---

## 三、修复

- 修复 virgl 初始化崩溃与 KVM vCPU 亲和性失效（大小核宿主）。
- 修复 `gl=es` 下 `pc 0x0` 崩溃、libepoxy 在 ANGLE 不可加载时 abort 进程的问题。
- 修复 GTK 构建中 Pango 枚举标注宏导致的编译失败。
- 修复 libepoxy ANGLE 补丁在 meson setup 之前应用导致失败的问题。
- 修复 IA-64 fork 缺少 `qemu_input_is_multitouch` 导致 GTK 构建失败。
- 修复 VRR 刷新率被钳回 33Hz 的问题。
- 修复 aarch64 使用 TCG 加速时启动崩溃。
- 修复 i386 安装 XP/2003 的 STOP 0x7E，IA-64 安装盘改走 CMD646 IDE。
- 修复 `lsi53c895a` 崩溃、Android `openpty/closefd` 兼容问题。
- 修复 NDK 下 `copy_file_range` 静态声明冲突。
- 修复分辨率/屏幕尺寸变化后 SDL/GTK 缩放模式失效。
- 修复 SDL letterbox 缩放下 `nativeMouseBounds` 坐标不一致。
- 修复打开磁盘后配置未持久化、存储设备参数不统一的问题。
- 修复 APK 升级后旧 `assets/roms` 固件残留。
- 修复注释中的 `egl*`/`gl*` 提前闭合块注释导致 JNI 编译失败。
- 修复 AGL 补丁未被应用、AGL 显示被误拒的问题。
- 修复 EDK2 固件因 `USE_EDK2` 开关被错误过滤。
- 修复 filepicker 的 `R` 包引用问题。
- 修复 CI 缓存 key 含逗号导致 `actions/cache` 校验失败。
- 修复 CI 中不存在的 `replace()` 表达式、过期依赖源与 502 问题。

---

## 四、行为与兼容性变更

- **架构范围调整**：新增 IA-64；当前构建不再提供 PowerPC / SPARC 引擎。
- **宿主 ABI 收敛**：仅打包 `arm64-v8a` 与 `x86_64`，不再提供 32 位宿主 APK。
- **QEMU 跨大版本升级**：原版 QEMU 5.1.0/2.9.1 的保存状态与快照不保证兼容，建议重新创建虚拟机或迁移磁盘镜像。
- **GTK 后端限制**：GTK4 依赖 Android 12/API 31+；低版本设备不显示该选项。
- **IA-64 功能限制**：IA-64 构建跳过 VirGL/virtio-gpu 与 SPICE 栈，不提供 `virtio-gpu-pci`、`virtio-gpu-gl-pci`、`qxl`。
- **单进程单引擎**：同一进程只能加载一套 QEMU 引擎，切换架构需要重启应用。
- **VM 数据隔离**：不同架构使用独立数据库分区与状态目录，原版同名虚拟机不会跨架构复用。

---

> [!NOTE]
> 当前版本还有许多未确定的 Bug ，因为缺少测试，所以建议大家发现有 Bug 的（或者一些建议）可以在 issue 中提出。