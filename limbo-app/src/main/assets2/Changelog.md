### Limbo FA v1.0.0 更新日志（相对原版 limboemu/limbo）

- \[新增\] IA-64 架构模拟
- \[新增\] 应用内架构切换
- \[新增\] KVM root 启动
- \[新增\] Gunyah / GZVM 加速支持（ARM64 宿主）
- \[新增\] AGL 显示后端
- \[新增\] GTK4 Android 显示后端
- \[新增\] SPICE 显示输出
- \[新增\] VirGL / VirGL Venus
- \[新增\] ANGLE GLES 渲染后端
- \[新增\] 多显卡画面切换
- \[新增\] 显示缩放模式
- \[新增\] 可变刷新率（VRR）
- \[新增\] virtio-multitouch-pci
- \[新增\] EDK2 UEFI 固件
- \[新增\] BIOS 固件下拉选择
- \[新增\] IA-64 NVRAM 自动管理
- \[优化\] APK 升级后强制刷新固件目录
- \[新增\] SIGABRT 崩溃回溯
- \[新增\] USB 控制器选择
- \[新增\] 独立存储设备编辑页
- \[优化\] 主界面迁移到 Jetpack Compose + Miuix
- \[新增\] ncat 控制台
- \[调整\] 架构范围调整：IA-64，当前构建不再提供 PowerPC / SPARC 引擎。
- \[调整\] 宿主 ABI 收敛：仅打包 `arm64-v8a` 与 `x86_64`，不再提供 32 位宿主 APK。
- \[调整\] QEMU 版本升级至 11.0

> [!IMPORTANT]
> GTK4 依赖 Android 12/API 31+，低版本设备不显示该选项。
> 
> IA-64 功能限制：IA-64 构建跳过 VirGL/virtio-gpu 与 SPICE 栈，不提供 `virtio-gpu-pci`、`virtio-gpu-gl-pci`、`qxl`。
> 
> 单进程单引擎：同一进程只能加载一套 QEMU 引擎，切换架构需要重启应用。
> 
> VM 数据隔离：不同架构使用独立数据库分区与状态目录，原版同名虚拟机不会跨架构复用。

> [!NOTE]
> 当前版本还有许多未确定的 Bug ，因为缺少测试，所以建议大家发现有 Bug 的（或者一些建议）可以在 issue 中提出。