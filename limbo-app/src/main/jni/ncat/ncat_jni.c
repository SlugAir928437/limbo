/*
 * ncat_jni.c -- JNI 桥接层：在应用进程内运行 Nmap ncat。
 *
 * 背景
 * ----
 * APK 关闭安装时解压（android:extractNativeLibs="false" /
 * packaging.jniLibs.useLegacyPackaging = false）后，jniLibs 里的 .so 不再被
 * 复制到 nativeLibraryDir，因此无法再像以前那样把 libncat.so 当独立可执行文件
 * 用 ProcessBuilder 拉起（Android 10+ / targetSdk>=29 也禁止执行应用私有目录下
 * 的文件）。
 *
 * 做法
 * ----
 * 把 ncat 编译成真正的共享库（见 make_ncat.sh），Java 侧通过
 * System.loadLibrary("ncat") 直接从 APK 加载（链接器支持从 APK 内 mmap，无需
 * 解压）。本文件导出一个 fork() 入口：
 *
 *   1. fork() 子进程：ncat 存在大量全局状态（getopt 的 optind、o 选项结构、
 *      nsock 等），fork 可以保证每次运行都是干净状态，与“每次新起进程”语义一致；
 *      同时 ncat 出错/--help 时内部会调用 exit()，若不隔离会直接杀掉整个 App。
 *   2. 子进程把 stdin/stdout/stderr 重定向到 Java 侧创建的管道，然后直接调用
 *      libncat.so 内导出的 main()（ncat_main.c 里的 main），返回后 _exit()。
 *   3. 子进程内把 exit() 替换为 _exit()：fork 出来的子进程绝不能运行 JVM/ART
 *      的 atexit 清理逻辑（可能死锁），退出码改为写入专用管道交给 Java 读取。
 *
 * 这样 ncat 依旧以“独立进程”的形态运行，但完全不需要安装时解压任何 JNI 库。
 */

#include <jni.h>

#include <dirent.h>
#include <errno.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include <sys/types.h>

/* ncat_main.c 里定义的是 main()；改用 asm 标签引用，避免直接调用 main 的告警。
 * 该符号在最终链接时会被 version script 置为 local，不会对外导出。 */
extern int ncat_main(int argc, char *argv[]) __asm__("main");

/* getopt 的解析位置是 libc 全局变量；fork 前若宿主（QEMU 等）动过它，ncat 的
 * 参数解析会从错误的位置开始，这里显式复位。 */
extern int optind;

/* ------------------------------------------------------------------------- */
/* 子进程退出码回传                                                          */
/*                                                                            */
/* fork 之后子进程只能执行不依赖 JVM 的代码。这里用一个普通管道把退出码写回      */
/* Java，避免依赖 waitpid()（libcore 的 Process 回收线程会用 waitpid(-1) 抢先    */
/* 回收子进程，导致拿不到退出码）。                                            */
/* ------------------------------------------------------------------------- */
static int g_exit_status_fd = -1;

/* ncat 内部通过 exit() 结束进程（--help、参数错误等都会走到 bye()->exit()）。
 * 在子进程里把 exit() 换成 _exit()，跳过 JVM/ART 的 atexit、stdio flush 等清理，
 * 只把退出码写回管道。符号被 version script 置为 local，不会插桩宿主的 exit()。 */
__attribute__((noreturn)) void exit(int status);

__attribute__((noreturn)) void exit(int status)
{
    if (g_exit_status_fd >= 0) {
        int value = status;
        ssize_t ignored = write(g_exit_status_fd, &value, sizeof(value));
        (void) ignored;
    }
    _exit(status);
}

/* 关闭除 0/1/2 以及 keep_fd 之外所有继承来的 fd。
 * fork 出来的子进程不应持有父进程（QEMU、socket 等）的文件描述符。 */
static void close_other_fds(int keep_fd)
{
    DIR *dir = opendir("/proc/self/fd");
    if (dir == NULL)
        return;

    int dir_fd = dirfd(dir);
    struct dirent *entry;
    while ((entry = readdir(dir)) != NULL) {
        int fd = atoi(entry->d_name);
        if (fd > STDERR_FILENO && fd != dir_fd && fd != keep_fd)
            close(fd);
    }
    closedir(dir);
}

/* 把 Java 传入的 String[] 复制成 C 的 argv（fork 前完成所有 JNI 调用）。 */
static int build_argv(JNIEnv *env, jobjectArray jargv, char ***out_argv)
{
    int argc = (*env)->GetArrayLength(env, jargv);
    char **argv = (char **) calloc((size_t) argc + 1, sizeof(char *));
    if (argv == NULL)
        return -1;

    int i;
    for (i = 0; i < argc; i++) {
        jstring js = (jstring) (*env)->GetObjectArrayElement(env, jargv, i);
        if (js == NULL)
            continue;
        const char *utf = (*env)->GetStringUTFChars(env, js, NULL);
        argv[i] = (utf != NULL) ? strdup(utf) : NULL;
        if (utf != NULL)
            (*env)->ReleaseStringUTFChars(env, js, utf);
        (*env)->DeleteLocalRef(env, js);
    }
    argv[argc] = NULL;
    *out_argv = argv;
    return argc;
}

static void free_argv(char **argv, int argc)
{
    if (argv == NULL)
        return;
    int i;
    for (i = 0; i < argc; i++)
        free(argv[i]);
    free(argv);
}

/*
 * 启动 ncat。
 *
 * argv            : 传给 ncat 的参数，argv[0] 建议为 "ncat"
 * stdin_read_fd   : 子进程作为 stdin 的管道读端
 * stdin_write_fd  : 管道写端（子进程必须关闭，否则 Java 关掉写端后读不到 EOF）
 * stdout_read_fd  : 管道读端（子进程必须关闭）
 * stdout_write_fd : 子进程作为 stdout/stderr 的管道写端
 * status_write_fd : 子进程写回退出码的管道写端
 *
 * 返回子进程 pid（>0），失败返回负的 errno。
 */
JNIEXPORT jint JNICALL
Java_com_werebug_androidnetcat_NativeNcat_fork(JNIEnv *env, jobject thiz,
                                               jobjectArray jargv,
                                               jint stdin_read_fd,
                                               jint stdin_write_fd,
                                               jint stdout_read_fd,
                                               jint stdout_write_fd,
                                               jint status_write_fd)
{
    char **argv = NULL;
    int argc = build_argv(env, jargv, &argv);
    if (argc < 0) {
        free_argv(argv, 0);
        return -ENOMEM;
    }

    /* fork 之前设置好退出码管道，子进程会继承这个 fd（以及 COW 内存里的值）。 */
    g_exit_status_fd = (int) status_write_fd;

    pid_t pid = fork();
    if (pid < 0) {
        int err = errno;
        g_exit_status_fd = -1;
        free_argv(argv, argc);
        return -err;
    }

    if (pid == 0) {
        /* ---------------- 子进程 ---------------- */
        /* 这里只能做不依赖 JVM 的事情：重定向 fd -> 调用 ncat_main -> _exit */
        if (dup2(stdin_read_fd, STDIN_FILENO) < 0)
            _exit(127);
        if (dup2(stdout_write_fd, STDOUT_FILENO) < 0)
            _exit(127);
        if (dup2(stdout_write_fd, STDERR_FILENO) < 0)
            _exit(127);

        /* stdout_write_fd 与 status_write_fd 需要保留，其余 fd 全部关闭 */
        close_other_fds((int) status_write_fd);

        /* 恢复默认信号行为，避免继承父进程/JVM 的处理方式 */
        signal(SIGPIPE, SIG_DFL);
        signal(SIGINT, SIG_DFL);
        signal(SIGHUP, SIG_DFL);
        signal(SIGTERM, SIG_DFL);

        /* 重置 getopt 状态，保证每次都从 argv[1] 开始解析 */
        optind = 1;

        /* stdout/stderr 是管道（非 tty），默认全缓冲；而子进程最终用 _exit 结束，
         * 不会做 stdio flush。这里改为无缓冲，保证 ncat --help 之类的 printf
         * 输出能立即到达 UI。 */
        setvbuf(stdout, NULL, _IONBF, 0);
        setvbuf(stderr, NULL, _IONBF, 0);

        int rc = ncat_main(argc, argv);
        exit(rc); /* 我们的 exit() -> 写回退出码 + _exit，noreturn */
    }

    /* ---------------- 父进程 ---------------- */
    g_exit_status_fd = -1;
    free_argv(argv, argc);
    return (jint) pid;
}

/* 向子进程发送信号（kill 的薄封装）。 */
JNIEXPORT void JNICALL
Java_com_werebug_androidnetcat_NativeNcat_sendSignal(JNIEnv *env, jobject thiz,
                                                     jint pid, jint sig)
{
    if (pid > 0)
        kill((pid_t) pid, (int) sig);
}
