#!/usr/bin/env bash
# =============================================================================
#  fix-net-debian13.sh
#  Debian 13 (trixie) 网卡修复脚本
#
#  症状：网卡设备存在、驱动已加载（lspci -k 能看到 "Kernel driver in use"），
#        但 ip a 没有 IPv4 地址 / 没有默认路由 / 域名解析失败 -> "无法联网"。
#
#  用法（在 Debian 13 客户机里、以 root 执行）：
#      sudo bash fix-net-debian13.sh          # 诊断 + 修复 + 验证
#      sudo bash fix-net-debian13.sh -d       # 只诊断，不改动系统
#
#  若脚本是从 Windows 传进来的，先去掉行尾 CR：
#      sed -i 's/\r$//' fix-net-debian13.sh
#
#  QEMU / Limbo "用户模式网络(SLIRP)" 的固定参数：
#      网关   10.0.2.2
#      DNS    10.0.2.3
#      客户机 10.0.2.15/24（内置 DHCP 自动下发）
#  因此默认策略：先试 DHCP；DHCP 拿不到地址就套用上面这套静态参数。
# =============================================================================

export LC_ALL=C

C_B=$'\033[1;34m'; C_G=$'\033[1;32m'; C_Y=$'\033[1;33m'; C_R=$'\033[1;31m'; C_0=$'\033[0m'
step(){ printf '\n%s==> %s%s\n' "$C_B" "$*" "$C_0"; }
info(){ printf '    %s\n' "$*"; }
ok(){   printf '%s[ OK ]%s %s\n'   "$C_G" "$C_0" "$*"; }
warn(){ printf '%s[WARN]%s %s\n'   "$C_Y" "$C_0" "$*"; }
err(){  printf '%s[FAIL]%s %s\n'   "$C_R" "$C_0" "$*"; }

SLIRP_GW=10.0.2.2
SLIRP_DNS=10.0.2.3
SLIRP_IP=10.0.2.15
SLIRP_PREFIX=24

DIAG_ONLY=0
case "${1:-}" in
  -d|--diagnose|--diagnose-only) DIAG_ONLY=1 ;;
  -h|--help) sed -n '2,24p' "$0"; exit 0 ;;
esac

if [ "$(id -u)" -ne 0 ]; then
  err "需要 root 权限，请用： sudo bash $0"
  exit 1
fi

# ------------------------------------------------------------------ 工具函数
has_ipv4(){
  ip -4 addr show "$1" 2>/dev/null | grep -q 'inet '
}
cur_ipv4(){
  ip -4 -o addr show "$1" 2>/dev/null | awk '{print $4}' | head -n1
}
cur_gw(){
  ip route show default 2>/dev/null | awk '/default/{print $3; exit}'
}
ping_ok(){ # $1=目标  $2=次数
  command -v ping >/dev/null 2>&1 || return 2
  ping -c "${2:-1}" -W 3 "$1" >/dev/null 2>&1
}

# 找一个"真网卡"：排除 lo 以及 docker0/veth/tun/tap/br- 等虚拟接口
detect_iface(){
  local i
  for i in /sys/class/net/*; do
    i=$(basename "$i")
    [ "$i" = "lo" ] && continue
    [ -e "/sys/class/net/$i/device" ] || continue
    IF="$i"; return 0
  done
  for i in /sys/class/net/*; do
    i=$(basename "$i")
    case "$i" in lo|docker*|veth*|virbr*|tun*|tap*|br-*|bond*) continue ;; esac
    IF="$i"; return 0
  done
  return 1
}

if ! detect_iface; then
  err "找不到任何网卡接口；请先确认虚拟机里是否添加了网卡设备（Limbo 里 Network 不能是 None）。"
  exit 1
fi
IF_DRV=$(basename "$(readlink -f "/sys/class/net/$IF/device/driver" 2>/dev/null)" 2>/dev/null)

# ------------------------------------------------------------------ 1. 诊断
step "1/5  诊断"

info "系统: $(. /etc/os-release 2>/dev/null; echo "${PRETTY_NAME:-unknown}")   内核: $(uname -r)"
info "网卡: $IF   驱动: ${IF_DRV:-未知}"

echo
info "--- 链路 / 地址 ---"
ip -br link show "$IF" 2>/dev/null
ip -br addr show "$IF" 2>/dev/null
if [ "$(cat "/sys/class/net/$IF/carrier" 2>/dev/null)" = "1" ]; then
  ok "链路 carrier = 1（对端可见）"
else
  warn "链路 carrier 读不到或为 0（接口 down，或对端没有 link）"
fi

echo
info "--- 路由 ---"
if [ -n "$(cur_gw)" ]; then
  ip route show
  ok "默认网关: $(cur_gw)"
else
  err "没有默认路由（这是『上不了网』的直接原因之一）"
  ip route show 2>/dev/null
fi

echo
info "--- DNS 解析配置 ---"
ls -l /etc/resolv.conf 2>/dev/null
grep -E '^[[:space:]]*nameserver' /etc/resolv.conf 2>/dev/null || warn "/etc/resolv.conf 里没有任何 nameserver"

echo
info "--- PCI 网络控制器 / 驱动绑定 ---"
if command -v lspci >/dev/null 2>&1; then
  lspci -nnk 2>/dev/null | grep -iE -A3 'ethernet|network controller' || warn "lspci 没列出网络控制器"
else
  info "（未安装 pciutils，跳过 lspci）"
fi
[ -n "$IF_DRV" ] && ok "接口 $IF 已绑定驱动: $IF_DRV"

echo
info "--- 网络管理服务状态 ---"
for s in NetworkManager systemd-networkd systemd-resolved networking; do
  printf '    %-18s %s\n' "$s" "$(systemctl is-active "$s" 2>/dev/null || true)"
done

echo
info "--- 已有的持久化配置 ---"
[ -f /etc/network/interfaces ] && { echo "    [/etc/network/interfaces]"; sed 's/^/      /' /etc/network/interfaces; }
ls /etc/network/interfaces.d/ 2>/dev/null | sed 's/^/    interfaces.d: /'
ls /etc/systemd/network/ 2>/dev/null | sed 's/^/    systemd-networkd: /'
command -v nmcli >/dev/null 2>&1 && nmcli -t -f DEVICE,TYPE,STATE,CONNECTION dev status 2>/dev/null | sed 's/^/    nmcli: /'

echo
info "--- 可用 DHCP 客户端 ---"
for c in dhclient dhcpcd udhcpc; do
  if command -v "$c" >/dev/null 2>&1; then info "$c -> $(command -v "$c")"; fi
done

if [ "$DIAG_ONLY" = "1" ]; then
  step "仅诊断模式，未做任何修改"
  exit 0
fi

# ------------------------------------------------------------------ 2. 修复
step "2/5  拉起接口"
ip link set dev "$IF" up 2>/dev/null && ok "ip link set $IF up" || err "无法拉起 $IF"

NM_ACTIVE=0; NETD_ACTIVE=0; RESOLVED_ACTIVE=0
systemctl is-active --quiet NetworkManager  && NM_ACTIVE=1
systemctl is-active --quiet systemd-networkd && NETD_ACTIVE=1
systemctl is-active --quiet systemd-resolved && RESOLVED_ACTIVE=1

MODE=""        # dhcp | static

# --- 2a. 先尝试 DHCP（TAP 模式 / SLIRP 模式都能用）
if ! has_ipv4 "$IF"; then
  step "3/5  尝试 DHCP 获取地址"
  if   command -v dhclient >/dev/null 2>&1; then
       timeout 25 dhclient -1 -4 -v "$IF" 2>&1 | tail -n 3
  elif command -v dhcpcd   >/dev/null 2>&1; then
       timeout 25 dhcpcd -4 -t 15 "$IF" 2>&1 | tail -n 3
  elif command -v udhcpc   >/dev/null 2>&1; then
       timeout 25 udhcpc -i "$IF" -n -q -t 5 -T 3 2>&1 | tail -n 3
  else
       warn "系统里没有任何 DHCP 客户端（dhclient/dhcpcd/udhcpc），直接走静态配置"
  fi

  if has_ipv4 "$IF"; then
    ok "DHCP 成功，地址: $(cur_ipv4 "$IF")"
    MODE="dhcp"
  else
    warn "DHCP 未拿到地址"
  fi
else
  ok "已有 IPv4 地址: $(cur_ipv4 "$IF")"
  MODE="dhcp"
fi

# --- 2b. DHCP 失败则套用 SLIRP 静态参数（QEMU/Limbo 用户模式网络）
if [ "$MODE" != "dhcp" ]; then
  step "3/5  DHCP 失败，改用 SLIRP 静态参数"
  ip addr flush dev "$IF" 2>/dev/null
  if ip addr add "${SLIRP_IP}/${SLIRP_PREFIX}" dev "$IF" 2>/dev/null; then
    ok "地址: ${SLIRP_IP}/${SLIRP_PREFIX}"
  else
    err "无法设置地址 ${SLIRP_IP}/${SLIRP_PREFIX}"
  fi
  ip route replace default via "$SLIRP_GW" dev "$IF" 2>/dev/null \
    && ok "默认网关: $SLIRP_GW" || err "无法设置默认网关 $SLIRP_GW"
  MODE="static"

  echo
  info "验证到网关的连通性（10.0.2.2 是 QEMU/Limbo 用户模式的虚拟网关）..."
  ping_ok "$SLIRP_GW" 2; rc=$?
  if [ "$rc" = "0" ]; then
    ok "能 ping 通网关 $SLIRP_GW —— 静态参数正确，网卡本身没问题"
  elif [ "$rc" = "2" ]; then
    warn "系统里没有 ping，跳过连通性检查"
  else
    err "ping 不通 $SLIRP_GW。请检查 Limbo 的 Network 是否为 User、网卡型号是否与安装时一致。"
  fi
fi

# ------------------------------------------------------------------ 3. DNS
step "4/5  修正 DNS"

if [ "$RESOLVED_ACTIVE" = "1" ] && command -v resolvectl >/dev/null 2>&1; then
  resolvectl dns "$IF" "$SLIRP_DNS" "$SLIRP_DNS" 2>/dev/null
  resolvectl domain "$IF" '~.' 2>/dev/null
  resolvectl flush-caches 2>/dev/null
  ok "已通过 systemd-resolved 为 $IF 设置 DNS $SLIRP_DNS"
elif [ -L /etc/resolv.conf ] && readlink -f /etc/resolv.conf | grep -q '^/run/'; then
  warn "/etc/resolv.conf 指向 /run（stub），但 systemd-resolved 没在运行 -> 解析必然失败"
  info "已启用 systemd-resolved"
  systemctl enable --now systemd-resolved 2>/dev/null
  ln -sf /run/systemd/resolve/stub-resolv.conf /etc/resolv.conf 2>/dev/null
  sleep 1
  resolvectl dns "$IF" "$SLIRP_DNS" 2>/dev/null
fi

if ! grep -qE '^[[:space:]]*nameserver' /etc/resolv.conf 2>/dev/null; then
  if [ -L /etc/resolv.conf ]; then
    warn "/etc/resolv.conf 是符号链接且没有 nameserver，暂不改动（交给 NetworkManager/resolved 管理）"
  else
    printf 'nameserver %s\nnameserver 1.1.1.1\n' "$SLIRP_DNS" > /etc/resolv.conf
    ok "已写入 /etc/resolv.conf: $SLIRP_DNS"
  fi
else
  ok "resolv.conf 已有 nameserver: $(grep -m1 -E '^[[:space:]]*nameserver' /etc/resolv.conf | awk '{print $2}')"
fi

# ------------------------------------------------------------------ 4. 持久化
step "5/5  写入持久化配置（重启后仍然生效）"

if [ "$NM_ACTIVE" = "1" ] && command -v nmcli >/dev/null 2>&1; then
  info "方式: NetworkManager (nmcli)"
  nmcli device set "$IF" managed yes 2>/dev/null
  for c in $(nmcli -t -f NAME,DEVICE,TYPE con show 2>/dev/null | awk -F: -v d="$IF" '$2==d && $3=="802-3-ethernet"{print $1}'); do
    nmcli con delete "$c" >/dev/null 2>&1
  done
  if [ "$MODE" = "static" ]; then
    nmcli con add type ethernet ifname "$IF" con-name "limbo-$IF" \
      ipv4.method manual ipv4.addresses "${SLIRP_IP}/${SLIRP_PREFIX}" \
      ipv4.gateway "$SLIRP_GW" ipv4.dns "$SLIRP_DNS" ipv6.method disabled >/dev/null 2>&1
  else
    nmcli con add type ethernet ifname "$IF" con-name "limbo-$IF" \
      ipv4.method auto ipv6.method disabled >/dev/null 2>&1
  fi
  nmcli con up "limbo-$IF" >/dev/null 2>&1 && ok "已创建并激活连接 limbo-$IF" || warn "nmcli 激活连接失败"

elif [ -x /sbin/ifup ]; then
  info "方式: ifupdown (/etc/network/interfaces.d)"
  cp -a /etc/network/interfaces "/etc/network/interfaces.bak.$(date +%s)" 2>/dev/null
  CFG="/etc/network/interfaces.d/limbo-$IF"
  mkdir -p /etc/network/interfaces.d
  # ifupdown 的 dhcp 方法需要 dhclient/dhcpcd；没有客户端就写静态
  if [ "$MODE" = "dhcp" ] && { command -v dhclient >/dev/null 2>&1 || command -v dhcpcd >/dev/null 2>&1 || command -v udhcpc >/dev/null 2>&1; }; then
    cat > "$CFG" <<EOF
auto $IF
iface $IF inet dhcp
EOF
  else
    cat > "$CFG" <<EOF
auto $IF
iface $IF inet static
    address $SLIRP_IP
    netmask 255.255.255.0
    gateway $SLIRP_GW
    dns-nameservers $SLIRP_DNS
EOF
  fi
  ok "已写入 $CFG"
  grep -q 'interfaces.d' /etc/network/interfaces 2>/dev/null || info "提示: 确认 /etc/network/interfaces 里有 'source /etc/network/interfaces.d/*'"
  systemctl enable networking >/dev/null 2>&1

elif command -v systemctl >/dev/null 2>&1; then
  info "方式: systemd-networkd"
  if [ "$MODE" = "static" ]; then
    cat > "/etc/systemd/network/20-limbo-$IF.network" <<EOF
[Match]
Name=$IF

[Network]
Address=${SLIRP_IP}/${SLIRP_PREFIX}
Gateway=$SLIRP_GW
DNS=$SLIRP_DNS
EOF
  else
    cat > "/etc/systemd/network/20-limbo-$IF.network" <<EOF
[Match]
Name=$IF

[Network]
DHCP=ipv4
EOF
  fi
  systemctl enable --now systemd-networkd >/dev/null 2>&1
  systemctl restart systemd-networkd >/dev/null 2>&1
  ok "已写入 /etc/systemd/network/20-limbo-$IF.network"
else
  warn "没有找到 NetworkManager / ifupdown / systemd-networkd，本次修改不会保留到重启后"
fi

# ------------------------------------------------------------------ 5. 验证
echo
step "最终验证"
FINAL_IP=$(cur_ipv4 "$IF"); [ -n "$FINAL_IP" ] || FINAL_IP="无"
FINAL_GW=$(cur_gw);        [ -n "$FINAL_GW" ] || FINAL_GW="无"
printf '    %-12s %s\n' "接口地址:" "$FINAL_IP"
printf '    %-12s %s\n' "默认网关:" "$FINAL_GW"

if ping_ok "$SLIRP_GW" 1; then ok "网关 ($SLIRP_GW) 可达"; else warn "网关不可达（或未装 ping）"; fi
if ping_ok 1.1.1.1 1;   then ok "外网 ICMP (1.1.1.1) 可达"; else warn "外网 ICMP 不通（SLIRP 下 ICMP 可能被限制，不一定代表故障）"; fi

if command -v getent >/dev/null 2>&1; then
  if timeout 10 getent hosts deb.debian.org >/dev/null 2>&1; then
    ok "DNS 解析正常: $(timeout 10 getent hosts deb.debian.org | awk '{print $1}' | head -n1)"
  else
    err "DNS 解析失败（检查 /etc/resolv.conf 或 systemd-resolved）"
  fi
fi

if command -v curl >/dev/null 2>&1; then
  code=$(timeout 15 curl -sS -o /dev/null -w '%{http_code}' http://deb.debian.org/ 2>/dev/null)
  [ -n "$code" ] && [ "$code" != "000" ] && ok "HTTP 测试通过 (deb.debian.org -> $code)" || warn "HTTP 测试未通过"
elif command -v wget >/dev/null 2>&1; then
  timeout 15 wget -q --spider http://deb.debian.org/ 2>/dev/null && ok "HTTP 测试通过 (wget)" || warn "HTTP 测试未通过"
fi

cat <<'TIP'

-----------------------------------------------------------------------
如果做完以上步骤仍然不通，请按顺序自查这几点：

1) Limbo 里的网络模式必须是 "User"（用户模式 / SLIRP）。
   "None" = 没有网络；"TAP" 需要 root 并在宿主机侧自行配置网桥 + NAT，
   否则客户机拿不到地址（本脚本对 TAP 只能帮到 DHCP 尝试那一步）。

2) 网卡型号要和你装系统时用的一致。Limbo/网卡型号变了，Linux 里接口名
   会跟着变（例如 enp0s3 -> ens3），老的 /etc/network/interfaces 或
   NetworkManager 连接配置就不会再被套用。
   建议固定使用 e1000 或 virtio-net-pci（驱动分别是 e1000 / virtio_net，
   Debian 13 内核都自带）。

3) 用户模式网络的固定值（可手动核对）：
   ip addr add 10.0.2.15/24 dev <网卡>
   ip route add default via 10.0.2.2 dev <网卡>
   echo 'nameserver 10.0.2.3' > /etc/resolv.conf

4) Debian 13 已弃用 isc-dhcp，用 ifupdown 的话建议装 dhcpcd-base：
   apt install dhcpcd-base

5) 重装/清空网卡命名缓存（接口名莫名其妙时）：
   rm -f /etc/udev/rules.d/70-persistent-net.rules
   systemctl restart systemd-udev-settle
-----------------------------------------------------------------------
TIP

step "完成"
