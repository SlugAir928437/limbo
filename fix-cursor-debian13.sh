#!/usr/bin/env bash
# =============================================================================
#  fix-cursor-debian13.sh
#  修复：Debian 13 装上 GPU 驱动之后鼠标指针消失
#
#  为什么会消失（一句话）：装上真正的 GPU/KMS 驱动后
#  （virtio-gpu / bochs-drm / qxl / vmwgfx / cirrus ...），桌面合成器会改用
#  DRM 的"硬件光标平面"，不再把指针画进帧缓冲；而这个硬件光标要靠 QEMU
#  侧把它显示出来，QEMU/SDL 在 Android 上做不到（SDL 后端没有实现光标接口），
#  于是屏幕上就没有指针了。
#
#  本脚本的做法：让桌面重新使用"软件光标"，把指针画回帧缓冲，
#  也就是回到你装 GPU 驱动之前那个能看见指针的状态。
#
#  用法（root）：
#      bash fix-cursor-debian13.sh            # 诊断 + 修复
#      bash fix-cursor-debian13.sh -d         # 只诊断，不动系统
#      bash fix-cursor-debian13.sh --revert   # 撤销本脚本的全部改动
#
#  生效方式：注销并重新登录即可（Wayland / Xorg 都适用）。
#
#  若脚本是从 Windows 传进来的，先去掉行尾 CR：
#      sed -i 's/\r$//' fix-cursor-debian13.sh
# =============================================================================

export LC_ALL=C

C_B=$'\033[1;34m'; C_G=$'\033[1;32m'; C_Y=$'\033[1;33m'; C_R=$'\033[1;31m'; C_0=$'\033[0m'
step(){ printf '\n%s==> %s%s\n' "$C_B" "$*" "$C_0"; }
info(){ printf '    %s\n' "$*"; }
ok(){   printf '%s[ OK ]%s %s\n'   "$C_G" "$C_0" "$*"; }
warn(){ printf '%s[WARN]%s %s\n'   "$C_Y" "$C_0" "$*"; }
err(){  printf '%s[FAIL]%s %s\n'   "$C_R" "$C_0" "$*"; }

ENV_FILE=/etc/environment
ENVD_FILE=/etc/environment.d/99-limbo-sw-cursor.conf
XORG_FILE=/etc/X11/xorg.conf.d/99-limbo-sw-cursor.conf
MARK_BEGIN="# >>> limbo-sw-cursor (managed by fix-cursor-debian13.sh)"
MARK_END="# <<< limbo-sw-cursor"

MODE=apply
case "${1:-}" in
  -d|--diagnose|--diagnose-only) MODE=diag ;;
  --revert|--undo)               MODE=revert ;;
  -h|--help) sed -n '2,30p' "$0"; exit 0 ;;
esac

if [ "$(id -u)" -ne 0 ]; then
  err "需要 root 权限，请用： sudo bash $0"
  exit 1
fi

# --------------------------------------------------------------- 撤销
if [ "$MODE" = "revert" ]; then
  step "撤销本脚本的改动"
  rm -f "$XORG_FILE"      && ok "已删除 $XORG_FILE"
  rm -f "$ENVD_FILE"      && ok "已删除 $ENVD_FILE"
  if [ -f "$ENV_FILE" ]; then
    cp -a "$ENV_FILE" "$ENV_FILE.limbo-bak.$(date +%Y%m%d%H%M%S)" 2>/dev/null
    sed -i "/$(printf '%s' "$MARK_BEGIN" | sed 's/[][\.*^$/]/\\&/g')/,/$(printf '%s' "$MARK_END" | sed 's/[][\.*^$/]/\\&/g')/d" "$ENV_FILE"
    ok "已从 $ENV_FILE 里移除 limbo-sw-cursor 段落"
  fi
  step "完成：注销并重新登录后即恢复原来的硬件光标行为"
  exit 0
fi

# --------------------------------------------------------------- 诊断
step "1/4  诊断"

info "系统: $(. /etc/os-release 2>/dev/null; echo "${PRETTY_NAME:-unknown}")   内核: $(uname -r)"

echo
info "--- 正在运行的图形会话 ---"
SESS_INFO=""
if command -v loginctl >/dev/null 2>&1; then
  for s in $(loginctl list-sessions --no-legend 2>/dev/null | awk '{print $1}'); do
    t=$(loginctl show-session "$s" -p Type --value 2>/dev/null)
    d=$(loginctl show-session "$s" -p Desktop --value 2>/dev/null)
    a=$(loginctl show-session "$s" -p Active --value 2>/dev/null)
    [ -n "$t" ] && { info "session $s: Type=$t Desktop=$d Active=$a"; SESS_INFO="$SESS_INFO$t"; }
  done
fi
for p in gnome-shell kwin_wayland kwin_x11 plasmashell sway labwc Hyprland mutter Xorg X; do
  if pgrep -x "$p" >/dev/null 2>&1; then
    info "进程: $p 在运行"
    SESS_INFO="$SESS_INFO $p"
  fi
done

echo
info "--- 显卡与已绑定的 KMS 驱动（这就是『硬件光标』的来源）---"
if command -v lspci >/dev/null 2>&1; then
  lspci -nnk 2>/dev/null | grep -iE -A3 'vga|display|3d controller' | sed 's/^/\t/'
else
  info "（未安装 pciutils，跳过 lspci）"
fi

KMS_DRIVERS=""
for d in /sys/class/drm/card*/device/driver; do
  [ -e "$d" ] || continue
  drv=$(basename "$(readlink -f "$d")")
  KMS_DRIVERS="$KMS_DRIVERS$drv|"
  ok "DRM 卡片使用的内核驱动: $drv"
done
KMS_DRIVERS=$(printf '%s' "$KMS_DRIVERS" | sed 's/|$//')
if [ -z "$KMS_DRIVERS" ]; then
  warn "没有找到已绑定的 DRM 驱动（客户机可能还在用 efifb/vesafb，这种情况下指针本来就该可见）"
  KMS_DRIVERS="virtio_gpu|qxl|bochs-drm|vmwgfx|cirrus|mgag200|vkms"
  info "回退为常见驱动列表: $KMS_DRIVERS"
fi

echo
info "--- 当前已经生效的软件光标设置 ---"
grep -i 'swcursor' /var/log/Xorg.0.log 2>/dev/null | tail -n 3 || info "（没有 Xorg 日志或日志里没有 SWcursor 记录）"
for v in MUTTER_DEBUG_DISABLE_HW_CURSORS KWIN_FORCE_SW_CURSOR WLR_NO_HARDWARE_CURSORS; do
  printf '    %-34s %s\n' "$v" "$(printenv "$v" 2>/dev/null || echo '(未设置)')"
done
grep -q 'limbo-sw-cursor' "$ENV_FILE" 2>/dev/null && ok "之前已经应用过本脚本的修复" || info "尚未应用本脚本的修复"

if [ "$MODE" = "diag" ]; then
  step "仅诊断模式，未做任何修改"
  exit 0
fi

# --------------------------------------------------------------- 修复
step "2/4  让合成器改用软件光标（环境变量）"

# 三个变量分别对应 mutter(GNOME) / KWin(KDE) / wlroots(Sway,Hyprland,labwc)，
# 彼此互不干扰，全部写上可以覆盖所有会话类型。
apply_env_block(){
  local f="$1"
  mkdir -p "$(dirname "$f")"
  [ -f "$f" ] || : > "$f"
  cp -a "$f" "$f.limbo-bak.$(date +%Y%m%d%H%M%S)" 2>/dev/null
  sed -i "/$(printf '%s' "$MARK_BEGIN" | sed 's/[][\.*^$/]/\\&/g')/,/$(printf '%s' "$MARK_END" | sed 's/[][\.*^$/]/\\&/g')/d" "$f"
  {
    printf '%s\n' "$MARK_BEGIN"
    printf 'MUTTER_DEBUG_DISABLE_HW_CURSORS=1\n'
    printf 'KWIN_FORCE_SW_CURSOR=1\n'
    printf 'WLR_NO_HARDWARE_CURSORS=1\n'
    printf '%s\n' "$MARK_END"
  } >> "$f"
  ok "已写入 $f"
}

apply_env_block "$ENV_FILE"
apply_env_block "$ENVD_FILE"
info "/etc/environment 由 PAM 读取（GDM 登录界面和用户会话都生效）"

step "3/4  让 Xorg 也用软件光标"
mkdir -p /etc/X11/xorg.conf.d
cat > "$XORG_FILE" <<EOF
# managed by fix-cursor-debian13.sh
# 让 Xorg 使用软件光标（把指针画进帧缓冲），删掉本文件即可恢复硬件光标。
# SWcursor 是 xorg modesetting 驱动支持的选项（默认 off）。
Section "OutputClass"
    Identifier "limbo-software-cursor"
    MatchDriver "$KMS_DRIVERS"
    Option "SWcursor" "on"
EndSection
EOF
ok "已写入 $XORG_FILE"
info "MatchDriver = $KMS_DRIVERS"

# --------------------------------------------------------------- 完成
step "4/4  完成"

cat <<'TIP'

-----------------------------------------------------------------------
接下来怎么做：

1) 注销当前会话再重新登录（或者直接 reboot）。
   只是新开一个终端窗口不会生效。

2) 重新登录后验证：

      printenv MUTTER_DEBUG_DISABLE_HW_CURSORS    # GNOME 应输出 1
      printenv KWIN_FORCE_SW_CURSOR               # KDE 应输出 1
      printenv WLR_NO_HARDWARE_CURSORS            # Sway 等应输出 1
      sudo grep -i swcursor /var/log/Xorg.0.log   # X11 会话应能看到 SWcursor

3) 如果还是看不见指针，可按顺序试：

   a. GNOME 改成 Xorg 会话（X11 下上面的 SWcursor 配置更直接）：
        sudo sed -i 's/^#\?WaylandEnable=.*/WaylandEnable=false/' /etc/gdm3/daemon.conf
        sudo systemctl restart gdm3

   b. 干脆不要用这个 GPU 驱动：把 Limbo 的 VGA 换回 std（或者干脆用
      virtio-gpu 但不装 guest 端 3D 驱动），指针会立刻恢复。

   c. 从 Limbo 侧修（一劳永逸，需要重新编译 APK）：
      Limbo 的 SDL 后端已经改为传 "sdl,...,show-cursor=on"，
      它让 QEMU 保留宿主机指针，不再指望客户机自己画光标。
      如果你用的是旧版 APK，可以确认一下启动参数里是否有
      show-cursor=on（VNC 模式下 QEMU 自己会处理光标，不受影响）。

4) 想还原本脚本的改动：

      sudo bash fix-cursor-debian13.sh --revert
-----------------------------------------------------------------------
TIP
