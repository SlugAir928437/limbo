#ifndef LIMBO_COMPAT_SPICE_H
#define LIMBO_COMPAT_SPICE_H

/* Android NDK API 24 compat prototypes (implementations live in
 * limbo_compat_stubs.c, linked into libqemu-system-*.so via -lcompat-limbo).
 * bionic only declares shm_open/shm_unlink from API 26, but spice-server's
 * stat-file.c calls them unconditionally. */
#if !defined(__ANDROID_API__) || __ANDROID_API__ < 26
#include <sys/types.h>
int shm_open(const char *name, int oflag, mode_t mode);
int shm_unlink(const char *name);
#endif

#endif
