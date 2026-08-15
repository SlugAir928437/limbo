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

typedef int (*gdk_android_initialize_fn)(JNIEnv *env,
                                         jobject application_classloader,
                                         jobject activity);

#define LOG_TAG "limbo-gtk"

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
