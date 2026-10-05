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
#include <stdint.h>
#include <inttypes.h>
#include <ucontext.h>
#include <sys/syscall.h>
#include <string.h>
#include "vm-executor-jni.h"
#include "limbo_compat.h"

#define MSG_BUFSIZE 1024
#define MAX_STRING_LEN 1024

static int started = 0;
void * handle = 0;

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



JNIEXPORT jint JNICALL Java_com_limbo_emu_jni_VMExecutor_getvncrefreshrate(
		JNIEnv* env, jobject thiz) {

    int res = get_qemu_var(env, thiz, "vnc_refresh_interval_inc");
    return res;
}

/* ---------------------------------------------------------------------------
 * SIGABRT 兜底回溯
 *
 * gunyah/gzvm 的 VM 跑在应用进程里，而 KernelSU 的进程内提权会把本进程变成
 * uid 0：这种进程 Android 的 crash_dump/debuggerd 不会转储（logcat 里只剩一行
 * "crash_dump helper failed to exec, or was killed"），崩溃线程的调用栈就没了。
 * 而 bionic 的 FORTIFY（"pthread_mutex_lock called on a destroyed mutex
 * (0x...)") 只打印一个地址，看不出是谁在锁这把锁。
 *
 * 这里自己装一个 SIGABRT handler：用 ucontext 里的帧指针走栈 + dladdr 解析，
 * 打完再把 bionic 原来的 handler 装回去并重新触发信号，tombstone/debuggerd 的
 * 行为和以前保持一致。handler 内只做栈读取、snprintf、dladdr 和 log 写入，
 * 不分配内存、不加锁。
 * ------------------------------------------------------------------------- */
#define LIMBO_BT_MAX_FRAMES   48
#define LIMBO_BT_STACK_LIMIT  (16 * 1024 * 1024)
#define LIMBO_BT_SCAN_BYTES   (64 * 1024)
#define LIMBO_BT_SCAN_MAX     12

/* 装我们之前保存的原 SIGABRT 处理（通常是 bionic 的 debuggerd handler） */
static struct sigaction limbo_bt_prev_sigabrt;
static int limbo_bt_installed;

static void limbo_bt_log_line(const char *line) {
	__android_log_write(ANDROID_LOG_FATAL, "LIMBO-CRASH", line);
}

static void limbo_bt_dump_frame(int index, uintptr_t pc) {
	char line[256];
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
	limbo_bt_log_line(line);
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

static void limbo_bt_sigabrt(int sig, siginfo_t *si, void *uctx) {
	uintptr_t pc = 0, fp = 0, sp = 0, lr = 0;
	char line[160];
	int frames;

	(void) sig;
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
			"===== SIGABRT backtrace (tid %d, pc %p, fp %p, sp %p) =====",
			(int) gettid(), (void *) pc, (void *) fp, (void *) sp);
	limbo_bt_log_line(line);

	if (pc != 0) {
		frames = limbo_bt_walk_fp(pc, fp, sp, lr);
		if (frames < 3) {
			limbo_bt_log_line("----- stack scan (fallback) -----");
			limbo_bt_scan_stack(sp);
		}
	}
	limbo_bt_log_line("===== end of backtrace =====");

	/* 把信号交还给系统原来的处理（通常是 bionic 的 debuggerd），
	 * 保证 tombstone/debuggerd 的既有行为不变。 */
	sigaction(SIGABRT, &limbo_bt_prev_sigabrt, NULL);
	raise(SIGABRT);
}

static void limbo_install_crash_handler(void) {
	struct sigaction sa;

	if (limbo_bt_installed)
		return;

	memset(&sa, 0, sizeof(sa));
	sa.sa_sigaction = limbo_bt_sigabrt;
	sa.sa_flags = SA_SIGINFO | SA_RESTART;
	sigemptyset(&sa.sa_mask);
	if (sigaction(SIGABRT, &sa, &limbo_bt_prev_sigabrt) != 0) {
		LOGE("SIGABRT backtrace handler install failed: %s", strerror(errno));
		return;
	}
	limbo_bt_installed = 1;
	LOGI("SIGABRT backtrace handler installed");
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

/* 掩码按结构体大小整体传给内核；内核只拷贝 cpumask_size() 那几字节，多余的
 * 位被忽略，所以传大一点是安全的（这样在 CPU 数不同的设备上都不用改）。 */
static int limbo_get_affinity(struct limbo_cpu_mask *mask) {
	if (syscall(__NR_sched_getaffinity, 0, sizeof(*mask), mask) != 0) {
		LOGW("cpu affinity: sched_getaffinity failed: %s", strerror(errno));
		return -1;
	}
	return 0;
}

static int limbo_set_affinity(const struct limbo_cpu_mask *mask) {
	if (syscall(__NR_sched_setaffinity, 0, sizeof(*mask), mask) != 0) {
		LOGW("cpu affinity: sched_setaffinity failed: %s", strerror(errno));
		return -1;
	}
	return 0;
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

/* 把当前线程收敛到单一同构簇。返回被选中的 CPU 数，0 表示未做改动。 */
static int limbo_pin_to_single_cluster(int argc, char **argv) {
	struct limbo_cluster clusters[LIMBO_MAX_CLUSTERS];
	struct limbo_cpu_mask allowed;
	int nclusters = 0, best = -1, smp = limbo_args_smp(argc, argv);
	int cpu, i;
	long best_score = -1;
	char line[128];

	limbo_mask_zero(&allowed);
	if (limbo_get_affinity(&allowed) != 0)
		return 0;

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

	if (limbo_set_affinity(&clusters[best].cpus) != 0)
		return 0;

	line[0] = '\0';
	for (cpu = 0; cpu < LIMBO_CPU_MAX; cpu++) {
		char one[8];

		if (!limbo_mask_isset(&clusters[best].cpus, cpu))
			continue;
		snprintf(one, sizeof(one), "%s%d", line[0] ? "," : "", cpu);
		if (strlen(line) + strlen(one) >= sizeof(line))
			break;
		strcat(line, one);
	}
	LOGI("cpu affinity: pinned to %d cpu(s) [%s] of cluster '%s' (%d cluster(s))",
			clusters[best].ncpu, line, clusters[best].key, nclusters);
	return clusters[best].ncpu;
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
    /* Same mode value drives both the SDL and the GTK display backends:
     * 0 = stretch, 1 = keep aspect ratio, 2 = 1:1 pixels.  Symbols that are
     * not exported by the loaded qemu library (e.g. VNC-only builds) are
     * simply ignored by set_qemu_var(). */
    // set_qemu_var(env, thiz, "limbo_sdl_scale_mode", sdl_scale_mode);
    // set_qemu_var(env, thiz, "limbo_gtk_scale_mode", sdl_scale_mode);

	// Use correct function signatures to avoid undefined behavior on ARM64
	typedef void (*qemu_init_t)(int argc, char **argv);
	typedef int (*main_t)(int argc, char **argv, char **envp);
    typedef void (*qemu_main_loop_t)(void);
	typedef void (*qemu_cleanup_t)(void);

    qemu_init_t qemu_init = NULL;
    main_t qemu_main = NULL;
    qemu_main_loop_t qemu_main_loop = NULL;
    qemu_cleanup_t qemu_cleanup = NULL;

	/* 装 SIGABRT 兜底回溯：uid 0 的进程拿不到 tombstone，QEMU 崩了只能靠它出栈 */
	limbo_install_crash_handler();

	/* big.LITTLE 宿主 + KVM：必须赶在 qemu_init() 之前把本线程收敛到单一
	 * 同构簇，否则 vCPU 在大小核之间迁移会让 cache/ID 寄存器写回失败
	 * （"Failed to put registers after init: Invalid argument"）。放在这里
	 * 也早于任何 QEMU 线程的创建，掩码会被它们继承。 */
	if (limbo_args_use_kvm(argc, argv))
		limbo_pin_to_single_cluster(argc, argv);

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
        qemu_main_loop();
        qemu_cleanup();
	}

	sprintf(res_msg, "Closing lib: %s", lib_path_str);
	LOGV("%s", res_msg);
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

