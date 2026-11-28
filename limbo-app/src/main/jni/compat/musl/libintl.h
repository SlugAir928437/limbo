#ifndef LIMBO_LIBINTL_H
#define LIMBO_LIBINTL_H

#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <errno.h>

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Limbo: Android NDK 没有 libintl.h，这里提供声明。
 *
 * format_arg 必须带上：glib/gi18n.h 把 _() 展开成 gettext(String)，如果编译器
 * 不知道 gettext 返回的是"格式串参数本身"，就会把 _() 当成非字面量格式串，
 * 在 glib 的 -Werror=format=2（含 -Wformat-security）下直接报错。
 */
#if defined(__GNUC__) || defined(__clang__)
#define LIMBO_FORMAT_ARG(n) __attribute__((__format_arg__(n)))
#else
#define LIMBO_FORMAT_ARG(n)
#endif

char *gettext(const char *msgid) LIMBO_FORMAT_ARG(1);

char *dgettext(const char *domainname, const char *msgid) LIMBO_FORMAT_ARG(2);

char *dcgettext(const char *domainname, const char *msgid, int category)
	LIMBO_FORMAT_ARG(2);

char *ngettext(const char *msgid1, const char *msgid2, unsigned long int n);

char *dngettext(const char *domainname, const char *msgid1, const char *msgid2,
		unsigned long int n);

char *dcngettext(const char *domainname, const char *msgid1,
		 const char *msgid2, unsigned long int n, int category);

char *textdomain(const char *domainname);

char *bindtextdomain(const char *domainname, const char *dirname);

char *bind_textdomain_codeset(const char *domainname, const char *codeset);

#undef LIMBO_FORMAT_ARG

#ifdef __cplusplus
}
#endif

#endif /* LIMBO_LIBINTL_H */
