#ifndef LIMBO_COMPAT_H
#define LIMBO_COMPAT_H

#include <jni.h>
#include <pthread.h>
#include <sys/types.h>
#include <time.h>

extern JavaVM *jvm;
extern jobject jobj;
extern jclass jcls;
extern pthread_mutex_t fd_lock;
extern const char * storage_base_dir;
extern const char * limbo_base_dir;

void set_jni(JNIEnv* env, jobject obj1, jclass jclass1, const char * storage_dir, const char * limbo_dir);
void * valloc (size_t size);

#ifndef __ANDROID_HAVE_STRCHRNUL__
#if !defined(__ANDROID_API__) || __ANDROID_API__ < 24
const char* strchrnul(const char* s, int c);
#endif
#endif

/*
 * Android NDK API 24 compat prototypes (implementations in
 * limbo_compat_stubs.c). QEMU 10.2.1 calls close_range/shm_open/shm_unlink
 * and getrandom (guard in the stubs; needs a prototype for each). Clang 16+
 * promotes implicit function declarations from a warning to a hard error,
 * and this header is force-included (-include) into the QEMU meson build.
 * Stay under the API where bionic declares them to avoid redefinition.
 */
#if !defined(__ANDROID_API__) || __ANDROID_API__ < 30
int close_range(unsigned int first, unsigned int last, int flags);
#endif
#if !defined(__ANDROID_API__) || __ANDROID_API__ < 26
int shm_open(const char *name, int oflag, mode_t mode);
int shm_unlink(const char *name);
#endif
#if !defined(__ANDROID_API__) || __ANDROID_API__ < 28
ssize_t getrandom(void *buf, size_t buflen, unsigned int flags);
#endif
/* bionic only exposes C11 timespec_get() from API 29 (used by the uftrace
 * TCG plugin); still, declare it here so no TU relies on an implicit decl. */
#if !defined(__ANDROID_API__) || __ANDROID_API__ < 29
int timespec_get(struct timespec *ts, int base);
#endif

#endif
