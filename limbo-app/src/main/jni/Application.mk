LIMBO_JNI_ROOT := $(CURDIR)/jni

include $(LIMBO_JNI_ROOT)/android-limbo-build.mak

# Output .so files directly to jniLibs instead of the default libs/
NDK_LIBS_OUT := $(NDK_PROJECT_PATH)/jniLibs

#Suppress Format errors from logutils.h macros
APP_CFLAGS += -Wno-format-security

# NDK r28 / clang 16+ promotes -Wincompatible-function-pointer-types to a hard
# error by default; SDL2 (and a few compat units) cast non-const GL function
# pointers. Demote it back to a warning so the 16 KB build succeeds.
APP_CFLAGS += -Wno-error=incompatible-function-pointer-types

# Clang 16+ also promotes implicit function declarations to hard errors in C99+
# (e.g. compat files calling open()/close() without including <fcntl.h>/<unistd.h>).
# This legacy port compiled with these as warnings on older clang (r25c); keep
# the same behavior instead of patching every unit's includes.
APP_CFLAGS += -Wno-error=implicit-function-declaration

#Debug/Release
ifeq ($(NDK_DEBUG),1)
    APP_OPTIM := debug
else
    APP_OPTIM := release
endif

#Don't remove this
APP_CFLAGS += -include $(LOGUTILS)
APP_LDFLAGS += -llog
# 16 KB page-size alignment for Android 15+ 16 KB page devices: every ndk-build
# module (SDL2, compat-*, limbo) must have ELF LOAD segments aligned to 16384.
APP_LDFLAGS += -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384
ifeq ($(USE_GCC),true)
	APP_CFLAGS +=-std=gnu99
endif

APP_ARM_MODE=$(ARM_MODE)

$(info NDK_TOOLCHAIN_VERSION = $(NDK_TOOLCHAIN_VERSION))
$(info NDK_DEBUG = $(NDK_DEBUG))
$(info APP_ARM_MODE = $(APP_ARM_MODE))
$(info APP_ARM_NEON = $(APP_ARM_NEON))
$(info APP_OPTIM = $(APP_OPTIM))
$(info APP_ABI = $(APP_ABI))
$(info APP_PLATFORM = $(APP_PLATFORM))
$(info NDK_PROJECT_PATH = $(NDK_PROJECT_PATH))
$(info ARCH_CFLAGS = $(ARCH_CFLAGS))
$(info APP_CFLAGS = $(APP_CFLAGS))
