############### Limbo Configuration ##############
# if the  makefile doesn't recognize the project path you can override it here:
#LIMBO_JNI_ROOT := /home/dev/limbo/workspace_limbo/limbo-android-lib/src/main/jni

# Last version with gcc support is 14b 
#NDK_ROOT = /home/dev/tools/ndk/android-ndk-r14b
#USE_GCC?=true
# r28+ required: provides 16 KB page-size defaults and the clang linker
# flags (-Wl,-z,max-page-size=16384) that keep native libs 16 KB aligned.
NDK_ROOT ?= /home/huang/android-ndk-r28c
USE_GCC?=false

### the ndk api should be the same as the minSdkVersion in your AndroidManifest.xml
###
### Android API compatibility window (api24-37):
### The native build is compiled against the LOWEST API of the supported window
### (ANDROID_API=24, identical to the Gradle minSdk). A .so linked against
### android-N only pulls in symbols that already exist since API N, so the SAME
### .so runs on every device from api24 up to api$(NDK_MAX_API) (37 = Gradle
### targetSdk/compileSdk) WITHOUT requiring one build per API level.
### The compat layer (compat/limbo_compat.h) already guards per-API bionic
### features with __ANDROID_API__<NN (strchrnul, shm_open, getrandom,
### timespec_get, close_range), and the 16 KB page-size linker flags cover
### Android 15+ / API 35+ 16 KB-page devices.
### DO NOT raise ANDROID_API above 24 unless you knowingly accept dropping
### support for the lower devices inside this api24-37 window.
ANDROID_API ?= 24
NDK_PLATFORM_API=$(ANDROID_API)

# Lowest API in the supported compatibility window (= Gradle minSdk)
NDK_MIN_API ?= 24
# Highest API the JNI build supports (= Gradle targetSdk/compileSdk, here 37)
NDK_MAX_API ?= 37

# Guard: refuse an ANDROID_API outside the api24-37 window so we can never
# silently emit a .so that runs on none of the supported devices.
API_IN_RANGE := $(shell test $(NDK_PLATFORM_API) -ge $(NDK_MIN_API) -a $(NDK_PLATFORM_API) -le $(NDK_MAX_API) && echo 1 || echo 0)
ifeq ($(API_IN_RANGE),0)
$(error ANDROID_API=$(NDK_PLATFORM_API) is outside the supported api$(NDK_MIN_API)-$(NDK_MAX_API) window)
endif

$(info SUPPORTED API WINDOW = api$(NDK_MIN_API)-$(NDK_MAX_API) (building against android$(NDK_PLATFORM_API)))

# Platform feature macros below only tell the sources which bionic features are
# available in the *build* platform; they never require a newer device, so the
# api24-37 device window stays fully covered regardless of these toggles.

# Set to true if you use platform-21 or above
USE_NDK_PLATFORM21 ?= true

# Set to true if you use platform-26 or above
USE_NDK_PLATFORM26 ?= false

# Optimization, generally it is better set to false when debugging
USE_OPTIMIZATION ?= true

# Hardening: it produces slower runtimes but helps preventing buffer overflow attacks
USE_SECURITY ?= true

# Uncomment to enable debugging
# If you enable debugging you should turn off optimization as well
#NDK_DEBUG=1

# Uncomment if you use Linux x86, Linux 64bit, or macosx PC to compile
# Compiling on Windows is no longer supported
#NDK_ENV ?= linux-x86
NDK_ENV ?= linux-x86_64
#NDK_ENV ?= darwin-x86

# Build threads (make -j ?) makes building faster
BUILD_THREADS ?= 3

############## QEMU Host and Guest

# Android device type (host arch)
# values: armeabi-v7a, arm64-v8a, x86, x86_64
BUILD_HOST?=arm64-v8a

# GUEST_ARCH is the Emulator type
# values: x86_64-softmmu,aarch64-softmmu
BUILD_GUEST?=x86_64-softmmu

# QEMU Version
# values: 2.9.1, 5.1.0, 6.2.0, 10.2.1
USE_QEMU_VERSION ?= 10.2.1

# GLib 2.66.x is new enough for QEMU 6.2.0 and uses Meson reliably.
GLIB_VERSION ?= 2.66.8

# If you want to use SDL interface
USE_SDL ?= true

# If you want to use SDL Audio with Android AudioTrack
USE_SDL_AUDIO ?= true

# If you want to use Android AAudio, it needs version platform API 26
USE_AAUDIO ?= true

# Enable KVM
# Note: KVM headers are available only for android-21 platform and above
USE_KVM ?= true

# Build the GTK4 (gtk4android) display stack for Android and link it into QEMU
# Set to false to skip the GTK4 build (faster builds, no GTK display support)
USE_GTK ?= true

# Android API level used for the GTK4 cross build (gtk4android)
# IMPORTANT: this MUST match the device minSdk (ANDROID_API, android-24).
# Building against a higher API level makes libgtk-4.so reference symbols
# that only exist on newer devices (e.g. the NDK native input API
# AMotionEvent_fromJava/AKeyEvent_fromJava/AInputEvent_release, which were
# introduced in API 31), so the library fails to dlopen on older devices.
# The gdk android backend reads input events via JNI and does not need them.
GTK_ANDROID_API ?= 24
