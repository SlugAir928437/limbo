/*
 Copyright (C) Max Kastanas 2012

 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 *
 */
#include <jni.h>
#include <stdio.h>
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
#include <sys/ioctl.h>
#include <stdint.h>
#include <inttypes.h>
#include <ucontext.h>
#include <sys/syscall.h>
#include <dirent.h>
#include <pthread.h>
#include <time.h>
#include <string.h>
#include <android/native_window_jni.h>
#include "vm-executor-jni.h"
#include "limbo_compat.h"

#define MSG_BUFSIZE 1024
#define MAX_STRING_LEN 1024

static int started = 0;
void * handle = 0;

/* Limbo：显示方式（缩放模式），0=拉伸至全屏 1=等比缩放 2=原始 1:1，-1=未设置。
 * 对应 QEMU 侧 ui/sdl2.c 的 limbo_sdl_scale_mode 和 ui/gtk.c 的
 * limbo_gtk_scale_mode（见 patches/qemu-v11.0.0-limbo-sdl-scale.patch）。
 * 允许在 VM 启动前调用（那时 qemu 库还没 dlopen，先缓存，等 start() 加载完
 * 库再统一下发）；运行中调用则立刻通过 set_qemu_var() 生效。 */
static int limbo_pending_sdl_scale_mode = -1;

void * loadLib(const char* lib_filename, const char * lib_path_str) {

	char res_msg[MAX_STRING_LEN];
	sprintf(res_msg, "Loading lib: %s", lib_path_str);
	LOGV("%s", res_msg);
	void *ldhandle = dlopen(lib_filename, RTLD_LAZY);
    if(ldhandle == NULL) {
        // try with the lib path
        printf("trying loading with full path: %s\n", lib_path_str);
        ldhandle = dlopen(lib_path_str, RTLD_LAZY);
    }
	return ldhandle;

}

void setup_jni(JNIEnv* env, jobject thiz, jstring storage_dir, jstring base_dir) {

    const char *base_dir_str = NULL;
    const char *storage_dir_str = NULL;

    if (base_dir != NULL)
		base_dir_str = (*env)->GetStringUTFChars(env, base_dir, 0);

    if (storage_dir != NULL)
		storage_dir_str = (*env)->GetStringUTFChars(env, storage_dir, 0);

	jclass c = (*env)->GetObjectClass(env, thiz);
	set_jni(env, thiz, c, storage_dir_str, base_dir_str);
}

int get_qemu_var(JNIEnv* env, jobject thiz, const char * var) {
    char res_msg[MSG_BUFSIZE + 1] = { 0 };
    
	dlerror();
    void * obj = dlsym (handle, var);
    const char *dlsym_error = dlerror();
    if (dlsym_error) {
        LOGE("Cannot load symbol %s: %s\n", var, dlsym_error);
    	return -1;
    }
    int * var_ptr = (int *) obj;
    return *var_ptr;
}

void set_qemu_var(JNIEnv* env, jobject thiz, const char * var, jint jvalue){
	int value_int = (jint) jvalue;

	dlerror();
    void * obj = dlsym (handle, var);
    const char *dlsym_error = dlerror();
    if (dlsym_error) {
        LOGE("Cannot load symbol %s: %s\n", var, dlsym_error);
    	return;
    }
    int * var_ptr = (int *) obj;
    *var_ptr = value_int;
}

/* ---------------------------------------------------------------------------
 * AGL display bridge and KernelSU root request.
 *
 * The AGL backend lives inside libqemu-system-*.so, which is loaded here with
 * dlopen() and therefore not linked into liblimbo.so: every entry point is
 * resolved with dlsym().  LimboAglActivity hands over its Surface so the guest
 * is rendered straight into the app window; that is the only display the
 * gunyah/gzvm accelerated VMs can use, because those have to run in this
 * process as root (see nativeGrantRoot()).
 * ------------------------------------------------------------------------- */

typedef void (*agl_set_window_fn)(ANativeWindow *window, uint32_t refresh_rate);
typedef void (*agl_cleanup_fn)(void);
typedef void (*agl_pointer_fn)(float x, float y, int buttons);
typedef void (*agl_scroll_fn)(float x, float y);
typedef void (*agl_key_fn)(int scan_code, bool down);

static void *get_qemu_symbol(const char *name) {
    void *obj;

    if (handle == NULL) {
        return NULL;
    }
    dlerror();
    obj = dlsym(handle, name);
    if (dlerror() != NULL) {
        return NULL;
    }
    return obj;
}

/* The activity owns a SurfaceView and therefore gets its Surface before the VM
 * is started, i.e. while libqemu-system-*.so is still unloaded.  Keep the last
 * window here until the library (and with it the AGL backend) is available. */
static ANativeWindow *agl_buffered_window = NULL;
static uint32_t agl_buffered_rate = 0;

static void buffer_agl_window(ANativeWindow *window, uint32_t rate) {
    ANativeWindow *old = agl_buffered_window;

    if (window != NULL) {
        ANativeWindow_acquire(window);
    }
    agl_buffered_window = window;
    agl_buffered_rate = rate;
    if (old != NULL) {
        ANativeWindow_release(old);
    }
}

/* Hands the buffered window to the backend; called once QEMU is initialized. */
static void flush_buffered_agl_window(void) {
    agl_set_window_fn set_window =
            (agl_set_window_fn) get_qemu_symbol("agl_set_window");
    ANativeWindow *window = agl_buffered_window;

    if (set_window == NULL || window == NULL) {
        return;
    }
    agl_buffered_window = NULL;
    set_window(window, agl_buffered_rate);
    ANativeWindow_release(window);
}

/* Stops and joins the AGL render thread.  Must run after qemu_cleanup() so the
 * renderer is gone before the library is closed; the backend state is reset so
 * a later VM start in this process can use AGL again. */
static void cleanup_agl_display(void) {
    agl_cleanup_fn cleanup = (agl_cleanup_fn) get_qemu_symbol("agl_cleanup");

    if (cleanup != NULL) {
        cleanup();
    }
}

JNIEXPORT void JNICALL Java_com_limbo_emu_jni_AglDisplay_setSurface(
        JNIEnv* env, jclass clazz, jobject surface, jfloat refresh_rate) {
    agl_set_window_fn set_window =
            (agl_set_window_fn) get_qemu_symbol("agl_set_window");
    ANativeWindow *window = NULL;
    uint32_t rate = 0;

    if (surface != NULL) {
        window = ANativeWindow_fromSurface(env, surface);
        if (window == NULL) {
            LOGE("Could not get an ANativeWindow for the AGL surface\n");
            return;
        }
    }
    /* The backend expects milli-Hz; 0 lets it keep its own default. */
    if (refresh_rate > 0) {
        rate = (uint32_t) (refresh_rate * 1000.0 + 0.5);
    }
    if (set_window == NULL) {
        /* QEMU is not loaded yet (the Surface arrives first): buffer it. */
        buffer_agl_window(window, rate);
    } else {
        set_window(window, rate);
    }
    if (window != NULL) {
        ANativeWindow_release(window);
    }
}

JNIEXPORT void JNICALL Java_com_limbo_emu_jni_AglDisplay_pointer(
        JNIEnv* env, jclass clazz, jfloat x, jfloat y, jint buttons) {
    agl_pointer_fn pointer =
            (agl_pointer_fn) get_qemu_symbol("limbo_agl_pointer");

    if (pointer != NULL) {
        pointer(x, y, buttons);
    }
}

JNIEXPORT void JNICALL Java_com_limbo_emu_jni_AglDisplay_scroll(
        JNIEnv* env, jclass clazz, jfloat x, jfloat y) {
    agl_scroll_fn scroll =
            (agl_scroll_fn) get_qemu_symbol("limbo_agl_scroll");

    if (scroll != NULL) {
        scroll(x, y);
    }
}

JNIEXPORT void JNICALL Java_com_limbo_emu_jni_AglDisplay_key(
        JNIEnv* env, jclass clazz, jint scan_code, jboolean down) {
    agl_key_fn key = (agl_key_fn) get_qemu_symbol("limbo_agl_key");

    if (key != NULL) {
        key(scan_code, down == JNI_TRUE);
    }
}

/*
 * KernelSU root request, following the ALS reference implementation: the
 * reboot syscall with the KernelSU magic hands back the driver fd and
 * ioctl(_IO('K', 1)) then grants root to the calling thread.  Magisk/su cannot
 * elevate an existing process, which is why the fallback for devices without
 * KernelSU is the separate root child process (headless).
 */
JNIEXPORT jint JNICALL Java_com_limbo_emu_jni_RootUtils_grantRoot(
        JNIEnv* env, jclass clazz) {
    int fd = -1;
    int status;

    errno = 0;
    syscall(SYS_reboot, 0xDEADBEEF, 0xCAFEBABE, 0, &fd);
    if (fd < 0) {
        return ENODEV;
    }
    if (ioctl(fd, _IO('K', 1), NULL) < 0) {
        status = errno;
        close(fd);
        return status;
    }
    close(fd);
    return geteuid() == 0 ? 0 : EPERM;
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *pvt) {
	return JNI_VERSION_1_2;
}

JNIEXPORT void JNICALL Java_com_limbo_emu_jni_VMExecutor_nativeRefreshScreen(
                JNIEnv* env, jobject thiz, jint jvalue) {
    if(handle == NULL) {
    	return;
    }
    set_qemu_var(env, thiz, "limbo_vga_full_update", jvalue);
}

JNIEXPORT void JNICALL Java_com_limbo_emu_jni_VMExecutor_setvncrefreshrate(
		JNIEnv* env, jobject thiz, jint jvalue) {
    set_qemu_var(env, thiz, "vnc_refresh_interval_inc", jvalue);
    set_qemu_var(env, thiz, "vnc_refresh_interval_base", jvalue);
}


JNIEXPORT void JNICALL Java_com_limbo_emu_jni_VMExecutor_setSDLRefreshRateDefault(
		JNIEnv* env, jobject thiz, jint jvalue) {
    set_qemu_var(env, thiz, "gui_refresh_interval_default", jvalue);
}

JNIEXPORT void JNICALL Java_com_limbo_emu_jni_VMExecutor_setSDLRefreshRateIdle(
		JNIEnv* env, jobject thiz, jint jvalue) {
            printf("setting sdl refresh rate idle: %d\n", jvalue);
    set_qemu_var(env, thiz, "gui_refresh_interval_idle", jvalue);
}


JNIEXPORT jint JNICALL Java_com_limbo_emu_jni_VMExecutor_getSDLRefreshRateDefault(
		JNIEnv* env, jobject thiz) {
    
    int res = get_qemu_var(env, thiz, "gui_refresh_interval_default");
    return res;
}

JNIEXPORT jint JNICALL Java_com_limbo_emu_jni_VMExecutor_getSDLRefreshRateIdle(
		JNIEnv* env, jobject thiz) {

    int res = get_qemu_var(env, thiz, "gui_refresh_interval_idle");
    printf("getting sdl refresh rate idle: %d\n", res);
    return res;
}

/*
 * 设置 SDL 显示方式（缩放模式）：
 *   0 = 拉伸至全屏（拉伸铺满，忽略客户机宽高比）
 *   1 = 等比缩放（保持客户机宽高比，居中留边，默认）
 *   2 = 原始分辨率 1:1（居中，超出部分裁剪）
 * 静态 native（VMExecutor.setSDLScaleMode），因此第二个参数是 jclass。
 */
JNIEXPORT void JNICALL Java_com_limbo_emu_jni_VMExecutor_setSDLScaleMode(
		JNIEnv* env, jclass clazz, jint jmode) {
    limbo_pending_sdl_scale_mode = (int) jmode;

    if (handle == NULL) {
        /* qemu 库还没加载（VM 尚未启动）：缓存，等 start() 里再下发 */
        return;
    }
    set_qemu_var(env, NULL, "limbo_sdl_scale_mode", limbo_pending_sdl_scale_mode);
    set_qemu_var(env, NULL, "limbo_gtk_scale_mode", limbo_pending_sdl_scale_mode);
}



JNIEXPORT jint JNICALL Java_com_limbo_emu_jni_VMExecutor_getvncrefreshrate(
		JNIEnv* env, jobject thiz) {

    int res = get_qemu_var(env, thiz, "vnc_refresh_interval_inc");
    return res;
}

/* ---------------------------------------------------------------------------
 * 致命信号兜底回溯（崩溃原因的唯一出口）
 *
 * gunyah/gzvm 的 VM 跑在应用进程里，而 KernelSU 的进程内提权会把本进程变成
 * uid 0：这种进程 Android 的 crash_dump/debuggerd 不会转储（logcat 里只剩一行
 * "crash_dump helper failed to exec, or was killed"），崩溃线程的调用栈就没了。
 * 而 bionic 的 FORTIFY（"pthread_mutex_lock called on a destroyed mutex
 * (0x...)") 只打印一个地址，看不出是谁在锁这把锁。
 *
 * root 子进程（RootVmLauncher）更糟：它 uid 0，一样拿不到 tombstone，而 App 的
 * logcat 又只能读到自己 uid 的日志——子进程里 QEMU/ART/linker 的输出对 App 完全
 * 不可见。子进程崩溃时来不及写状态文件，父进程就只看到状态停在 "starting"，
 * 报错自然没有任何下文。
 *
 * 所以这里对全部会立刻终结进程的信号装 handler：用 ucontext 里的帧指针走栈 +
 * dladdr 解析，每条都写两份——logcat（adb 侧）和真正的 fd 2（root 子进程的
 * stderr 是父进程的管道，父进程会把它落到 root_vm_stderr.log 并显示在报错里，
 * 这条链路不受 uid 限制）。打完再把原来的 handler 装回去并重新触发信号，
 * tombstone/debuggerd 的行为和以前保持一致。handler 内只做栈读取、snprintf、
 * dladdr、write(2) 和 log 写入，不分配内存、不加锁。
 * ------------------------------------------------------------------------- */
#define LIMBO_BT_MAX_FRAMES   48
#define LIMBO_BT_STACK_LIMIT  (16 * 1024 * 1024)
#define LIMBO_BT_SCAN_BYTES   (64 * 1024)
#define LIMBO_BT_SCAN_MAX     12
#define LIMBO_BT_LINE_MAX     320

/* 需要兜底回溯的信号：这些都是"进程立刻消失、来不及自己交代"的致命信号。 */
static const int limbo_bt_signals[] = {
	SIGABRT, SIGSEGV, SIGBUS, SIGILL, SIGFPE, SIGTRAP,
};
#define LIMBO_BT_SIGNAL_COUNT \
	((int) (sizeof(limbo_bt_signals) / sizeof(limbo_bt_signals[0])))

/* 装我们之前保存的原 handler（通常是 bionic 的 debuggerd handler） */
static struct sigaction limbo_bt_prev[LIMBO_BT_SIGNAL_COUNT];
static int limbo_bt_installed;

static const char *limbo_bt_signal_name(int sig) {
	switch (sig) {
	case SIGABRT: return "SIGABRT";
	case SIGSEGV: return "SIGSEGV";
	case SIGBUS:  return "SIGBUS";
	case SIGILL:  return "SIGILL";
	case SIGFPE:  return "SIGFPE";
	case SIGTRAP: return "SIGTRAP";
	default:      return "signal";
	}
}

/* 一行日志写两份：logcat + 真正的 fd 2。
 * 必须用 write(2) 而不是 fprintf(stderr)：limbo_logutils.h 把 printf/fprintf 全部
 * 改道到 logcat 了，用 stdio 写 stderr 反而出不去（qemu 自己的报错就是这样被吞掉的）。 */
static void limbo_bt_write(const char *line) {
	char buf[LIMBO_BT_LINE_MAX + 16];
	int n;

	__android_log_write(ANDROID_LOG_FATAL, "LIMBO-CRASH", line);

	n = snprintf(buf, sizeof(buf), "LIMBO-CRASH: %s\n", line);
	if (n <= 0)
		return;
	if ((size_t) n > sizeof(buf) - 1)
		n = (int) (sizeof(buf) - 1);
	/* stderr 可能不存在（App 进程里通常是 /dev/null），失败无所谓 */
	if (write(STDERR_FILENO, buf, (size_t) n) < 0) {
		/* ignore */
	}
}

static void limbo_bt_dump_frame(int index, uintptr_t pc) {
	char line[LIMBO_BT_LINE_MAX];
	Dl_info info;

	if (pc == 0)
		return;

	memset(&info, 0, sizeof(info));
	if (dladdr((void *) pc, &info) != 0 && info.dli_fname != NULL) {
		if (info.dli_sname != NULL) {
			snprintf(line, sizeof(line),
					"#%02d pc %016" PRIxPTR "  %s (%s+0x%" PRIxPTR ")",
					index, pc, info.dli_fname, info.dli_sname,
					pc - (uintptr_t) info.dli_saddr);
		} else {
			snprintf(line, sizeof(line),
					"#%02d pc %016" PRIxPTR "  %s +0x%" PRIxPTR,
					index, pc, info.dli_fname,
					pc - (uintptr_t) info.dli_fbase);
		}
	} else {
		snprintf(line, sizeof(line), "#%02d pc %016" PRIxPTR "  <unknown>",
				index, pc);
	}
	limbo_bt_write(line);
}

static int limbo_bt_frame_ok(uintptr_t fp, uintptr_t sp) {
	return (fp & (sizeof(uintptr_t) - 1)) == 0 &&
			fp >= sp && fp < sp + LIMBO_BT_STACK_LIMIT;
}

/* 按各 ABI 的帧记录布局走栈：AArch64/x86_64 都是 {上一帧 fp, 返回地址}。
 * 返回实际打印的帧数，帧数太少时调用方再退化成扫栈。 */
static int limbo_bt_walk_fp(uintptr_t pc, uintptr_t fp, uintptr_t sp, uintptr_t lr) {
	int index = 0;

	limbo_bt_dump_frame(index++, pc);
	if (lr != 0 && lr != pc)
		limbo_bt_dump_frame(index++, lr);

	while (index < LIMBO_BT_MAX_FRAMES && limbo_bt_frame_ok(fp, sp)) {
#if defined(__aarch64__) || defined(__x86_64__)
		uintptr_t next_fp = ((uintptr_t *) fp)[0]; /* 保存的上一帧 fp */
		uintptr_t ret = ((uintptr_t *) fp)[1];     /* 保存的返回地址 */
		if (ret == 0 || next_fp <= fp)             /* 必须递增，防死循环 */
			break;
		limbo_bt_dump_frame(index++, ret);
		fp = next_fp;
#else
		break;
#endif
	}
	return index;
}

/* 帧指针链断了（某个库不带帧指针）时，退化成在栈上扫返回地址：
 * 只挑能解析到共享库、且不在 libc 里的候选，避免刷屏。 */
static void limbo_bt_scan_stack(uintptr_t sp) {
	uintptr_t *p;
	uintptr_t *end = (uintptr_t *) (sp + LIMBO_BT_SCAN_BYTES);
	int found = 0;

	for (p = (uintptr_t *) sp;
			p < end && found < LIMBO_BT_SCAN_MAX; p++) {
		Dl_info info;
		uintptr_t val = *p;

		if (val < 0x1000)
			continue;
		memset(&info, 0, sizeof(info));
		if (dladdr((void *) val, &info) == 0 || info.dli_fname == NULL)
			continue;
		if (strstr(info.dli_fname, "libc.so") != NULL)
			continue;
		limbo_bt_dump_frame(100 + found, val);
		found++;
	}
}

static void limbo_bt_fatal(int sig, siginfo_t *si, void *uctx) {
	uintptr_t pc = 0, fp = 0, sp = 0, lr = 0;
	char line[LIMBO_BT_LINE_MAX];
	int frames, i;

	(void) si;

#if defined(__aarch64__)
	{
		ucontext_t *uc = (ucontext_t *) uctx;
		pc = (uintptr_t) uc->uc_mcontext.pc;
		fp = (uintptr_t) uc->uc_mcontext.regs[29];
		sp = (uintptr_t) uc->uc_mcontext.sp;
		lr = (uintptr_t) uc->uc_mcontext.regs[30];
	}
#elif defined(__x86_64__)
	{
		ucontext_t *uc = (ucontext_t *) uctx;
		pc = (uintptr_t) uc->uc_mcontext.gregs[REG_RIP];
		fp = (uintptr_t) uc->uc_mcontext.gregs[REG_RBP];
		sp = (uintptr_t) uc->uc_mcontext.gregs[REG_RSP];
	}
#else
	(void) uctx;
#endif

	snprintf(line, sizeof(line),
			"===== %s (%d) backtrace (tid %d, pc %p, fp %p, sp %p) =====",
			limbo_bt_signal_name(sig), sig, (int) gettid(),
			(void *) pc, (void *) fp, (void *) sp);
	limbo_bt_write(line);

	if (pc != 0) {
		frames = limbo_bt_walk_fp(pc, fp, sp, lr);
		if (frames < 3) {
			limbo_bt_write("----- stack scan (fallback) -----");
			limbo_bt_scan_stack(sp);
		}
	}
	/* 收尾再打一行摘要：父进程的错误提示只带日志末尾几行，
	 * 关键结论（哪个信号、崩在哪）必须落在最后一行。 */
	snprintf(line, sizeof(line),
			"===== end of backtrace: %s tid %d pc %p =====",
			limbo_bt_signal_name(sig), (int) gettid(), (void *) pc);
	limbo_bt_write(line);

	/* 把信号交还给系统原来的处理（通常是 bionic 的 debuggerd），
	 * 保证 tombstone/debuggerd 的既有行为不变。 */
	for (i = 0; i < LIMBO_BT_SIGNAL_COUNT; i++) {
		if (limbo_bt_signals[i] != sig)
			continue;
		sigaction(sig, &limbo_bt_prev[i], NULL);
		break;
	}
	raise(sig);
}

static void limbo_install_crash_handler(void) {
	struct sigaction sa;
	int i, installed = 0;

	if (limbo_bt_installed)
		return;

	memset(&sa, 0, sizeof(sa));
	sa.sa_sigaction = limbo_bt_fatal;
	sa.sa_flags = SA_SIGINFO | SA_RESTART;
	sigemptyset(&sa.sa_mask);

	for (i = 0; i < LIMBO_BT_SIGNAL_COUNT; i++) {
		if (sigaction(limbo_bt_signals[i], &sa, &limbo_bt_prev[i]) != 0) {
			LOGE("%s backtrace handler install failed: %s",
					limbo_bt_signal_name(limbo_bt_signals[i]),
					strerror(errno));
			continue;
		}
		installed++;
	}
	if (installed == 0)
		return;
	limbo_bt_installed = 1;
	LOGI("backtrace handlers installed for %d fatal signal(s)", installed);
}

/* ---------------------------------------------------------------------------
 * 大小核（big.LITTLE）宿主上的 KVM 亲和性收敛
 *
 * KVM for Arm 不支持一个 VM 的 vCPU 在大小核之间来回迁移。用 -cpu host 时 QEMU
 * 会把从宿主读到的 cache/ID 寄存器（KVM 的 demux cache 寄存器：CLIDR/CCSIDR/
 * CTR）写回内核，而大小核这两组寄存器取值不同，内核直接用 EINVAL 拒绝，表现为：
 *   Could not set register demuxed reg 6020000000110000 to ... (is ...)
 *   Failed to put registers after init: Invalid argument
 * （QEMU maintainer Peter Maydell 对这一报错的解释：cache config ID 寄存器不
 * 匹配，常见原因就是 big.LITTLE 宿主没有把 VM 限制在单一簇上。）
 *
 * 官方给的做法就是 CPU pinning：把 VM 限制在一类核上跑。这里在 qemu_init()
 * 之前把当前线程的亲和性收敛到一个同构簇；此后 QEMU 创建的所有线程（vCPU、
 * 显示、IO）都由该线程 fork 出来，会继承这个掩码。
 *
 * 实测有两个坑要一起绕开，否则这套收敛形同虚设：
 *  1) 读亲和性的成功判断写错了：sched_getaffinity 成功时返回的是"写入的字节
 *     数"（cpumask_size()），不是 0。原来按 "返回值 != 0" 判断成功，于是永远
 *     走失败分支，日志里那条 "sched_getaffinity failed: No such file or
 *     directory" 其实是过期的 errno（返回值本身是正的）。改成"返回值 < 0 才算
 *     失败"，并保留 libc -> /proc/self/status 的 Cpus_allowed_list ->
 *     /sys/devices/system/cpu/online 逐层回退兜底。
 *  2) 写亲和性：按 系统调用 -> libc sched_setaffinity 回退，写完回读校验
 *     （qemu_init() 之后再校一次，日志里能直接看出有没有跑出簇外）。
 *
 * 只对 "-accel kvm" 生效：TCG 是纯软件模拟，gunyah/gzvm 有自己的加速器语义，
 * 都不该被这里改动。
 * ------------------------------------------------------------------------- */

#define LIMBO_MAX_CLUSTERS     16
#define LIMBO_CLUSTER_KEY_LEN  64

/* CPU 掩码自己实现，不用 bionic 的 cpu_set_t / CPU_* 宏：这些符号在 sched.h
 * 里被 #if defined(__USE_GNU) 包着，而本文件由 `-include $(LOGUTILS)` 先把
 * limbo_logutils.h（会 include <string.h>）处理掉，features.h 至此定稿，文件
 * 里再 #define _GNU_SOURCE 已经来不及，只有加编译参数才有效。直接走
 * sched_getaffinity / sched_setaffinity 两个系统调用可以完全绕开这个坑。
 * 掩码布局与内核一致：unsigned long 数组，位 cpu 表示第 cpu 号 CPU。 */
#define LIMBO_CPU_BITS        (8 * (int) sizeof(unsigned long))
#define LIMBO_CPU_MASK_LONGS  16
#define LIMBO_CPU_MAX         (LIMBO_CPU_MASK_LONGS * LIMBO_CPU_BITS)

struct limbo_cpu_mask {
	unsigned long bits[LIMBO_CPU_MASK_LONGS];
};

/* 一个"同构簇"：key 是簇标识，cpus 是簇内"当前允许运行"的 CPU */
struct limbo_cluster {
	char key[LIMBO_CLUSTER_KEY_LEN];
	long speed;     /* 该簇最大频率(kHz)，读不到为 0 */
	struct limbo_cpu_mask cpus;
	int ncpu;
};

static void limbo_mask_zero(struct limbo_cpu_mask *mask) {
	memset(mask->bits, 0, sizeof(mask->bits));
}

static void limbo_mask_set(struct limbo_cpu_mask *mask, int cpu) {
	mask->bits[cpu / LIMBO_CPU_BITS] |= 1UL << (cpu % LIMBO_CPU_BITS);
}

static int limbo_mask_isset(const struct limbo_cpu_mask *mask, int cpu) {
	return (mask->bits[cpu / LIMBO_CPU_BITS] >> (cpu % LIMBO_CPU_BITS)) & 1UL;
}

/* 把 "0-3,5,7-9" 形式的 CPU 列表解析进掩码，返回新置位的 CPU 个数。 */
static int limbo_parse_cpu_list(const char *list, struct limbo_cpu_mask *mask) {
	int count = 0;

	while (list != NULL && *list != '\0') {
		char *endptr;
		long first, last, cpu;

		if (*list < '0' || *list > '9') {
			list++;
			continue;
		}
		first = strtol(list, &endptr, 10);
		list = endptr;
		last = first;
		if (*list == '-') {
			list++;
			last = strtol(list, &endptr, 10);
			list = endptr;
		}
		if (last < first) {
			long swap = first;
			first = last;
			last = swap;
		}
		for (cpu = first; cpu <= last; cpu++) {
			if (cpu < 0 || cpu >= LIMBO_CPU_MAX)
				continue;
			if (!limbo_mask_isset(mask, (int) cpu)) {
				limbo_mask_set(mask, (int) cpu);
				count++;
			}
		}
	}
	return count;
}

/* 读 procfs/sysfs 的文本，在内容里找 key（key 为空串表示整篇都要），
 * 再从 key 之后解析 CPU 列表。返回置位的 CPU 个数，0 表示没读到。 */
static int limbo_read_cpu_list_from_file(const char *path, const char *key,
		struct limbo_cpu_mask *mask) {
	char buf[4096];
	size_t used = 0;
	ssize_t n;
	int fd = open(path, O_RDONLY | O_CLOEXEC);
	char *pos;

	if (fd < 0)
		return 0;
	while (used < sizeof(buf) - 1) {
		n = read(fd, buf + used, sizeof(buf) - 1 - used);
		if (n <= 0)
			break;
		used += (size_t) n;
	}
	close(fd);
	if (used == 0)
		return 0;
	buf[used] = '\0';

	pos = strstr(buf, key);
	if (pos == NULL)
		return 0;
	pos += strlen(key);
	return limbo_parse_cpu_list(pos, mask);
}

/* libc 导出的 sched_getaffinity / sched_setaffinity。用 dlsym 取而不做声明：
 * limbo_logutils.h 已经把 features.h 定稿，<sched.h> 里的声明（要 __USE_GNU
 * 才可见）拿不到；dlsym 不需要声明，符号真的不存在时还能安全跳过。 */
typedef int (*limbo_sched_affinity_fn)(int pid, size_t set_size, const void *mask);

static limbo_sched_affinity_fn limbo_libc_affinity_fn(const char *name) {
	return (limbo_sched_affinity_fn) dlsym(RTLD_DEFAULT, name);
}

/* 掩码 -> "0-3,5" 形式的字符串，只为日志核对。 */
static void limbo_mask_to_string(const struct limbo_cpu_mask *mask,
		char *buf, size_t bufsize) {
	int cpu, start = -1;
	size_t used = 0;

	if (bufsize == 0)
		return;
	buf[0] = '\0';
	for (cpu = 0; cpu <= LIMBO_CPU_MAX; cpu++) {
		int isset = (cpu < LIMBO_CPU_MAX) ? limbo_mask_isset(mask, cpu) : 0;

		if (isset && start < 0) {
			start = cpu;
			continue;
		}
		if (!isset && start >= 0) {
			char one[32];

			if (start == cpu - 1)
				snprintf(one, sizeof(one), "%s%d",
						used ? "," : "", start);
			else
				snprintf(one, sizeof(one), "%s%d-%d",
						used ? "," : "", start, cpu - 1);
			if (used + strlen(one) >= bufsize)
				break;
			strcat(buf, one);
			used += strlen(one);
			start = -1;
		}
	}
}

/* sub 是否完全落在 super 内，用于回读校验。 */
static int limbo_mask_subset(const struct limbo_cpu_mask *sub,
		const struct limbo_cpu_mask *super) {
	int cpu;

	for (cpu = 0; cpu < LIMBO_CPU_MAX; cpu++) {
		if (limbo_mask_isset(sub, cpu) && !limbo_mask_isset(super, cpu))
			return 0;
	}
	return 1;
}

/* 掩码按结构体大小整体传给内核；内核只拷贝 cpumask_size() 那几字节，多余的
 * 位被忽略，所以传大一点是安全的（这样在 CPU 数不同的设备上都不用改）。 */
static int limbo_get_affinity(struct limbo_cpu_mask *mask) {
	limbo_sched_affinity_fn libc_fn;
	long ret;
	int err;

	limbo_mask_zero(mask);
	errno = 0;
	/* 这个系统调用成功时返回写入的字节数（cpumask_size()，一定 > 0），
	 * 只有返回值 < 0 才是失败。 */
	ret = syscall(__NR_sched_getaffinity, 0, sizeof(*mask), mask);
	if (ret >= 0)
		return 0;
	err = errno;
	LOGW("cpu affinity: sched_getaffinity syscall failed: ret=%ld errno=%d (%s)",
			ret, err, strerror(err));

	/* 下面几层是为了"读不到也要能继续收敛"：只要能拿到 CPU 列表就行。 */
	limbo_mask_zero(mask);
	libc_fn = limbo_libc_affinity_fn("sched_getaffinity");
	if (libc_fn != NULL && libc_fn(0, sizeof(*mask), mask) == 0) {
		LOGI("cpu affinity: allowed cpus read via libc sched_getaffinity");
		return 0;
	}

	limbo_mask_zero(mask);
	if (limbo_read_cpu_list_from_file("/proc/self/status",
			"Cpus_allowed_list:", mask) > 0) {
		LOGI("cpu affinity: allowed cpus read from /proc/self/status");
		return 0;
	}

	limbo_mask_zero(mask);
	if (limbo_read_cpu_list_from_file("/sys/devices/system/cpu/online",
			"", mask) > 0) {
		LOGW("cpu affinity: allowed cpus unavailable, assuming every online "
				"cpu is usable");
		return 0;
	}

	LOGW("cpu affinity: cannot determine the allowed cpu set");
	return -1;
}

static int limbo_set_affinity(const struct limbo_cpu_mask *mask) {
	limbo_sched_affinity_fn libc_fn;
	long ret;
	int err;

	errno = 0;
	/* 这个系统调用成功返回 0，失败返回 -1。 */
	ret = syscall(__NR_sched_setaffinity, 0, sizeof(*mask), mask);
	if (ret == 0)
		return 0;
	err = errno;
	LOGW("cpu affinity: sched_setaffinity syscall failed: ret=%ld errno=%d (%s)",
			ret, err, strerror(err));

	libc_fn = limbo_libc_affinity_fn("sched_setaffinity");
	if (libc_fn != NULL && libc_fn(0, sizeof(*mask), mask) == 0) {
		LOGI("cpu affinity: mask applied via libc sched_setaffinity");
		return 0;
	}

	LOGW("cpu affinity: sched_setaffinity failed in every way");
	return -1;
}

/* 读 sysfs 文本文件开头的十进制整数；失败返回 -1 */
static long limbo_read_sysfs_long(const char *path) {
	char buf[64];
	ssize_t n;
	int fd = open(path, O_RDONLY | O_CLOEXEC);

	if (fd < 0)
		return -1;
	n = read(fd, buf, sizeof(buf) - 1);
	close(fd);
	if (n <= 0)
		return -1;
	buf[n] = '\0';
	return strtol(buf, NULL, 10);
}

/* 读 sysfs 文本文件到 buf 并去掉首尾空白；读到内容返回 1，否则 0 */
static int limbo_read_sysfs_string(const char *path, char *buf, size_t bufsize) {
	ssize_t n;
	size_t len;
	int fd = open(path, O_RDONLY | O_CLOEXEC);

	if (fd < 0 || bufsize < 2)
		return 0;
	n = read(fd, buf, bufsize - 1);
	close(fd);
	if (n <= 0)
		return 0;
	buf[n] = '\0';

	len = strlen(buf);
	while (len > 0 && (buf[len - 1] == '\n' || buf[len - 1] == '\r' ||
			buf[len - 1] == ' ' || buf[len - 1] == '\t'))
		buf[--len] = '\0';
	return len > 0;
}

/* argv 里是否请求了 KVM 加速（-accel kvm） */
static int limbo_args_use_kvm(int argc, char **argv) {
	int i;

	for (i = 0; i + 1 < argc; i++) {
		if (argv[i] == NULL || argv[i + 1] == NULL)
			continue;
		if (strcmp(argv[i], "-accel") != 0)
			continue;
		if (strcmp(argv[i + 1], "kvm") == 0 ||
				strncmp(argv[i + 1], "kvm,", 4) == 0)
			return 1;
	}
	return 0;
}

/* argv 里的 vCPU 数（-smp N / -smp cpus=N[,...]）；未知返回 0 */
static int limbo_args_smp(int argc, char **argv) {
	int i;

	for (i = 0; i + 1 < argc; i++) {
		const char *val;
		long n;

		if (argv[i] == NULL || argv[i + 1] == NULL)
			continue;
		if (strcmp(argv[i], "-smp") != 0)
			continue;
		val = argv[i + 1];
		if (strncmp(val, "cpus=", 5) == 0)
			val += 5;
		n = strtol(val, NULL, 10);
		if (n > 0 && n < LIMBO_CPU_MAX)
			return (int) n;
	}
	return 0;
}

/* 为 cpu 生成簇标识与簇内最大频率(kHz)，读到返回 1：
 *   1) cpuN/cpufreq/related_cpus    —— cpufreq policy 就是硬件簇
 *   2) cpuN/cpu_capacity            —— EAS 算力，同簇相同
 *   3) cpuN/cpufreq/cpuinfo_max_freq
 * 都读不到返回 0（该 CPU 不参与收敛，绝不因此让 VM 起不来） */
static int limbo_cpu_cluster_key(int cpu, char *key, size_t keysize, long *speed) {
	char path[160];
	char value[LIMBO_CLUSTER_KEY_LEN];
	long freq, capacity;

	*speed = 0;

	snprintf(path, sizeof(path),
			"/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", cpu);
	freq = limbo_read_sysfs_long(path);
	if (freq > 0)
		*speed = freq;

	snprintf(path, sizeof(path),
			"/sys/devices/system/cpu/cpu%d/cpufreq/related_cpus", cpu);
	if (limbo_read_sysfs_string(path, value, sizeof(value))) {
		snprintf(key, keysize, "policy:%s", value);
		return 1;
	}

	snprintf(path, sizeof(path),
			"/sys/devices/system/cpu/cpu%d/cpu_capacity", cpu);
	capacity = limbo_read_sysfs_long(path);
	if (capacity > 0) {
		snprintf(key, keysize, "capacity:%ld", capacity);
		if (*speed <= 0)
			*speed = capacity;
		return 1;
	}

	if (freq > 0) {
		snprintf(key, keysize, "freq:%ld", freq);
		return 1;
	}
	return 0;
}

/* 收敛结果，给 qemu_init() 之后的回读校验用；limbo_pin_cpus == 0 表示没 pin。 */
static struct limbo_cpu_mask limbo_pin_mask;
static int limbo_pin_cpus = 0;

/* 把当前线程收敛到单一同构簇。返回被选中的 CPU 数，0 表示未做改动。 */
static int limbo_pin_to_single_cluster(int argc, char **argv) {
	struct limbo_cluster clusters[LIMBO_MAX_CLUSTERS];
	struct limbo_cpu_mask allowed;
	int nclusters = 0, best = -1, smp = limbo_args_smp(argc, argv);
	int cpu, i;
	long best_score = -1;
	char line[128];

	limbo_pin_cpus = 0;
	limbo_mask_zero(&allowed);
	if (limbo_get_affinity(&allowed) != 0) {
		LOGW("cpu affinity: cpu set unknown, cannot pin; KVM will most "
				"likely fail on big.LITTLE hosts, consider TCG");
		return 0;
	}

	for (cpu = 0; cpu < LIMBO_CPU_MAX; cpu++) {
		char key[LIMBO_CLUSTER_KEY_LEN];
		int found = -1;
		long speed = 0;

		if (!limbo_mask_isset(&allowed, cpu))
			continue;
		if (!limbo_cpu_cluster_key(cpu, key, sizeof(key), &speed))
			continue;

		for (i = 0; i < nclusters; i++) {
			if (strcmp(clusters[i].key, key) == 0) {
				found = i;
				break;
			}
		}
		if (found < 0) {
			if (nclusters >= LIMBO_MAX_CLUSTERS)
				continue;
			found = nclusters++;
			memset(&clusters[found], 0, sizeof(clusters[found]));
			snprintf(clusters[found].key, sizeof(clusters[found].key),
					"%s", key);
		}
		if (speed > clusters[found].speed)
			clusters[found].speed = speed;
		limbo_mask_set(&clusters[found].cpus, cpu);
		clusters[found].ncpu++;
	}

	if (nclusters == 0) {
		LOGW("cpu affinity: cpu topology unavailable, "
				"KVM may fail on big.LITTLE hosts");
		return 0;
	}
	if (nclusters == 1) {
		LOGI("cpu affinity: only one cpu cluster allowed, no pinning needed");
		return 0;
	}

	/* 以"算力 x 可用核数"选簇：核数按 -smp 截断，这样 1 个 vCPU 时选最快
	 * 的那颗，多个 vCPU 时选核算力总量最大的一簇。 */
	for (i = 0; i < nclusters; i++) {
		long effective = clusters[i].ncpu;
		long score;

		if (smp > 0 && effective > smp)
			effective = smp;
		score = clusters[i].speed > 0 ?
				clusters[i].speed * effective : effective;
		if (score > best_score) {
			best_score = score;
			best = i;
		}
	}

	if (best < 0)
		return 0;

	if (limbo_set_affinity(&clusters[best].cpus) != 0) {
		LOGW("cpu affinity: cannot pin; KVM will most likely fail on "
				"big.LITTLE hosts, consider TCG");
		return 0;
	}

	/* 记下来：qemu_init() 之后还要回读一次，确认线程没跑出这一簇。 */
	limbo_pin_mask = clusters[best].cpus;
	limbo_pin_cpus = clusters[best].ncpu;

	limbo_mask_to_string(&clusters[best].cpus, line, sizeof(line));
	LOGI("cpu affinity: pinned to %d cpu(s) [%s] of cluster '%s' (%d cluster(s))",
			clusters[best].ncpu, line, clusters[best].key, nclusters);

	if (limbo_get_affinity(&allowed) == 0) {
		limbo_mask_to_string(&allowed, line, sizeof(line));
		LOGV("cpu affinity: kernel now reports [%s]", line);
	}

	return clusters[best].ncpu;
}

/* qemu_init() 之后回读一次亲和性：vCPU 线程继承本线程的掩码，万一它跑到了
 * 簇外，KVM 写回 cache/ID 寄存器（demux CCSIDR）就会再次失败，日志要能看出来。 */
static void limbo_verify_affinity(const char *when) {
	struct limbo_cpu_mask mask;
	char line[128];

	if (limbo_pin_cpus == 0)
		return;
	if (limbo_get_affinity(&mask) != 0) {
		LOGI("cpu affinity %s: unreadable, cannot verify", when);
		return;
	}
	limbo_mask_to_string(&mask, line, sizeof(line));
	if (limbo_mask_subset(&mask, &limbo_pin_mask))
		LOGI("cpu affinity %s: still on the pinned cpus [%s]", when, line);
	else
		LOGW("cpu affinity %s: outside the pinned cpus, now [%s]; KVM "
				"cache/ID register write may fail", when, line);
}

/* ---------------------------------------------------------------------------
 * 亲和性看门狗
 *
 * 一次性 pin 挡不住 Android 的 cpuset 迁移：进程被挪进另一个 cpuset 时，内核
 * 会把该组线程的掩码重置成 cpuset 的集合，实测 qemu_init() 期间就会发生
 * （日志 "outside the pinned cpus, now [4-7]"），于是 vCPU 又可能跑到异构核上，
 * 触发 KVM 写回 demux CCSIDR 失败。所以这里起一个有界的小线程：在 VM 头几秒
 * 里盯着本线程和 QEMU 的 vCPU 线程（线程名 "CPU <n>/KVM"），发现跑出簇外就收
 * 回来，跑完自动退出，不常驻。
 * ------------------------------------------------------------------------- */

#define LIMBO_WATCHDOG_ROUNDS       30
#define LIMBO_WATCHDOG_INTERVAL_MS  300

/* 把已存在的 vCPU 线程收回簇内，返回被改动的线程数。 */
static int limbo_pin_vcpu_threads(const struct limbo_cpu_mask *mask) {
	DIR *dir = opendir("/proc/self/task");
	struct dirent *ent;
	int pinned = 0;

	if (dir == NULL)
		return 0;

	while ((ent = readdir(dir)) != NULL) {
		struct limbo_cpu_mask current;
		char comm[32] = { 0 };
		char path[64];
		char *endptr;
		long tid;
		int fd;

		if (ent->d_name[0] < '0' || ent->d_name[0] > '9')
			continue;
		tid = strtol(ent->d_name, &endptr, 10);
		if (tid <= 0 || *endptr != '\0')
			continue;

		snprintf(path, sizeof(path), "/proc/self/task/%ld/comm", tid);
		fd = open(path, O_RDONLY | O_CLOEXEC);
		if (fd < 0)
			continue;
		if (read(fd, comm, sizeof(comm) - 1) <= 0) {
			close(fd);
			continue;
		}
		close(fd);

		/* QEMU 的 vCPU 线程名是 "CPU <n>/KVM"，只认这个前缀，
		 * 免得动到显示/IO 线程。 */
		if (strncmp(comm, "CPU ", 4) != 0)
			continue;

		limbo_mask_zero(&current);
		if (syscall(__NR_sched_getaffinity, (int) tid, sizeof(current),
				&current) >= 0 && limbo_mask_subset(&current, mask))
			continue;	/* 已经在簇内，不用动 */

		if (syscall(__NR_sched_setaffinity, (int) tid, sizeof(*mask), mask) == 0)
			pinned++;
	}
	closedir(dir);
	return pinned;
}

static void *limbo_affinity_watchdog(void *unused) {
	struct timespec interval;
	char line[128];
	int round;

	(void) unused;
	interval.tv_sec = LIMBO_WATCHDOG_INTERVAL_MS / 1000;
	interval.tv_nsec = (LIMBO_WATCHDOG_INTERVAL_MS % 1000) * 1000000L;

	for (round = 0; round < LIMBO_WATCHDOG_ROUNDS; round++) {
		struct limbo_cpu_mask now;

		nanosleep(&interval, NULL);

		if (limbo_get_affinity(&now) == 0 &&
				!limbo_mask_subset(&now, &limbo_pin_mask) &&
				limbo_set_affinity(&limbo_pin_mask) == 0) {
			limbo_mask_to_string(&limbo_pin_mask, line, sizeof(line));
			LOGW("cpu affinity watchdog: mask was widened, pinned back to [%s]",
					line);
		}

		if (limbo_pin_vcpu_threads(&limbo_pin_mask) > 0) {
			limbo_mask_to_string(&limbo_pin_mask, line, sizeof(line));
			LOGI("cpu affinity watchdog: re-pinned the vCPU threads to [%s]",
					line);
		}
	}

	LOGV("cpu affinity watchdog: done");
	return NULL;
}

static void limbo_pin_watchdog_start(void) {
	pthread_t thread;

	if (limbo_pin_cpus == 0)
		return;
	if (pthread_create(&thread, NULL, limbo_affinity_watchdog, NULL) == 0) {
		pthread_detach(thread);
		LOGV("cpu affinity watchdog: started (%d x %dms)",
				LIMBO_WATCHDOG_ROUNDS, LIMBO_WATCHDOG_INTERVAL_MS);
	} else {
		LOGW("cpu affinity watchdog: pthread_create failed");
	}
}

/* Shared VM bootstrap used by both the in-process VMExecutor.start() and
 * the root child process (RootVmLauncher.startVm).  In the root child
 * thiz is NULL, so the per-instance JNI wiring (set_jni) is skipped. */
static jstring start_qemu(JNIEnv* env, jobject thiz,
        jstring storage_dir, jstring base_dir,
        jstring lib_filename, jstring lib_path,
        jobjectArray params) {
	int res;
	char res_msg[MSG_BUFSIZE + 1] = { 0 };

	if (started) {
		sprintf(res_msg, "VM Already started");
		LOGV("%s", res_msg);
		return (*env)->NewStringUTF(env, res_msg);
	}

	LOGV("Processing params");

	int argc = 0;
	char ** argv = NULL;

	argc = (*env)->GetArrayLength(env, params);

	argv = (char **) malloc((argc + 1) * sizeof(*argv));
	if (argv == NULL) {
		LOGE("Failed to allocate argv array\n");
		return (*env)->NewStringUTF(env, "Memory allocation failed");
	}
	memset(argv, 0, (argc + 1) * sizeof(*argv));

	for (int i = 0; i < argc; i++) {
        jstring string = (jstring)((*env)->GetObjectArrayElement(env, params, i));
        if (string == NULL) {
            LOGE("Param at index %d is null, skipping\n", i);
            argv[i] = (char *) malloc(1);
            if (argv[i]) argv[i][0] = '\0';
            continue;
        }
		const char *param_str = (*env)->GetStringUTFChars(env, string, 0);
		if (param_str == NULL) {
			LOGE("GetStringUTFChars failed at index %d\n", i);
			(*env)->DeleteLocalRef(env, string);
			// cleanup already allocated args
			for (int j = 0; j < i; j++) {
				free(argv[j]);
			}
			free(argv);
			return (*env)->NewStringUTF(env, "Failed to convert Java string");
		}
		int length = strlen(param_str)+1;
        argv[i] = (char *) malloc(length * sizeof(char));
		if (argv[i] == NULL) {
			LOGE("Failed to allocate memory for param %d\n", i);
			(*env)->ReleaseStringUTFChars(env, string, param_str);
			(*env)->DeleteLocalRef(env, string);
			for (int j = 0; j < i; j++) {
				free(argv[j]);
			}
			free(argv);
			return (*env)->NewStringUTF(env, "Memory allocation failed");
		}
		memcpy(argv[i], param_str, length);
		(*env)->ReleaseStringUTFChars(env, string, param_str);
		(*env)->DeleteLocalRef(env, string);
	}

	// QEMU requires argv[argc] == NULL
	argv[argc] = NULL;

	printf("Starting VM\n");
    started = 1;

    //LOAD LIB
	const char *lib_filename_str = NULL;
	if (lib_filename!= NULL)
		lib_filename_str = (*env)->GetStringUTFChars(env, lib_filename, 0);
    const char *lib_path_str = NULL;
    if (lib_path != NULL)
        lib_path_str = (*env)->GetStringUTFChars(env, lib_path, 0);

	if (handle == NULL) {
		handle = loadLib(lib_filename_str, lib_path_str);
	}

	if (!handle) {
		sprintf(res_msg, "Error opening lib: %s :%s", lib_path_str, dlerror());
		LOGV("%s", res_msg);
		// cleanup argv
		for (int i = 0; i < argc; i++) {
			free(argv[i]);
		}
		free(argv);
		started = 0;
		return (*env)->NewStringUTF(env, res_msg);
	}

	if (thiz != NULL) {
		setup_jni(env, thiz, storage_dir, base_dir);
	}
    /* 把 Java 侧选择的显示方式（缩放模式）下发给 QEMU 显示后端，SDL 和 GTK
     * 共用同一个取值：0 = 拉伸至全屏，1 = 等比缩放，2 = 1:1 原始像素。
     * 符号由 patches/qemu-v11.0.0-limbo-sdl-scale.patch 与 GTK 补丁在
     * ui/sdl2.c / ui/gtk.c 里导出；VNC-only 或没打补丁的构建没有这些符号，
     * set_qemu_var() 会打条日志后忽略，不影响启动。 */
    if (limbo_pending_sdl_scale_mode >= 0) {
        set_qemu_var(env, thiz, "limbo_sdl_scale_mode", limbo_pending_sdl_scale_mode);
        set_qemu_var(env, thiz, "limbo_gtk_scale_mode", limbo_pending_sdl_scale_mode);
    }

	// Use correct function signatures to avoid undefined behavior on ARM64
	typedef void (*qemu_init_t)(int argc, char **argv);
	typedef int (*main_t)(int argc, char **argv, char **envp);
    typedef void (*qemu_main_loop_t)(void);
	typedef void (*qemu_cleanup_t)(void);

    qemu_init_t qemu_init = NULL;
    main_t qemu_main = NULL;
    qemu_main_loop_t qemu_main_loop = NULL;
    qemu_cleanup_t qemu_cleanup = NULL;

	/* 装致命信号兜底回溯：uid 0 的进程拿不到 tombstone（root 子进程连 logcat
	 * 都对 App 不可见），QEMU 崩了只能靠它出栈，并顺带写一份到 stderr 让父进程
	 * 落进 root_vm_stderr.log。 */
	limbo_install_crash_handler();

	/* big.LITTLE 宿主 + KVM：必须赶在 qemu_init() 之前把本线程收敛到单一
	 * 同构簇，否则 vCPU 在大小核之间迁移会让 cache/ID 寄存器写回失败
	 * （"Failed to put registers after init: Invalid argument"）。放在这里
	 * 也早于任何 QEMU 线程的创建，掩码会被它们继承。 */
	limbo_pin_cpus = 0;
	if (limbo_args_use_kvm(argc, argv)) {
		limbo_pin_to_single_cluster(argc, argv);
		/* 看门狗必须在 qemu_init() 之前起来：vCPU 的寄存器初始化就在
		 * qemu_init() 里完成，期间掩码若被 cpuset 迁移放开就来不及收。 */
		limbo_pin_watchdog_start();
	}

	dlerror();
	qemu_init = (qemu_init_t) dlsym(handle, "qemu_init");
	const char *dlsym_error = dlerror();
	if (dlsym_error) { // older versions of qemu use "main"
		LOGE("Cannot find qemu symbol 'qemu_init' trying 'main': %s\n", dlsym_error);
	    qemu_main = (main_t) dlsym(handle, "main");
	    dlsym_error = dlerror();
	    if (dlsym_error) {
        	LOGE("Cannot find qemu symbol 'qemu_init' or 'main': %s\n", dlsym_error);
        	dlclose(handle);
        	handle = NULL;
        	started = 0;
        	for (int i = 0; i < argc; i++) {
        		free(argv[i]);
        	}
        	free(argv);
        	return (*env)->NewStringUTF(env, dlsym_error);
        }
        qemu_main(argc, argv, NULL);
    } else { // new versions of qemu: qemu_init takes only 2 args
        qemu_main_loop = (qemu_main_loop_t) dlsym(handle, "qemu_main_loop");
	    dlsym_error = dlerror();
	    if (dlsym_error) {
        	LOGE("Cannot find qemu symbol 'qemu_main_loop': %s\n", dlsym_error);
        	dlclose(handle);
        	handle = NULL;
        	started = 0;
        	for (int i = 0; i < argc; i++) {
        		free(argv[i]);
        	}
        	free(argv);
        	return (*env)->NewStringUTF(env, dlsym_error);
        }

        qemu_cleanup = (qemu_cleanup_t) dlsym(handle, "qemu_cleanup");
	    dlsym_error = dlerror();
	    if (dlsym_error) {
        	LOGE("Cannot find qemu symbol 'qemu_cleanup': %s\n", dlsym_error);
        	dlclose(handle);
        	handle = NULL;
        	started = 0;
        	for (int i = 0; i < argc; i++) {
        		free(argv[i]);
        	}
        	free(argv);
        	return (*env)->NewStringUTF(env, dlsym_error);
        }

        qemu_init(argc, argv);
        /* KVM 的 vCPU 线程在 qemu_init() 里创建，继承上面收敛出来的掩码；
         * 这里回读一次，确认没有跑出同构簇（跑出去就会再次触发 demux CCSIDR
         * 写回失败）。 */
        limbo_verify_affinity("after qemu_init");
        /* The display backends exist now: deliver a Surface that arrived
         * before the library was loaded (AGL display). */
        flush_buffered_agl_window();
        qemu_main_loop();
        qemu_cleanup();
	}

	sprintf(res_msg, "Closing lib: %s", lib_path_str);
	LOGV("%s", res_msg);
	/* Tear the AGL renderer down before unloading the library (no-op unless
	 * the VM was started with "-display agl"). */
	cleanup_agl_display();
	dlclose(handle);
	handle = NULL;
	started = 0;

    if (lib_path != NULL && lib_path_str != NULL)
        (*env)->ReleaseStringUTFChars(env, lib_path, lib_path_str);

	// free argv
	for (int i = 0; i < argc; i++) {
		free(argv[i]);
	}
	free(argv);

	sprintf(res_msg, "VM shutdown");
	LOGV("%s", res_msg);
    return (*env)->NewStringUTF(env, res_msg);
}

JNIEXPORT jstring JNICALL Java_com_limbo_emu_jni_VMExecutor_start(
        JNIEnv* env, jobject thiz,
		jstring storage_dir, jstring base_dir,
		jstring lib_filename, jstring lib_path,
		jobjectArray params) {
	return start_qemu(env, thiz, storage_dir, base_dir,
			lib_filename, lib_path, params);
}

/* Entry point for the root child process (RootVmLauncher.main): runs the
 * very same in-process VM bootstrap but without any Android UI object. */
JNIEXPORT jstring JNICALL Java_com_limbo_emu_jni_RootVmLauncher_startVm(
        JNIEnv* env, jclass clazz,
		jstring storage_dir, jstring base_dir,
		jstring lib_filename, jstring lib_path,
		jobjectArray params) {
	return start_qemu(env, NULL, storage_dir, base_dir,
			lib_filename, lib_path, params);
}


JNIEXPORT jstring JNICALL Java_com_limbo_emu_jni_VMExecutor_stop(
		JNIEnv* env, jobject thiz, jint jint_restart) {
	char res_msg[MSG_BUFSIZE + 1] = { 0 };

	int restart_int = jint_restart;

    if(restart_int) {
        typedef void (*reset_vm_t)(int);
        dlerror();
        reset_vm_t qemu_system_reset_request = (reset_vm_t) dlsym(handle, "qemu_system_reset_request");
        const char *dlsym_error = dlerror();
        if (dlsym_error) {
            LOGE("Cannot load symbol 'qemu_system_reset_request': %s\n", dlsym_error);
            return (*env)->NewStringUTF(env, res_msg);
        }
        qemu_system_reset_request(6); //SHUTDOWN_CAUSE_GUEST_RESET
        sprintf(res_msg, "VM Restart Request");
    } else {
        typedef void (*stop_vm_t)(int);
        dlerror();
        stop_vm_t qemu_system_shutdown_request = (stop_vm_t) dlsym(handle, "qemu_system_shutdown_request");
        const char *dlsym_error = dlerror();
        if (dlsym_error) {
            LOGE("Cannot load symbol 'qemu_system_shutdown_request': %s\n", dlsym_error);
            return (*env)->NewStringUTF(env, res_msg);
        }
        qemu_system_shutdown_request(3); //SHUTDOWN_CAUSE_HOST_SIGNAL
        sprintf(res_msg, "VM Stop Request");
	}

	LOGV("%s", res_msg);

	started = restart_int;

	return (*env)->NewStringUTF(env, res_msg);
}

// JNI End

