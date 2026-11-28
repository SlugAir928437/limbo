LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)

LOCAL_ARM_MODE:= $(APP_ARM_MODE)

LOCAL_SRC_FILES := \
    $(LOCAL_PATH)/../musl/musl-locale/iconv.c

LOCAL_MODULE := compat-musl

LOCAL_C_INCLUDES :=			\
	$(LOCAL_PATH)/../musl/include

LOCAL_ARM_MODE := $(ARM_MODE)
# gettext 的实现在 compat/intl 里（静态库 intl），这里静态链接进来并强制
# 导出，保持 libcompat-musl.so 原有的 gettext/ngettext 符号（libglib 的
# DT_NEEDED 里有它）。
LOCAL_STATIC_LIBRARIES := intl
LOCAL_LDFLAGS += -u iconv_open -u gettext -u ngettext -u bindtextdomain
include $(BUILD_SHARED_LIBRARY)

