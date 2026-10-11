#Generic defs first
include $(LIMBO_JNI_ROOT)/android-config/android-device-config/android-generic.mak

ARCH_CFLAGS += -D__ANDROID_API__=$(NDK_PLATFORM_API)
ARCH_LD_FLAGS += -latomic

# Enable CMPXCHG16B (CPUID.1:ECX.CX16) so the compiler may use it for 16-byte
# (128-bit) atomics.  Without -mcx16 every __atomic_*_16 builtin degrades into
# a libatomic call, which makes QEMU's configure probes in qemu/meson.build
# (CONFIG_ATOMIC128 / CONFIG_ATOMIC128_OPT / CONFIG_CMPXCHG128) fail.  QEMU
# needs one of them to instantiate atomic16_cmpxchg -- see
# host/include/generic/host/atomic128-cas.h.inc -- which backs the atomic
# 128-bit helpers TCG uses for the x86 guest's CMPXCHG16B; with none of them
# QEMU falls back to the slow gen_helper_exit_atomic path.  Upstream QEMU adds
# -mcx16 itself for x86-64 hosts (qemu_isa_flags), and cmpxchg16b is present on
# essentially every x86_64 processor.
ARCH_CFLAGS += -mcx16

#CLANG ONLY
ifeq ($(NDK_TOOLCHAIN_VERSION),clang)
    TARGET_PREFIX = x86_64-linux-android
    ARCH_CLANG_FLAGS += -target $(TARGET_PREFIX)$(NDK_PLATFORM_API)
    ARCH_CFLAGS += $(ARCH_CLANG_FLAGS) -D__ANDROID_API__=$(NDK_PLATFORM_API)
    # ARCH_CFLAGS += -fno-integrated-as
    ARCH_LD_FLAGS += -target $(TARGET_PREFIX)$(NDK_PLATFORM_API)
endif

#TARGET ARCH
APP_ABI = x86_64

# Use 64bit
ARCH_CFLAGS += -m64



