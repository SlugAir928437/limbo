#!/bin/bash
set -e
export NDK_ROOT=/home/huang/android-ndk-r28c
export ANDROID_NDK_ROOT=/home/huang/android-ndk-r28c
export BUILD_HOST=arm64-v8a
export APP_ABI=arm64-v8a
export BUILD_GUEST=ia64-softmmu
export USE_GTK=1
J=/mnt/c/Users/Huang/AndroidStudioProjects/limbo/limbo-android-lib/src/main/jni
cd "$J"
echo "=== BUILD START $(date) ==="
make qemu 2>&1
echo "=== BUILD RC=$? $(date) ==="