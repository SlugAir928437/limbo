#!/usr/bin/env bash
# =============================================================================
#  diag-venus.sh  （只读诊断，不改动系统）
#  定位：客户机里的 Venus（virtio-gpu 上的 Vulkan）为什么起不来
#
#  典型症状：
#      vulkaninfo --summary → vkCreateInstance failed with ERROR_OUT_OF_HOST_MEMORY
#      VN_DEBUG=init vkcube  → MESA-VIRTIO: debug: connected to renderer
#                              MESA-VIRTIO: debug: wire format version 1
#                              MESA-VIRTIO: debug: vk xml version 1.3.269
#                              MESA-VIRTIO: debug: VK_MESA_venus_protocol spec version 2
#                              MESA-VIRTIO: debug: failed to allocate/map ring shmem
#                              vkCreateInstance failed.
#
#  怎么读这段日志：
#      前面几行说明 venus 和宿主渲染器（virglrenderer venus）**握手成功**，
#      capset / 协议都是通的；失败点只剩下"拿不到 host-visible 的 blob" ——
#      ring shmem 是客户机申请的第一个 host-visible blob，它要么在
#      DRM_VIRTGPU_RESOURCE_CREATE_BLOB 被宿主拒了，要么没法 mmap 进客户机。
#      这条路靠的是 virtio-gpu 的 hostmem 窗口（QEMU 里 hostmem= / blob=），
#      按 Mesa 文档，客户机侧映射还要靠宿主 KVM 与客户机内核配合完成
#      （映射被交给 KVM_SET_USER_MEMORY_REGION）——所以"是不是真跑在 KVM 上"
#      是这台机器必须确认的一件事。
#
#  用法（在客户机里跑）：
#      sudo bash diag-venus.sh      # 推荐：dmesg 要 root 才看得全
#      bash diag-venus.sh
#  若脚本是从 Windows 传进来的，先去掉行尾 CR：
#      sed -i 's/\r$//' diag-venus.sh
#
#  依赖：bash、coreutils；lscpu/pciutils/vulkan-tools/vkmark 可选。
# =============================================================================
export LC_ALL=C

C_B=$'\033[1;34m'; C_G=$'\033[1;32m'; C_Y=$'\033[1;33m'; C_R=$'\033[1;31m'; C_0=$'\033[0m'
step(){ printf '\n%s==> %s%s\n' "$C_B" "$*" "$C_0"; }
info(){ printf '    %s\n' "$*"; }
ok(){   printf '%s[ OK ]%s %s\n' "$C_G" "$C_0" "$*"; }
warn(){ printf '%s[WARN]%s %s\n' "$C_Y" "$C_0" "$*"; }
err(){  printf '%s[FAIL]%s %s\n' "$C_R" "$C_0" "$*"; }
have(){ command -v "$1" >/dev/null 2>&1; }

TMO=""
have timeout && TMO="timeout 25"

VENUS_ICD=""
FEAT_BLOB="?"; FEAT_CTX="?"; FEAT_VIRGL="?"
FEAT_HOSTVIS="?"                       # dmesg 里的 +host_visible
VIRT="?"                               # kvm / tcg / ...
VIRTIO_GPU_SEEN=0

# --------------------------------------------------------------- 1 虚拟机类型
step "1/6  系统、内核、以及这台机器到底是怎么虚拟化的"
[ -r /etc/os-release ] && . /etc/os-release
info "发行版 : ${PRETTY_NAME:-unknown}"
info "内核   : $(uname -r)   ($(uname -m))"
KVER=$(uname -r | sed 's/[^0-9.].*$//')
if [ "$(printf '%s\n5.16\n' "$KVER" | sort -V | head -n1)" = "5.16" ]; then
  ok "内核 $KVER >= 5.16：满足 venus 对客户机内核的最低要求"
else
  warn "内核 $KVER < 5.16：低于 venus 的客户机最低要求（Mesa 文档：5.16+ / Mesa 24.2+）"
fi

if have systemd-detect-virt; then
  VIRT=$(systemd-detect-virt 2>/dev/null)
  info "虚拟化类型: $VIRT"
fi
if [ "$VIRT" = "kvm" ]; then
  ok "这台客户机跑在 KVM 上（venus 的 host-visible 映射依赖宿主 KVM，这一步是必要的）"
elif [ "$VIRT" != "?" ]; then
  err "这台客户机不是跑在 KVM 上（$VIRT）"
  info "venus 的 host-visible blob 映射按 Mesa 文档是交给 KVM_SET_USER_MEMORY_REGION、由宿主 KVM +"
  info "客户机内核配合完成的；纯 TCG 下 ring shmem 这一步基本必挂，报的就是 ERROR_OUT_OF_HOST_MEMORY"
else
  HYPER=$(lscpu 2>/dev/null | grep -i 'hypervisor vendor')
  if [ -n "$HYPER" ]; then info "lscpu: $HYPER"; else warn "看不出虚拟化类型（没装 systemd-detect-virt / util-linux）"; fi
fi
KVMLOG=$(dmesg 2>/dev/null | grep -iE 'kvm-clock|KVM guest|Booting paravirtualized kernel on KVM' | head -n 3)
[ -n "$KVMLOG" ] && printf '    %s\n' "$KVMLOG"

# --------------------------------------------------------------- 2 显卡能力
step "2/6  客户机看到的 DRM 显卡 与 virtio-gpu 能力"
if [ -d /dev/dri ]; then
  ok "/dev/dri: $(ls /dev/dri | tr '\n' ' ')"
else
  err "没有 /dev/dri：客户机根本没拿到 DRM 显卡设备（virtio-gpu 驱动没绑定）"
fi
for d in /sys/class/drm/card*/device/driver; do
  [ -e "$d" ] || continue
  info "DRM 驱动: $(basename "$(readlink -f "$d")")"
done

getbit(){ [ $(( (F >> $1) & 1 )) -eq 1 ]; }
for vd in /sys/bus/virtio/devices/*; do
  [ -r "$vd/device" ] || continue
  case "$(cat "$vd/device" 2>/dev/null)" in
    0x0010|0x10|16) : ;;                 # VIRTIO_ID_GPU = 16
    *) continue ;;
  esac
  VIRTIO_GPU_SEEN=1
  info "virtio-gpu 设备: $(basename "$vd")"
  [ -r "$vd/status" ] && info "  status = $(cat "$vd/status" 2>/dev/null)"
  F_RAW=$(cat "$vd/features" 2>/dev/null)
  case "$F_RAW" in
    0x*|[0-9]*)
      F=$((F_RAW))
      info "  features = $F_RAW"
      if getbit 0; then ok "  VIRTIO_GPU_F_VIRGL (bit0)：有 → OpenGL(virgl) 直通可用"; FEAT_VIRGL=1
      else warn "  VIRTIO_GPU_F_VIRGL (bit0)：无 → virgl(OpenGL) 这一路也没通"; FEAT_VIRGL=0; fi
      if getbit 3; then ok "  VIRTIO_GPU_F_RESOURCE_BLOB (bit3)：有 → venus 前置条件之一满足"; FEAT_BLOB=1
      else err "  VIRTIO_GPU_F_RESOURCE_BLOB (bit3)：无 → venus 必然起不来"; FEAT_BLOB=0; fi
      if getbit 4; then ok "  VIRTIO_GPU_F_CONTEXT_INIT (bit4)：有 → venus 前置条件之一满足"; FEAT_CTX=1
      else err "  VIRTIO_GPU_F_CONTEXT_INIT (bit4)：无 → venus 必然起不来"; FEAT_CTX=0; fi
      ;;
    *) warn "  读不到 $vd/features（内核没暴露，或权限不足）" ;;
  esac
done
[ "$VIRTIO_GPU_SEEN" = 1 ] || err "找不到 virtio 的 GPU 设备"

# --------------------------------------------------------------- 3 内核日志
step "3/6  内核日志：驱动协商出来的能力（决定 host-visible 能不能用）"
if have dmesg && dmesg >/dev/null 2>&1; then
  FEATLINE=$(dmesg | grep -iE '\[drm\] features|virtio-gpu.*features|features:' | head -n 2)
  if [ -n "$FEATLINE" ]; then
    printf '    %s\n' "$FEATLINE"
    case "$FEATLINE" in
      *"+host_visible"*) ok "host_visible 已协商 → 客户机拿到了 host memory 窗口"; FEAT_HOSTVIS=1 ;;
      *"-host_visible"*) err "host_visible 未协商（-host_visible）→ 拿不到 host blob，venus 必然起不来"; FEAT_HOSTVIS=0 ;;
    esac
    case "$FEATLINE" in *"+resource_blob"*) ok "resource_blob 已协商" ;; *"-resource_blob"*) err "resource_blob 未协商" ;; esac
    case "$FEATLINE" in *"+context_init"*) ok "context_init 已协商" ;; *"-context_init"*) err "context_init 未协商" ;; esac
  else
    warn "没找到 [drm] features 行（内核太旧不会打印，或 dmesg 权限不足）"
  fi
  OUT=$(dmesg | grep -iE 'virtio.?gpu|virgl|hostmem' | tail -n 15)
  [ -n "$OUT" ] && printf '    %s\n' "$OUT"
else
  warn "dmesg 不可用或权限不足（用 sudo 重跑本脚本）"
fi

if have lspci; then
  BDF=$(lspci -nn 2>/dev/null | grep -i virtio | grep -iE 'gpu|display|3d' | awk '{print $1}' | head -n1)
  if [ -n "$BDF" ]; then
    info "PCI $BDF 的 BAR（host memory 窗口就在这里，hostmem=2G 应该看到一个 2G 的 64-bit BAR）:"
    lspci -v -s "$BDF" 2>/dev/null | grep -iE 'Region|Memory at|size=' | head -n 8
  fi
else
  info "（没装 pciutils，跳过 BAR 检查；apt install pciutils）"
fi

# --------------------------------------------------------------- 4 ICD
step "4/6  Vulkan ICD 清单（客户机侧的 Vulkan 驱动）"
ICD_SEEN=0
for dir in /usr/share/vulkan/icd.d /etc/vulkan/icd.d "$HOME/.local/share/vulkan/icd.d"; do
  [ -d "$dir" ] || continue
  ICD_SEEN=1
  info "$dir:"
  for j in "$dir"/*.json; do
    [ -e "$j" ] && info "    $(basename "$j")"
  done
done
[ "$ICD_SEEN" = 1 ] || err "系统里没有 vulkan 的 icd.d 目录（看不出装了哪些 Vulkan 驱动）"

VENUS_ICD=$(grep -ils venus /usr/share/vulkan/icd.d/*.json /etc/vulkan/icd.d/*.json \
                "$HOME/.local/share/vulkan/icd.d"/*.json 2>/dev/null | head -n1)
if [ -n "$VENUS_ICD" ]; then
  ok "venus ICD: $VENUS_ICD"
  LIB=$(sed -n 's/.*"library_path"[^"]*"\([^"]*\)".*/\1/p' "$VENUS_ICD" | head -n1)
  if [ -n "$LIB" ]; then
    case "$LIB" in
      /*) LIBP="$LIB" ;;
      *)  LIBP="$(dirname "$VENUS_ICD")/$LIB" ;;
    esac
    if [ -e "$LIBP" ]; then ok "  ICD 指向的库存在: $LIB"
    else err "  ICD 指向的库缺失: $LIB （驱动装坏了/没装全）"; fi
  fi
else
  err "没有 venus ICD：客户机里没有 Venus 驱动"
  info "Debian 上它在 mesa-vulkan-drivers 里（apt install mesa-vulkan-drivers），装完确认 icd.d 里出现含 venus 的 json"
fi

# --------------------------------------------------------------- 5 实测
step "5/6  实测：拿 Venus 的真实报错"
info "本次实测环境变量: VK_LOADER_DEBUG=error,warn  VN_DEBUG=init  ${VENUS_ICD:+VK_DRIVER_FILES=$VENUS_ICD}"
export VK_LOADER_DEBUG=error,warn
export VN_DEBUG=init
[ -n "$VENUS_ICD" ] && export VK_DRIVER_FILES="$VENUS_ICD"

if have glxinfo; then
  G=$(glxinfo -B 2>/dev/null | grep -iE 'OpenGL renderer|OpenGL version' | tr '\n' ' ')
  [ -n "$G" ] && info "GL 侧对照: $G   （出现 virgl 说明 OpenGL 直通是通的，坏的只有 Vulkan/venus）"
fi

run_test(){
  _name="$1"; _cmd="$2"; shift 2
  have "$_cmd" || { warn "未安装 $_cmd（Debian: apt install vulkan-tools / vkmark），跳过"; return; }
  info "---- $_name ----"
  # shellcheck disable=SC2086
  $TMO "$_cmd" "$@" 2>&1 | head -n 40
}

run_test "vulkaninfo --summary" vulkaninfo --summary
run_test "vkcube（窗口出现即成功，会被 timeout 结束）" vkcube
run_test "vkmark --winsys xcb" vkmark --winsys xcb

# --------------------------------------------------------------- 6 判读
step "6/6  判读"
if [ "$FEAT_HOSTVIS" = 0 ] || [ "$FEAT_BLOB" = 0 ] || [ "$FEAT_CTX" = 0 ]; then
  err "宿主（QEMU/Limbo）没把 venus 需要的 virtio-gpu 能力交给客户机 → venus 不可能起来。"
  info "去 Limbo 侧查：显卡必须是 virtio-gpu-gl-pci；不能用'以 root 子进程启动'"
  info "（那条路只能 -display none，Limbo 会把 GL 显卡降级成普通 virtio-gpu，blob/hostmem/venus 全被摘掉）；"
  info "APK 里的 QEMU 要带 -Dvirglrenderer=enabled，virglrenderer 要带 venus"
  info "（jni/android-config/android-limbo-config.mak: USE_VIRGL / USE_VIRGL_VENUS / USE_GTK = true）。"
elif [ "$VIRT" != "kvm" ] && [ "$VIRT" != "?" ]; then
  err "能力都到位了，但客户机不是跑在 KVM 上（$VIRT）→ 失败点在 host-visible blob 的映射。"
  info "venus 把宿主内存映射进客户机这一步按 Mesa 文档依赖宿主 KVM；纯 TCG 下 ring shmem 就是挂在这里。"
  info "在 Limbo 里用'保留 GPU 加速'（su 放开 /dev/kvm），别落进 TCG 或 root 子进程路径。"
  info "另外确认客户机架构和宿主一致：x86_64 客户机跑在 arm64 手机上时没有 KVM 可用。"
elif [ -n "$VENUS_ICD" ]; then
  warn "客户机侧一切正常（venus 驱动在、blob/context-init/host_visible 都有、跑在 KVM 上）→ 失败点回到宿主侧："
  warn "virglrenderer 的 venus 渲染器拿不到/映射不了 host blob（venus 统一报成 ERROR_OUT_OF_HOST_MEMORY）。"
  info "查 Limbo 侧 QEMU / virglrenderer 的输出（Limbo 日志，或 adb logcat | grep -iE 'virgl|venus|vulkan'）。"
  info "宿主 GPU 驱动要能把内存导出成 dma-buf：Android 平台要求 Vulkan 1.1 + VK_EXT_external_memory_dma_buf +"
  info "VK_EXT_image_drm_format_modifier + VK_EXT_queue_family_foreign；Mesa 只在 Mali 专有驱动 r32p0+ /"
  info "Turnip / PanVK / lavapipe 等上验证过。若宿主是 Linux，QEMU 文档要求宿主内核 6.13+、virglrenderer 1.0.0+。"
  info "便宜的实验：把 hostmem 从 2G 调小（512M / 1G）再试一次，排除 2G 窗口本身的问题。"
else
  warn "客户机里没找到 venus ICD：先装 mesa-vulkan-drivers，再确认 icd.d 里出现含 venus 的 json。"
fi

printf '\n%s把 1/6、3/6、5/6 三段输出，以及 Limbo 侧日志里 virgl|venus|vulkan 那几行贴出来即可定位。%s\n' "$C_B" "$C_0"
