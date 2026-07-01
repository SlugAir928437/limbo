#### DO NOT CHANGE
QEMU_TARGET_LIST = $(BUILD_GUEST)
QEMU_CONFIG_DIR=$(LIMBO_JNI_ROOT)/android-config

include $(QEMU_CONFIG_DIR)/android-qemu-config-6.2.0.mak

##### QEMU generic configuration
#Enable Internal profiler
#CONFIG_PROFILER = --enable-gprof
# Set SDL Software rendering (issue with pause/resume)
#SDL_RENDERING = -D__LIMBO_SDL_FORCE_SOFTWARE_RENDERING__
# Or SDL Hardware Acceleration (faster though needs whole screen redraw)

#Enable debugging for QEMU
DEBUG =
ifeq ($(NDK_DEBUG), 1)
    DEBUG = --enable-debug
else
    DEBUG = --disable-debug-tcg --disable-debug-info  --disable-sparse
endif

ifeq ($(APP_ABI), armeabi)
    QEMU_HOST_CPU = arm
else ifeq ($(APP_ABI), armeabi-v7a)
    QEMU_HOST_CPU = arm
else ifeq ($(APP_ABI), armeabi-v7a-hard)
    QEMU_HOST_CPU = arm
else ifeq ($(APP_ABI), arm64-v8a)
    QEMU_HOST_CPU = aarch64
else ifeq ($(APP_ABI), x86)
    QEMU_HOST_CPU = i686
else ifeq ($(APP_ABI), x86_64)
    QEMU_HOST_CPU = x86_64
endif

config:
    echo TOOLCHAIN DIR: $(TOOLCHAIN_DIR)
    echo NDK ROOT: $(NDK_ROOT)
    echo NDK PLATFORM: $(NDK_PLATFORM)
    echo USR INCLUDE: $(NDK_INCLUDE)
    cd ./qemu ; \
    ./configure \
    --cc=$(CC) \
    --cxx=$(CXX) \
    --target-list=$(QEMU_TARGET_LIST) \
    --cpu=$(QEMU_HOST_CPU) \
    $(PIXMAN) \
    --enable-fdt \
    --enable-vnc --disable-vnc-jpeg --disable-vnc-png --disable-vnc-sasl \
    --disable-smartcard \
    --enable-kvm \
    --disable-spice \
    --disable-xen --disable-xen-pci-passthrough \
    --disable-numa \
    --disable-linux-aio \
    --disable-virtfs \
    --disable-vhost-net --disable-vhost-scsi \
    --disable-curses --disable-cocoa --disable-gtk \
    --disable-usb-redir \
    --disable-libusb \
    --enable-sdl \
    --audio-drv-list=sdl \
    --enable-coroutine-pool \
    --disable-tools --disable-libnfs --disable-tpm --disable-qom-cast-debug --disable-libnfs --disable-libiscsi --disable-docs --disable-rdma --disable-brlapi --disable-curl --disable-vde --disable-netmap --disable-cap-ng --disable-zlib-test --disable-attr --disable-guest-agent --disable-pie --disable-rbd --disable-xfsctl  --disable-lzo  --disable-snappy --disable-seccomp --disable-bzip2 --disable-glusterfs --disable-vte --disable-opengl --disable-blobs --disable-werror --disable-gnutls --disable-nettle --disable-user \
    --extra-ldflags=" \
    -L$(LIMBO_JNI_ROOT)/../obj/local/$(APP_ABI) \
    -lcompat-limbo \
    -lglib-2.0 \
    -lpixman-1 \
    -lc -lm -llog \
    $(INCLUDE_SYMS) \
    -shared \
    " \
    --extra-cflags=" \
    $(SYSTEM_INCLUDE) \
    -I$(LIMBO_JNI_ROOT)/compat \
    -I$(LIMBO_JNI_ROOT)/qemu/dtc/libfdt \
    -D__LIMBO_SDL_FORCE_HARDWARE_RENDERING__ \
    $(ENV_EXTRA) \
    -Wno-redundant-decls -Wno-unused-variable \
    -Wno-maybe-uninitialized -Wno-unused-function \
    -Wunused-but-set-variable -Wno-unknown-warning-option \
    $(ARCH_CFLAGS) \
    " \
    --with-coroutine=sigaltstack \
    $(DEBUG)
# $(CONFIG_PROFILER)