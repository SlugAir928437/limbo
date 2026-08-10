/*
 * limbo_compat_stubs.c
 *
 * Compatibility stubs for functions not available in Android NDK API 24:
 *   - close_range  (Linux kernel 5.9+, not in bionic at API 24)
 *   - shm_open     (POSIX shm, bionic API 26+)
 *   - shm_unlink   (POSIX shm, bionic API 26+)
 *   - getrandom    (Linux syscall, bionic wrapper API 28+)
 *
 * These are needed by QEMU 10.2.1 when cross-compiling for Android.
 */

#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <sys/stat.h>
#include <sys/types.h>

/* For syscall() */
#include <sys/syscall.h>

#include "limbo_logutils.h"

/* ========================================================================
 * close_range() — Close all file descriptors in [first, last].
 *
 * QEMU uses this in qemu_close_all_open_fd() (guarded by CONFIG_CLOSE_RANGE).
 * We try the real syscall first; if the kernel doesn't support it we fall
 * back to closing fds one by one.
 * ======================================================================== */

#ifdef __NR_close_range
#define HAVE_CLOSE_RANGE_SYSCALL 1
#endif

int close_range(unsigned int first, unsigned int last, int flags)
{
#ifdef HAVE_CLOSE_RANGE_SYSCALL
    int ret = (int)syscall(__NR_close_range, first, last, flags);
    if (ret == 0 || errno != ENOSYS) {
        return ret;
    }
    /* Fall through to emulation if kernel returned ENOSYS */
#endif

    /* Emulation: close each fd individually.
     * flags == CLOSE_RANGE_CLOEXEC (1) means set FD_CLOEXEC instead of closing. */
    if (flags & 1) {
        /* CLOSE_RANGE_CLOEXEC — just set cloexec on each fd */
        for (unsigned int fd = first; fd <= last; fd++) {
            int curflags = fcntl(fd, F_GETFD);
            if (curflags >= 0) {
                fcntl(fd, F_SETFD, curflags | FD_CLOEXEC);
            }
            if (fd == 0xFFFFFFFFU) break; /* overflow guard */
        }
        return 0;
    }

    for (unsigned int fd = first; fd <= last; fd++) {
        close(fd);
        if (fd == 0xFFFFFFFFU) break; /* overflow guard */
    }
    return 0;
}

/* ========================================================================
 * shm_open() / shm_unlink() — POSIX shared memory.
 *
 * QEMU uses these in qemu_shm_alloc() (util/oslib-posix.c) to create
 * anonymous shared memory.  The pattern is:
 *   fd = shm_open(name, O_RDWR|O_CREAT|O_EXCL, mode);
 *   shm_unlink(name);   // immediately unlink, keep fd
 *   ftruncate(fd, size);
 *   mmap(... fd ...);
 *
 * On Android < API 26 these don't exist in bionic.  We emulate them
 * using memfd_create() when available, or regular files in a temp dir.
 * ======================================================================== */

#ifndef MFD_CLOEXEC
#define MFD_CLOEXEC 0x0001U
#endif

#ifdef __NR_memfd_create
#define HAVE_MEMFD_CREATE 1
#endif

static int android_memfd_create(const char *name, unsigned int flags)
{
#ifdef HAVE_MEMFD_CREATE
    return (int)syscall(__NR_memfd_create, name, flags | MFD_CLOEXEC);
#else
    return -1;
#endif
}

/*
 * Try to use memfd_create first (anonymous, no filesystem footprint).
 * If that fails, fall back to a file in /data/local/tmp or TMPDIR.
 */
int shm_open(const char *name, int oflag, mode_t mode)
{
    /* If O_CREAT is requested, try memfd_create for an anonymous backing */
    if (oflag & O_CREAT) {
        int fd = android_memfd_create(name, 0);
        if (fd >= 0) {
            return fd;
        }
        /* Fall through to file-based approach */
    }

    /* File-based fallback: map the shm name to a path in a temp directory */
    const char *tmpdir = getenv("TMPDIR");
    if (!tmpdir || !tmpdir[0]) {
        tmpdir = "/data/local/tmp";
    }

    /* Build path: skip leading '/' in name */
    const char *clean_name = name;
    while (*clean_name == '/') {
        clean_name++;
    }

    char path[512];
    snprintf(path, sizeof(path), "%s/shm_%s", tmpdir, clean_name);

    /* Ensure the temp directory exists */
    mkdir(tmpdir, 0777);

    int fd = open(path, oflag | O_CLOEXEC, mode);
    if (fd < 0) {
        LOGE("shm_open: failed to create '%s': %s", path, strerror(errno));
        return -1;
    }

    return fd;
}

int shm_unlink(const char *name)
{
    /* If we used memfd_create, there's nothing to unlink */
    /* Try file-based cleanup anyway (harmless if file doesn't exist) */
    const char *tmpdir = getenv("TMPDIR");
    if (!tmpdir || !tmpdir[0]) {
        tmpdir = "/data/local/tmp";
    }

    const char *clean_name = name;
    while (*clean_name == '/') {
        clean_name++;
    }

    char path[512];
    snprintf(path, sizeof(path), "%s/shm_%s", tmpdir, clean_name);

    int ret = unlink(path);
    if (ret < 0 && errno != ENOENT) {
        LOGE("shm_unlink: failed to remove '%s': %s", path, strerror(errno));
    }
    /* Return 0 on success or if file doesn't exist (memfd case) */
    return (ret < 0 && errno != ENOENT) ? -1 : 0;
}

/* ========================================================================
 * getrandom() — Fill buffer with random bytes.
 *
 * QEMU uses this in crypto/random-platform.c (guarded by CONFIG_GETRANDOM).
 * We try the real syscall first; if unavailable, read from /dev/urandom.
 * ======================================================================== */

#ifndef GRND_NONBLOCK
#define GRND_NONBLOCK 0x0001
#endif

#ifdef __NR_getrandom
#define HAVE_GETRANDOM_SYSCALL 1
#endif

ssize_t getrandom(void *buf, size_t buflen, unsigned int flags)
{
    if (buflen == 0) {
        /* QEMU calls getrandom(NULL, 0, 0) to check availability */
#ifdef HAVE_GETRANDOM_SYSCALL
        int ret = (int)syscall(__NR_getrandom, buf, buflen, flags);
        if (ret >= 0 || errno != ENOSYS) {
            return ret;
        }
#endif
        return 0; /* pretend success for the availability check */
    }

#ifdef HAVE_GETRANDOM_SYSCALL
    ssize_t ret = (ssize_t)syscall(__NR_getrandom, buf, buflen, flags);
    if (ret >= 0) {
        return ret;
    }
    if (errno != ENOSYS) {
        return -1;
    }
    /* Fall through to /dev/urandom */
#endif

    /* Fallback: read from /dev/urandom */
    int fd = open("/dev/urandom", O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        LOGE("getrandom: cannot open /dev/urandom: %s", strerror(errno));
        return -1;
    }

    ssize_t total = 0;
    char *p = (char *)buf;
    while (total < (ssize_t)buflen) {
        ssize_t n = read(fd, p + total, buflen - total);
        if (n < 0) {
            if (errno == EINTR) continue;
            close(fd);
            return -1;
        }
        if (n == 0) break;
        total += n;
    }
    close(fd);
    return total;
}
