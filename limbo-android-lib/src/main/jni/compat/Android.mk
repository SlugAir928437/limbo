LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)

LOCAL_SRC_FILES := \
	limbo_compat_filesystem.c \
	limbo_compat.c \
	limbo_compat_qemu.c \
	limbo_compat_stubs.c \
	limbo_compat_signals.cpp \
	$(NDK_ROOT)/sources/android/cpufeatures/cpu-features.c

LOCAL_MODULE := compat-limbo

LOCAL_C_INCLUDES :=			\
	$(LOCAL_PATH)/.. \
	$(LOCAL_PATH)/signals \
	$(LOCAL_PATH)/../SDL2/src \
	$(LOCAL_PATH)/../SDL2/include

LOCAL_CFLAGS += -include $(LOGUTILS)
LOCAL_ARM_MODE := $(ARM_MODE)
include $(BUILD_SHARED_LIBRARY)

