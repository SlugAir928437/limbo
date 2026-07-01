# Do not modify this file, all configuration is under directory android-config

LIMBO_JNI_ROOT:=$(shell dirname $(realpath $(lastword $(MAKEFILE_LIST))))
include $(LIMBO_JNI_ROOT)/android-config/android-limbo-config.mak

# prepend the NDK_ROOT and LLVM toolchain in the path so ndk-build/clang/pkg-config are the correct ones
TOOLCHAIN_DIR = $(NDK_ROOT)/toolchains/llvm/prebuilt/$(NDK_ENV)
TOOLCHAIN_CLANG_DIR = $(TOOLCHAIN_DIR)
TOOLCHAIN_CLANG_PREFIX := $(TOOLCHAIN_CLANG_DIR)/bin
PATH  := $(TOOLCHAIN_CLANG_PREFIX):$(NDK_ROOT):$(PATH)
SHELL := env PATH=$(PATH) /bin/bash

#PLATFORM CONFIG
# Ideally App platform used to compile should be equal or lower than the minSdkVersion in AndroidManifest.xml
APP_PLATFORM = android-$(NDK_PLATFORM_API)
NDK_PLATFORM = platforms/$(APP_PLATFORM)

ifeq ($(USE_NDK_PLATFORM21),true)
USE_PLATFORM21_FLAGS = -D__ANDROID_HAS_SIGNAL__ \
	-D__ANDROID_HAS_FS_IOC__ \
	-D__ANDROID_HAS_SYS_GETTID__ \
	-D__ANDROID_HAS_PARPORT__ \
	-D__ANDROID_HAS_IEEE__ \
	-D__ANDROID_HAS_STATVFS__ \
	-D__ANDROID__HAS_PTHREAD_ATFORK_
endif

ifeq ($(USE_NDK_PLATFORM26),true)
USE_PLATFORM26_FLAGS = -D__ANDROID_HAVE_STRCHRNUL__
endif

#SET/RESET vars
ARCH_CFLAGS := -D__LIMBO__ -D__ANDROID__ -DANDROID -D__linux__ -DCONFIG_LINUX $(USE_NDK11) \
  $(USE_PLATFORM21_FLAGS) $(USE_PLATFORM26_FLAGS)
ARCH_LD_FLAGS=

ifeq ($(BUILD_HOST), arm64-v8a)
######### Armv8 64 bit (Newest ARM phones only, Supports VNC, Needs android-21)
include $(LIMBO_JNI_ROOT)/android-config/android-device-config/android-armv8.mak
else ifeq ($(BUILD_HOST), armeabi-v7a)
######### ARMv7 Soft Float  (Most ARM phones, Supports VNC and SDL, Needs android-21)
include $(LIMBO_JNI_ROOT)/android-config/android-device-config/android-armv7a-softfp.mak
else ifeq ($(BUILD_HOST), x86)
######### x86 (x86 Phones only, Supports VNC and SDL, Needs android-21)
include $(LIMBO_JNI_ROOT)/android-config/android-device-config/android-x86.mak
else ifeq ($(BUILD_HOST), x86_64)
######### x86_64 (x86 64bit Phones only, Supports VNC, Needs android-21)
include $(LIMBO_JNI_ROOT)/android-config/android-device-config/android-x86_64.mak
endif

ifeq ($(APP_ABI),armeabi-v7a)
    EABI = arm-linux-androideabi-$(GCC_TOOLCHAIN_VERSION)
    HOST_PREFIX = arm-linux-androideabi
    GNU_HOST = arm-unknown-linux-android
    TARGET_ARCH=arm
    APP_ABI_DIR=$(APP_ABI)
else ifeq ($(APP_ABI),arm64-v8a)
    EABI = aarch64-linux-android-$(GCC_TOOLCHAIN_VERSION)
    HOST_PREFIX = aarch64-linux-android
    GNU_HOST = aarch64-unknown-linux-android
    TARGET_ARCH=arm64
    APP_ABI_DIR=$(APP_ABI)
else ifeq ($(APP_ABI),x86)
    EABI = x86-$(GCC_TOOLCHAIN_VERSION)
    HOST_PREFIX = i686-linux-android
    GNU_HOST = i686-unknown-linux-android
    TARGET_ARCH=x86
    APP_ABI_DIR=$(APP_ABI)
else ifeq ($(APP_ABI),x86_64)
    EABI = x86_64-$(GCC_TOOLCHAIN_VERSION)
    HOST_PREFIX = x86_64-linux-android
    GNU_HOST = x86_64-unknown-linux-android
    TARGET_ARCH=x86_64
    APP_ABI_DIR=$(APP_ABI)
endif

ifeq ($(APP_ABI),armeabi-v7a)
    MESON_CPU_FAMILY = arm
    MESON_CPU = armv7
else ifeq ($(APP_ABI),arm64-v8a)
    MESON_CPU_FAMILY = aarch64
    MESON_CPU = aarch64
else ifeq ($(APP_ABI),x86)
    MESON_CPU_FAMILY = x86
    MESON_CPU = i686
else ifeq ($(APP_ABI),x86_64)
    MESON_CPU_FAMILY = x86_64
    MESON_CPU = x86_64
endif


# Since we need ndk 11 and above we need to fix some missing calls
USE_NDK11 = -D__NDK11_FUNC_MISSING__

TOOLCHAIN_PREFIX := $(TOOLCHAIN_DIR)/bin/$(HOST_PREFIX)-
NDK_PROJECT_PATH := $(LIMBO_JNI_ROOT)/..

ifneq ($(NDK_TOOLCHAIN_VERSION),clang)
    NDK_SYSROOT_ARCH_INC=-I$(NDK_ROOT)/sysroot/usr/include/$(HOST_PREFIX)
    NDK_SYSROOT=$(NDK_ROOT)/sysroot
endif

ifeq ($(NDK_TOOLCHAIN_VERSION),clang)
    NDK_SYSROOT=$(TOOLCHAIN_CLANG_DIR)/sysroot
    NDK_SYSROOT_INC=-I$(NDK_SYSROOT)/usr/include
    ##### CLANG binaries
    CC=$(TOOLCHAIN_CLANG_PREFIX)/clang
	CPP=$(TOOLCHAIN_CLANG_PREFIX)/clang -E
    CXX=$(TOOLCHAIN_CLANG_PREFIX)/clang++
    CXXCPP=$(TOOLCHAIN_CLANG_PREFIX)/clang++ -E
    AR=$(TOOLCHAIN_CLANG_PREFIX)/llvm-ar
    AS=$(TOOLCHAIN_CLANG_PREFIX)/llvm-as
    LNK=$(TOOLCHAIN_CLANG_PREFIX)/clang
    LD=$(TOOLCHAIN_CLANG_PREFIX)/llvm-ld
    NM=$(TOOLCHAIN_CLANG_PREFIX)/llvm-nm
    OBJ_COPY=$(TOOLCHAIN_CLANG_PREFIX)/llvm-objcopy
    STRIP=$(TOOLCHAIN_CLANG_PREFIX)/llvm-strip
else
    #NDK Toolchain
    CC=$(TOOLCHAIN_PREFIX)gcc
    CXX=$(TOOLCHAIN_CLANG_PREFIX)/g++
    AR=$(TOOLCHAIN_PREFIX)ar
    AS=${TOOLCHAIN_PREFIX}as
    LNK = $(TOOLCHAIN_PREFIX)g++
    LD=${TOOLCHAIN_PREFIX}ld
    NM=${TOOLCHAIN_PREFIX}nm
    OBJ_COPY=$(TOOLCHAIN_PREFIX)objcopy
    STRIP=$(TOOLCHAIN_PREFIX)strip
endif

PKG_CONFIG_HOST := $(TARGET_PREFIX)$(NDK_PLATFORM_API)
PKG_CONFIG_LINK := $(TOOLCHAIN_CLANG_PREFIX)/$(PKG_CONFIG_HOST)-pkg-config
PKG_CONFIG ?= $(PKG_CONFIG_LINK)

GLIB_BUILD_DIR := $(LIMBO_JNI_ROOT)/glib/build-android-$(APP_ABI)
GLIB_INSTALL_DIR := $(LIMBO_JNI_ROOT)/glib/android-install/$(APP_ABI)
GLIB_CROSS_FILE := $(LIMBO_JNI_ROOT)/android-config/meson-android-$(APP_ABI).ini
GLIB_CROSS_FILE_TEMPLATE := $(LIMBO_JNI_ROOT)/android-config/meson-glib-android-cross.ini.in
GLIB_PKG_CONFIG_DIR := $(GLIB_INSTALL_DIR)/lib/pkgconfig
LIBFFI_PKG_CONFIG_DIR := $(NDK_PROJECT_PATH)/obj/local/$(APP_ABI)/pkgconfig
LIBFFI_PC_FILE := $(LIBFFI_PKG_CONFIG_DIR)/libffi.pc
PKG_CONFIG_PATH := $(GLIB_PKG_CONFIG_DIR):$(LIBFFI_PKG_CONFIG_DIR):$(PKG_CONFIG_PATH)

CREATE_PKG_CONFIG_LINK = \
	if [ ! -x "$(PKG_CONFIG_LINK)" ]; then \
		PKG_CONFIG_BIN=$$(command -v pkg-config); \
		if [ -z "$$PKG_CONFIG_BIN" ]; then \
			echo "pkg-config not found in PATH"; \
			exit 1; \
		fi; \
		ln -sf "$$PKG_CONFIG_BIN" "$(PKG_CONFIG_LINK)"; \
	fi

CREATE_LIBFFI_PC = \
	mkdir -p "$(LIBFFI_PKG_CONFIG_DIR)" && \
	printf '%s\n' \
		'prefix=$(NDK_PROJECT_PATH)/obj/local/$(APP_ABI)' \
		'exec_prefix=$${prefix}' \
		'libdir=$${prefix}' \
		'includedir=$(LIMBO_JNI_ROOT)/libffi/$(GNU_HOST)/include' \
		'' \
		'Name: libffi' \
		'Description: Foreign Function Interface library' \
		'Version: 3.4.2' \
		'Libs: -L$${libdir} -lffi' \
		'Libs.private: -llog' \
		'Cflags: -I$${includedir}' \
		> "$(LIBFFI_PC_FILE)"

CREATE_GLIB_MESON_CROSS_FILE = \
	sed \
		-e 's#@CC@#$(CC)#g' \
		-e 's#@CXX@#$(CXX)#g' \
		-e 's#@AR@#$(AR)#g' \
		-e 's#@STRIP@#$(STRIP)#g' \
		-e 's#@PKG_CONFIG@#$(PKG_CONFIG)#g' \
		-e 's#@MESON_CPU_FAMILY@#$(MESON_CPU_FAMILY)#g' \
		-e 's#@MESON_CPU@#$(MESON_CPU)#g' \
		-e 's#@GLIB_INSTALL_DIR@#$(GLIB_INSTALL_DIR)#g' \
		-e 's#@LIBGLIB_BUILDTYPE@#$(LIBGLIB_BUILDTYPE)#g' \
		-e 's#@TARGET_TRIPLE@#$(TARGET_PREFIX)$(NDK_PLATFORM_API)#g' \
		-e 's#@SYSROOT@#$(SYSROOT)#g' \
		-e 's#@ANDROID_API@#$(NDK_PLATFORM_API)#g' \
		-e 's#@LIMBO_JNI_ROOT@#$(LIMBO_JNI_ROOT)#g' \
		-e 's#@NDK_PROJECT_PATH@#$(NDK_PROJECT_PATH)#g' \
		-e 's#@APP_ABI@#$(APP_ABI)#g' \
		"$(GLIB_CROSS_FILE_TEMPLATE)" > "$(GLIB_CROSS_FILE)"

AR_FLAGS = crs
ifeq ($(NDK_TOOLCHAIN_VERSION),clang)
    SYSROOT = $(TOOLCHAIN_CLANG_DIR)/sysroot
else
    SYSROOT = $(NDK_ROOT)/$(NDK_PLATFORM)/arch-$(TARGET_ARCH)
endif

SYS_ROOT = --sysroot=$(SYSROOT)

NDK_INCLUDE = $(SYSROOT)/usr/include

# INCLUDE_FIXED contains overrides for include files found under the toolchain's /usr/include.
# Currently we don't use, left here as a placeholder.
# INCLUDE_FIXED = $(LIMBO_JNI_ROOT)/include-fixed

# The logutils header is injected into all compiled files in order to redirect
# output to the Android console, and provide debugging macros.
LOGUTILS = $(LIMBO_JNI_ROOT)/compat/limbo_logutils.h

#Some fixes for Android compatibility
COMPATUTILS_FD = $(LIMBO_JNI_ROOT)/compat/limbo_compat_filesystem.h
COMPATUTILS_QEMU = $(LIMBO_JNI_ROOT)/compat/limbo_compat_qemu.h
COMPATMACROS = $(LIMBO_JNI_ROOT)/compat/limbo_compat_macros.h
COMPATANDROID = $(LIMBO_JNI_ROOT)/compat/limbo_compat.h
	
# Needed for some c++ source code to compile some ARM 64 disas
# We don't need them right now
#STL port
#APP_STL := stlport_shared
#APP_STL := c++_shared
#STL_INCLUDE := -I$(NDK_ROOT)/sources/android/support/include
#STL_INCLUDE += -I$(NDK_ROOT)/sources/cxx-stl/stlport/stlport
#STL_INCLUDE += -I$(NDK_ROOT)/sources/cxx-stl/llvm-libc++/include
#STL_INCLUDE += -I$(NDK_ROOT)/sources/cxx-stl/llvm-libc++abi/include
#STL_INCLUDE += -D__STDC_CONSTANT_MACROS
#STL_LIB :=$(LIMBO_JNI_ROOT)/../obj/local/$(APP_ABI)/libstlport_shared.so
#STL_LIB :=$(LIMBO_JNI_ROOT)/../obj/local/$(APP_ABI)/libc++_shared.so
#CXX=$(TOOLCHAIN_PREFIX)g++

SYSTEM_INCLUDE = \
    $(SYS_ROOT) \
    -I$(NDK_INCLUDE) \
    -include $(LOGUTILS) \
    -include $(COMPATUTILS_FD) \
    -include $(COMPATUTILS_QEMU) \
    -include $(COMPATMACROS) \
    -include $(COMPATANDROID)
	
#info
$(info VARIABLES)
$(info PATH = $(PATH))
$(info NDK_ROOT = $(NDK_ROOT))
$(info NDK_TOOLCHAIN_VERSION = $(NDK_TOOLCHAIN_VERSION))
$(info APP_PLATFORM = $(APP_PLATFORM))
$(info USE_NDK_PLATFORM21 = $(USE_NDK_PLATFORM21))
$(info USE_NDK_PLATFORM26 = $(USE_NDK_PLATFORM26))
$(info APP_ABI = $(APP_ABI))
$(info USE_OPTIMIZATION = $(USE_OPTIMIZATION))
$(info USE_SECURITY = $(USE_SECURITY))
$(info BUILD_THREADS = $(BUILD_THREADS))
$(info NDK_ENV = $(NDK_ENV))
$(info BUILD_HOST = $(BUILD_HOST))
$(info BUILD_GUEST= $(BUILD_GUEST))
$(info USE_QEMU_VERSION = $(USE_QEMU_VERSION))
$(info USE_SDL = $(USE_SDL))
$(info USE_SDL_AUDIO = $(USE_SDL_AUDIO))
$(info USE_AAUDIO = $(USE_AAUDIO))
$(info USE_KVM = $(USE_KVM))
