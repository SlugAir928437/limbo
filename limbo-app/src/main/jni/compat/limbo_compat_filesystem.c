#include <jni.h>
#include <pthread.h>
#include <unistd.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <errno.h>
#include <android/log.h>
#include <stdlib.h> // for mkstemp

#include "limbo_compat_filesystem.h"

#define LOG_TAG "limbo_compat_filesystem"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#if ENABLE_ASF

// ==================== 全局安全初始化 ====================
static JavaVM *g_jvm = NULL;
static jobject g_obj = NULL;
static pthread_mutex_t g_fd_lock = PTHREAD_MUTEX_INITIALIZER;

typedef struct {
    int fd;
    int res;
} fd_t;

// ==================== JNI 注册 ====================
jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    g_jvm = vm;
    return JNI_VERSION_1_6;
}

// 从 Java 传入对象
JNIEXPORT void JNICALL
Java_com_android_limbo_jni_VMExecutor_nativeSetBinderObject(JNIEnv *env, jclass clazz, jobject obj) {
    if (g_obj != NULL) {
        (*env)->DeleteGlobalRef(env, g_obj);
    }
    g_obj = (*env)->NewGlobalRef(env, obj);
}

// ==================== 安全 close_fd ====================
static int close_fd(int fd) {
    if (g_jvm == NULL || g_obj == NULL) {
        LOGE("close_fd: JVM or object not initialized");
        return -1;
    }

    JNIEnv *env = NULL;
    jint rs = (*g_jvm)->AttachCurrentThread(g_jvm, &env, NULL);
    if (rs != JNI_OK || env == NULL) {
        LOGE("close_fd: Attach failed");
        return -1;
    }

    jclass cls = (*env)->GetObjectClass(env, g_obj);
    jmethodID mid = (*env)->GetMethodID(env, cls, "close_fd", "(I)I");
    if (mid == NULL) {
        LOGE("close_fd: method not found");
        (*g_jvm)->DetachCurrentThread(g_jvm);
        return -1;
    }

    jint res = (*env)->CallIntMethod(env, g_obj, mid, fd);
    (*g_jvm)->DetachCurrentThread(g_jvm);
    return (int)res;
}

// ==================== 线程函数 ====================
void *close_fd_thread(void *arg) {
    fd_t *data = (fd_t *)arg;
    if (data == NULL) {
        pthread_exit(NULL);
    }
    data->res = close_fd(data->fd);
    pthread_exit(NULL);
}

// ==================== 创建线程关闭 ====================
static int create_thread_close_fd(int fd) {
    fd_t *data = malloc(sizeof(fd_t));
    if (data == NULL) return -1;

    data->fd = fd;
    data->res = -1;

    pthread_t thread;
    if (pthread_create(&thread, NULL, close_fd_thread, data) != 0) {
        free(data);
        return -1;
    }

    pthread_join(thread, NULL);
    int res = data->res;
    free(data);
    return res;
}

#endif // ENABLE_ASF

// ==================== 对外接口 ====================
int android_close(int fd) {
    int res = -1;
    LOGD("Closing fd: %d", fd);

#if ENABLE_ASF
    res = create_thread_close_fd(fd);
#else
    res = close(fd);
#endif

    if (res < 0) {
        LOGE("close fd %d error: %s", fd, strerror(errno));
    }
    return res;
}

// ==================== 修复 QEMU 链接错误：android_mkstemp 实现 ====================
int android_mkstemp(char *template) {
    return mkstemp(template);
}

FILE *android_fopen(const char *path, const char *mode) {
    return fopen(path, mode);
}

int android_open(const char *pathname, int flags, mode_t mode) {
    return open(pathname, flags, mode);
}

int android_stat(const char *pathname, struct stat *statbuf) {
    return stat(pathname, statbuf);
}

int android_unlink(const char *pathname) {
    return unlink(pathname);
}