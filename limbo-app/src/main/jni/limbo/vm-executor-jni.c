/*
 Copyright (C) Max Kastanas 2012

 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 *
 */
#include <jni.h>
#include <stdio.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <errno.h>
#include <malloc.h>
#include <signal.h>
#include <stdlib.h>
#include <unistd.h>
#include <dlfcn.h>
#include <unwind.h>
#include <dlfcn.h>
#include <sys/ioctl.h>
#include <sys/syscall.h>
#include <android/native_window_jni.h>
#include "vm-executor-jni.h"
#include "limbo_compat.h"

#define MSG_BUFSIZE 1024
#define MAX_STRING_LEN 1024

static int started = 0;
void * handle = 0;

void * loadLib(const char* lib_filename, const char * lib_path_str) {

	char res_msg[MAX_STRING_LEN];
	sprintf(res_msg, "Loading lib: %s", lib_path_str);
	LOGV("%s", res_msg);
	void *ldhandle = dlopen(lib_filename, RTLD_LAZY);
    if(ldhandle == NULL) {
        // try with the lib path
        printf("trying loading with full path: %s\n", lib_path_str);
        ldhandle = dlopen(lib_path_str, RTLD_LAZY);
    }
	return ldhandle;

}

void setup_jni(JNIEnv* env, jobject thiz, jstring storage_dir, jstring base_dir) {

    const char *base_dir_str = NULL;
    const char *storage_dir_str = NULL;

    if (base_dir != NULL)
		base_dir_str = (*env)->GetStringUTFChars(env, base_dir, 0);

    if (storage_dir != NULL)
		storage_dir_str = (*env)->GetStringUTFChars(env, storage_dir, 0);

	jclass c = (*env)->GetObjectClass(env, thiz);
	set_jni(env, thiz, c, storage_dir_str, base_dir_str);
}

int get_qemu_var(JNIEnv* env, jobject thiz, const char * var) {
    char res_msg[MSG_BUFSIZE + 1] = { 0 };
    
	dlerror();
    void * obj = dlsym (handle, var);
    const char *dlsym_error = dlerror();
    if (dlsym_error) {
        LOGE("Cannot load symbol %s: %s\n", var, dlsym_error);
    	return -1;
    }
    int * var_ptr = (int *) obj;
    return *var_ptr;
}

void set_qemu_var(JNIEnv* env, jobject thiz, const char * var, jint jvalue){
	int value_int = (jint) jvalue;

	dlerror();
    void * obj = dlsym (handle, var);
    const char *dlsym_error = dlerror();
    if (dlsym_error) {
        LOGE("Cannot load symbol %s: %s\n", var, dlsym_error);
    	return;
    }
    int * var_ptr = (int *) obj;
    *var_ptr = value_int;
}

/* ---------------------------------------------------------------------------
 * AGL display bridge and KernelSU root request.
 *
 * The AGL backend lives inside libqemu-system-*.so, which is loaded here with
 * dlopen() and therefore not linked into liblimbo.so: every entry point is
 * resolved with dlsym().  LimboAglActivity hands over its Surface so the guest
 * is rendered straight into the app window; that is the only display the
 * gunyah/gzvm accelerated VMs can use, because those have to run in this
 * process as root (see nativeGrantRoot()).
 * ------------------------------------------------------------------------- */

typedef void (*agl_set_window_fn)(ANativeWindow *window, uint32_t refresh_rate);
typedef void (*agl_cleanup_fn)(void);
typedef void (*agl_pointer_fn)(float x, float y, int buttons);
typedef void (*agl_scroll_fn)(float x, float y);
typedef void (*agl_key_fn)(int scan_code, bool down);

static void *get_qemu_symbol(const char *name) {
    void *obj;

    if (handle == NULL) {
        return NULL;
    }
    dlerror();
    obj = dlsym(handle, name);
    if (dlerror() != NULL) {
        return NULL;
    }
    return obj;
}

/* The activity owns a SurfaceView and therefore gets its Surface before the VM
 * is started, i.e. while libqemu-system-*.so is still unloaded.  Keep the last
 * window here until the library (and with it the AGL backend) is available. */
static ANativeWindow *agl_buffered_window = NULL;
static uint32_t agl_buffered_rate = 0;

static void buffer_agl_window(ANativeWindow *window, uint32_t rate) {
    ANativeWindow *old = agl_buffered_window;

    if (window != NULL) {
        ANativeWindow_acquire(window);
    }
    agl_buffered_window = window;
    agl_buffered_rate = rate;
    if (old != NULL) {
        ANativeWindow_release(old);
    }
}

/* Hands the buffered window to the backend; called once QEMU is initialized. */
static void flush_buffered_agl_window(void) {
    agl_set_window_fn set_window =
            (agl_set_window_fn) get_qemu_symbol("agl_set_window");
    ANativeWindow *window = agl_buffered_window;

    if (set_window == NULL || window == NULL) {
        return;
    }
    agl_buffered_window = NULL;
    set_window(window, agl_buffered_rate);
    ANativeWindow_release(window);
}

/* Stops and joins the AGL render thread.  Must run after qemu_cleanup() so the
 * renderer is gone before the library is closed; the backend state is reset so
 * a later VM start in this process can use AGL again. */
static void cleanup_agl_display(void) {
    agl_cleanup_fn cleanup = (agl_cleanup_fn) get_qemu_symbol("agl_cleanup");

    if (cleanup != NULL) {
        cleanup();
    }
}

JNIEXPORT void JNICALL Java_com_limbo_emu_jni_AglDisplay_setSurface(
        JNIEnv* env, jclass clazz, jobject surface, jfloat refresh_rate) {
    agl_set_window_fn set_window =
            (agl_set_window_fn) get_qemu_symbol("agl_set_window");
    ANativeWindow *window = NULL;
    uint32_t rate = 0;

    if (surface != NULL) {
        window = ANativeWindow_fromSurface(env, surface);
        if (window == NULL) {
            LOGE("Could not get an ANativeWindow for the AGL surface\n");
            return;
        }
    }
    /* The backend expects milli-Hz; 0 lets it keep its own default. */
    if (refresh_rate > 0) {
        rate = (uint32_t) (refresh_rate * 1000.0 + 0.5);
    }
    if (set_window == NULL) {
        /* QEMU is not loaded yet (the Surface arrives first): buffer it. */
        buffer_agl_window(window, rate);
    } else {
        set_window(window, rate);
    }
    if (window != NULL) {
        ANativeWindow_release(window);
    }
}

JNIEXPORT void JNICALL Java_com_limbo_emu_jni_AglDisplay_pointer(
        JNIEnv* env, jclass clazz, jfloat x, jfloat y, jint buttons) {
    agl_pointer_fn pointer =
            (agl_pointer_fn) get_qemu_symbol("limbo_agl_pointer");

    if (pointer != NULL) {
        pointer(x, y, buttons);
    }
}

JNIEXPORT void JNICALL Java_com_limbo_emu_jni_AglDisplay_scroll(
        JNIEnv* env, jclass clazz, jfloat x, jfloat y) {
    agl_scroll_fn scroll =
            (agl_scroll_fn) get_qemu_symbol("limbo_agl_scroll");

    if (scroll != NULL) {
        scroll(x, y);
    }
}

JNIEXPORT void JNICALL Java_com_limbo_emu_jni_AglDisplay_key(
        JNIEnv* env, jclass clazz, jint scan_code, jboolean down) {
    agl_key_fn key = (agl_key_fn) get_qemu_symbol("limbo_agl_key");

    if (key != NULL) {
        key(scan_code, down == JNI_TRUE);
    }
}

/*
 * KernelSU root request, following the ALS reference implementation: the
 * reboot syscall with the KernelSU magic hands back the driver fd and
 * ioctl(_IO('K', 1)) then grants root to the calling thread.  Magisk/su cannot
 * elevate an existing process, which is why the fallback for devices without
 * KernelSU is the separate root child process (headless).
 */
JNIEXPORT jint JNICALL Java_com_limbo_emu_jni_RootUtils_grantRoot(
        JNIEnv* env, jclass clazz) {
    int fd = -1;
    int status;

    errno = 0;
    syscall(SYS_reboot, 0xDEADBEEF, 0xCAFEBABE, 0, &fd);
    if (fd < 0) {
        return ENODEV;
    }
    if (ioctl(fd, _IO('K', 1), NULL) < 0) {
        status = errno;
        close(fd);
        return status;
    }
    close(fd);
    return geteuid() == 0 ? 0 : EPERM;
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *pvt) {
	return JNI_VERSION_1_2;
}

JNIEXPORT void JNICALL Java_com_limbo_emu_jni_VMExecutor_nativeRefreshScreen(
                JNIEnv* env, jobject thiz, jint jvalue) {
    if(handle == NULL) {
    	return;
    }
    set_qemu_var(env, thiz, "limbo_vga_full_update", jvalue);
}

JNIEXPORT void JNICALL Java_com_limbo_emu_jni_VMExecutor_setvncrefreshrate(
		JNIEnv* env, jobject thiz, jint jvalue) {
    set_qemu_var(env, thiz, "vnc_refresh_interval_inc", jvalue);
    set_qemu_var(env, thiz, "vnc_refresh_interval_base", jvalue);
}


JNIEXPORT void JNICALL Java_com_limbo_emu_jni_VMExecutor_setSDLRefreshRateDefault(
		JNIEnv* env, jobject thiz, jint jvalue) {
    set_qemu_var(env, thiz, "gui_refresh_interval_default", jvalue);
}

JNIEXPORT void JNICALL Java_com_limbo_emu_jni_VMExecutor_setSDLRefreshRateIdle(
		JNIEnv* env, jobject thiz, jint jvalue) {
            printf("setting sdl refresh rate idle: %d\n", jvalue);
    set_qemu_var(env, thiz, "gui_refresh_interval_idle", jvalue);
}


JNIEXPORT jint JNICALL Java_com_limbo_emu_jni_VMExecutor_getSDLRefreshRateDefault(
		JNIEnv* env, jobject thiz) {
    
    int res = get_qemu_var(env, thiz, "gui_refresh_interval_default");
    return res;
}

JNIEXPORT jint JNICALL Java_com_limbo_emu_jni_VMExecutor_getSDLRefreshRateIdle(
		JNIEnv* env, jobject thiz) {

    int res = get_qemu_var(env, thiz, "gui_refresh_interval_idle");
    printf("getting sdl refresh rate idle: %d\n", res);
    return res;
}



JNIEXPORT jint JNICALL Java_com_limbo_emu_jni_VMExecutor_getvncrefreshrate(
		JNIEnv* env, jobject thiz) {

    int res = get_qemu_var(env, thiz, "vnc_refresh_interval_inc");
    return res;
}

/* Shared VM bootstrap used by both the in-process VMExecutor.start() and
 * the root child process (RootVmLauncher.startVm).  In the root child
 * thiz is NULL, so the per-instance JNI wiring (set_jni) is skipped. */
static jstring start_qemu(JNIEnv* env, jobject thiz,
        jstring storage_dir, jstring base_dir,
        jstring lib_filename, jstring lib_path,
        jobjectArray params) {
	int res;
	char res_msg[MSG_BUFSIZE + 1] = { 0 };

	if (started) {
		sprintf(res_msg, "VM Already started");
		LOGV("%s", res_msg);
		return (*env)->NewStringUTF(env, res_msg);
	}

	LOGV("Processing params");

	int argc = 0;
	char ** argv = NULL;

	argc = (*env)->GetArrayLength(env, params);

	argv = (char **) malloc((argc + 1) * sizeof(*argv));
	if (argv == NULL) {
		LOGE("Failed to allocate argv array\n");
		return (*env)->NewStringUTF(env, "Memory allocation failed");
	}
	memset(argv, 0, (argc + 1) * sizeof(*argv));

	for (int i = 0; i < argc; i++) {
        jstring string = (jstring)((*env)->GetObjectArrayElement(env, params, i));
        if (string == NULL) {
            LOGE("Param at index %d is null, skipping\n", i);
            argv[i] = (char *) malloc(1);
            if (argv[i]) argv[i][0] = '\0';
            continue;
        }
		const char *param_str = (*env)->GetStringUTFChars(env, string, 0);
		if (param_str == NULL) {
			LOGE("GetStringUTFChars failed at index %d\n", i);
			(*env)->DeleteLocalRef(env, string);
			// cleanup already allocated args
			for (int j = 0; j < i; j++) {
				free(argv[j]);
			}
			free(argv);
			return (*env)->NewStringUTF(env, "Failed to convert Java string");
		}
		int length = strlen(param_str)+1;
        argv[i] = (char *) malloc(length * sizeof(char));
		if (argv[i] == NULL) {
			LOGE("Failed to allocate memory for param %d\n", i);
			(*env)->ReleaseStringUTFChars(env, string, param_str);
			(*env)->DeleteLocalRef(env, string);
			for (int j = 0; j < i; j++) {
				free(argv[j]);
			}
			free(argv);
			return (*env)->NewStringUTF(env, "Memory allocation failed");
		}
		memcpy(argv[i], param_str, length);
		(*env)->ReleaseStringUTFChars(env, string, param_str);
		(*env)->DeleteLocalRef(env, string);
	}

	// QEMU requires argv[argc] == NULL
	argv[argc] = NULL;

	printf("Starting VM\n");
    started = 1;

    //LOAD LIB
	const char *lib_filename_str = NULL;
	if (lib_filename!= NULL)
		lib_filename_str = (*env)->GetStringUTFChars(env, lib_filename, 0);
    const char *lib_path_str = NULL;
    if (lib_path != NULL)
        lib_path_str = (*env)->GetStringUTFChars(env, lib_path, 0);

	if (handle == NULL) {
		handle = loadLib(lib_filename_str, lib_path_str);
	}

	if (!handle) {
		sprintf(res_msg, "Error opening lib: %s :%s", lib_path_str, dlerror());
		LOGV("%s", res_msg);
		// cleanup argv
		for (int i = 0; i < argc; i++) {
			free(argv[i]);
		}
		free(argv);
		started = 0;
		return (*env)->NewStringUTF(env, res_msg);
	}

	if (thiz != NULL) {
		setup_jni(env, thiz, storage_dir, base_dir);
	}
    /* Same mode value drives both the SDL and the GTK display backends:
     * 0 = stretch, 1 = keep aspect ratio, 2 = 1:1 pixels.  Symbols that are
     * not exported by the loaded qemu library (e.g. VNC-only builds) are
     * simply ignored by set_qemu_var(). */
    // set_qemu_var(env, thiz, "limbo_sdl_scale_mode", sdl_scale_mode);
    // set_qemu_var(env, thiz, "limbo_gtk_scale_mode", sdl_scale_mode);

	// Use correct function signatures to avoid undefined behavior on ARM64
	typedef void (*qemu_init_t)(int argc, char **argv);
	typedef int (*main_t)(int argc, char **argv, char **envp);
    typedef void (*qemu_main_loop_t)(void);
	typedef void (*qemu_cleanup_t)(void);

    qemu_init_t qemu_init = NULL;
    main_t qemu_main = NULL;
    qemu_main_loop_t qemu_main_loop = NULL;
    qemu_cleanup_t qemu_cleanup = NULL;

	dlerror();
	qemu_init = (qemu_init_t) dlsym(handle, "qemu_init");
	const char *dlsym_error = dlerror();
	if (dlsym_error) { // older versions of qemu use "main"
		LOGE("Cannot find qemu symbol 'qemu_init' trying 'main': %s\n", dlsym_error);
	    qemu_main = (main_t) dlsym(handle, "main");
	    dlsym_error = dlerror();
	    if (dlsym_error) {
        	LOGE("Cannot find qemu symbol 'qemu_init' or 'main': %s\n", dlsym_error);
        	dlclose(handle);
        	handle = NULL;
        	started = 0;
        	for (int i = 0; i < argc; i++) {
        		free(argv[i]);
        	}
        	free(argv);
        	return (*env)->NewStringUTF(env, dlsym_error);
        }
        qemu_main(argc, argv, NULL);
    } else { // new versions of qemu: qemu_init takes only 2 args
        qemu_main_loop = (qemu_main_loop_t) dlsym(handle, "qemu_main_loop");
	    dlsym_error = dlerror();
	    if (dlsym_error) {
        	LOGE("Cannot find qemu symbol 'qemu_main_loop': %s\n", dlsym_error);
        	dlclose(handle);
        	handle = NULL;
        	started = 0;
        	for (int i = 0; i < argc; i++) {
        		free(argv[i]);
        	}
        	free(argv);
        	return (*env)->NewStringUTF(env, dlsym_error);
        }

        qemu_cleanup = (qemu_cleanup_t) dlsym(handle, "qemu_cleanup");
	    dlsym_error = dlerror();
	    if (dlsym_error) {
        	LOGE("Cannot find qemu symbol 'qemu_cleanup': %s\n", dlsym_error);
        	dlclose(handle);
        	handle = NULL;
        	started = 0;
        	for (int i = 0; i < argc; i++) {
        		free(argv[i]);
        	}
        	free(argv);
        	return (*env)->NewStringUTF(env, dlsym_error);
        }

        qemu_init(argc, argv);
        /* The display backends exist now: deliver a Surface that arrived
         * before the library was loaded (AGL display). */
        flush_buffered_agl_window();
        qemu_main_loop();
        qemu_cleanup();
	}

	sprintf(res_msg, "Closing lib: %s", lib_path_str);
	LOGV("%s", res_msg);
	/* Tear the AGL renderer down before unloading the library (no-op unless
	 * the VM was started with "-display agl"). */
	cleanup_agl_display();
	dlclose(handle);
	handle = NULL;
	started = 0;

    if (lib_path != NULL && lib_path_str != NULL)
        (*env)->ReleaseStringUTFChars(env, lib_path, lib_path_str);

	// free argv
	for (int i = 0; i < argc; i++) {
		free(argv[i]);
	}
	free(argv);

	sprintf(res_msg, "VM shutdown");
	LOGV("%s", res_msg);
    return (*env)->NewStringUTF(env, res_msg);
}

JNIEXPORT jstring JNICALL Java_com_limbo_emu_jni_VMExecutor_start(
        JNIEnv* env, jobject thiz,
		jstring storage_dir, jstring base_dir,
		jstring lib_filename, jstring lib_path,
		jobjectArray params) {
	return start_qemu(env, thiz, storage_dir, base_dir,
			lib_filename, lib_path, params);
}

/* Entry point for the root child process (RootVmLauncher.main): runs the
 * very same in-process VM bootstrap but without any Android UI object. */
JNIEXPORT jstring JNICALL Java_com_limbo_emu_jni_RootVmLauncher_startVm(
        JNIEnv* env, jclass clazz,
		jstring storage_dir, jstring base_dir,
		jstring lib_filename, jstring lib_path,
		jobjectArray params) {
	return start_qemu(env, NULL, storage_dir, base_dir,
			lib_filename, lib_path, params);
}


JNIEXPORT jstring JNICALL Java_com_limbo_emu_jni_VMExecutor_stop(
		JNIEnv* env, jobject thiz, jint jint_restart) {
	char res_msg[MSG_BUFSIZE + 1] = { 0 };

	int restart_int = jint_restart;

    if(restart_int) {
        typedef void (*reset_vm_t)(int);
        dlerror();
        reset_vm_t qemu_system_reset_request = (reset_vm_t) dlsym(handle, "qemu_system_reset_request");
        const char *dlsym_error = dlerror();
        if (dlsym_error) {
            LOGE("Cannot load symbol 'qemu_system_reset_request': %s\n", dlsym_error);
            return (*env)->NewStringUTF(env, res_msg);
        }
        qemu_system_reset_request(6); //SHUTDOWN_CAUSE_GUEST_RESET
        sprintf(res_msg, "VM Restart Request");
    } else {
        typedef void (*stop_vm_t)(int);
        dlerror();
        stop_vm_t qemu_system_shutdown_request = (stop_vm_t) dlsym(handle, "qemu_system_shutdown_request");
        const char *dlsym_error = dlerror();
        if (dlsym_error) {
            LOGE("Cannot load symbol 'qemu_system_shutdown_request': %s\n", dlsym_error);
            return (*env)->NewStringUTF(env, res_msg);
        }
        qemu_system_shutdown_request(3); //SHUTDOWN_CAUSE_HOST_SIGNAL
        sprintf(res_msg, "VM Stop Request");
	}

	LOGV("%s", res_msg);

	started = restart_int;

	return (*env)->NewStringUTF(env, res_msg);
}

// JNI End

