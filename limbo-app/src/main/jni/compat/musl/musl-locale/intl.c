/*
 * Limbo: 一个最小可用的 GNU gettext (libintl) 实现。
 *
 * Android 的 bionic 没有 libintl，glib 的 proxy-libintl 子项目编译出来的
 * 是永远返回 msgid 的 stub，QEMU 的 GTK 菜单因此一直显示英文。这里实现
 * 真正读取 .mo 的 gettext 入口，供 glib（dependency('intl') 命中的
 * libintl.a）和 QEMU 使用。
 *
 * 语言与翻译目录：
 *   LIMBO_LANG       界面语言（如 "zh_CN"；未设置时依次看 LANGUAGE /
 *                    LC_ALL / LC_MESSAGES / LANG）
 *   LIMBO_LOCALE_DIR .mo 根目录（<base_dir>/locale）。QEMU 调
 *                    bindtextdomain() 时传的是编译期 LOCALEDIR（设备上
 *                    不存在），找不到时用这个目录兜底。
 */

#include <errno.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <unistd.h>

/* musl-locale/libintl.h 是空的占位文件，原型在 compat/musl/libintl.h */
#include "../libintl.h"

#define LIMBO_DOMAIN_DEFAULT "messages"
#define LIMBO_LOCALE_DEFAULT "/usr/share/locale"
#define LIMBO_LANG_MAX 64
#define LIMBO_PATH_MAX 1024

/* .mo 文件格式（GNU gettext）：小端魔数 0x950412de，交换过来即为大端文件。 */
#define LIMBO_MO_MAGIC 0x950412deU

struct limbo_mo {
    unsigned char *data;
    size_t size;
    int swap;                 /* 1 = 文件是大端，读取时需要交换 */
    unsigned int nstrings;
    unsigned int orig_off;
    unsigned int trans_off;
    unsigned int nplurals;    /* 0 = 没有 Plural-Forms 头 */
};

struct limbo_binding {
    char *domain;
    char *dir;
    struct limbo_binding *next;
};

struct limbo_catalog {
    char *dir;
    char *lang;
    char *domain;
    struct limbo_mo mo;
    int loaded;               /* 1 = 已尝试加载（失败时 mo.data 为 NULL） */
    struct limbo_catalog *next;
};

static pthread_mutex_t limbo_lock = PTHREAD_MUTEX_INITIALIZER;
static struct limbo_binding *limbo_bindings;
static struct limbo_catalog *limbo_catalogs;
static char limbo_domain[LIMBO_LANG_MAX] = LIMBO_DOMAIN_DEFAULT;
static char limbo_codeset[LIMBO_LANG_MAX] = "UTF-8";

static char *limbo_strdup(const char *s)
{
    size_t len = strlen(s) + 1;
    char *copy = malloc(len);

    if (copy)
        memcpy(copy, s, len);
    return copy;
}

static unsigned int limbo_u32(const struct limbo_mo *mo, unsigned int off)
{
    const unsigned char *p;

    if (off > mo->size || mo->size - off < 4)
        return 0;
    p = mo->data + off;
    if (mo->swap)
        return ((unsigned int)p[0] << 24) | ((unsigned int)p[1] << 16) |
               ((unsigned int)p[2] << 8) | (unsigned int)p[3];
    return (unsigned int)p[0] | ((unsigned int)p[1] << 8) |
           ((unsigned int)p[2] << 16) | ((unsigned int)p[3] << 24);
}

static const char *limbo_mem_find(const char *hay, size_t hay_len,
                                  const char *needle, size_t needle_len)
{
    size_t i;

    if (needle_len == 0 || hay_len < needle_len)
        return NULL;
    for (i = 0; i + needle_len <= hay_len; i++) {
        if (memcmp(hay + i, needle, needle_len) == 0)
            return hay + i;
    }
    return NULL;
}

/* 在 .mo 的原文表里查找 msgid（key 可能含内嵌 NUL，因此带长度）。 */
static const char *limbo_mo_find(const struct limbo_mo *mo, const char *key,
                                 size_t key_len, size_t *out_len)
{
    unsigned int i;

    for (i = 0; i < mo->nstrings; i++) {
        unsigned int orig_len = limbo_u32(mo, mo->orig_off + i * 8);
        unsigned int orig_pos = limbo_u32(mo, mo->orig_off + i * 8 + 4);
        unsigned int trans_len, trans_pos;
        const unsigned char *orig;

        if (orig_pos > mo->size || orig_len > mo->size - orig_pos)
            continue;
        orig = mo->data + orig_pos;
        if (orig_len < key_len || memcmp(orig, key, key_len) != 0)
            continue;
        /* 长度可能多带一个结尾 NUL，两种写法都接受 */
        if (orig_len != key_len &&
            !(orig_len == key_len + 1 && orig[key_len] == '\0'))
            continue;

        trans_len = limbo_u32(mo, mo->trans_off + i * 8);
        trans_pos = limbo_u32(mo, mo->trans_off + i * 8 + 4);
        if (trans_pos > mo->size || trans_len > mo->size - trans_pos)
            continue;
        if (out_len)
            *out_len = trans_len;
        return (const char *)(mo->data + trans_pos);
    }
    return NULL;
}

static unsigned int limbo_mo_nplurals(const struct limbo_mo *mo)
{
    size_t len = 0;
    const char *header = limbo_mo_find(mo, "", 0, &len);
    const char *p;

    if (!header)
        return 0;
    p = limbo_mem_find(header, len, "nplurals=", 9);
    if (!p)
        return 0;
    return (unsigned int)strtoul(p + 9, NULL, 10);
}

static int limbo_mo_load(struct limbo_mo *mo, const char *path)
{
    FILE *fp;
    long fsize;
    unsigned char *data;
    unsigned int magic;
    struct limbo_mo tmp;

    fp = fopen(path, "rb");
    if (!fp)
        return 0;
    if (fseek(fp, 0, SEEK_END) != 0 || (fsize = ftell(fp)) < 28) {
        fclose(fp);
        return 0;
    }
    rewind(fp);
    data = malloc((size_t)fsize);
    if (!data) {
        fclose(fp);
        return 0;
    }
    if (fread(data, 1, (size_t)fsize, fp) != (size_t)fsize) {
        free(data);
        fclose(fp);
        return 0;
    }
    fclose(fp);

    magic = (unsigned int)data[0] | ((unsigned int)data[1] << 8) |
            ((unsigned int)data[2] << 16) | ((unsigned int)data[3] << 24);
    memset(&tmp, 0, sizeof(tmp));
    tmp.data = data;
    tmp.size = (size_t)fsize;
    if (magic == LIMBO_MO_MAGIC) {
        tmp.swap = 0;
    } else if (magic == 0xde120495U) {
        tmp.swap = 1;
    } else {
        free(data);
        return 0;
    }
    tmp.nstrings = limbo_u32(&tmp, 8);
    tmp.orig_off = limbo_u32(&tmp, 12);
    tmp.trans_off = limbo_u32(&tmp, 16);
    if (tmp.orig_off > tmp.size || tmp.trans_off > tmp.size ||
        tmp.nstrings > (tmp.size - tmp.orig_off) / 8 ||
        tmp.nstrings > (tmp.size - tmp.trans_off) / 8) {
        free(data);
        return 0;
    }
    tmp.nplurals = limbo_mo_nplurals(&tmp);
    *mo = tmp;
    return 1;
}

/* 取 .mo 里翻译串的第 idx 个复数形式（msgfmt 用 NUL 分隔各形式）。 */
static const char *limbo_plural_pick(const struct limbo_mo *mo,
                                     const char *trans, size_t trans_len,
                                     unsigned long int n, size_t *out_len)
{
    unsigned int idx, i;
    size_t remaining = trans_len;
    const char *p = trans;

    if (mo->nplurals <= 1)
        idx = 0;
    else
        idx = (n == 1) ? 0 : 1;

    for (i = 0; i < idx && remaining > 0; i++) {
        const char *nul = memchr(p, '\0', remaining);

        if (!nul)
            return NULL;
        p = nul + 1;
        remaining = trans_len - (size_t)(p - trans);
    }
    if (remaining == 0)
        return NULL;
    {
        const char *nul = memchr(p, '\0', remaining);

        *out_len = nul ? (size_t)(nul - p) : remaining;
    }
    return p;
}

/* 语言候选：完整语言 + 去掉 _COUNTRY 的基语言（zh_CN -> zh）。 */
static int limbo_current_lang(char *out, size_t out_size)
{
    static const char *const vars[] = {
        "LIMBO_LANG", "LANGUAGE", "LC_ALL", "LC_MESSAGES", "LANG"
    };
    const char *raw = NULL;
    size_t i, n = 0;
    char *p;

    for (i = 0; i < sizeof(vars) / sizeof(vars[0]); i++) {
        const char *value = getenv(vars[i]);

        if (value && *value) {
            raw = value;
            break;
        }
    }
    if (!raw)
        return 0;

    /* LANGUAGE 可能是冒号分隔的列表，取第一个 */
    while (raw[n] && raw[n] != ':' && n + 1 < out_size) {
        out[n] = raw[n];
        n++;
    }
    out[n] = '\0';

    if ((p = strchr(out, '.')) != NULL)   /* 去掉 .UTF-8 */
        *p = '\0';
    if ((p = strchr(out, '@')) != NULL)   /* 去掉 @modifier */
        *p = '\0';
    if ((p = strchr(out, '_')) != NULL) { /* 只保留 language_COUNTRY */
        char *second = strchr(p + 1, '_');

        if (second)
            *second = '\0';
    }
    if (!out[0] || strcmp(out, "C") == 0 || strcmp(out, "POSIX") == 0)
        return 0;
    return 1;
}

static const char *limbo_binding_dir_locked(const char *domain)
{
    struct limbo_binding *b;

    for (b = limbo_bindings; b; b = b->next) {
        if (strcmp(b->domain, domain) == 0)
            return b->dir ? b->dir : LIMBO_LOCALE_DEFAULT;
    }
    return LIMBO_LOCALE_DEFAULT;
}

static struct limbo_catalog *limbo_catalog_get_locked(const char *dir,
                                                      const char *lang,
                                                      const char *domain)
{
    struct limbo_catalog *c;
    char path[LIMBO_PATH_MAX];

    for (c = limbo_catalogs; c; c = c->next) {
        if (strcmp(c->dir, dir) == 0 && strcmp(c->lang, lang) == 0 &&
            strcmp(c->domain, domain) == 0)
            return c;
    }

    c = calloc(1, sizeof(*c));
    if (!c)
        return NULL;
    c->dir = limbo_strdup(dir);
    c->lang = limbo_strdup(lang);
    c->domain = limbo_strdup(domain);
    if (!c->dir || !c->lang || !c->domain) {
        free(c->dir);
        free(c->lang);
        free(c->domain);
        free(c);
        return NULL;
    }
    snprintf(path, sizeof(path), "%s/%s/LC_MESSAGES/%s.mo", dir, lang, domain);
    limbo_mo_load(&c->mo, path);
    c->loaded = 1;
    c->next = limbo_catalogs;
    limbo_catalogs = c;
    return c;
}

static const char *limbo_translate(const char *domain, const char *msgid1,
                                   const char *msgid2, unsigned long int n)
{
    char lang[LIMBO_LANG_MAX];
    char base[LIMBO_LANG_MAX];
    const char *candidates[2];
    const char *fallback;
    const char *result;
    const char *dirs[2];
    const char *env_dir;
    char *key = NULL;
    size_t key_len;
    size_t msgid1_len, msgid2_len;
    int i, count, ndirs = 1, d, found = 0;

    if (!msgid1 || !*msgid1)
        return msgid1;
    fallback = msgid2 ? ((n == 1) ? msgid1 : msgid2) : msgid1;
    if (!domain || !*domain)
        domain = limbo_domain;
    if (!limbo_current_lang(lang, sizeof(lang)))
        return fallback;

    count = 1;
    candidates[0] = lang;
    memcpy(base, lang, sizeof(base));
    {
        char *p = strchr(base, '_');

        if (p) {
            *p = '\0';
            if (base[0]) {
                candidates[1] = base;
                count = 2;
            }
        }
    }

    msgid1_len = strlen(msgid1);
    msgid2_len = msgid2 ? strlen(msgid2) : 0;
    if (msgid2) {
        key_len = msgid1_len + 1 + msgid2_len;
        key = malloc(key_len + 1);
        if (!key)
            return fallback;
        memcpy(key, msgid1, msgid1_len);
        key[msgid1_len] = '\0';
        memcpy(key + msgid1_len + 1, msgid2, msgid2_len);
        key[key_len] = '\0';
    } else {
        key = (char *)msgid1;
        key_len = msgid1_len;
    }

    result = fallback;
    pthread_mutex_lock(&limbo_lock);
    dirs[0] = limbo_binding_dir_locked(domain);
    /* Limbo: QEMU 在 Android 上 bindtextdomain() 用的是编译期 LOCALEDIR，
     * 设备上不存在 qemu.mo。这里再补一个 native 层下发的 LIMBO_LOCALE_DIR
     * 作为后备，不必修改 QEMU 源码。 */
    env_dir = getenv("LIMBO_LOCALE_DIR");
    if (env_dir && *env_dir && strcmp(env_dir, dirs[0]) != 0) {
        dirs[1] = env_dir;
        ndirs = 2;
    }
    for (d = 0; d < ndirs && !found; d++) {
        for (i = 0; i < count; i++) {
            struct limbo_catalog *c = limbo_catalog_get_locked(dirs[d], candidates[i],
                                                               domain);
            const char *trans;
            size_t trans_len = 0;

            if (!c || !c->mo.data)
                continue;
            trans = limbo_mo_find(&c->mo, key, key_len, &trans_len);
            if (!trans)
                continue;
            if (msgid2) {
                size_t picked_len = 0;
                const char *picked = limbo_plural_pick(&c->mo, trans, trans_len,
                                                       n, &picked_len);

                if (picked)
                    result = picked;
            } else {
                result = trans;
            }
            found = 1;
            break;
        }
    }
    pthread_mutex_unlock(&limbo_lock);

    if (msgid2)
        free(key);
    return result;
}

char *gettext(const char *msgid)
{
    return (char *)limbo_translate(NULL, msgid, NULL, 0);
}

char *dgettext(const char *domainname, const char *msgid)
{
    return (char *)limbo_translate(domainname, msgid, NULL, 0);
}

char *dcgettext(const char *domainname, const char *msgid, int category)
{
    (void)category;
    return (char *)limbo_translate(domainname, msgid, NULL, 0);
}

char *ngettext(const char *msgid1, const char *msgid2, unsigned long int n)
{
    return (char *)limbo_translate(NULL, msgid1, msgid2, n);
}

char *dngettext(const char *domainname, const char *msgid1,
                const char *msgid2, unsigned long int n)
{
    return (char *)limbo_translate(domainname, msgid1, msgid2, n);
}

char *dcngettext(const char *domainname, const char *msgid1,
                 const char *msgid2, unsigned long int n, int category)
{
    (void)category;
    return (char *)limbo_translate(domainname, msgid1, msgid2, n);
}

char *textdomain(const char *domainname)
{
    if (domainname && *domainname) {
        pthread_mutex_lock(&limbo_lock);
        snprintf(limbo_domain, sizeof(limbo_domain), "%s", domainname);
        pthread_mutex_unlock(&limbo_lock);
    }
    return limbo_domain;
}

char *bindtextdomain(const char *domainname, const char *dirname)
{
    struct limbo_binding *b;
    char *dir_copy = NULL;
    const char *result;

    if (!domainname || !*domainname) {
        errno = EINVAL;
        return NULL;
    }
    if (dirname) {
        dir_copy = limbo_strdup(dirname);
        if (!dir_copy) {
            errno = ENOMEM;
            return NULL;
        }
    }

    pthread_mutex_lock(&limbo_lock);
    for (b = limbo_bindings; b; b = b->next) {
        if (strcmp(b->domain, domainname) == 0)
            break;
    }
    if (!b) {
        b = calloc(1, sizeof(*b));
        if (b)
            b->domain = limbo_strdup(domainname);
        if (!b || !b->domain) {
            free(b);
            free(dir_copy);
            pthread_mutex_unlock(&limbo_lock);
            errno = ENOMEM;
            return NULL;
        }
        b->next = limbo_bindings;
        limbo_bindings = b;
    }
    if (dir_copy) {
        free(b->dir);
        b->dir = dir_copy;
    }
    result = b->dir ? b->dir : LIMBO_LOCALE_DEFAULT;
    pthread_mutex_unlock(&limbo_lock);
    return (char *)result;
}

char *bind_textdomain_codeset(const char *domainname, const char *codeset)
{
    if (!domainname || !*domainname) {
        errno = EINVAL;
        return NULL;
    }
    if (codeset && *codeset) {
        pthread_mutex_lock(&limbo_lock);
        snprintf(limbo_codeset, sizeof(limbo_codeset), "%s", codeset);
        pthread_mutex_unlock(&limbo_lock);
    }
    return limbo_codeset;
}
