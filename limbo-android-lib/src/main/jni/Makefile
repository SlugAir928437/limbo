include android-limbo-build.mak

ifeq ($(NDK_ENV), windows)
	NDK_BUILD = ndk-build.cmd
else ifeq ($(NDK_ENV), windows-x86_64)
	NDK_BUILD = ndk-build.cmd
else
	NDK_BUILD = ndk-build
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

INSTALL_QEMU_LIBS=for target in x86_64 aarch64 ia64; do \
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
LIMBO_APP_LIBS=$(LIMBO_PRJ_ROOT)/limbo-android-lib/src/main

# ncat (Nmap's netcat) - cross-compiled per BUILD_HOST ABI into jniLibs so
# Android bundles it as libncat.so (executed at runtime, not dlopen'ed).
NCAT_BUILD_DIR=$(LIMBO_JNI_ROOT)/ncat
NCAT_OUT_DIR=$(NDK_PROJECT_PATH)/jniLibs

SYNC_LIBS_X86=rsync -av --include='libqemu-system-x86*.so' \
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
else
	LIBFFI_DEBUG = --disable-debug
    LIBGLIB_BUILDTYPE = release
    LIBPIXMAN_DEBUG =
    GTK_BUILDTYPE = release
endif

export

.PHONY: limbo clean distclean clean-ndk createdirs pkg-config-link clean-ffi clean-glib clean-qemu clean-pixman clean-gtk ncat clean-ncat fetch fetch-glib fetch-libffi fetch-pixman fetch-sdl2 fetch-qemu fetch-gtk4android

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

_qemu: ndk
	cd $(LIMBO_JNI_ROOT)/qemu && $(MAKE) all -j $(BUILD_THREADS) BUILD_GUEST=$(BUILD_GUEST) CFLAGS="$(ARCH_CFLAGS)"

config-qemu: ndk pkg-config-link
	$(MAKE) -f $(LIMBO_JNI_ROOT)/android-config/android-qemu-config.mak

clean-qemu:
	-cd $(LIMBO_JNI_ROOT)/qemu && $(MAKE) distclean
	-cd $(LIMBO_JNI_ROOT)/qemu && $(MAKE) clean
	-rm $(NDK_PROJECT_PATH)/obj/local/$(APP_ABI)/libqemu-system-x86_64.a
	-rm -rf ../assets/roms/* 
	-rm -rf $(LIMBO_APP_LIBS)/assets/roms/*

config-deps: ndk config-ffi config-glib config-pixman $(if $(USE_GTK),config-gtk)

deps: ndk glib pixman $(if $(USE_GTK),gtk)

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

install-common:
	$(CREATE_LIBSDIR)
	$(INSTALL_LIBS)
	$(STRIP_LIBS)

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
	make config-qemu
	make qemu
	make ncat
	make install

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
QEMU_FORK_BRANCH    := main
GTK4_URL            := https://github.com/SlugAir928437/gtk4android.git
GTK4_BRANCH         := master
GLIB_URL            := https://gitlab.gnome.org/GNOME/glib.git
GLIB_COMMIT         := ed3abf0b5221d613a333ed5b118b2f2acc66acd3
LIBFFI_TGZ          := https://github.com/libffi/libffi/releases/download/v3.5.2/libffi-3.5.2.tar.gz
PIXMAN_TGZ          := https://www.cairographics.org/releases/pixman-0.43.4.tar.gz
PIXMAN_TGZ_ALT      := https://github.com/mesonbuild/wrapdb/releases/download/pixman_0.43.4-1/pixman-0.43.4.tar.gz
SDL2_TGZ            := https://www.libsdl.org/release/SDL2-2.0.8.tar.gz

# x86/arm（非 ia64）guest 使用官方 QEMU；其余（或未设置）用 IA-64 fork
ifneq ($(filter x86_64-softmmu i386-softmmu arm-softmmu aarch64-softmmu,$(BUILD_GUEST)),)
QEMU_USE_OFFICIAL := yes
else
QEMU_USE_OFFICIAL := no
endif

fetch: fetch-glib fetch-libffi fetch-pixman fetch-sdl2 fetch-gtk4android fetch-qemu

fetch-glib:
	@if [ ! -d "$(LIMBO_JNI_ROOT)/glib/.git" ] || [ ! -f "$(LIMBO_JNI_ROOT)/glib/meson.build" ]; then \
		echo ">> GET glib @ $(GLIB_COMMIT)"; \
		rm -rf "$(LIMBO_JNI_ROOT)/glib"; \
		git init "$(LIMBO_JNI_ROOT)/glib" && \
		git -C "$(LIMBO_JNI_ROOT)/glib" remote add origin $(GLIB_URL) && \
		git -C "$(LIMBO_JNI_ROOT)/glib" fetch --depth 1 origin $(GLIB_COMMIT) && \
		git -C "$(LIMBO_JNI_ROOT)/glib" checkout FETCH_HEAD && \
		git -C "$(LIMBO_JNI_ROOT)/glib" apply "$(LIMBO_JNI_ROOT)/patches/glib-2.89.3.patch"; \
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
		( cd "$(LIMBO_JNI_ROOT)/SDL2" && patch -p1 < "$(LIMBO_JNI_ROOT)/patches/sdl2-2.0.8.patch" ); \
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
	fi

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

distclean: clean-qemu clean-ffi clean-glib clean-pixman clean-gtk clean-ncat clean-ndk

clean: 
	make clean-qemu 
	make clean-ffi 
	make clean-glib 
	make clean-pixman
	make clean-gtk
	make clean-ndk
	-find . -name "*.d" -exec rm -rf {} \;
	-find . -name "*.a" -exec rm -rf {} \;
	-find . -name "*.o" -exec rm -rf {} \;
	-find . -name "*.so" -exec rm -rf {} \;
	$(MAKE) createdirs
	
help:
	@echo " Usage: export BUILD_GUEST=<emu-arch>-softmmu && export BUILD_HOST=<eabi> && make limbo"
	@echo "  emu-arch (emulator): x86_64, aarch64"
	@echo "  eabi (device): armeabi-v7a, arm64-v8a, x86, x86_64"
	@echo "  optional: export NDK_DEBUG=1 for debuggable binaries"
	@echo " example: export BUILD_GUEST=x86_64-softmmu && export BUILD_HOST=armeabi-v7a && make limbo"
