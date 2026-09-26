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
LIMBO_APP_LIBS=$(LIMBO_PRJ_ROOT)/limbo-app/src/main

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
	VIRGL_BUILDTYPE = debug
else
	LIBFFI_DEBUG = --disable-debug
    LIBGLIB_BUILDTYPE = release
    LIBPIXMAN_DEBUG =
    GTK_BUILDTYPE = release
    VIRGL_BUILDTYPE = release
endif

export

.PHONY: limbo clean distclean clean-ndk createdirs pkg-config-link clean-ffi clean-glib clean-qemu clean-pixman clean-slirp clean-spice clean-gtk clean-virgl ncat clean-ncat fetch fetch-glib fetch-libffi fetch-pixman fetch-sdl2 fetch-slirp fetch-openssl fetch-libjpeg fetch-spice-protocol fetch-spice fetch-qemu fetch-gtk4android fetch-virgl config-virgl virgl

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
config-spice: ndk pkg-config-link ffi glib pixman
	# openssl 3.0.16 (static libcrypto/libssl)
	cd $(LIMBO_JNI_ROOT)/openssl && make clean >/dev/null 2>&1 || true
	cd $(LIMBO_JNI_ROOT)/openssl && \
	ANDROID_NDK_ROOT="$(NDK_ROOT)" ANDROID_NDK_HOME="$(NDK_ROOT)" \
	./Configure android-arm64 -D__ANDROID_API__=$(NDK_PLATFORM_API) \
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
	$(CREATE_EGL_PC)
	cd $(LIMBO_JNI_ROOT)/virglrenderer && rm -rf "$(VIRGL_BUILD_DIR)" "$(VIRGL_INSTALL_DIR)"
	# meson option names/types differ between virglrenderer releases (venus vs
	# venus-experimental, drm as boolean or feature, ...), so the flags are
	# derived from what the checked out tree declares - an unknown -D option is
	# only a warning in meson, but a wrong *value* is a hard error.
	# Everything found is switched OFF: Android has no libdrm/gbm and the
	# Android/iOS venus (Vulkan) path is not used by virtio-gpu-gl-pci.
	cd $(LIMBO_JNI_ROOT)/virglrenderer && \
	virgl_opts="-Dplatforms=egl"; \
	for opt in venus venus-experimental venus-validate drm drm-msm drm-msm-experimental minigbm_allocation tests; do \
		if grep -q "option('$$opt'" meson_options.txt 2>/dev/null; then \
			opt_type=$$(sed -n "/option('$$opt'/,/)/p" meson_options.txt | \
				sed -n "s/.*type *: *'\([a-z]*\)'.*/\1/p" | head -n1); \
			case "$$opt_type" in \
				feature) virgl_opts="$$virgl_opts -D$$opt=disabled" ;; \
				boolean) virgl_opts="$$virgl_opts -D$$opt=false" ;; \
			esac; \
		fi; \
	done; \
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
	$(MAKE) -f $(LIMBO_JNI_ROOT)/android-config/android-qemu-config.mak ACCEL_MESON_FLAGS="$(ACCEL_MESON_FLAGS)" AGL_MESON_FLAGS="$(AGL_MESON_FLAGS)" VIRGL_MESON_FLAGS="$(VIRGL_MESON_FLAGS)"

clean-qemu:
	-cd $(LIMBO_JNI_ROOT)/qemu && $(MAKE) distclean
	-cd $(LIMBO_JNI_ROOT)/qemu && $(MAKE) clean
	-rm $(NDK_PROJECT_PATH)/obj/local/$(APP_ABI)/libqemu-system-x86_64.a
	-rm -rf ../assets/roms/* 
	-rm -rf $(LIMBO_APP_LIBS)/assets/roms/*

# virglrenderer 不在这里配置/构建：它依赖 GTK4 安装出来的 epoxy（见 config-virgl
# 注释），必须排在 deps（构建+安装 GTK4）之后，因此由 limbo 目标显式执行。
config-deps: ndk config-ffi config-glib config-pixman config-slirp config-spice $(if $(USE_GTK),config-gtk)

# virglrenderer 同上：由 limbo 在 GTK4 安装完成后显式构建
deps: ndk glib pixman slirp spice $(if $(USE_GTK),gtk)

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
# virglrenderer 显式排在 config-qemu 之前：QEMU 只有拿到 virglrenderer.pc 才能
# 用 -Dopengl=enabled -Dvirglrenderer=enabled 配出 virtio-gpu-gl-pci（AGL 显示
# 的 3D 客户机要用它）。config-virgl/virgl 本身也会被 config-deps/deps 条件带上，
# 这里显式列出是为了让依赖顺序一目了然、且 USE_VIRGL 被覆盖时也不会漏。
ifeq ($(USE_VIRGL),true)
	make config-virgl
	make virgl
endif
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

# gzvm / gunyah 加速器补丁（合并版，AnyLaySys 上游移植到 QEMU 11.0.0）
ACCEL_PATCH         := $(LIMBO_JNI_ROOT)/patches/qemu-v11.0.0-gunyah-gzvm-accel.patch

# AGL（Android Graphics Layer）显示后端补丁：gunyah/gzvm 加速的 aarch64 虚拟机
# 必须把画面画到 App 自己的 Surface 上（-display agl），而独立 root 子进程拿不到
# Surface，因此还需要配合进程内提权（见 RootUtils.grantRoot）。该后端依赖
# epoxy/EGL，即 USE_GTK/USE_VIRGL 提供的 OpenGL 栈。同样只适用于官方 QEMU 树。
AGL_PATCH           := $(LIMBO_JNI_ROOT)/patches/qemu-v11.0.0-agl-display.patch
AGL_MESON_FLAGS     := -Dagl=enabled
AGL_APPLY           := yes

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
QEMU_FORK_PATCH     := $(LIMBO_JNI_ROOT)/patches/qemu-ia64-fork-copy_file_range.patch
# virglrenderer (host side of QEMU's virtio-gpu-gl-pci / CONFIG_VIRGL).
# Override the tag with e.g. export VIRGL_TAG=1.1.1 to follow upstream.
VIRGL_TAG           ?= 1.1.0
VIRGL_URL           := https://gitlab.freedesktop.org/virgl/virglrenderer.git
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

# gzvm / gunyah 加速器仅适用于官方 QEMU 11.0.0 树：
# IA-64 fork 无对应 meson 选项，传入 -Dgunyah/-Dgzvm 会导致
# "Unknown option" 配置失败，补丁也不应打到 fork 树上。
ifneq ($(QEMU_USE_OFFICIAL), yes)
ACCEL_MESON_FLAGS    :=
ACCEL_APPLY          :=
AGL_MESON_FLAGS      :=
AGL_APPLY            :=
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

fetch: fetch-glib fetch-libffi fetch-pixman fetch-sdl2 fetch-slirp fetch-openssl fetch-libjpeg fetch-spice-protocol fetch-spice fetch-gtk4android fetch-virgl fetch-qemu

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
		( cd "$(LIMBO_JNI_ROOT)/SDL2" && patch -p1 < "$(LIMBO_JNI_ROOT)/patches/sdl2-2.0.8-android.patch" ); \
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

distclean: clean-qemu clean-ffi clean-glib clean-pixman clean-slirp clean-spice clean-gtk clean-virgl clean-ncat clean-ndk

clean: 
	make clean-qemu 
	make clean-ffi 
	make clean-glib 
	make clean-pixman
	make clean-slirp
	make clean-spice
	make clean-gtk
	make clean-virgl
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
	@echo " example: export BUILD_GUEST=x86_64-softmmu && export BUILD_HOST=armeabi-v7a && make limbo"
