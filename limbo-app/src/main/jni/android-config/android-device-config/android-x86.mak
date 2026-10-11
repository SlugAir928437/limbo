#Generic defs first
include $(LIMBO_JNI_ROOT)/android-config/android-device-config/android-generic.mak

ARCH_CFLAGS += -D__ANDROID_API__=$(NDK_PLATFORM_API)
ARCH_LD_FLAGS += -latomic

# Enable CMPXCHG16B (CPUID.1:ECX.CX16) so the compiler may use it for 16-byte
# (128-bit) atomics instead of falling back to libatomic's __atomic_*_16 calls.
# This is what lets QEMU's configure probes in qemu/meson.build
# (CONFIG_ATOMIC128 / CONFIG_CMPXCHG128) succeed and provide atomic16_cmpxchg
# (host/include/generic/host/atomic128-cas.h.inc), which TCG uses for the x86
# guest's CMPXCHG16B; otherwise it degrades to the slow
# gen_helper_exit_atomic path.  cmpxchg16b is available on every CPU that can
# run a 64-bit-capable Android x86 image; note that a few ancient 32-bit-only
# parts (e.g. Atom N270) lack it, unlike on x86_64.
ARCH_CFLAGS += -mcx16

#CLANG ONLY
ifeq ($(NDK_TOOLCHAIN_VERSION),clang)
    TARGET_PREFIX = i686-linux-android
    ARCH_CLANG_FLAGS += -target $(TARGET_PREFIX)$(NDK_PLATFORM_API)
    ARCH_CFLAGS += $(ARCH_CLANG_FLAGS) -D__ANDROID_API__=$(NDK_PLATFORM_API)
    #ARCH_CFLAGS += -fno-integrated-as
    ARCH_LD_FLAGS += -target $(TARGET_PREFIX)$(NDK_PLATFORM_API)
endif

#TARGET ARCH
APP_ABI = x86



