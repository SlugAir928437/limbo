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

# Cross-compiles nmap for a specified android target.
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
  make build-ncat
  cp ncat/ncat "${NCAT_OUT_DIR}/${abi}/libncat.so"
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
  local abi="${1:-${BUILD_HOST:-arm64-v8a}}"
  mkdir -p "${NCAT_OUT_DIR}/${abi}"
  build_one_abi "${abi}"
}

main "$@"