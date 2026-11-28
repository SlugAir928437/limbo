LOCAL_PATH := $(call my-dir)

# Limbo: 真正的 gettext (libintl) 实现，编译成 obj/local/$(APP_ABI)/libintl.a。
# 用途有两处：
#   * compat-musl.so 静态链接它（导出 gettext 系列符号）；
#   * glib 的 meson 通过 intl.pc（见 jni/android-limbo-build.mak）命中
#     dependency('intl')，从而不再使用 proxy-libintl 的 stub；
#     QEMU 再由 glib-2.0.pc 的 Libs 带上 -lintl。
include $(CLEAR_VARS)

LOCAL_MODULE := intl
LOCAL_SRC_FILES := ../musl/musl-locale/intl.c
LOCAL_C_INCLUDES := $(LOCAL_PATH)/../musl
LOCAL_CFLAGS += -fPIC
LOCAL_ARM_MODE := $(ARM_MODE)

include $(BUILD_STATIC_LIBRARY)
