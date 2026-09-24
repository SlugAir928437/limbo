#include <jni.h>
#include <stdio.h>
#include "limbo_logutils.h"
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
#include <sys/stat.h>
#include "limbo_compat_qemu.h"
#include "limbo_compat.h"
#include <errno.h>

// ===================== JNI 全局缓存（核心修复）=====================
static jclass        g_cls_res          = NULL;
static jmethodID     g_mid_res_changed  = NULL;
static int           g_jni_cache_init   = 0;

/**
 * 初始化分辨率回调 JNI 全局缓存（主线程调用一次）
 */
void init_sdl_res_jni_cache(JNIEnv *env, jobject obj)
{
	if (g_jni_cache_init || !env || !obj)
		return;

	// 获取本地类引用
	jclass local_cls = (*env)->GetObjectClass(env, obj);
	if (!local_cls)
	{
		LOGE("init_sdl_res_jni_cache: GetObjectClass failed");
		return;
	}

	// 转为全局引用，防止GC回收
	g_cls_res = (jclass)(*env)->NewGlobalRef(env, local_cls);
	(*env)->DeleteLocalRef(env, local_cls);

	// 预缓存静态方法 onVMResolutionChanged(II)V
	g_mid_res_changed = (*env)->GetStaticMethodID(env, g_cls_res,
												  "onVMResolutionChanged", "(II)V");
	if (!g_mid_res_changed)
	{
		LOGE("init_sdl_res_jni_cache: GetStaticMethodID failed");
		(*env)->DeleteGlobalRef(env, g_cls_res);
		g_cls_res = NULL;
		return;
	}

	g_jni_cache_init = 1;
	LOGI("init_sdl_res_jni_cache: JNI cache init success");
}

/**
 * 释放 JNI 全局引用（Activity销毁时调用）
 */
void release_sdl_res_jni_cache(JNIEnv *env)
{
	if (g_cls_res && env)
	{
		(*env)->DeleteGlobalRef(env, g_cls_res);
		g_cls_res = NULL;
	}
	g_mid_res_changed = NULL;
	g_jni_cache_init = 0;
	LOGI("release_sdl_res_jni_cache: JNI cache released");
}

void set_sdl_res(int width, int height)
{
    pthread_mutex_lock(&fd_lock);

    // 多层判空，杜绝空指针崩溃
    if (!g_jni_cache_init || !jvm || !g_cls_res || !g_mid_res_changed)
    {
        LOGE("set_sdl_res: JNI cache not ready or invalid");
        pthread_mutex_unlock(&fd_lock);
        return;
    }

    JNIEnv *env = NULL;
    jint rs = (*jvm)->AttachCurrentThread(jvm, &env, NULL);
    if (rs != JNI_OK || !env)
    {
        LOGE("set_sdl_res: AttachCurrentThread failed, ret=%d", rs);
        pthread_mutex_unlock(&fd_lock);
        return;
    }

    // 调用Java静态方法（仅保留这一行）
    (*env)->CallStaticVoidMethod(env, g_cls_res, g_mid_res_changed, width, height);

    (*jvm)->DetachCurrentThread(jvm);
    pthread_mutex_unlock(&fd_lock);
}

void *set_sdl_res_thread(void *t)
{
	sdl_res_t * sdl_res_data = (sdl_res_t *) t;
	if (sdl_res_data)
	{
		set_sdl_res(sdl_res_data->width, sdl_res_data->height);
	}
	pthread_exit(NULL);
}

void create_thread_set_sdl_res(int width, int height)
{
	int rc;
	pthread_t thread;
	pthread_attr_t attr;

	sdl_res_t * sdl_res_data = (struct sdl_res_t*) malloc(sizeof(struct sdl_res_t));
	if (!sdl_res_data)
	{
		LOGE("create_thread_set_sdl_res: malloc memory failed");
		return;
	}

	sdl_res_data->width  = width;
	sdl_res_data->height = height;
	void * param = (void *) sdl_res_data;

	pthread_attr_init(&attr);
	// 使用分离线程，避免 pthread_join 阻塞主线程
	pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);

	rc = pthread_create(&thread, NULL, set_sdl_res_thread, param);
	if (rc)
	{
		LOGE("create_thread_set_sdl_res: pthread_create failed, ret=%d", rc);
		free(sdl_res_data);
		pthread_attr_destroy(&attr);
		return;
	}

	pthread_attr_destroy(&attr);
	// 分离线程自动回收资源，无需 join
}

// 对外JNI入口：QEMU调用此接口
void Android_JNI_SetVMResolution(int width, int height)
{
	create_thread_set_sdl_res(width, height);
}

// ===================== Java 对应 Native 方法实现 =====================
/**
 * Java: public native void initSdlResJniCache();
 * 包名+类名严格对应：com.max2idea.android.limbo.main.LimboSDLActivity
 */
JNIEXPORT void JNICALL
Java_com_limbo_emu_main_LimboSDLActivity_initSdlResJniCache
(JNIEnv *env, jobject thiz)
{
init_sdl_res_jni_cache(env, thiz);
}

/**
 * Java: public native void releaseSdlResJniCache();
 */
JNIEXPORT void JNICALL
Java_com_limbo_emu_main_LimboSDLActivity_releaseSdlResJniCache
(JNIEnv *env, jobject thiz)
{
release_sdl_res_jni_cache(env);
}