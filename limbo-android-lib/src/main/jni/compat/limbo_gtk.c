/*
 * limbo_gtk.c - GTK4 android glue initialization for the Limbo emulator.
 *
 * The GDK android backend must be initialized with the JNI environment,
 * the application class loader and the host activity before QEMU's
 * "-display gtk" can open a display.  gdk_android_initialize() caches the
 * Java classes/fields (registered by libgtk-4.so) and the host activity.
 *
 * NOTE: gtk_init() is intentionally NOT called here.  GTK is single
 * threaded, so initialization and the main loop must happen on the thread
 * QEMU runs its UI on: qemu's ui/gtk.c calls gtk_init_check() itself in
 * gtk_display_early_init().  Calling gtk_init() from the Android UI thread
 * here would initialize GTK on the wrong thread.
 *
 * libgtk-4.so is resolved at RUNTIME via dlopen() on purpose: this keeps
 * libcompat-limbo.so free of any link-time dependency on the GTK stack, so
 * the native build order (compat -> glib -> pixman -> gtk) stays free of
 * cycles.
 *
 * This file is compiled into libcompat-limbo.so by compat/Android.mk.
 */

#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <sys/stat.h>
#include <sys/types.h>

typedef int (*gdk_android_initialize_fn)(JNIEnv *env,
                                         jobject application_classloader,
                                         jobject activity);

#define LOG_TAG "limbo-gtk"

/*
 * Recursively create a directory path (like mkdir -p).
 * Returns 0 on success, -1 on error.
 */
static int mkdir_p(const char *path)
{
    char tmp[1024];
    size_t len = strlen(path);
    size_t i;

    if (len == 0 || len >= sizeof(tmp)) {
        return -1;
    }
    memcpy(tmp, path, len + 1);

    for (i = 1; i < len; i++) {
        if (tmp[i] == '/') {
            tmp[i] = '\0';
            if (mkdir(tmp, 0755) != 0 && errno != EEXIST) {
                return -1;
            }
            tmp[i] = '/';
        }
    }
    if (mkdir(tmp, 0755) != 0 && errno != EEXIST) {
        return -1;
    }
    return 0;
}

/*
 * Point TMPDIR at the app's writable cache directory.
 *
 * On Android there is no /tmp (or /var/tmp), so GLib's g_get_tmp_dir()
 * returns "/tmp" and any mkstemp()/memfd fallback fails with ENOENT.  That
 * is what crashes qemu_memfd_alloc() (util/memfd.c) when memfd_create() is
 * unavailable: "failed to allocate shared memory: No such file or
 * directory".  The app already creates <cacheDir>/limbo/var/tmp
 * (getTmpFolder()), so set TMPDIR to that directory here, before QEMU
 * starts.
 */
static void setup_tmp_dir(JNIEnv *env, jobject activity)
{
    jclass cls;
    jmethodID mid;
    jobject file;
    jstring jpath;
    const char *cache_dir;
    char tmpdir[1024];

    if (activity == NULL) {
        return;
    }

    cls = (*env)->GetObjectClass(env, activity);
    if (cls == NULL) {
        return;
    }
    mid = (*env)->GetMethodID(env, cls, "getCacheDir",
                              "()Ljava/io/File;");
    if (mid == NULL) {
        return;
    }
    file = (*env)->CallObjectMethod(env, activity, mid);
    if (file == NULL) {
        return;
    }
    cls = (*env)->GetObjectClass(env, file);
    mid = (*env)->GetMethodID(env, cls, "getAbsolutePath",
                              "()Ljava/lang/String;");
    if (mid == NULL) {
        return;
    }
    jpath = (jstring)(*env)->CallObjectMethod(env, file, mid);
    if (jpath == NULL) {
        return;
    }
    cache_dir = (*env)->GetStringUTFChars(env, jpath, NULL);
    if (cache_dir == NULL) {
        return;
    }

    snprintf(tmpdir, sizeof(tmpdir), "%s/limbo/var/tmp", cache_dir);
    (*env)->ReleaseStringUTFChars(env, jpath, cache_dir);

    if (mkdir_p(tmpdir) == 0) {
        setenv("TMPDIR", tmpdir, 1);
        __android_log_print(ANDROID_LOG_INFO, LOG_TAG,
                            "TMPDIR set to %s", tmpdir);
    } else {
        __android_log_print(ANDROID_LOG_WARN, LOG_TAG,
                            "could not create temp dir %s", tmpdir);
    }
}

JNIEXPORT void JNICALL
Java_com_max2idea_android_limbo_jni_LimboGtk_nativeInit(JNIEnv *env,
                                                        jclass klass,
                                                        jobject class_loader,
                                                        jobject activity)
{
    void *handle;
    gdk_android_initialize_fn gdk_android_initialize;

    if (class_loader == NULL || activity == NULL) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG,
                            "class loader or activity is NULL, aborting init");
        return;
    }

    setup_tmp_dir(env, activity);

    handle = dlopen("libgtk-4.so", RTLD_NOW | RTLD_GLOBAL);
    if (handle == NULL) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG,
                            "dlopen(libgtk-4.so) failed: %s", dlerror());
        return;
    }

    gdk_android_initialize = (gdk_android_initialize_fn)
        dlsym(handle, "gdk_android_initialize");
    if (gdk_android_initialize == NULL) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG,
                            "dlsym(gdk_android_initialize) failed: %s", dlerror());
        return;
    }

    if (!gdk_android_initialize(env, class_loader, activity)) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG,
                            "gdk_android_initialize() failed");
        return;
    }
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG,
                        "GDK android backend initialized for -display gtk");
}
