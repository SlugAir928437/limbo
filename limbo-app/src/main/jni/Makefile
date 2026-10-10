include android-limbo-build.mak

ifeq ($(NDK_ENV), windows)
	NDK_BUILD = ndk-build.cmd
else ifeq ($(NDK_ENV), windows-x86_64)
	NDK_BUILD = ndk-build.cmd
else
	NDK_BUILD = ndk-build
endif

# ---------------------------------------------------------------------------
# IA-64 客户机（BUILD_GUEST=ia64-softmmu）走 IA-64 QEMU fork，跳过 VirGL/VirtIO-GPU
# 与 SPICE 两套栈：
#   * VirGL/VirtIO-GPU：virglrenderer + QEMU 的 -Dopengl=enabled
#     -Dvirglrenderer=enabled（即 virtio-gpu-gl-pci）。IA-64 机型用不到，关掉
#     USE_VIRGL 后 fetch/config/build/link 各环节都自动跳过。
#   * SPICE：spice-server 栈（openssl/libjpeg-turbo/spice-protocol）+ QEMU 的
#     --enable-spice（即 qxl-vga 显示卡）。IA-64 的 QEMU 改为 --disable-spice，
#     spice 相关的 fetch/config/build 目标也不再执行。
# Java 侧同步隐藏这些显示卡（见 ArchDefinitions#getVGAValues）。
# ---------------------------------------------------------------------------
ifeq ($(strip $(BUILD_GUEST)),ia64-softmmu)
IA64_GUEST := yes
else
IA64_GUEST := no
endif

ifeq ($(IA64_GUEST),yes)
USE_VIRGL := false
SPICE_CONFIGURE_FLAG := --disable-spice
SPICE_CONFIG_TARGET :=
SPICE_DEPS_TARGET :=
SPICE_FETCH_TARGETS :=
else
SPICE_CONFIGURE_FLAG := --enable-spice
SPICE_CONFIG_TARGET := config-spice
SPICE_DEPS_TARGET := spice
SPICE_FETCH_TARGETS := fetch-openssl fetch-libjpeg fetch-spice-protocol fetch-spice
endif

ndkbuild=cd $(NDK_PROJECT_PATH) && GTK_INSTALL_DIR="$(GTK_INSTALL_DIR)" $(NDK_BUILD) -j $(BUILD_THREADS)
ndkclean=cd $(NDK_PROJECT_PATH) && $(NDK_BUILD) clean
ndkcleanobj=rm -rf $(NDK_PROJECT_PATH)/obj/

ifeq ($(NDK_ENV), windows)
	ndkgdb=cd $(NDK_PROJECT_PATH) && ndk-gdb.cmd
else ifeq ($(NDK_ENV), windows-x86_64)
	ndkgdb=cd $(NDK_PROJECT_PATH) && ndk-gdb.cmd
else
	ndkgdb=cd $(NDK_PROJECT_PATH) && ndk-gdb
endif

CREATE_OBJDIR=mkdir -p $(NDK_PROJECT_PATH)/obj/local/$(APP_ABI)/
CREATE_LIBSDIR=mkdir -p $(NDK_PROJECT_PATH)/jniLibs/$(APP_ABI_DIR)/
DEL_LIBSDIR=rm -rf $(NDK_PROJECT_PATH)/jniLibs/* \
	$(LIMBO_APP_LIBS)/jniLibs/* \


INSTALL_GLIB_LIBS=cp -Lf glib/android-install/$(APP_ABI)/lib/libglib-2.0.so* \
    glib/android-install/$(APP_ABI)/lib/libgthread-2.0.so* \
    glib/android-install/$(APP_ABI)/lib/libgio-2.0.so* \
    glib/android-install/$(APP_ABI)/lib/libgobject-2.0.so* \
    glib/android-install/$(APP_ABI)/lib/libgmodule-2.0.so* \
    $(NDK_PROJECT_PATH)/obj/local/$(APP_ABI)/

INSTALL_FFI_LIBS=cp -f libffi/.libs/*.a \
    $(NDK_PROJECT_PATH)/obj/local/$(APP_ABI)/

INSTALL_PIXMAN_LIBS=cp -Lf pixman/build-android-$(APP_ABI)/pixman/libpixman-1.so* \
    $(NDK_PROJECT_PATH)/obj/local/$(APP_ABI)/ ; \
    if ls pixman/build-android-$(APP_ABI)/pixman/libpixman-1.a >/dev/null 2>&1; then \
        cp -f pixman/build-android-$(APP_ABI)/pixman/libpixman-1.a \
        $(NDK_PROJECT_PATH)/obj/local/$(APP_ABI)/ ; \
    fi

# QEMU 引擎库：把 qemu/build/qemu-system-<target> 重命名部署为
# obj/local/<abi>/libqemu-system-<target>.so，随后由 INSTALL_LIBS 一并复制进
# src/main/jniLibs（即最终打进 APK 的那份）。
# 这里必须列出 App 支持的全部客户机架构，漏掉某一项该引擎就不会进包：
#   x86_64 -> libqemu-system-x86_64.so   （64 位 x86 客户机）
#   i386   -> libqemu-system-i386.so     （32 位 x86 客户机）
#   arm    -> libqemu-system-arm.so      （32 位 ARM 客户机）
#   aarch64-> libqemu-system-aarch64.so  （64 位 ARM 客户机）
#   ia64   -> libqemu-system-ia64.so     （IA-64 fork）
# 命名需与 VMExecutor#getQemuLibrary / ArchManager#libName 保持一致；
# ArchManager 正是通过扫描 APK 内是否存在这些 libqemu-system-*.so 来判断某个
# 模拟架构在当前安装包中是否可选，因此遗漏会直接导致该架构在“切换模拟架构”
# 菜单里不可见。
INSTALL_QEMU_LIBS=for target in x86_64 i386 arm aarch64 ia64; do \
	if [ -f "$(LIMBO_JNI_ROOT)/qemu/build/qemu-system-$$target" ]; then \
		cp -f "$(LIMBO_JNI_ROOT)/qemu/build/qemu-system-$$target" \
			"$(NDK_PROJECT_PATH)/obj/local/$(APP_ABI)/libqemu-system-$$target.so"; \
	fi; \
done

MOD_QEMU_LIBS=$(OBJCOPY) --redefine-sym open=android_open  \
    qemu/x86_64-softmmu/qemu-system-x86_64

INSTALL_LIBS=mkdir -p ../jniLibs/$(APP_ABI_DIR)/ && cp -Lf $(NDK_PROJECT_PATH)/obj/local/$(APP_ABI)/*.so* \
	../jniLibs/$(APP_ABI_DIR)/

REMOVE_GDBSETUP=rm -f ../jniLibs/$(APP_ABI_DIR)/gdb.setup
REMOVE_GDBSERVER=rm -f ../jniLibs/$(APP_ABI_DIR)/gdbserver

SYNC_LIBS=rsync -av --exclude libqemu-system-* ../libs/* ../jniLibs/

LIMBO_PRJ_ROOT=../../../..
LIMBO_APP_LIBS=$(LIMBO_PRJ_ROOT)/limbo-app/src/main

# ncat (Nmap's netcat) - cross-compiled per BUILD_HOST ABI into jniLibs so
# Android bundles it as libncat.so.  It is loaded with System.loadLibrary()
# and run in-process (native fork) instead of being executed as a standalone
# binary, so jniLibs can be packaged with useLegacyPackaging=false
# (no install-time extraction).  See ncat/ncat_jni.c and ncat/make_ncat.sh.
NCAT_BUILD_DIR=$(LIMBO_JNI_ROOT)/ncat
NCAT_OUT_DIR=$(NDK_PROJECT_PATH)/jniLibs

SYNC_LIBS_X86=rsync -av --include='libqemu-system-x86*.so' \
    --include='libqemu-system-i386*.so' \
    --include='gdbserver' --include='gdb.setup' \
    --include='*/' --exclude='*' \
    ../jniLibs/ $(LIMBO_APP_LIBS)/jniLibs/
SYNC_LIBS_ARM=rsync -av --include='libqemu-system-arm*.so' \
    --include='libqemu-system-aarch64*.so' \
    --include='gdbserver' --include='gdb.setup' \
    --include='*/' --exclude='*' \
    ../jniLibs/ $(LIMBO_APP_LIBS)/jniLibs/
SYNC_LIBS_IA64=rsync -av --include='libqemu-system-ia64*.so' \
    --include='gdbserver' --include='gdb.setup' \
    --include='*/' --exclude='*' \
    ../jniLibs/ $(LIMBO_APP_LIBS)/jniLibs/

# 注意：ncat 由 make ncat 直接产出到 $(NCAT_OUT_DIR)，而 NCAT_OUT_DIR 就是 app 的
# src/main/jniLibs，因此不需要额外的同步规则（STRIP_LIBS/COLLECT_SYMBOLS 也会覆盖到它）。

COPY_ROMS=-mkdir -p ../assets/roms && cp -rf ./qemu/pc-bios/*.rom \
./qemu/pc-bios/*.bin \
./qemu/pc-bios/*.img \
./qemu/pc-bios/*.bmp \
./qemu/pc-bios/*.ico \
./qemu/pc-bios/*.svg \
./qemu/pc-bios/*.rsrc \
./qemu/pc-bios/openbios-* \
./qemu/pc-bios/README \
../assets/roms/

COPY_KEYMAPS=cp -rf ./qemu/pc-bios/keymaps ../assets/roms/
COPY_QEMU_VERSION=cp -f ./qemu/VERSION ../assets/QEMU_VERSION
COPY_RESOLV_CONF=mkdir -p ../assets/roms/etc/ && printf "nameserver 8.8.8.8\n\n" > ../assets/roms/etc/resolv.conf

#RENAME_TUNCTL=mv -f $(NDK_PROJECT_PATH)/libs/$(APP_ABI)/tunctl \
	$(NDK_PROJECT_PATH)/libs/$(APP_ABI)/libtunctl.so
#RENAME_PARPROUTED=mv -f $(NDK_PROJECT_PATH)/libs/$(APP_ABI)/parprouted \
	$(NDK_PROJECT_PATH)/libs/$(APP_ABI)/libparprouted.so
	
#GDB needs the gdb.setup under ./jniLibs
COPY_GDB_SETUP_LIBS=-cp -f $(NDK_PROJECT_PATH)/jniLibs/$(APP_ABI)/gdb.setup \
	$(NDK_PROJECT_PATH)/jniLibs/gdb.setup
	
ifneq ($(NDK_DEBUG),1)
	STRIP_LIBS=$(STRIP) $(NDK_PROJECT_PATH)/jniLibs/$(APP_ABI_DIR)/*.so*
endif

# ---------------------------------------------------------------------------
# 符号表收集：把所有 .so 构建产物的符号表输出到 obj（崩溃符号化用：
# ndk-stack / llvm-symbolizer / addr2line）。
#   obj/symbols/$(APP_ABI)/<name>.so         未 strip 的副本（完整符号+调试信息）
#   obj/symbols/$(APP_ABI)/<name>.so.sym     完整符号表（llvm-nm，含局部/静态符号）
#   obj/symbols/$(APP_ABI)/<name>.so.dynsym  动态符号表（运行时可见的符号）
# 采集源只用未 strip 的构建产物：ndk-build 的 obj/local/$(APP_ABI)（各 ndk 模块、
# glib/pixman、libqemu-system-*.so）与各 meson 安装前缀（GTK4 及其自带依赖、
# virglrenderer）。jniLibs 下的是 strip 过的部署副本（符号表为空），故不作为源。
# SYMBOLS_KEEP_UNSTRIPPED=false 时只导出符号表文本、不复制 .so（省磁盘）。
# obj/ 整体会被 ndk-build clean / make clean-ndk 清掉，属于临时构建输出。
# ---------------------------------------------------------------------------
SYMBOLS_DIR := $(NDK_PROJECT_PATH)/obj/symbols/$(APP_ABI)
SYMBOLS_KEEP_UNSTRIPPED ?= true
SYMBOLS_SRC_DIRS := $(NDK_PROJECT_PATH)/obj/local/$(APP_ABI) \
	$(GLIB_INSTALL_DIR)/lib \
	$(PIXMAN_INSTALL_DIR)/lib \
	$(GTK_INSTALL_DIR)/lib \
	$(VIRGL_INSTALL_DIR)/lib

COLLECT_SYMBOLS= \
	mkdir -p "$(SYMBOLS_DIR)" && \
	count=0; \
	emit() { \
		base=$$1; src=$$2; \
		if [ "$(SYMBOLS_KEEP_UNSTRIPPED)" = "true" ]; then \
			cp -Lf "$$src" "$(SYMBOLS_DIR)/$$base"; \
		fi; \
		$(NM) -C -D --defined-only "$$src" > "$(SYMBOLS_DIR)/$$base.dynsym" 2>/dev/null || true; \
		$(NM) -C --defined-only "$$src" > "$(SYMBOLS_DIR)/$$base.sym" 2>/dev/null || true; \
		count=$$((count + 1)); \
	}; \
	for d in $(SYMBOLS_SRC_DIRS); do \
		[ -d "$$d" ] || continue; \
		for so in "$$d"/*.so; do \
			[ -f "$$so" ] || continue; \
			emit "$$(basename "$$so")" "$$so"; \
		done; \
	done; \
	for ncat in $(NCAT_BUILD_DIR)/nmap-*/ncat/libncat.so; do \
		[ -f "$$ncat" ] || continue; \
		emit libncat.so "$$ncat"; \
	done; \
	echo ">> 符号表已输出到 $(SYMBOLS_DIR)（共 $$count 个 .so）"

# we disable for now some options so armv7 host can compile with gcc
PIXMAN_DISABLE_OPTIONS=--disable-mmx --disable-arm-iwmmxt --disable-arm-iwmmxt2  \
	--disable-openmp  --disable-loongson-mmi  --disable-mmx    --disable-sse2   --disable-ssse3 \
	--disable-vmx --disable-arm-simd   --disable-arm-neon   --disable-arm-iwmmxt   \
	--disable-arm-iwmmxt2  --disable-mips-dspr2  --disable-gcc-inline-asm

DEBUG =
ifeq ($(NDK_DEBUG), 1)
	LIBFFI_DEBUG = --enable-debug
	LIBGLIB_BUILDTYPE = debug
	LIBPIXMAN_DEBUG =
	GTK_BUILDTYPE = debug
	VIRGL_BUILDTYPE = debug
else
	LIBFFI_DEBUG = --disable-debug
    LIBGLIB_BUILDTYPE = release
    LIBPIXMAN_DEBUG =
    GTK_BUILDTYPE = release
    VIRGL_BUILDTYPE = release
endif

export

.PHONY: limbo symbols clean-symbols clean distclean clean-ndk createdirs pkg-config-link clean-ffi clean-glib clean-qemu clean-pixman clean-slirp clean-spice clean-gtk clean-virgl ncat clean-ncat fetch fetch-glib fetch-libffi fetch-pixman fetch-sdl2 fetch-slirp fetch-openssl fetch-libjpeg fetch-spice-protocol fetch-spice fetch-qemu fetch-gtk4android fetch-virgl config-virgl virgl edk2 fetch-edk2 clean-edk2

default: limbo
	
createdirs:
	$(CREATE_OBJDIR)
	$(DEL_LIBSDIR)
	$(CREATE_LIBSDIR)

pkg-config-link:
	$(CREATE_PKG_CONFIG_LINK)

ndk:
	$(ndkbuild)

clean-ndk:
	$(ndkclean)
	$(ndkcleanobj)

clean-ffi:
	-cd $(LIMBO_JNI_ROOT)/libffi && $(MAKE) distclean
	-cd $(LIMBO_JNI_ROOT)/libffi && $(MAKE) clean

config-ffi: pkg-config-link
	cd $(LIMBO_JNI_ROOT)/libffi && ./configure --host=$(GNU_HOST) $(LIBFFI_DEBUG) --disable-builddir --enable-static --disable-shared \
	CFLAGS="$(ARCH_CFLAGS) $(SYS_ROOT) " \
	LDFLAGS="$(ARCH_LD_FLAGS) $(SYS_ROOT) -nostdlib -fno-unwind-tables "

ffi:
	cd $(LIMBO_JNI_ROOT)/libffi && $(MAKE) -j $(BUILD_THREADS) \
	CFLAGS="$(ARCH_CFLAGS) $(SYS_ROOT) " \
	LDFLAGS="$(ARCH_LD_FLAGS) $(SYS_ROOT) -nostdlib -fno-unwind-tables "
	$(INSTALL_FFI_LIBS)
	$(CREATE_LIBFFI_PC)

config-glib: ndk ffi pkg-config-link
	$(CREATE_GLIB_MESON_CROSS_FILE)
	cd $(LIMBO_JNI_ROOT)/glib && rm -rf "$(GLIB_BUILD_DIR)" "$(GLIB_INSTALL_DIR)"
	# pkg-config sees only the jni libs (glib itself + libffi): GTK's install dir
	# is intentionally NOT included, so pcre2/zlib resolve through the wrap files
	# and no stale GTK-built .pc files can leak into this build.
	cd $(LIMBO_JNI_ROOT)/glib && PKG_CONFIG_PATH="$(GLIB_PKG_CONFIG_DIR):$(LIBFFI_PKG_CONFIG_DIR)" PKG_CONFIG_LIBDIR="$(GLIB_PKG_CONFIG_DIR):$(LIBFFI_PKG_CONFIG_DIR)" meson setup "$(GLIB_BUILD_DIR)" . \
	--cross-file "$(GLIB_CROSS_FILE)"

glib: ndk ffi
	ninja -C "$(GLIB_BUILD_DIR)" -j $(BUILD_THREADS)
	DESTDIR= ninja -C "$(GLIB_BUILD_DIR)" install
	# the installed glib tools are Android ELFs and cannot run on the build
	# machine; point the pkg-config tool variables at the host tools (GTK
	# resolves glib-compile-resources/glib-mkenums/glib-genmarshal via these)
	sed -i -e 's|^glib_compile_resources=.*|glib_compile_resources=/usr/bin/glib-compile-resources|' \
		-e 's|^glib_compile_schemas=.*|glib_compile_schemas=/usr/bin/glib-compile-schemas|' \
		-e 's|^glib_mkenums=.*|glib_mkenums=/usr/bin/glib-mkenums|' \
		"$(GLIB_PKG_CONFIG_DIR)/gio-2.0.pc"
	sed -i -e 's|^glib_compile_schemas=.*|glib_compile_schemas=/usr/bin/glib-compile-schemas|' \
		-e 's|^glib_mkenums=.*|glib_mkenums=/usr/bin/glib-mkenums|' \
		"$(GLIB_PKG_CONFIG_DIR)/glib-2.0.pc"
	# replace the Android-ELF glib tools with the host ones: meson's
	# glib_compile_schemas module resolves them via the installed bindir even
	# in cross builds, and the Android ELFs cannot run on the build machine
	cp -f /usr/bin/glib-compile-resources /usr/bin/glib-compile-schemas \
		/usr/bin/glib-genmarshal /usr/bin/glib-mkenums /usr/bin/gdbus-codegen \
		$(GLIB_INSTALL_DIR)/bin/
	$(INSTALL_GLIB_LIBS)

clean-glib:
	-rm -rf "$(GLIB_BUILD_DIR)" "$(GLIB_INSTALL_DIR)" "$(GLIB_CROSS_FILE)"
	-rm -rf $(LIMBO_JNI_ROOT)/glib/android.cache
	# wrap download cache inside the glib tree (the global ~/.cache/meson wrap
	# cache and the vendored subproject sources are intentionally kept)
	-rm -rf $(LIMBO_JNI_ROOT)/glib/subprojects/packagecache


config-pixman: ndk pkg-config-link
	$(CREATE_PIXMAN_MESON_CROSS_FILE)
	cd $(LIMBO_JNI_ROOT)/pixman && rm -rf "$(PIXMAN_BUILD_DIR)"
	cd $(LIMBO_JNI_ROOT)/pixman && \
	PKG_CONFIG_PATH="$(GLIB_PKG_CONFIG_DIR):$(LIBFFI_PKG_CONFIG_DIR)" PKG_CONFIG_LIBDIR="$(GLIB_PKG_CONFIG_DIR):$(LIBFFI_PKG_CONFIG_DIR)" \
	meson setup "$(PIXMAN_BUILD_DIR)" . \
	--cross-file "$(PIXMAN_CROSS_FILE)"

pixman: ndk
	ninja -C "$(PIXMAN_BUILD_DIR)" -j $(BUILD_THREADS)
	DESTDIR= ninja -C "$(PIXMAN_BUILD_DIR)" install
	$(INSTALL_PIXMAN_LIBS)
	mkdir -p "$(NDK_PROJECT_PATH)/obj/local/$(APP_ABI)/pkgconfig" && printf '%s\n' \
		'prefix=$(NDK_PROJECT_PATH)/obj/local/$(APP_ABI)' \
		'exec_prefix=$${prefix}' \
		'libdir=$${prefix}' \
		'includedir=$(LIMBO_JNI_ROOT)/pixman/pixman' \
		'' \
		'Name: Pixman' \
		'Description: The pixman library' \
		'Version: 0.43.4' \
		'Libs: -L$${libdir} -lpixman-1' \
		'Cflags: -I$${includedir} -I$(PIXMAN_BUILD_DIR)/pixman' \
		> "$(NDK_PROJECT_PATH)/obj/local/$(APP_ABI)/pkgconfig/pixman-1.pc"

clean-pixman:
	-rm -rf "$(PIXMAN_BUILD_DIR)" "$(PIXMAN_INSTALL_DIR)" "$(PIXMAN_CROSS_FILE)"

# libslirp: the QEMU "user" network backend. Built as a static lib against the
# cross glib so QEMU's `-net user,model=e1000` works on Android.
# config-slirp must run after glib so slirp's pkg-config finds glib-2.0.
config-slirp: ndk pkg-config-link ffi glib
	$(CREATE_SLIRP_MESON_CROSS_FILE)
	cd $(LIMBO_JNI_ROOT)/slirp && rm -rf "$(SLIRP_BUILD_DIR)"
	# .tarball-version pins the version so build-aux/git-version-gen does not
	# abort on a version that mismatches meson.project_version().
	cd $(LIMBO_JNI_ROOT)/slirp && test -f .tarball-version || echo "4.7.0" > .tarball-version
	cd $(LIMBO_JNI_ROOT)/slirp && \
	PKG_CONFIG_PATH="$(GLIB_PKG_CONFIG_DIR)" \
	meson setup "$(SLIRP_BUILD_DIR)" . \
	--cross-file "$(SLIRP_CROSS_FILE)"

slirp: ndk
	ninja -C "$(SLIRP_BUILD_DIR)" -j $(BUILD_THREADS) libslirp.a
	DESTDIR= ninja -C "$(SLIRP_BUILD_DIR)" install
	# android-qemu-build.mak expects libslirp.a at qemu/slirp/libslirp.a for the
	# relinked libqemu-system-*.so, so mirror it there.
	mkdir -p "$(LIMBO_JNI_ROOT)/qemu/slirp"
	cp -f "$(SLIRP_INSTALL_DIR)/lib/libslirp.a" "$(LIMBO_JNI_ROOT)/qemu/slirp/libslirp.a"

clean-slirp:
	-rm -rf "$(SLIRP_BUILD_DIR)" "$(SLIRP_INSTALL_DIR)" "$(SLIRP_CROSS_FILE)"

# spice stack for the qxl-vga device (QEMU CONFIG_QXL).  All static:
#   spice-protocol (headers) -> openssl/libjpeg (spice-server deps) -> spice-server
# config-spice must run after glib/pixman so spice's pkg-config finds them.
#
# The OpenSSL Configure target name is NOT just a label: Configurations/
# 15-android.conf derives $arch from it (line ~56: `m|[^-]+-([^-]+)$|`), maps
# that to a compiler triplet (arm => armv7a-linux-androideabi, arm64 =>
# aarch64-linux-android, ...) and then selects $triarch$api-clang as CC.  So a
# hardcoded "android-arm64" silently builds AArch64 objects even into the
# armeabi-v7a prefix, and the stale libssl.a/libcrypto.a then fail to link into
# the 32-bit ARM binaries ("is incompatible with armelf_linux_eabi").  Mirror
# APP_ABI here the same way libjpeg does with -DANDROID_ABI=$(APP_ABI).
ifeq ($(APP_ABI),armeabi-v7a)
    OPENSSL_TARGET = android-arm
    # Pin the float ABI as well.  armeabi-v7a means the "softfp" ABI (base
    # AAPCS, no VFP register arguments); this NDK already defaults to softfp for
    # the armv7a-linux-androideabi triple (verified: e_flags 0x05000000, no
    # EF_ARM_ABI_FLOAT_HARD), and this build passes -mfloat-abi=softfp
    # explicitly everywhere else (ARCH_CFLAGS).  State it here too so the ABI
    # cannot drift with a compiler default change: a hard-float libssl.a cannot
    # be linked into the soft-float objects.  Bare -flags are appended to
    # CFLAGS/CXXFLAGS by Configure (see its argument parsing).
    OPENSSL_EXTRA_CFLAGS = -mfloat-abi=softfp
else ifeq ($(APP_ABI),arm64-v8a)
    OPENSSL_TARGET = android-arm64
else ifeq ($(APP_ABI),x86)
    OPENSSL_TARGET = android-x86
else ifeq ($(APP_ABI),x86_64)
    OPENSSL_TARGET = android-x86_64
endif

config-spice: ndk pkg-config-link ffi glib pixman
	# openssl 3.0.16 (static libcrypto/libssl)
	cd $(LIMBO_JNI_ROOT)/openssl && make clean >/dev/null 2>&1 || true
	cd $(LIMBO_JNI_ROOT)/openssl && \
	ANDROID_NDK_ROOT="$(NDK_ROOT)" ANDROID_NDK_HOME="$(NDK_ROOT)" \
	./Configure $(OPENSSL_TARGET) -D__ANDROID_API__=$(NDK_PLATFORM_API) \
		$(OPENSSL_EXTRA_CFLAGS) \
		--prefix="$(OPENSSL_INSTALL_DIR)" --openssldir="$(OPENSSL_INSTALL_DIR)/ssl" \
		no-shared no-tests no-legacy no-unit-test \
		no-async no-dso no-engine no-ui-console
	# libjpeg-turbo 3.0.4 (static libjpeg)
	cd $(LIMBO_JNI_ROOT)/libjpeg && rm -rf "$(JPEG_BUILD_DIR)" "$(JPEG_INSTALL_DIR)" && \
	cmake -S . -B "$(JPEG_BUILD_DIR)" -G Ninja \
		-DCMAKE_TOOLCHAIN_FILE=$(NDK_ROOT)/build/cmake/android.toolchain.cmake \
		-DANDROID_ABI=$(APP_ABI) -DANDROID_PLATFORM=android-$(NDK_PLATFORM_API) \
		-DANDROID_ARM_NEON=ON \
		-DCMAKE_INSTALL_PREFIX="$(JPEG_INSTALL_DIR)" \
		-DENABLE_SHARED=OFF -DENABLE_STATIC=ON \
		-DWITH_SIMD=ON -DWITH_TURBOJPEG=OFF \
		-DWITH_JAVA=OFF -DWITH_12BIT=OFF -DWITH_16BIT=OFF \
		-DCMAKE_BUILD_TYPE=Release
	# spice-protocol (headers only)
	$(CREATE_SPICE_PROTO_MESON_CROSS_FILE)
	cd $(LIMBO_JNI_ROOT)/spice-protocol && rm -rf "$(SPICE_PROTO_BUILD_DIR)" "$(SPICE_PROTO_INSTALL_DIR)" && \
	meson setup "$(SPICE_PROTO_BUILD_DIR)" . \
		--cross-file "$(SPICE_PROTO_CROSS_FILE)" \
		--prefix "$(SPICE_PROTO_INSTALL_DIR)" --libdir lib --buildtype release
	# build+install the spice deps first so spice-server's meson setup can find their .pc
	cd $(LIMBO_JNI_ROOT)/openssl && make -j $(BUILD_THREADS) && make install_sw
	ninja -C "$(JPEG_BUILD_DIR)" -j $(BUILD_THREADS) && cmake --install "$(JPEG_BUILD_DIR)"
	ninja -C "$(SPICE_PROTO_BUILD_DIR)" -j $(BUILD_THREADS)
	DESTDIR= ninja -C "$(SPICE_PROTO_BUILD_DIR)" install
	# spice-server 0.15.2 (static libspice-server.a, incl. spice-common subproject)
	$(CREATE_SPICE_MESON_CROSS_FILE)
	cd $(LIMBO_JNI_ROOT)/spice && rm -rf "$(SPICE_BUILD_DIR)" "$(SPICE_INSTALL_DIR)" && \
	PKG_CONFIG_PATH="$(SPICE_PROTO_PKG_CONFIG_DIR):$(OPENSSL_PKG_CONFIG_DIR):$(JPEG_PKG_CONFIG_DIR):$(GLIB_PKG_CONFIG_DIR):$(PIXMAN_PKG_CONFIG_DIR):$(LIBFFI_PKG_CONFIG_DIR)" \
	PKG_CONFIG_LIBDIR="$(SPICE_PROTO_PKG_CONFIG_DIR):$(OPENSSL_PKG_CONFIG_DIR):$(JPEG_PKG_CONFIG_DIR):$(GLIB_PKG_CONFIG_DIR):$(PIXMAN_PKG_CONFIG_DIR):$(LIBFFI_PKG_CONFIG_DIR)" \
	meson setup "$(SPICE_BUILD_DIR)" . \
		--cross-file "$(SPICE_CROSS_FILE)" \
		--prefix "$(SPICE_INSTALL_DIR)" --libdir lib --buildtype release \
		-Dgstreamer=no -Dlz4=false -Dsasl=false -Dopus=disabled \
		-Dsmartcard=disabled -Dtests=false -Dmanual=false \
		-Dstatistics=false -Dalignment-checks=false -Dextra-checks=false \
		-Ddefault_library=static

spice: ndk
	ninja -C "$(JPEG_BUILD_DIR)" -j $(BUILD_THREADS)
	cmake --install "$(JPEG_BUILD_DIR)"
	cd $(LIMBO_JNI_ROOT)/openssl && make -j $(BUILD_THREADS) && make install_sw
	ninja -C "$(SPICE_PROTO_BUILD_DIR)" -j $(BUILD_THREADS)
	DESTDIR= ninja -C "$(SPICE_PROTO_BUILD_DIR)" install
	ninja -C "$(SPICE_BUILD_DIR)" -j $(BUILD_THREADS)
	DESTDIR= ninja -C "$(SPICE_BUILD_DIR)" install

clean-spice:
	-rm -rf "$(SPICE_BUILD_DIR)" "$(SPICE_INSTALL_DIR)" "$(SPICE_CROSS_FILE)"
	-rm -rf "$(SPICE_PROTO_BUILD_DIR)" "$(SPICE_PROTO_INSTALL_DIR)" "$(SPICE_PROTO_CROSS_FILE)"
	-rm -rf "$(JPEG_BUILD_DIR)" "$(JPEG_INSTALL_DIR)"
	-rm -rf "$(OPENSSL_INSTALL_DIR)"
	-cd $(LIMBO_JNI_ROOT)/openssl && make clean >/dev/null 2>&1 || true

# GTK4 (gtk4android) display stack, only built when USE_GTK=true
# gtk builds against the jni shared libs (glib/pixman/libffi), so they must
# be built (and their .pc files generated) before we can configure GTK.
config-gtk: ndk pkg-config-link ffi glib pixman
	$(CREATE_GTK_MESON_CROSS_FILE)
	# apply the required glib hack patch (meson skips diff_files for pre-existing
	# dirs); only when a glib source tree exists inside gtk4android (the wrap
	# dirs are not shipped with the submodule, so CI simply skips this)
	@if [ -f $(LIMBO_JNI_ROOT)/gtk4android/subprojects/glib/glib/gutilsprivate.h ] && \
		! grep -q 'GLIB_AVAILABLE_IN_ALL' \
		$(LIMBO_JNI_ROOT)/gtk4android/subprojects/glib/glib/gutilsprivate.h; then \
		cd $(LIMBO_JNI_ROOT)/gtk4android/subprojects/glib && \
		git apply ../packagefiles/glib/hack.patch || { echo 'ERROR: failed to apply glib hack.patch'; exit 1; }; \
	fi
	# keep the downloaded subproject sources across reconfigures
	@if [ -d "$(GTK_BUILD_DIR).subprojects-save" ]; then \
		rm -rf "$(GTK_BUILD_DIR).subprojects-save"; \
	fi; \
	if [ -d "$(GTK_BUILD_DIR)/subprojects" ]; then \
		mv "$(GTK_BUILD_DIR)/subprojects" "$(GTK_BUILD_DIR).subprojects-save"; \
	fi
	cd $(LIMBO_JNI_ROOT)/gtk4android && rm -rf "$(GTK_BUILD_DIR)" "$(GTK_INSTALL_DIR)"
	@if [ -d "$(GTK_BUILD_DIR).subprojects-save" ]; then \
		mkdir -p "$(GTK_BUILD_DIR)"; \
		cp -a "$(GTK_BUILD_DIR).subprojects-save" "$(GTK_BUILD_DIR)/subprojects"; \
		rm -rf "$(GTK_BUILD_DIR).subprojects-save"; \
	fi
	# mirror the jni shared-lib trees under the NDK sysroot: pkg-config prefixes
	# -I/-L with the sysroot when a cross config carries sys_root, and these
	# symlinks keep those prefixed paths resolvable (needs passwordless sudo)
	mkdir -p "$(dir $(SYSROOT_JNI_LINK))" "$(dir $(SYSROOT_OBJ_LINK))"; \
	ln -sfn "$(LIMBO_JNI_ROOT)" "$(SYSROOT_JNI_LINK)"; \
	ln -sfn "$(NDK_PROJECT_PATH)/obj" "$(SYSROOT_OBJ_LINK)"
	# NOTE: as the top-level project, [gtk:project options] in the cross file does NOT apply,
	# so the key options must be passed explicitly (see gtk4android README)
	# glib/pixman/libffi come from the jni builds (shared with QEMU) via pkg-config;
	# setting PKG_CONFIG_LIBDIR also keeps the host's pcre2/zlib/... out of the picture.
	cd $(LIMBO_JNI_ROOT)/gtk4android && \
	PKG_CONFIG_LIBDIR="$(GLIB_PKG_CONFIG_DIR):$(LIBFFI_PKG_CONFIG_DIR)" \
	PKG_CONFIG_PATH="$(GLIB_PKG_CONFIG_DIR):$(LIBFFI_PKG_CONFIG_DIR)" \
	meson setup "$(GTK_BUILD_DIR)" . \
		--cross-file "$(GTK_CROSS_FILE)" \
		-Dandroid-backend=true \
		-Dx11-backend=false \
		-Dwayland-backend=false \
		-Dbroadway-backend=false \
		-Dwin32-backend=false \
		-Dmacos-backend=false \
		-Dmedia-gstreamer=disabled \
		-Dprint-cups=disabled \
		-Dprint-cpdb=disabled \
		-Dcloudproviders=disabled \
		-Dcolord=disabled \
		-Dtracker=disabled \
		-Daccesskit=disabled \
		-Dsysprof=disabled \
		-Dintrospection=disabled \
		-Dbuild-demos=false \
		-Dbuild-examples=false \
		-Dbuild-tests=false \
		-Dbuild-testsuite=false \
		-Dvulkan=disabled

gtk: ffi glib pixman
	ninja -C "$(GTK_BUILD_DIR)" -j $(BUILD_THREADS)
	ninja -C "$(GTK_BUILD_DIR)" install
	# deploy the GTK4 shared libraries into the APK jniLibs so the app can load them
	$(CREATE_LIBSDIR)
	cp -Lf $(GTK_INSTALL_DIR)/lib/*.so $(NDK_PROJECT_PATH)/jniLibs/$(APP_ABI_DIR)/

clean-gtk:
	-rm -rf "$(GTK_BUILD_DIR)" "$(GTK_INSTALL_DIR)" "$(GTK_CROSS_FILE)" "$(GTK_BUILD_DIR).subprojects-save"
	# remove the sysroot-prefixed jni symlinks created by config-gtk
	rm -f "$(SYSROOT_JNI_LINK)" "$(SYSROOT_OBJ_LINK)" || true; \
	rmdir "$(dir $(SYSROOT_JNI_LINK))" "$(dir $(SYSROOT_OBJ_LINK))" 2>/dev/null || true

# virglrenderer: host side renderer behind QEMU's virtio-gpu-gl-pci device
# (CONFIG_VIRGL / USE_VIRGL in android-limbo-config.mak).  Built like the other
# meson deps against the cross glib/libffi/pixman, plus the libepoxy that
# gtk4android installs - epoxy is what QEMU's CONFIG_OPENGL and virglrenderer's
# EGL platform both link against.  EGL platform only: Android has no GLX/gbm.
#
# 依赖是 gtk（构建+安装）而不是 config-gtk：virglrenderer 通过 pkg-config 找
# epoxy.pc、运行时链接 libepoxy.so，两者都只在 GTK4 完成 install 后才出现在
# $(GTK_INSTALL_DIR) 里；而 config-gtk 还会 rm -rf $(GTK_INSTALL_DIR)，所以在
# config-virgl 里重复它会把刚装好的 GTK4 又清掉。因此 config-virgl 只能排在
# GTK4 构建之后（make limbo 里排在 make deps 之后）。
config-virgl: ndk pkg-config-link ffi glib pixman $(if $(filter true,$(USE_GTK)),gtk)
	$(CREATE_VIRGL_MESON_CROSS_FILE)
	cd $(LIMBO_JNI_ROOT)/virglrenderer && rm -rf "$(VIRGL_BUILD_DIR)" "$(VIRGL_INSTALL_DIR)"
	# The pkg-config shims must be written *after* the wipe above: they live in
	# $(VIRGL_PKG_CONFIG_DIR), i.e. inside the very install dir rm -rf removes.
	# Written first they would be gone by the time meson runs, and
	# dependency('vulkan') would then resolve against the *host* vulkan.pc and
	# link a desktop libvulkan into the Android library.
	$(CREATE_EGL_PC)
	# meson option names/types differ between virglrenderer releases (venus vs
	# venus-experimental, drm as boolean or feature, ...), so the flags are
	# derived from what the checked out tree declares - an unknown -D option is
	# only a warning in meson, but a wrong *value* is a hard error.
	# Venus is the one option that follows USE_VIRGL_VENUS instead of being
	# forced off: QEMU asks virglrenderer for VIRGL_RENDERER_VENUS when the GL
	# device is started with ,venus=on (see VMExecutor#addGraphicsOptions).
	# Everything else found stays OFF - Android has no libdrm/gbm, and the venus
	# validation layer / DRM native contexts are desktop-only.  With venus the
	# render server (which venus switches on by itself) is told to service its
	# contexts from a thread: the default 'process' worker fork+execs
	# virgl_render_server, which does not exist inside the app sandbox.
	cd $(LIMBO_JNI_ROOT)/virglrenderer && \
	virgl_opts="-Dplatforms=egl"; \
	if [ "$(USE_VIRGL_VENUS)" = "true" ]; then venus_on=1; else venus_on=0; fi; \
	for opt in venus venus-experimental venus-validate drm drm-msm drm-msm-experimental minigbm_allocation tests; do \
		opt_found=$$(grep -cE "option\('$$opt'|^[[:space:]]*'$$opt'[[:space:]]*," meson_options.txt 2>/dev/null || true); \
		if [ "$$opt_found" != "0" ]; then \
			opt_type=$$(grep -A4 -E "option\('$$opt'|^[[:space:]]*'$$opt'[[:space:]]*," meson_options.txt 2>/dev/null | \
				sed -n "s/.*type *: *'\([a-z]*\)'.*/\1/p" | head -n1); \
			opt_on=0; \
			if [ "$$venus_on" = "1" ]; then \
				case "$$opt" in venus|venus-experimental) opt_on=1 ;; esac; \
			fi; \
			case "$$opt_type" in \
				feature) if [ "$$opt_on" = "1" ]; then virgl_opts="$$virgl_opts -D$$opt=enabled"; else virgl_opts="$$virgl_opts -D$$opt=disabled"; fi ;; \
				boolean) if [ "$$opt_on" = "1" ]; then virgl_opts="$$virgl_opts -D$$opt=true"; else virgl_opts="$$virgl_opts -D$$opt=false"; fi ;; \
			esac; \
		fi; \
	done; \
	rs_worker=$$(grep -cE "option\('render-server-worker'|^[[:space:]]*'render-server-worker'[[:space:]]*," meson_options.txt 2>/dev/null || true); \
	if [ "$$venus_on" = "1" ] && [ "$$rs_worker" != "0" ]; then \
		virgl_opts="$$virgl_opts -Drender-server-worker=thread"; \
	fi; \
	echo ">> virglrenderer meson options:$$virgl_opts"; \
	PKG_CONFIG_PATH="$(VIRGL_PKG_CONFIG_DIR):$(GTK_PKG_CONFIG_DIR):$(GLIB_PKG_CONFIG_DIR):$(PIXMAN_PKG_CONFIG_DIR):$(LIBFFI_PKG_CONFIG_DIR)" \
	PKG_CONFIG_LIBDIR="$(VIRGL_PKG_CONFIG_DIR):$(GTK_PKG_CONFIG_DIR):$(GLIB_PKG_CONFIG_DIR):$(PIXMAN_PKG_CONFIG_DIR):$(LIBFFI_PKG_CONFIG_DIR)" \
	meson setup "$(VIRGL_BUILD_DIR)" . \
		--cross-file "$(VIRGL_CROSS_FILE)" \
		$$virgl_opts

virgl: ndk config-virgl
	ninja -C "$(VIRGL_BUILD_DIR)" -j $(BUILD_THREADS)
	DESTDIR= ninja -C "$(VIRGL_BUILD_DIR)" install
	# deploy the renderer next to the GTK libs so the app can load it
	$(CREATE_LIBSDIR)
	cp -Lf $(VIRGL_INSTALL_DIR)/lib/libvirglrenderer.so* $(NDK_PROJECT_PATH)/jniLibs/$(APP_ABI_DIR)/

clean-virgl:
	-rm -rf "$(VIRGL_BUILD_DIR)" "$(VIRGL_INSTALL_DIR)" "$(VIRGL_CROSS_FILE)"

_qemu: ndk
	cd $(LIMBO_JNI_ROOT)/qemu && $(MAKE) all -j $(BUILD_THREADS) BUILD_GUEST=$(BUILD_GUEST)

config-qemu: ndk pkg-config-link fetch-qemu
	@if [ "$(USE_VIRGL)" = "true" ] && [ "$(USE_GTK)" != "true" ]; then \
		echo ">> WARNING: USE_VIRGL=true needs the libepoxy provided by the gtk4android"; \
		echo ">>          prefix (USE_GTK=true) - QEMU's -Dopengl=enabled fails without it"; \
	fi
	@if [ "$(USE_VIRGL)" = "true" ] && [ -z "$(VIRGL_MESON_FLAGS)" ]; then \
		echo ">> NOTE: this QEMU tree declares no virglrenderer/opengl meson option:"; \
		echo ">>       virtio-gpu-gl-pci will NOT be part of the build (set USE_VIRGL=false to silence)"; \
	fi
	$(MAKE) -f $(LIMBO_JNI_ROOT)/android-config/android-qemu-config.mak ACCEL_MESON_FLAGS="$(ACCEL_MESON_FLAGS)" AGL_MESON_FLAGS="$(AGL_MESON_FLAGS)" VIRGL_MESON_FLAGS="$(VIRGL_MESON_FLAGS)" SPICE_CONFIGURE_FLAG="$(SPICE_CONFIGURE_FLAG)"

clean-qemu:
	-cd $(LIMBO_JNI_ROOT)/qemu && $(MAKE) distclean
	-cd $(LIMBO_JNI_ROOT)/qemu && $(MAKE) clean
	-rm $(NDK_PROJECT_PATH)/obj/local/$(APP_ABI)/libqemu-system-x86_64.a
	-rm -rf ../assets/roms/* 
	-rm -rf $(LIMBO_APP_LIBS)/assets/roms/*

# virglrenderer 不在这里配置/构建：它依赖 GTK4 安装出来的 epoxy（见 config-virgl
# 注释），必须排在 deps（构建+安装 GTK4）之后，因此由 limbo 目标显式执行。
config-deps: ndk config-ffi config-glib config-pixman config-slirp $(SPICE_CONFIG_TARGET) $(if $(USE_GTK),config-gtk)

# virglrenderer 同上：由 limbo 在 GTK4 安装完成后显式构建
deps: ndk glib pixman slirp $(SPICE_DEPS_TARGET) $(if $(USE_GTK),gtk)

_build-qemu: ndk 
	make _qemu
	$(INSTALL_QEMU_LIBS)
	$(INSTALL_LIBS)
	$(RENAME_TUNCTL)
	$(RENAME_PARPROUTED)
	$(COPY_ROMS)
	$(COPY_KEYMAPS)
	$(COPY_QEMU_VERSION)
	$(COPY_RESOLV_CONF)

qemu: ndk
	make _build-qemu
	make install

ncat: ndk
	cd $(NCAT_BUILD_DIR) && ANDROID_NDK_ROOT="$(NDK_ROOT)" NCAT_OUT_DIR="$(NCAT_OUT_DIR)" bash make_ncat.sh "$(APP_ABI)"

clean-ncat:
	-rm -rf "$(NCAT_BUILD_DIR)/lib" $(NCAT_BUILD_DIR)/openssl-* $(NCAT_BUILD_DIR)/nmap-*.tgz $(NCAT_BUILD_DIR)/nmap-*
	-rm -f $(NCAT_OUT_DIR)/$(APP_ABI_DIR)/libncat.so

# EDK2 客户机固件（详见上方 EDK2 段与 edk2/make_edk2.sh）。
# 与 QEMU/ncat 不同，固件不依赖 NDK，也不需要先 make ndk。
# 【默认不编译】：USE_EDK2 默认 false，`make limbo` / `make fetch` 都不会碰它；
# 单独启用用 `make edk2`（或 export USE_EDK2=true 后走 `make limbo`）。
#
# 这里必须用【执行期】的 $(if ...) 而不是解析期 ifeq：EDK2_ARCHS 在文件下方才按
# BUILD_GUEST 推导，解析到这里时它还没赋值，ifeq 会把它误判为空而永远跳过。
# EDK2_ARCHS 为空（例如只选了 ia64-softmmu 客户机：上游无 IA64 平台）时只打印提示。
EDK2_SKIP_MSG = >> EDK2_ARCHS 为空（BUILD_GUEST=$(BUILD_GUEST)）：没有对应的 EDK2 客户机固件，跳过

fetch-edk2:
	$(if $(EDK2_ARCHS),$(EDK2_ENV) fetch $(EDK2_ARCHS),@echo "$(EDK2_SKIP_MSG)")

edk2:
	$(if $(EDK2_ARCHS),$(EDK2_ENV) build $(EDK2_ARCHS),@echo "$(EDK2_SKIP_MSG)")

# clean 不需要提示：EDK2_ARCHS 为空（ia64）时本来就没有产物可清，展开为空行即空操作
clean-edk2:
	$(if $(EDK2_ARCHS),$(EDK2_ENV) clean $(EDK2_ARCHS))

symbols:
	$(COLLECT_SYMBOLS)

clean-symbols:
	-rm -rf "$(SYMBOLS_DIR)"

install-common:
	$(CREATE_LIBSDIR)
	$(INSTALL_LIBS)
	$(STRIP_LIBS)
	$(COLLECT_SYMBOLS)

install:
	make install-common
	$(SYNC_LIBS_X86)
	$(SYNC_LIBS_ARM)
	$(SYNC_LIBS_IA64)

limbo:
	make fetch
	make ndk
	make config-deps
	make deps
# virglrenderer 显式排在 config-qemu 之前：QEMU 只有拿到 virglrenderer.pc 才能
# 用 -Dopengl=enabled -Dvirglrenderer=enabled 配出 virtio-gpu-gl-pci。
# config-virgl/virgl 本身也会被 config-deps/deps 条件带上，
# 这里显式列出是为了让依赖顺序一目了然、且 USE_VIRGL 被覆盖时也不会漏。
ifeq ($(USE_VIRGL),true)
	make config-virgl
	make virgl
endif
	make config-qemu
	make qemu
	make ncat
	make install
# EDK2 客户机固件：与 BUILD_HOST 无关，只为当前 BUILD_GUEST 对应的客户机架构
# 构建一份 UEFI 固件并部署到 assets/roms/。【默认不编译】：USE_EDK2 在
# android-limbo-config.mak 里默认为 false，需要时 export USE_EDK2=true。
ifeq ($(USE_EDK2),true)
	make edk2
endif

# ---------------------------------------------------------------------------
# 源码获取与补丁（把 build workflow 里的“下载代码 / 打补丁”收进 Makefile）
# 幂等：目标目录已就绪则跳过。qemu 按 BUILD_GUEST 自动选择：
#   x86/arm (i386/x86_64/arm/aarch64) -> 官方 QEMU + GTK4 补丁（qemu-v11.0.0.patch）
#   ia64（或空）                    -> IA-64 fork（FreshingAir/qemu-system-ia64）
# gtk4android 亦由此处克隆（不再以 git submodule 形式存在于仓库）。
# ---------------------------------------------------------------------------
QEMU_OFFICIAL_TAG   := v11.0.0
QEMU_OFFICIAL_URL   := https://gitlab.com/qemu-project/qemu.git
QEMU_OFFICIAL_PATCH := $(LIMBO_JNI_ROOT)/patches/qemu-v11.0.0.patch
QEMU_OFFICIAL_MARK  := $(LIMBO_JNI_ROOT)/qemu/.gtk4-official
QEMU_FORK_URL       := https://github.com/FreshingAir/qemu-system-ia64.git

# gzvm / gunyah 加速器补丁（合并版，AnyLaySys 上游移植到 QEMU 11.0.0）
ACCEL_PATCH         := $(LIMBO_JNI_ROOT)/patches/qemu-v11.0.0-gunyah-gzvm-accel.patch

# AGL（Android Graphics Layer）显示后端补丁：gunyah/gzvm 加速的 aarch64 虚拟机
# 必须把画面画到 App 自己的 Surface 上（-display agl），而独立 root 子进程拿不到
# Surface，因此还需要配合进程内提权（见 RootUtils.grantRoot）。该后端依赖
# epoxy/EGL，即 USE_GTK/USE_VIRGL 提供的 OpenGL 栈。同样只适用于官方 QEMU 树。
AGL_PATCH           := $(LIMBO_JNI_ROOT)/patches/qemu-v11.0.0-agl-display.patch
AGL_MESON_FLAGS     := -Dagl=enabled
AGL_APPLY           := yes

# Android EGL 初始化补丁：把系统 libEGL 的 surfaceless GLES3 上下文接进
# ui/egl-helpers.c，供 USE_VIRGL=true（-Dopengl=enabled -Dvirglrenderer=enabled）
# 时的 virtio-gpu-gl / virglrenderer 使用。仅适用于官方 QEMU 树
# （IA-64 fork 关闭 VirGL/OpenGL，无需该补丁）。
EGL_PATCH           := $(LIMBO_JNI_ROOT)/patches/qemu-v11.0.0-android-egl.patch
EGL_APPLY           := yes

# SDL 显示方式（缩放模式）补丁：给 ui/sdl2-2d.c / ui/sdl2-gl.c 加上
# limbo_sdl_scale_mode（0=拉伸至全屏 1=等比缩放 2=1:1），由 Java 侧经
# set_qemu_var() 下发，取值与 GTK 后端的 limbo_gtk_scale_mode 一致。
# 只改 sdl2*，不依赖主补丁的上下文，因此单独成一个补丁文件。
SDL_SCALE_PATCH     := $(LIMBO_JNI_ROOT)/patches/qemu-v11.0.0-limbo-sdl-scale.patch
SDL_SCALE_APPLY     := yes

# venus（Vulkan over VirGL）诊断补丁：在 hw/display/virtio-gpu-virgl.c 的
# virgl_cmd_resource_create_blob() 里无条件打印一行 create_blob 的请求参数
# （res / ctx_id / blob_mem / blob_flags / blob_id / size / nr_entries）。
# 用途：客户机里 "venus 起不来"（vkmark/vkcube 报 vkCreateInstance failed
# with ERROR_OUT_OF_HOST_MEMORY）时，QEMU 只会打印
# "virgl blob create error: <errno>"，看不到它到底收到了什么参数；有这一行
# 就能直接判断是哪个字段不被 virglrenderer 接受。
# 只加日志、不改行为；定位完把 VIRGL_BLOB_LOG_APPLY 置空（或删掉本补丁）即可停用。
VIRGL_BLOB_LOG_PATCH := $(LIMBO_JNI_ROOT)/patches/qemu-v11.0.0-virgl-blob-log.patch
VIRGL_BLOB_LOG_APPLY := yes

# 是否构建并启用 gzvm / gunyah 加速器（默认开启；可通过
#  export BUILD_ACCEL=gunyah / gzvm / both / none 覆盖）
# 映射成 config-qemu 里传给 ./configure 的 -Dgunyah/-Dgzvm meson 特性
ifeq ($(BUILD_ACCEL), none)
ACCEL_MESON_FLAGS    :=
ACCEL_APPLY          :=
else ifeq ($(BUILD_ACCEL), gunyah)
ACCEL_MESON_FLAGS    := -Dgunyah=enabled -Dgzvm=disabled
ACCEL_APPLY          := yes
else ifeq ($(BUILD_ACCEL), gzvm)
ACCEL_MESON_FLAGS    := -Dgunyah=disabled -Dgzvm=enabled
ACCEL_APPLY          := yes
else
ACCEL_MESON_FLAGS    := -Dgunyah=enabled -Dgzvm=enabled
ACCEL_APPLY          := yes
endif
QEMU_FORK_BRANCH    := main
# IA-64 fork 仍缺的 Android 适配（官方树的 qemu-v11.0.0.patch 已有、fork 未合入）：
#   - 强制 HAVE_COPY_FILE_RANGE，让 block/file-posix.c 走 limbo_compat_stubs.c
#     的实现而不是自己编一份 static copy_file_range()（会与 limbo_compat.h 的
#     原型冲突）
#   - spice-server >=0.15 是 C++ 静态库，必须显式链接 NDK libc++ 运行时，否则
#     libspice-server.a 的 __cxxabiv1::* / __cxa_pure_virtual 无人解析
#   - Android 交叉构建跳过整个 tests/：宿主测试二进制既不能在设备上运行，也编不过
QEMU_FORK_PATCH     := $(LIMBO_JNI_ROOT)/patches/qemu-ia64-fork-android.patch
# virglrenderer (host side of QEMU's virtio-gpu-gl-pci / CONFIG_VIRGL).
# Override the tag with e.g. export VIRGL_TAG=1.1.1 to follow upstream.
VIRGL_TAG           ?= 1.1.0
VIRGL_URL           := https://gitlab.freedesktop.org/virgl/virglrenderer.git
# Android 上 virglrenderer 必须带 EGL winsys：它没有 gbm/libdrm，上游 meson 的
# 通用分支（have_egl = gbm_dep.found() and ...）会静默把 EGL 关掉、
# vrend_winsys_egl.c 不进编译，于是 vrend_winsys_init_external() 直接报
# "EGL is not supported on this platform"，virtio-gpu-gl-pci 无法渲染。
# 该补丁把 Android 并入 Windows/macOS 那条 have_egl = true 分支（QEMU 通过
# get_egl_display 回调把自己的 EGLDisplay 交给它，不需要 gbm/render node）。
VIRGL_PATCH         := $(LIMBO_JNI_ROOT)/patches/virglrenderer-android-egl.patch
GTK4_URL            := https://github.com/SlugAir928437/gtk4android.git
GTK4_BRANCH         := master
GLIB_URL            := https://gitlab.gnome.org/GNOME/glib.git
GLIB_COMMIT         := ed3abf0b5221d613a333ed5b118b2f2acc66acd3
LIBFFI_TGZ          := https://github.com/libffi/libffi/releases/download/v3.5.2/libffi-3.5.2.tar.gz
PIXMAN_TGZ          := https://www.cairographics.org/releases/pixman-0.43.4.tar.gz
PIXMAN_TGZ_ALT      := https://github.com/mesonbuild/wrapdb/releases/download/pixman_0.43.4-1/pixman-0.43.4.tar.gz
SDL2_TGZ            := https://www.libsdl.org/release/SDL2-2.0.8.tar.gz
SLIRP_URL            := https://gitlab.freedesktop.org/slirp/libslirp.git
# revision pinned in qemu/subprojects/slirp.wrap (libslirp 4.7.0)
SLIRP_COMMIT         := 26be815b86e8d49add8c9a8b320239b9594ff03d
# spice stack tarballs (config-spice deps: spice-server vendors spice-common)
OPENSSL_TGZ          := https://www.openssl.org/source/openssl-3.0.16.tar.gz
LIBJPEG_TGZ          := https://github.com/libjpeg-turbo/libjpeg-turbo/releases/download/3.0.4/libjpeg-turbo-3.0.4.tar.gz
SPICE_PROTO_TGZ      := https://www.spice-space.org/download/releases/spice-protocol-0.14.5.tar.xz
SPICE_TGZ            := https://www.spice-space.org/download/releases/spice-0.15.2.tar.bz2

# x86/arm（非 ia64）guest 使用官方 QEMU；其余（或未设置）用 IA-64 fork
ifneq ($(filter x86_64-softmmu i386-softmmu arm-softmmu aarch64-softmmu,$(BUILD_GUEST)),)
QEMU_USE_OFFICIAL := yes
else
QEMU_USE_OFFICIAL := no
endif

# gzvm / gunyah 加速器、Android EGL 初始化、venus blob 诊断补丁仅适用于官方
# QEMU 11.0.0 树：IA-64 fork 无对应 meson 选项，传入 -Dgunyah/-Dgzvm 会导致
# "Unknown option" 配置失败，补丁也不应打到 fork 树上。
ifneq ($(QEMU_USE_OFFICIAL), yes)
ACCEL_MESON_FLAGS    :=
ACCEL_APPLY          :=
AGL_MESON_FLAGS      :=
AGL_APPLY            :=
EGL_APPLY            :=
VIRGL_BLOB_LOG_APPLY :=
endif

# VirtIO GPU GL (virtio-gpu-gl-pci): hw/display/meson.build only builds
# virtio-gpu-pci-gl.c when meson found both virglrenderer and epoxy/EGL, so
# QEMU must be configured with -Dopengl=enabled -Dvirglrenderer=enabled.
#  - the flags are only passed when the checked out QEMU tree really declares
#    those meson options (official 11.0.0 tree and the IA-64 fork both do),
#    otherwise ./configure would abort with "Unknown option";
#  - recursive '=' on purpose: the tree check runs when the config-qemu recipe
#    is expanded, i.e. after fetch-qemu has downloaded the source tree.
#  - the option names are matched without the "option('...')" punctuation: a
#    bare '(' inside a $(shell ...) argument unbalances make's paren matching
#    and aborts with "unterminated call to function 'if'".
VIRGL_MESON_FLAGS = $(if $(filter true,$(USE_VIRGL)),$(shell if grep -q virglrenderer "$(LIMBO_JNI_ROOT)/qemu/meson_options.txt" 2>/dev/null && grep -q opengl "$(LIMBO_JNI_ROOT)/qemu/meson_options.txt" 2>/dev/null; then echo -Dopengl=enabled -Dvirglrenderer=enabled; fi))

# ---------------------------------------------------------------------------
# EDK2（TianoCore）客户机 UEFI 固件
#
# 为各客户机架构产出 QEMU 可直接 -bios 使用的 UEFI 固件，部署到 assets/roms/：
#   x86(IA32)   -> edk2-i386-code.fd      x86_64(X64)      -> edk2-x86_64-code.fd
#   Arm(ARM)    -> edk2-arm-code.fd       Aarch64(AARCH64) -> edk2-aarch64-code.fd
#
# 标签固定 edk2-stable202508（理由详见 edk2/make_edk2.sh 头部说明）：它是最后一
# 个同时保留【纯 32 位 x86 的 OvmfPkgIa32.dsc】与【32 位 ARM 支持（ArmVirtQemu
# 的 SUPPORTED_ARCHITECTURES 含 ARM）】的稳定版；202511 起上游已先后移除二者。
#
# 不构建 IA64：上游 edk2 已于 2019 年移除 IPF，且仍含 IPF 的 edk2-stable201808
# 与 UDK2018 也没有任何可构建的 IA64 平台（同样详见 edk2/make_edk2.sh 头部说明）。
# 因此 ia64-softmmu 客户机的 EDK2_ARCHS 为空，edk2 目标整体跳过。
#
# 固件与 BUILD_HOST 无关、只与客户机架构有关。默认【不编译 EDK2】（USE_EDK2=false，
# 见 android-limbo-config.mak）；启用后只构建当前 BUILD_GUEST 对应的那一个架构，
# 需要一次构建全部时用 EDK2_ARCHS="IA32 X64 ARM AARCH64"。
# ---------------------------------------------------------------------------
EDK2_DIR          := $(LIMBO_JNI_ROOT)/edk2
EDK2_SCRIPT       := $(EDK2_DIR)/make_edk2.sh
EDK2_FIRMWARE_DIR := $(LIMBO_APP_LIBS)/assets/roms
EDK2_GIT_URL      ?= https://github.com/tianocore/edk2.git
EDK2_TAG          ?= edk2-stable202508
# edk2 工具链标签。默认 GCCNOLTO（非 LTO）：GCC5/GCC 带 -flto，在很新的 binutils
# （例如 Ubuntu 26.04 的 2.46）下链接超大 LTO 归档会失败
# （ld.bfd: .../OpensslLibCrypto.lib: ELF section name out of range），
# GCCNOLTO 不产生 .gnu.lto_* 节，可稳定构建。需要 LTO 时可 export EDK2_TOOLCHAIN=GCC5。
EDK2_TOOLCHAIN    ?= GCCNOLTO

# BUILD_GUEST 可以是逗号分隔的多客户机列表（见 make all 的 EXPORT_GUEST_ALL，例如
# x86_64-softmmu,aarch64-softmmu,ia64-softmmu），也可能是单个；先拆成空格分隔，
# 再逐项映射到 EDK2 架构。ia64-softmmu 不映射：上游没有可构建的 IA64 平台。
EDK2_COMMA := ,
EDK2_EMPTY :=
EDK2_SPACE := $(EDK2_EMPTY) $(EDK2_EMPTY)
EDK2_GUESTS := $(subst $(EDK2_COMMA),$(EDK2_SPACE),$(strip $(BUILD_GUEST)))

ifeq ($(strip $(EDK2_GUESTS)),)
# 未指定 BUILD_GUEST：默认构建全部四个架构
EDK2_ARCHS_DERIVED := IA32 X64 ARM AARCH64
else
EDK2_ARCHS_DERIVED := $(strip $(if $(filter x86_64-softmmu,$(EDK2_GUESTS)),X64) \
	$(if $(filter i386-softmmu,$(EDK2_GUESTS)),IA32) \
	$(if $(filter arm-softmmu,$(EDK2_GUESTS)),ARM) \
	$(if $(filter aarch64-softmmu,$(EDK2_GUESTS)),AARCH64))
endif

# 可用命令行/环境覆盖（export EDK2_ARCHS="IA32 X64 ARM AARCH64"）；派生结果为空
# （只选了 ia64 客户机）时 edk2 / fetch-edk2 会跳过。
ifndef EDK2_ARCHS
EDK2_ARCHS := $(EDK2_ARCHS_DERIVED)
endif

EDK2_ENV = EDK2_FIRMWARE_DIR="$(EDK2_FIRMWARE_DIR)" EDK2_GIT_URL="$(EDK2_GIT_URL)" \
	EDK2_TAG="$(EDK2_TAG)" EDK2_TOOLCHAIN="$(EDK2_TOOLCHAIN)" \
	BUILD_THREADS="$(BUILD_THREADS)" bash "$(EDK2_SCRIPT)"

fetch: fetch-glib fetch-libffi fetch-pixman fetch-sdl2 fetch-slirp $(SPICE_FETCH_TARGETS) fetch-gtk4android fetch-virgl fetch-qemu $(if $(filter true,$(USE_EDK2)),fetch-edk2)

fetch-virgl:
	@if [ "$(USE_VIRGL)" != "true" ]; then \
		echo ">> USE_VIRGL=$(USE_VIRGL)，跳过 virglrenderer"; \
	elif [ ! -f "$(LIMBO_JNI_ROOT)/virglrenderer/meson.build" ]; then \
		echo ">> GET virglrenderer $(VIRGL_TAG)"; \
		rm -rf "$(LIMBO_JNI_ROOT)/virglrenderer"; \
		git clone --depth 1 --branch $(VIRGL_TAG) --single-branch $(VIRGL_URL) "$(LIMBO_JNI_ROOT)/virglrenderer"; \
	else \
		echo ">> virglrenderer 已存在，跳过"; \
	fi
	@if [ "$(USE_VIRGL)" = "true" ] && [ -f "$(LIMBO_JNI_ROOT)/virglrenderer/meson.build" ] && \
	    git -C "$(LIMBO_JNI_ROOT)/virglrenderer" apply --check "$(VIRGL_PATCH)" 2>/dev/null; then \
		echo ">> 应用 virglrenderer Android EGL 补丁"; \
		git -C "$(LIMBO_JNI_ROOT)/virglrenderer" apply "$(VIRGL_PATCH)"; \
	fi

fetch-slirp:
	@if [ ! -d "$(LIMBO_JNI_ROOT)/slirp/.git" ] || [ ! -f "$(LIMBO_JNI_ROOT)/slirp/meson.build" ]; then \
		echo ">> GET libslirp @ $(SLIRP_COMMIT)"; \
		rm -rf "$(LIMBO_JNI_ROOT)/slirp"; \
		git clone $(SLIRP_URL) "$(LIMBO_JNI_ROOT)/slirp" && \
		git -C "$(LIMBO_JNI_ROOT)/slirp" checkout "$(SLIRP_COMMIT)" && \
		git -C "$(LIMBO_JNI_ROOT)/slirp" apply "$(LIMBO_JNI_ROOT)/patches/libslirp-cross-build.patch"; \
	else \
		echo ">> libslirp 已存在，跳过"; \
	fi

fetch-openssl:
	@if [ ! -f "$(LIMBO_JNI_ROOT)/openssl/Configure" ]; then \
		echo ">> GET openssl 3.0.16"; \
		rm -f /tmp/openssl-3.0.16.tar.gz; \
		wget --quiet --timeout=60 $(OPENSSL_TGZ) -O /tmp/openssl-3.0.16.tar.gz; \
		rm -rf /tmp/openssl-3.0.16; \
		tar -xzf /tmp/openssl-3.0.16.tar.gz -C /tmp && \
		mv -f /tmp/openssl-3.0.16 "$(LIMBO_JNI_ROOT)/openssl"; \
	else \
		echo ">> openssl 已存在，跳过"; \
	fi

fetch-libjpeg:
	@if [ ! -f "$(LIMBO_JNI_ROOT)/libjpeg/CMakeLists.txt" ]; then \
		echo ">> GET libjpeg-turbo 3.0.4"; \
		rm -f /tmp/libjpeg-turbo-3.0.4.tar.gz; \
		wget --quiet --timeout=60 $(LIBJPEG_TGZ) -O /tmp/libjpeg-turbo-3.0.4.tar.gz; \
		rm -rf /tmp/libjpeg-turbo-3.0.4; \
		tar -xzf /tmp/libjpeg-turbo-3.0.4.tar.gz -C /tmp && \
		mv -f /tmp/libjpeg-turbo-3.0.4 "$(LIMBO_JNI_ROOT)/libjpeg"; \
	else \
		echo ">> libjpeg 已存在，跳过"; \
	fi

fetch-spice-protocol:
	@if [ ! -f "$(LIMBO_JNI_ROOT)/spice-protocol/meson.build" ]; then \
		echo ">> GET spice-protocol 0.14.5"; \
		rm -f /tmp/spice-protocol-0.14.5.tar.xz; \
		wget --quiet --timeout=60 $(SPICE_PROTO_TGZ) -O /tmp/spice-protocol-0.14.5.tar.xz; \
		rm -rf /tmp/spice-protocol-0.14.5; \
		tar -xJf /tmp/spice-protocol-0.14.5.tar.xz -C /tmp && \
		mv -f /tmp/spice-protocol-0.14.5 "$(LIMBO_JNI_ROOT)/spice-protocol"; \
	else \
		echo ">> spice-protocol 已存在，跳过"; \
	fi

fetch-spice:
	@if [ ! -f "$(LIMBO_JNI_ROOT)/spice/meson.build" ]; then \
		echo ">> GET spice-server 0.15.2"; \
		rm -f /tmp/spice-0.15.2.tar.bz2; \
		wget --quiet --timeout=60 $(SPICE_TGZ) -O /tmp/spice-0.15.2.tar.bz2; \
		rm -rf /tmp/spice-0.15.2; \
		tar -xjf /tmp/spice-0.15.2.tar.bz2 -C /tmp && \
		mv -f /tmp/spice-0.15.2 "$(LIMBO_JNI_ROOT)/spice"; \
		cd $(LIMBO_JNI_ROOT)/spice && test -f .tarball-version || echo "0.15.2" > .tarball-version; \
		echo ">> Applying spice-android.patch"; \
		patch -p1 -d $(LIMBO_JNI_ROOT)/spice < $(LIMBO_JNI_ROOT)/patches/spice-android.patch; \
	else \
		echo ">> spice 已存在，跳过"; \
	fi

fetch-glib:
	@if [ ! -d "$(LIMBO_JNI_ROOT)/glib/.git" ] || [ ! -f "$(LIMBO_JNI_ROOT)/glib/meson.build" ]; then \
		echo ">> GET glib @ $(GLIB_COMMIT)"; \
		rm -rf "$(LIMBO_JNI_ROOT)/glib"; \
		git init "$(LIMBO_JNI_ROOT)/glib" && \
		git -C "$(LIMBO_JNI_ROOT)/glib" remote add origin $(GLIB_URL) && \
		git -C "$(LIMBO_JNI_ROOT)/glib" fetch --depth 1 origin $(GLIB_COMMIT) && \
		git -C "$(LIMBO_JNI_ROOT)/glib" checkout FETCH_HEAD && \
		git -C "$(LIMBO_JNI_ROOT)/glib" apply "$(LIMBO_JNI_ROOT)/patches/glib-2.89.3-android.patch"; \
	else \
		echo ">> glib 已存在，跳过"; \
	fi

fetch-libffi:
	@if [ ! -f "$(LIMBO_JNI_ROOT)/libffi/configure.ac" ]; then \
		echo ">> GET libffi 3.5.2"; \
		rm -f /tmp/libffi-3.5.2.tar.gz; \
		wget --quiet --timeout=60 $(LIBFFI_TGZ) -O /tmp/libffi-3.5.2.tar.gz || \
			wget --quiet --timeout=60 $(LIBFFI_TGZ) -O /tmp/libffi-3.5.2.tar.gz; \
		rm -rf /tmp/libffi-3.5.2; \
		tar -xzf /tmp/libffi-3.5.2.tar.gz -C /tmp && \
		mv -f /tmp/libffi-3.5.2 "$(LIMBO_JNI_ROOT)/libffi"; \
	else \
		echo ">> libffi 已存在，跳过"; \
	fi

fetch-pixman:
	@if [ ! -f "$(LIMBO_JNI_ROOT)/pixman/meson.build" ]; then \
		echo ">> GET pixman 0.43.4"; \
		rm -f /tmp/pixman-0.43.4.tar.gz; \
		wget --quiet --timeout=60 $(PIXMAN_TGZ) -O /tmp/pixman-0.43.4.tar.gz || \
			wget --quiet --timeout=60 $(PIXMAN_TGZ_ALT) -O /tmp/pixman-0.43.4.tar.gz; \
		rm -rf /tmp/pixman-0.43.4; \
		tar -xzf /tmp/pixman-0.43.4.tar.gz -C /tmp && \
		mv -f /tmp/pixman-0.43.4 "$(LIMBO_JNI_ROOT)/pixman"; \
	else \
		echo ">> pixman 已存在，跳过"; \
	fi

fetch-sdl2:
	@if [ ! -f "$(LIMBO_JNI_ROOT)/SDL2/configure" ]; then \
		echo ">> GET SDL2 2.0.8"; \
		rm -f /tmp/SDL2-2.0.8.tar.gz; \
		wget --quiet --timeout=60 $(SDL2_TGZ) -O /tmp/SDL2-2.0.8.tar.gz || \
			wget --quiet --timeout=60 $(SDL2_TGZ) -O /tmp/SDL2-2.0.8.tar.gz; \
		rm -rf /tmp/SDL2-2.0.8; \
		tar -xzf /tmp/SDL2-2.0.8.tar.gz -C /tmp && \
		mv -f /tmp/SDL2-2.0.8 "$(LIMBO_JNI_ROOT)/SDL2" && \
		( cd "$(LIMBO_JNI_ROOT)/SDL2" && patch -p1 < "$(LIMBO_JNI_ROOT)/patches/sdl2-2.0.8-android.patch" ) && \
		( cd "$(LIMBO_JNI_ROOT)/SDL2" && patch -p1 < "$(LIMBO_JNI_ROOT)/patches/sdl2-2.0.8-android-silent-capture.patch" ); \
	else \
		echo ">> SDL2 已存在，跳过"; \
	fi

fetch-qemu:
	@echo ">> fetch-qemu: BUILD_GUEST='$(BUILD_GUEST)' 使用官方?=$(QEMU_USE_OFFICIAL)"
	@if [ "$(QEMU_USE_OFFICIAL)" = "yes" ]; then \
		if [ ! -f "$(QEMU_OFFICIAL_MARK)" ]; then \
			if [ ! -f "$(QEMU_OFFICIAL_PATCH)" ]; then \
				echo "ERROR: $(QEMU_OFFICIAL_PATCH) 缺失，需 git add 提交后再构建"; exit 1; \
			fi; \
			echo ">> x86/arm guest: 下载官方 QEMU $(QEMU_OFFICIAL_TAG) + 应用 GTK4 补丁"; \
			rm -rf "$(LIMBO_JNI_ROOT)/qemu"; \
			git clone --depth 1 --branch $(QEMU_OFFICIAL_TAG) --single-branch $(QEMU_OFFICIAL_URL) "$(LIMBO_JNI_ROOT)/qemu" && \
			git -C "$(LIMBO_JNI_ROOT)/qemu" apply "$(QEMU_OFFICIAL_PATCH)" && \
			touch "$(QEMU_OFFICIAL_MARK)"; \
		fi; \
		echo ">> official qemu 就绪（GTK4 补丁已应用）"; \
	else \
		if [ ! -d "$(LIMBO_JNI_ROOT)/qemu/.git" ]; then \
			echo ">> ia64 guest: 下载 IA-64 fork $(QEMU_FORK_BRANCH)"; \
			rm -rf "$(LIMBO_JNI_ROOT)/qemu"; \
			git clone --depth 1 --branch $(QEMU_FORK_BRANCH) --single-branch $(QEMU_FORK_URL) "$(LIMBO_JNI_ROOT)/qemu"; \
		else \
			echo ">> qemu fork 已存在，跳过"; \
		fi; \
		if [ ! -f "$(QEMU_FORK_PATCH)" ]; then \
			echo "ERROR: $(QEMU_FORK_PATCH) 缺失，需 git add 提交后再构建"; exit 1; \
		fi; \
		if git -C "$(LIMBO_JNI_ROOT)/qemu" apply --check "$(QEMU_FORK_PATCH)" 2>/dev/null; then \
			echo ">> qemu fork: 应用 copy_file_range 补丁"; \
			git -C "$(LIMBO_JNI_ROOT)/qemu" apply "$(QEMU_FORK_PATCH)"; \
		else \
			echo ">> qemu fork: copy_file_range 补丁已应用或无需应用"; \
		fi; \
	fi
	$(if $(ACCEL_APPLY),@if [ -f "$(ACCEL_PATCH)" ] && git -C "$(LIMBO_JNI_ROOT)/qemu" apply --check "$(ACCEL_PATCH)" 2>/dev/null; then echo ">> 应用 gunyah/gzvm 加速器合并补丁"; git -C "$(LIMBO_JNI_ROOT)/qemu" apply "$(ACCEL_PATCH)"; fi;)
	$(if $(AGL_APPLY),@if [ -f "$(AGL_PATCH)" ] && git -C "$(LIMBO_JNI_ROOT)/qemu" apply --check "$(AGL_PATCH)" 2>/dev/null; then echo ">> 应用 AGL 显示后端补丁"; git -C "$(LIMBO_JNI_ROOT)/qemu" apply "$(AGL_PATCH)"; fi;)
	$(if $(EGL_APPLY),@if [ -f "$(EGL_PATCH)" ] && git -C "$(LIMBO_JNI_ROOT)/qemu" apply --check "$(EGL_PATCH)" 2>/dev/null; then echo ">> 应用 Android EGL 初始化补丁"; git -C "$(LIMBO_JNI_ROOT)/qemu" apply "$(EGL_PATCH)"; fi;)
	$(if $(SDL_SCALE_APPLY),@if [ -f "$(SDL_SCALE_PATCH)" ] && git -C "$(LIMBO_JNI_ROOT)/qemu" apply --check "$(SDL_SCALE_PATCH)" 2>/dev/null; then echo ">> 应用 SDL 显示方式（缩放模式）补丁"; git -C "$(LIMBO_JNI_ROOT)/qemu" apply "$(SDL_SCALE_PATCH)"; fi;)
	$(if $(VIRGL_BLOB_LOG_APPLY),@if [ -f "$(VIRGL_BLOB_LOG_PATCH)" ] && git -C "$(LIMBO_JNI_ROOT)/qemu" apply --check "$(VIRGL_BLOB_LOG_PATCH)" 2>/dev/null; then echo ">> 应用 venus blob 请求诊断日志补丁"; git -C "$(LIMBO_JNI_ROOT)/qemu" apply "$(VIRGL_BLOB_LOG_PATCH)"; fi;)

fetch-gtk4android:
	@if [ ! -d "$(LIMBO_JNI_ROOT)/gtk4android/.git" ]; then \
		echo ">> GET gtk4android @ $(GTK4_BRANCH)"; \
		rm -rf "$(LIMBO_JNI_ROOT)/gtk4android"; \
		git clone --depth 1 --branch $(GTK4_BRANCH) --single-branch $(GTK4_URL) "$(LIMBO_JNI_ROOT)/gtk4android"; \
	else \
		echo ">> gtk4android 已存在，跳过"; \
	fi

EXPORT_GUEST_ALL=export BUILD_GUEST=x86_64-softmmu,aarch64-softmmu,ia64-softmmu

#most common combinations
all:
	make distclean && $(EXPORT_GUEST_ALL) && export BUILD_HOST=arm64-v8a && make limbo
	make distclean && $(EXPORT_GUEST_ALL) && export BUILD_HOST=armeabi-v7a && make limbo
	make distclean && $(EXPORT_GUEST_ALL) && export BUILD_HOST=x86 && make limbo
	make distclean && $(EXPORT_GUEST_ALL) && export BUILD_HOST=x86_64 && make limbo

ndk-gdb:
	@echo APP_ABI=$(APP_ABI)
	$(COPY_GDB_SETUP_LIBS)
	$(ndkgdb) --attach $(PKG_NAME) --verbose -t

distclean: clean-qemu clean-ffi clean-glib clean-pixman clean-slirp clean-spice clean-gtk clean-virgl clean-ncat clean-edk2 clean-symbols clean-ndk

clean: 
	make clean-qemu 
	make clean-ffi 
	make clean-glib 
	make clean-pixman
	make clean-slirp
	make clean-spice
	make clean-gtk
	make clean-virgl
	make clean-edk2
	make clean-symbols
	make clean-ndk
	# only remove build artifacts (files), never directories -- the old -name
	# "*.d" pattern also matched any dir whose basename ends in ".d" (e.g.
	# subprojects/fontconfig/conf.d) and wiped it with rm -rf.
	-find . -type f -name "*.d" -exec rm -f {} \;
	-find . -type f -name "*.a" -exec rm -f {} \;
	-find . -type f -name "*.o" -exec rm -f {} \;
	-find . -type f -name "*.so" -exec rm -f {} \;
	$(MAKE) createdirs
	
help:
	@echo " Usage: export BUILD_GUEST=<emu-arch>-softmmu && export BUILD_HOST=<eabi> && make limbo"
	@echo "  emu-arch (emulator): x86_64, aarch64"
	@echo "  eabi (device): armeabi-v7a, arm64-v8a, x86, x86_64"
	@echo "  optional: export NDK_DEBUG=1 for debuggable binaries"
	@echo "  optional: make symbols 把所有 .so 构建产物的符号表输出到 obj/symbols/<abi>/"
	@echo "           （SYMBOLS_KEEP_UNSTRIPPED=false 可只导出符号表文本、不复制 .so）"
	@echo "  optional: make edk2 构建当前 BUILD_GUEST 对应的 EDK2 UEFI 固件到 assets/roms/"
	@echo "           （默认不编译 EDK2；USE_EDK2=true 时 make limbo 才会带上它。"
	@echo "            EDK2_ARCHS=\"IA32 X64 ARM AARCH64\" 可一次构建全部；IA64 不在范围内，见 edk2/make_edk2.sh）"
	@echo " example: export BUILD_GUEST=x86_64-softmmu && export BUILD_HOST=armeabi-v7a && make limbo"
