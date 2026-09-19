#ifndef LIMBO_COMPAT_FILESYSTEM_H
#define LIMBO_COMPAT_FILESYSTEM_H

#include <stdio.h>
#include <sys/stat.h>

#if defined(__ANDROID__) && !defined(major)
#define major(dev) ((unsigned int)((((unsigned long long)(dev)) >> 8) & 0xfff) | \
                    ((unsigned int)((((unsigned long long)(dev)) >> 32) & ~0xfff)))
#endif

#if defined(__ANDROID__) && !defined(minor)
#define minor(dev) ((unsigned int)(((unsigned long long)(dev)) & 0xff) | \
                    ((unsigned int)((((unsigned long long)(dev)) >> 12) & ~0xff)))
#endif

#ifdef __cplusplus
extern "C" {
#endif

#define ENABLE_ASF 1

FILE *android_fopen(const char *path, const char *mode);
int android_open(const char *pathname, int flags, mode_t mode);
int android_close(int fd);
int android_stat(const char *pathname, struct stat *statbuf);
int android_unlink(const char *pathname);
int android_mkstemp(char *template); // <-- 新增声明

#ifdef __cplusplus
}
#endif

#endif // LIMBO_COMPAT_FILESYSTEM_H
