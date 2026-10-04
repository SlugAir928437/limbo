#!/usr/bin/env bash

set -u
set -e

readonly SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" &> /dev/null \
                      && pwd )

readonly NMAP_VERSION='7.99'
readonly NMAP_SRC="nmap-${NMAP_VERSION}.tgz"
readonly NMAP_DOWNLOAD_URL="https://nmap.org/dist/${NMAP_SRC}"
readonly NMAP_BUILD_DIR="nmap-${NMAP_VERSION}"
readonly OPENSSL_VERSION='3.5.6'
readonly OPENSSL_SRC="openssl-${OPENSSL_VERSION}.tar.gz"
readonly OPENSSL_DOWNLOAD_URL="https://github.com/openssl/openssl/releases/download/openssl-${OPENSSL_VERSION}/${OPENSSL_SRC}"
readonly OPENSSL_BUILD_DIR="${SCRIPT_DIR}/openssl-${OPENSSL_VERSION}"
readonly HOST_ARCH='linux-x86_64'

# Android ABI   -> cross-compile target triple
declare -A ABI_TO_TARGET=(['arm64-v8a']='aarch64-linux-android' \
                          ['armeabi-v7a']='armv7a-linux-androideabi' \
                          ['x86']='i686-linux-android' \
                          ['x86_64']='x86_64-linux-android')

# Output directory where <abi>/libncat.so is installed.  Defaults to the
# jniLibs tree of limbo-android-lib (which Android bundles into the APK).
readonly NCAT_OUT_DIR="${NCAT_OUT_DIR:-$(cd "${SCRIPT_DIR}/../.." && pwd)/jniLibs}"

# Exports variables needed to cross-compile for Android.
# Args:
#   $1 Target (android target triple)
function export_make_toolchain() {
  export TARGET="$1"
  export TOOLCHAIN="${ANDROID_NDK_ROOT}/toolchains/llvm/prebuilt/${HOST_ARCH}"
  export API=24
  export AR="${TOOLCHAIN}/bin/llvm-ar"
  export CC="${TOOLCHAIN}/bin/${TARGET}${API}-clang"
  export AS="${CC}"
  export CXX="${TOOLCHAIN}/bin/${TARGET}${API}-clang++"
  export LD="${TOOLCHAIN}/bin/ld"
  export RANLIB="${TOOLCHAIN}/bin/llvm-ranlib"
  export STRIP="${TOOLCHAIN}/bin/llvm-strip"
}

# Extracts Nmap source. Removes it before, if it already exists.
function prepare_nmap_source() {
  if ! [[ -f "${NMAP_SRC}" ]]; then
    wget "${NMAP_DOWNLOAD_URL}" -O "${NMAP_SRC}"
  fi
  rm -rf "${NMAP_BUILD_DIR}"
  tar -xzf "${NMAP_SRC}"
}

# Extracts openssl source. Removes it before, if it already exists.
function prepare_openssl_source() {
  if ! [[ -f "${OPENSSL_SRC}" ]]; then
    wget "${OPENSSL_DOWNLOAD_URL}" -O "${OPENSSL_SRC}"
  fi
  rm -rf "${OPENSSL_BUILD_DIR}"
  tar -xzf "${OPENSSL_SRC}"
}

# Function to patch sockaddr_u.h.
# The patch file takes care of missing SUN_LEN macro.
function patch_source() {
  patch "${NMAP_BUILD_DIR}/ncat/sockaddr_u.h" < patches/sockaddr_u.h.patch
  patch "${NMAP_BUILD_DIR}/libdnet-stripped/configure.ac" < patches/libdnet-configure.ac.patch
  patch "${NMAP_BUILD_DIR}/libdnet-stripped/acconfig.h" < patches/libdnet-acconfig.h.patch
  (cd "${NMAP_BUILD_DIR}/libdnet-stripped" && autoreconf -f)
}

# Cross-compiles openssl for a specified android target.
# Args:
#   $1 Target (android target triple)
function cross_compile_openssl() {
  target="$1"
  if [[ "${target}" == 'aarch64-linux-android' ]]; then
    ./Configure android-arm64
  elif [[ "${target}" == 'armv7a-linux-androideabi' ]]; then
    ./Configure android-arm
  elif [[ "${target}" == 'i686-linux-android' ]]; then
      ./Configure android-x86
  elif [[ "${target}" == 'x86_64-linux-android' ]]; then
      ./Configure android-x86_64
  fi
  make -j CCOPT="-Wl,-z,max-page-size=16384"
}

# This function creates the folder OPENSSL_BUILD_DIR/lib, then copies
# needed openssl libraries in order to be properly included in nmap build.
function setup_openssl_dir_for_ncat_build() {
  local DEPS=('libcrypto.a'
              'libssl.a')
  mkdir lib
  for dep in "${DEPS[@]}"; do
      cp "${dep}" lib/
  done
}

# Cross-compiles nmap for a specified android target and links ncat as a shared
# library (libncat.so) instead of a standalone executable.
#
# 关闭安装时解压（useLegacyPackaging=false / extractNativeLibs=false）后，
# libncat.so 不会被复制到 nativeLibraryDir，也就不能再当可执行文件被
# ProcessBuilder 拉起（Android 10+ 还禁止执行应用私有目录下的文件）。
# 因此这里把 ncat 链接成真正的共享库，由 Java 侧 System.loadLibrary("ncat")
# 直接从 APK 加载，再通过 ncat_jni.c 的 fork() 入口在进程内运行。
#
# Args:
#   $1 Target (android target triple)
#   $2 Android ABI (output subdirectory)
function cross_compile_ncat() {
  export_make_toolchain "$1"
  local abi="$2"

  ./configure --host "${TARGET}" \
              --without-nping \
              --without-zenmap \
              --without-ndiff \
              --with-openssl="${OPENSSL_BUILD_DIR}" \
              --with-libpcap=included \
              --with-liblua=included

  # Android clang 默认不生成位置无关代码：aarch64 上会出现
  # R_AARCH64_ADR_PREL_PG_HI21 之类的重定位，无法链接进 .so。
  # 注意不能通过命令行覆盖 CFLAGS 注入 -fPIC：nmap 的 nbase/nsock 等 Makefile 把
  # $(DEFS)（含 -DHAVE_CONFIG_H）、$(INCLS) 也拼进了 CFLAGS，覆盖会一并抹掉，
  # 导致 nbase.h 走到 #ifndef HAVE_GETTIMEOFDAY 分支、与系统头文件类型冲突。
  # 因此直接修改 configure 生成好的 Makefile，在 CFLAGS 前补 -fPIC。
  add_pic_to_cflags() {
    local mk="$1"
    [ -f "${mk}" ] || return 0
    sed -i -E 's/^(CFLAGS[[:space:]]*=[[:space:]]*)/\1-fPIC /' "${mk}"
  }
  add_pic_to_cflags Makefile
  add_pic_to_cflags nbase/Makefile
  add_pic_to_cflags nsock/src/Makefile
  add_pic_to_cflags libpcap/Makefile
  add_pic_to_cflags ncat/Makefile

  # 按原样构建 ncat：会一并编译 liblua/libpcap/libnsock/libnbase 等依赖。
  make build-ncat

  # 编译 JNI 桥接对象（jni.h 由 NDK sysroot 提供）。
  "${CC}" -fPIC -O2 -Wall -c "${SCRIPT_DIR}/ncat_jni.c" -o ncat/ncat_jni.o

  # 往 ncat/Makefile 追加共享库目标：ncat 目标文件 + JNI 桥接对象一起链接成
  # 可被 System.loadLibrary() 加载的 .so。
  #   --exclude-libs,ALL       隐藏静态依赖（openssl/lua/pcap/nsock/nbase）的符号
  #   --version-script=...     只导出 JNI 入口（见 ncat.ver）
  #   -z,max-page-size=16384   与其余 jniLibs 一致的 16K 页对齐
  {
    printf '\n# --- added by limbo: build ncat as a loadable shared library ---\n'
    printf 'libncat.so: $(top_srcdir)/../liblua/liblua.a $(OBJS) $(NSOCKLIB) $(NBASELIB) $(NCAT_EXTRA_OBJS)\n'
    printf '\t$(CC) -shared -o $@ $(CFLAGS) $(LDFLAGS) $(OBJS) $(NCAT_EXTRA_OBJS) $(NSOCKLIB) $(NBASELIB) $(OPENSSL_LIBS) $(PCAP_LIBS) $(LUA_LIBS) $(LIBS) -Wl,-z,max-page-size=16384 -Wl,--exclude-libs,ALL -Wl,--version-script=$(NCAT_VERSION_SCRIPT)\n'
  } >> ncat/Makefile

  make -C ncat libncat.so \
      NCAT_EXTRA_OBJS=ncat_jni.o \
      NCAT_VERSION_SCRIPT="${SCRIPT_DIR}/ncat.ver"

  cp ncat/libncat.so "${NCAT_OUT_DIR}/${abi}/libncat.so"
}

# Builds ncat (and its openssl dependency) for a single Android ABI.
# Args:
#   $1 Android ABI
function build_one_abi() {
  local abi="$1"
  local target="${ABI_TO_TARGET[$abi]}"
  if [[ -z "${target}" ]]; then
    echo "ERROR: unsupported Android ABI: ${abi}" >&2
    exit 1
  fi
  export ANDROID_NDK_ROOT
  (
    prepare_openssl_source
    cd "${OPENSSL_BUILD_DIR}" || exit
    PATH="${ANDROID_NDK_ROOT}/toolchains/llvm/prebuilt/${HOST_ARCH}/bin:${PATH}"
    cross_compile_openssl "${target}"
    setup_openssl_dir_for_ncat_build
  )
  (
    prepare_nmap_source
    patch_source
    cd "${NMAP_BUILD_DIR}" || exit
    cross_compile_ncat "${target}" "${abi}"
  )
}

function main() {
  # The limbo jni Makefile 'export's its own variables (TARGET_ARCH, APP_ABI,
  # GNU_HOST, SYSROOT, ...).  Without clearing them they leak into the nmap /
  # openssl sub-make and end up as stray words on the compiler command line,
  # e.g. GNU make's implicit rule expands $(TARGET_ARCH) into clang's args.
  unset TARGET_ARCH APP_ABI APP_ABI_DIR APP_PLATFORM GNU_HOST HOST_PREFIX \
        EABI SYSROOT NDK_SYSROOT NDK_SYSROOT_INC NDK_INCLUDE ARCH_CFLAGS \
        ARCH_LD_FLAGS CFLAGS CXXFLAGS 2>/dev/null || true

  local abi="${1:-${BUILD_HOST:-arm64-v8a}}"
  mkdir -p "${NCAT_OUT_DIR}/${abi}"
  build_one_abi "${abi}"
}

main "$@"