#!/usr/bin/env bash
# =============================================================================
# EDK2（TianoCore）UEFI 固件构建 —— 为 limbo 各客户机架构产出 QEMU 可用的
# -bios 固件（UEFI BIOS），部署到 limbo-app/src/main/assets/roms/。
#
# 用法（通常由 jni/Makefile 的 fetch-edk2 / edk2 / clean-edk2 目标调用）：
#   make_edk2.sh fetch [ARCH...]    仅克隆/更新所需的 edk2 源码树
#   make_edk2.sh build [ARCH...]    构建并把固件部署到 assets/roms/
#   make_edk2.sh clean [ARCH...]    删除构建目录（Build/）与已部署的固件
#
# ARCH 取值（大小写不敏感，兼容 QEMU softmmu 写法）：
#   IA32 / x86 / i386   -> IA32        X64 / x86_64        -> X64
#   ARM  / arm          -> ARM         AARCH64 / arm64     -> AARCH64
#
# 可通过环境变量覆盖：
#   EDK2_FIRMWARE_DIR  固件输出目录（默认 <main>/assets/roms）
#   EDK2_GIT_URL       edk2 仓库地址（默认 github.com/tianocore/edk2.git）
#   EDK2_TAG           主标签（默认 edk2-stable202508）
#   EDK2_PATCH_DIR     补丁目录（默认 <jni>/patches，fetch 时自动应用 edk2-*.patch）
#   EDK2_TOOLCHAIN     edk2 工具链标签（默认 GCCNOLTO；见“宿主工具链”一节）
#   EDK2_BUILD_TARGET  构建类型（默认 RELEASE）
#   BUILD_THREADS      并行度（默认 nproc）
#   PYTHON_COMMAND     构建用 Python（默认 python3）
# =============================================================================
#
# -----------------------------------------------------------------------------
# 架构 -> EDK2 平台映射与标签
# -----------------------------------------------------------------------------
#   客户机        EDK2 架构   EDK2 平台（DSC）             标签
#   x86（32 位）  IA32        OvmfPkg/OvmfPkgIa32.dsc      edk2-stable202508
#   x86_64        X64         OvmfPkg/OvmfPkgX64.dsc       edk2-stable202508
#   Arm（32 位）  ARM         ArmVirtPkg/ArmVirtQemu.dsc   edk2-stable202508
#   Aarch64       AARCH64     ArmVirtPkg/ArmVirtQemu.dsc   edk2-stable202508
#
# 产物命名沿用 QEMU pc-bios 的惯例，便于直接作为 -bios 固件使用：
#   IA32    -> edk2-i386-code.fd      （OvmfPkgIa32 的 OVMF_CODE.fd）
#   X64     -> edk2-x86_64-code.fd    （OvmfPkgX64 的 OVMF_CODE.fd）
#   ARM     -> edk2-arm-code.fd       （ArmVirtQemu-ARM 的 QEMU_EFI.fd）
#   AARCH64 -> edk2-aarch64-code.fd   （ArmVirtQemu-AARCH64 的 QEMU_EFI.fd）
#
# -----------------------------------------------------------------------------
# 为什么主标签固定在 edk2-stable202508
# -----------------------------------------------------------------------------
# 这是同时保留【纯 32 位 x86 的 OVMF 平台 OvmfPkgIa32.dsc】与【32 位 ARM 平台
# 支持（ArmVirtQemu.dsc 的 SUPPORTED_ARCHITECTURES = AARCH64|ARM）】的最后一个
# 稳定版：自 edk2-stable202511 起，上游先后移除了 OVMF IA32（commit 1fb88ffe，
# "OvmfPkg: Remove OVMF IA32"）与 ARM32（commit 49b3eb59，"MdePkg: Remove ARM32
# Support from BaseLib"）。因此若要同时构建 x86 与 Arm 两个 32 位客户机固件，
# 必须固定在 202508。
#
# -----------------------------------------------------------------------------
# 为什么不构建 IA64（IPF）
# -----------------------------------------------------------------------------
# 上游 EDK2 的 IA64 / Itanium（IPF）支持已于 2019 年被移除（收尾提交
# 4e1daa60f5372c22a11503961061ffa569eaf873，"MdePkg: Removed IPF related code"，
# 早于 edk2-stable201905）：edk2-stable201903 与 edk2-stable201811 的
# MdePkg/Library/BaseLib/BaseLib.inf 里已无 IPF，只有 edk2-stable201808 仍含
# VALID_ARCHITECTURES = IA32 X64 IPF EBC ARM AARCH64。
#
# 但即便是在仍含 IPF 的 edk2-stable201808 与 UDK2018（tag vUDK2018）里，也只有
# IPF 的核心库 / BaseTools 工具链，没有任何可构建的 IA64 平台：全部 .dsc 中带
# IPF 的都是包级 DSC（MdePkg/MdeModulePkg/ShellPkg/FatPkg/SecurityPkg/NetworkPkg
# 等），19 个 .fdf 无一引用 IPF；EdkCompatibilityPkg/Sample/Platform/CommonIpf.dsc
# 只是库类包含片段、并非平台。上游既无 IA64 平台，自然产不出可给 QEMU 当 -bios
# 的 IA64 固件，故本项目不把 IA64 纳入 EDK2 构建。
#
# -----------------------------------------------------------------------------
# 兼容性补丁
# -----------------------------------------------------------------------------
# <jni>/patches/edk2-*.patch 会在 fetch 时按「先 git apply --check 再 apply」的
# 方式自动应用（幂等：已打过就跳过）。当前有两个：
#   edk2-nasm3.patch
#     NASM 3.x（如 3.01）不再允许在 64 位模式下写 `push strict dword`，会报
#     “instruction not valid with 32-bit operand size”，使
#     UefiCpuPkg/Library/CpuExceptionHandlerLib/X64/ExceptionHandlerAsm.nasm 编不过。
#     补丁把其中两处改为 `push strict qword`（上游 master 也是这么改的），编码仍是
#     68 imm32（5 字节），IDT stub 尺寸不变。IA32 版本处于 32 位模式，NASM 3 下
#     `push strict dword` 仍合法，无需改动。
#
#   edk2-gcc15.patch
#     GCC 15 的 -Wmaybe-uninitialized 比旧版激进：
#     OvmfPkg/Library/BaseMemEncryptSevLib/X64/SnpPageStateChangeInternal.c 的
#     MemoryStateToGhcbOp() 里局部变量 Cmd 其实被 switch 全覆盖，但 GCC 15 仍判为
#     “may be used uninitialized”，而工具链带 -Werror，于是构建失败：
#         error: 'Cmd' may be used uninitialized [-Werror=maybe-uninitialized]
#     补丁做两件事：① 将该变量初始化为 0；② 在 tools_def.template 的
#     GCC_ALL_CC_FLAGS 末尾追加 -Wno-error=maybe-uninitialized（保留告警、只降级为
#     非致命），以兼容其它架构可能出现的同类告警。
#
# -----------------------------------------------------------------------------
# 宿主工具链
# -----------------------------------------------------------------------------
# EDK2 用宿主的 GNU 工具链构建（gcc / ld.bfd，交叉架构用 <triplet>-gcc），因此：
#  1) 构建前会把 Android/NDK 工具链目录从 PATH 中剔除——NDK 的 bin 里有
#     `ld -> ld.lld`，gcc 若拿到这个 LLD，会对 edk2 的 `-Wl,-n` +
#     `-z common-page-size` 只发出告警，而 edk2 传了 -Wl,--fatal-warnings，
#     告警会升级为致命错误：
#         ld: error: -z common-page-size set, but paging disabled by omagic or nmagic
#  2) 默认使用 GCCNOLTO（非 LTO）工具链。GCC5/GCC 带 -flto，在 binutils 很新
#     （例如 2.46）时会因链接超大 LTO 归档而失败：
#         ld.bfd: error: .../OpensslLibCrypto.lib: ELF section name out of range
#     GCCNOLTO 不生成 .gnu.lto_* 节，可稳定构建；需要 LTO 时设 EDK2_TOOLCHAIN=GCC5。
# =============================================================================

set -euo pipefail

EDK2_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LIMBO_MAIN_DIR="$(cd "$EDK2_DIR/.." && pwd)"        # limbo-app/src/main
EDK2_FIRMWARE_DIR="${EDK2_FIRMWARE_DIR:-$LIMBO_MAIN_DIR/assets/roms}"

EDK2_GIT_URL="${EDK2_GIT_URL:-https://github.com/tianocore/edk2.git}"
EDK2_TAG="${EDK2_TAG:-edk2-stable202508}"
# 补丁目录：<jni>/patches（edk2-*.patch 会在 fetch 时自动应用）
EDK2_PATCH_DIR="${EDK2_PATCH_DIR:-$(cd "$EDK2_DIR/.." && pwd)/patches}"

if [ -z "${BUILD_THREADS:-}" ]; then
    BUILD_THREADS="$( (nproc 2>/dev/null) || echo 4 )"
fi
PYTHON_COMMAND="${PYTHON_COMMAND:-python3}"

# 工具链标签：默认 GCCNOLTO（非 LTO）。
# 新版 binutils（如 2.46）在链接带 LTO 的大归档（OpensslLibCrypto.lib，含
# 18694 个 .gnu.lto_* 节）时会报：
#     ld.bfd: error: .../OpensslLibCrypto.lib: ELF section name out of range
# 而 GCC5/GCC 工具链带 -flto，必然踩到这个坑。GCCNOLTO 是 edk2 自带的非 LTO
# 工具链（IA32/X64/ARM/AARCH64 都有定义），在各代宿主工具链上都能构建。
# 需要 LTO 时用 EDK2_TOOLCHAIN=GCC5 覆盖（binutils 较旧的环境，例如
# Ubuntu 24.04 / binutils 2.42，用 GCC5 是正常的）。
TOOLCHAIN_TAG="${EDK2_TOOLCHAIN:-GCCNOLTO}"
BUILD_TARGET="${EDK2_BUILD_TARGET:-RELEASE}"

msg()  { echo ">> $*"; }
warn() { echo "WARNING: $*" >&2; }
die()  { echo "ERROR: $*" >&2; exit 1; }

# ---------------------------------------------------------------------------
# 用宿主 GNU 工具链构建：从 PATH 中剔除 NDK / Android 工具链
# ---------------------------------------------------------------------------
# 本项目的 jni/Makefile 会把 NDK/LLVM 工具链目录放到 PATH 最前
# （android-limbo-build.mak: PATH := $(TOOLCHAIN_CLANG_PREFIX):$(NDK_ROOT):$(CLEAN_PATH)），
# 而 NDK 的 bin 里有 `ld -> ld.lld`。gcc 一旦从 PATH 里拿到这个 LLD，就会对 edk2 的
# `-Wl,-n` + `-z common-page-size=0x40` 组合发出：
#     ld: warning: -z common-page-size set, but paging disabled by omagic or nmagic
# 又因为 edk2 会传 -Wl,--fatal-warnings，该警告被升级为致命错误：
#     ld: error: -z common-page-size set, but paging disabled by omagic or nmagic
# EDK2 应当用宿主 gcc/ld.bfd 构建（交叉架构则用 <triplet>-gcc/<triplet>-ld），
# 因此这里把 Android/NDK 目录从 PATH 里剔除，并确保 /usr/bin 在其中。
_edk2_strip_ndk_path() {
    local out
    out="$(printf '%s' "$PATH" | tr ':' '\n' \
        | grep -v -E 'android-ndk|/ndk/|android-sdk|/sdk/ndk' | paste -sd: -)"
    [ -n "$out" ] || out="/usr/local/bin:/usr/bin:/bin"
    case ":$out:" in
        *:/usr/bin:*) ;;
        *) out="/usr/bin:/bin:$out" ;;
    esac
    printf '%s' "$out"
}

EDK2_HOST_PATH="${EDK2_HOST_PATH:-$(_edk2_strip_ndk_path)}"
if [ "$EDK2_HOST_PATH" != "$PATH" ]; then
    msg "EDK2 构建使用清洗后的 PATH（已剔除 NDK/Android 工具链，避免 LLD 与 --fatal-warnings 冲突）"
fi
export PATH="$EDK2_HOST_PATH"

# ---------------------------------------------------------------------------
# 参数解析
# ---------------------------------------------------------------------------
ACTION="${1:-build}"
if [ "$#" -gt 0 ]; then shift; fi
ARCH_ARGS=("$@")

normalize_arch() {
    case "$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]')" in
        ia32|x86|i386|i386-softmmu|x86-softmmu)      echo IA32 ;;
        x64|x86_64|x86-64|x86_64-softmmu)            echo X64 ;;
        arm|arm-softmmu|aarch32)                     echo ARM ;;
        aarch64|arm64|aarch64-softmmu)               echo AARCH64 ;;
        *) return 1 ;;
    esac
}

ARCHS=()
for a in "${ARCH_ARGS[@]}"; do
    [ -n "$a" ] || continue
    n="$(normalize_arch "$a")" || die "未知架构 '$a'（可选：IA32 X64 ARM AARCH64）"
    # 去重
    found=0
    for e in "${ARCHS[@]:-}"; do [ "$e" = "$n" ] && found=1; done
    [ "$found" = 1 ] || ARCHS+=("$n")
done
if [ "${#ARCHS[@]}" -eq 0 ]; then
    ARCHS=(IA32 X64 ARM AARCH64)
fi

arch_dsc() {
    case "$1" in
        IA32)        echo "OvmfPkg/OvmfPkgIa32.dsc" ;;
        X64)         echo "OvmfPkg/OvmfPkgX64.dsc" ;;
        ARM|AARCH64) echo "ArmVirtPkg/ArmVirtQemu.dsc" ;;
    esac
}
arch_fv() {
    case "$1" in
        IA32|X64)    echo "OVMF_CODE.fd" ;;
        ARM|AARCH64) echo "QEMU_EFI.fd" ;;
    esac
}
arch_out() {
    case "$1" in
        IA32)    echo "edk2-i386-code.fd" ;;
        X64)     echo "edk2-x86_64-code.fd" ;;
        ARM)     echo "edk2-arm-code.fd" ;;
        AARCH64) echo "edk2-aarch64-code.fd" ;;
    esac
}

# 标签 -> 源码树目录（去掉 edk2-stable 前缀，如 edk2-stable202508 -> edk2-202508）
src_dir_for() { echo "$EDK2_DIR/edk2-${1#edk2-stable}"; }

# ---------------------------------------------------------------------------
# 依赖预检
# ---------------------------------------------------------------------------
preflight_common() {
    local missing=()
    for t in git make bash "$PYTHON_COMMAND"; do
        command -v "$t" >/dev/null 2>&1 || missing+=("$t")
    done
    if [ "${#missing[@]}" -gt 0 ]; then
        die "缺少构建工具：${missing[*]}
       Ubuntu 依赖：sudo apt-get install -y build-essential git python3 python3-pip python3-venv uuid-dev acpica-tools nasm device-tree-compiler"
    fi
}

# 单架构的前置检查；失败时 warn + 返回 1（由 build_one 逐架构汇报，不中断其它架构）
preflight_arch() {
    case "$1" in
        IA32|X64)
            command -v nasm >/dev/null 2>&1 || { warn "IA32/X64 构建需要 nasm（apt: nasm）"; return 1; }
            command -v iasl >/dev/null 2>&1 || { warn "OVMF 构建需要 iasl（apt: acpica-tools）"; return 1; }
            # 32 位 OVMF（IA32）在 x86_64 宿主上需要 gcc 的 -m32 支持
            if [ "$1" = "IA32" ]; then
                command -v i686-linux-gnu-gcc >/dev/null 2>&1 || \
                    echo '#include <stdio.h>' | "${CC:-gcc}" -m32 -E - >/dev/null 2>&1 || \
                    { warn "IA32 构建需要 32 位编译支持（apt: gcc-multilib libc6-dev-i386）"; return 1; }
            fi
            ;;
        ARM)
            detect_arm_prefix >/dev/null 2>&1 || \
                { warn "ARM 构建需要交叉编译器（apt: gcc-arm-linux-gnueabi 或 gcc-arm-linux-gnueabihf）"; return 1; }
            ;;
        AARCH64)
            detect_aarch64_prefix >/dev/null 2>&1 || \
                { warn "AARCH64 构建需要交叉编译器（apt: gcc-aarch64-linux-gnu）"; return 1; }
            ;;
    esac
    return 0
}

detect_aarch64_prefix() {
    local p
    for p in aarch64-linux-gnu- aarch64-unknown-linux-gnu-; do
        if command -v "${p}gcc" >/dev/null 2>&1; then echo "$p"; return 0; fi
    done
    return 1
}

detect_arm_prefix() {
    local p
    # edk2 的 ARM 平台按软浮点 ABI 构建，优先 gnueabi；部分发行版只带 gnueabihf
    for p in arm-linux-gnueabi- arm-linux-gnueabihf- arm-none-eabi-; do
        if command -v "${p}gcc" >/dev/null 2>&1; then echo "$p"; return 0; fi
    done
    return 1
}

# ---------------------------------------------------------------------------
# 源码获取 + 打补丁
# ---------------------------------------------------------------------------
# 应用 $EDK2_PATCH_DIR/edk2-*.patch（幂等）：
#   可正向 apply  -> 应用
#   已经打过      -> 提示后跳过（--reverse --check 成功）
#   两者都不成立  -> 报错退出（避免带着半成品继续构建）
apply_edk2_patches() {
    local dir="$1" p
    [ -d "$EDK2_PATCH_DIR" ] || return 0
    for p in "$EDK2_PATCH_DIR"/edk2-*.patch; do
        [ -f "$p" ] || continue
        if git -C "$dir" apply --check "$p" 2>/dev/null; then
            msg "应用补丁 $(basename "$p")"
            git -C "$dir" apply "$p"
        elif git -C "$dir" apply --reverse --check "$p" 2>/dev/null; then
            msg "补丁 $(basename "$p") 已应用，跳过"
        else
            die "补丁 $(basename "$p") 无法应用（既不能正向应用，也不是已应用状态）"
        fi
    done
}

fetch_src() {
    local tag="$1" dir
    dir="$(src_dir_for "$tag")"
    if [ -f "$dir/edksetup.sh" ]; then
        msg "edk2 $tag 已存在于 $dir，跳过克隆"
    else
        msg "克隆 edk2 $tag -> $dir"
        rm -rf "$dir"
        git clone --depth 1 --branch "$tag" --single-branch "$EDK2_GIT_URL" "$dir"
        # 构建 OVMF/ArmVirt 需要 CryptoPkg 的 openssl、BaseTools/固件里的 brotli 等子模块
        msg "初始化子模块（$tag）"
        git -C "$dir" submodule update --init --recursive --depth 1 \
            || git -C "$dir" submodule update --init --recursive
    fi
    # 每次 fetch 都过一遍补丁：已克隆好的源码树（上一次运行时拉的）也能自动补上，
    # 无需重新克隆
    apply_edk2_patches "$dir"
}

# ---------------------------------------------------------------------------
# BaseTools 构建（生成 GenFw/GenFds/GenFfs 等构建期工具）
# ---------------------------------------------------------------------------
build_basetools() {
    local dir="$1"
    if [ -x "$dir/BaseTools/Source/C/bin/GenFw" ] || [ -x "$dir/BaseTools/Source/C/bin/GenFw.exe" ]; then
        msg "BaseTools 已构建，跳过"
        return 0
    fi
    msg "构建 BaseTools"
    ( cd "$dir" && PYTHON_COMMAND="$PYTHON_COMMAND" make -C BaseTools -j "$BUILD_THREADS" )
}

# ---------------------------------------------------------------------------
# 同步 Conf/tools_def.txt
# ---------------------------------------------------------------------------
# Conf/tools_def.txt 是 edksetup.sh 从 BaseTools/Conf/tools_def.template 复制出来的
# 副本（且只在目标不存在时生成）。补丁改的是 template，若不同步这个副本，补丁对构建
# 不生效（例如 edk2-gcc15.patch 追加的 -Wno-error=maybe-uninitialized）。
# 这里只做“把缺失的标志补上”这一件事，幂等，且不覆盖用户在副本里的其它本地改动。
ensure_tools_def_flag() {
    local dir="$1" gen="$dir/Conf/tools_def.txt"
    [ -f "$gen" ] || return 0
    grep -q 'Wno-error=maybe-uninitialized' "$gen" && return 0
    sed -i 's/^\(DEFINE GCC_ALL_CC_FLAGS[^\r]*\)\r\?$/\1 -Wno-error=maybe-uninitialized\r/' "$gen"
    if grep -q 'Wno-error=maybe-uninitialized' "$gen"; then
        msg "同步 Conf/tools_def.txt：追加 -Wno-error=maybe-uninitialized"
    else
        warn "未能向 Conf/tools_def.txt 注入 -Wno-error=maybe-uninitialized；新版 GCC 下可能仍因 -Wmaybe-uninitialized 报错"
    fi
}

# ---------------------------------------------------------------------------
# 单架构构建
# ---------------------------------------------------------------------------
build_one() {
    local arch="$1"

    local dsc fv out dir outdir_rel fvpath
    dsc="$(arch_dsc "$arch")"
    fv="$(arch_fv "$arch")"
    out="$(arch_out "$arch")"
    dir="$(src_dir_for "$EDK2_TAG")"

    fetch_src "$EDK2_TAG"
    build_basetools "$dir"
    ensure_tools_def_flag "$dir"
    preflight_arch "$arch" || return 1

    case "$arch" in
        AARCH64)
            local ap; ap="$(detect_aarch64_prefix)" || { warn "未找到 aarch64 交叉编译器"; return 1; }
            export GCC5_AARCH64_PREFIX="$ap" GCC_AARCH64_PREFIX="$ap" GCCNOLTO_AARCH64_PREFIX="$ap"
            ;;
        ARM)
            local ap; ap="$(detect_arm_prefix)" || { warn "未找到 arm 交叉编译器"; return 1; }
            export GCC5_ARM_PREFIX="$ap" GCC_ARM_PREFIX="$ap" GCCNOLTO_ARM_PREFIX="$ap"
            ;;
    esac

    msg "构建客户机固件 $arch（$dsc，tag=$EDK2_TAG，toolchain=$TOOLCHAIN_TAG，target=$BUILD_TARGET）"
    (
        cd "$dir"
        export WORKSPACE="$PWD"
        export PACKAGES_PATH="$PWD"
        export EDK_TOOLS_PATH="$PWD/BaseTools"
        export PYTHON_COMMAND="$PYTHON_COMMAND"
        # edksetup.sh 会 source BaseTools/BuildEnv，而 BuildEnv 里有
        # `if [ -z "$CONF_PATH" ]` 这类直接引用未定义变量的写法，在 set -u 下会
        # 直接报 “CONF_PATH: unbound variable” 并中止。故 source 期间先关掉 -u/-e，
        # source 完再恢复（build 是独立进程，不受影响）。
        set +eu
        . ./edksetup.sh BaseTools
        set -eu
        build -a "$arch" -t "$TOOLCHAIN_TAG" -b "$BUILD_TARGET" -p "$dsc" -n "$BUILD_THREADS"
    )

    # 从 DSC 的 OUTPUT_DIRECTORY 推导产物路径（ArmVirtQemu 里含 $(ARCH) 占位）
    outdir_rel="$(grep -E '^[[:space:]]*OUTPUT_DIRECTORY' "$dir/$dsc" | head -n1 | cut -d= -f2- | tr -d ' \r')"
    outdir_rel="${outdir_rel/\$(ARCH)/$arch}"
    fvpath="$dir/$outdir_rel/${BUILD_TARGET}_${TOOLCHAIN_TAG}/FV/$fv"
    [ -f "$fvpath" ] || { warn "未找到构建产物：$fvpath"; return 1; }

    mkdir -p "$EDK2_FIRMWARE_DIR"
    cp -f "$fvpath" "$EDK2_FIRMWARE_DIR/$out"
    msg "部署 $out（$(du -h "$EDK2_FIRMWARE_DIR/$out" 2>/dev/null | cut -f1)）"
}

# ---------------------------------------------------------------------------
# clean
# ---------------------------------------------------------------------------
clean_one() {
    local arch="$1" dir out
    out="$(arch_out "$arch")"
    rm -f "$EDK2_FIRMWARE_DIR/$out"
    dir="$(src_dir_for "$EDK2_TAG")"
    if [ -d "$dir" ]; then
        rm -rf "$dir/Build"
    fi
    return 0
}

# ---------------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------------
case "$ACTION" in
    fetch)
        preflight_common
        for arch in "${ARCHS[@]}"; do
            fetch_src "$EDK2_TAG"
        done
        msg "EDK2 源码就绪"
        ;;
    build)
        preflight_common
        failed=""
        for arch in "${ARCHS[@]}"; do
            # 逐架构构建：某个架构失败（例如缺交叉编译器）不影响其它架构继续
            if ! build_one "$arch"; then
                failed="$failed $arch"
            fi
        done
        if [ -n "$failed" ]; then
            warn "以下架构构建失败（其它架构已部署到 $EDK2_FIRMWARE_DIR）：$failed"
            exit 1
        fi
        msg "EDK2 固件构建完成：$EDK2_FIRMWARE_DIR"
        ;;
    clean)
        for arch in "${ARCHS[@]}"; do
            clean_one "$arch"
        done
        # 全部架构都请求时，连同源码树一起删除（与其它依赖的 distclean 语义一致）
        if [ "${#ARCHS[@]}" -ge 4 ]; then
            rm -rf "$(src_dir_for "$EDK2_TAG")"
        fi
        msg "EDK2 构建产物已清理"
        ;;
    -h|--help|help)
        cat <<'USAGE'
用法：
  make_edk2.sh fetch [ARCH...]    仅克隆/更新所需的 edk2 源码树
  make_edk2.sh build [ARCH...]    构建并把固件部署到 assets/roms/
  make_edk2.sh clean [ARCH...]    删除构建目录（Build/）与已部署的固件

ARCH（大小写不敏感，兼容 QEMU softmmu 写法；不传则默认全部）：
  IA32 / x86 / i386  -> edk2-i386-code.fd
  X64  / x86_64      -> edk2-x86_64-code.fd
  ARM  / arm         -> edk2-arm-code.fd
  AARCH64 / arm64    -> edk2-aarch64-code.fd

可用环境变量：EDK2_FIRMWARE_DIR / EDK2_GIT_URL / EDK2_TAG / EDK2_PATCH_DIR /
              EDK2_TOOLCHAIN（默认 GCCNOLTO）/ EDK2_BUILD_TARGET / BUILD_THREADS / PYTHON_COMMAND
不包含 IA64（IPF）：上游 edk2 已移除 IPF，且 201808 / UDK2018 均无可构建的 IA64 平台。
USAGE
        ;;
    *)
        die "未知动作 '$ACTION'（可选：fetch | build | clean）"
        ;;
esac
