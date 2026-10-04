package com.werebug.androidnetcat

/**
 * 进程内运行 Nmap ncat 的 JNI 封装。
 *
 * 关闭安装时解压（`useLegacyPackaging = false`）后，[libncat.so] 不再被复制到
 * `nativeLibraryDir`，因此不能再作为独立可执行文件执行。这里改为把它当成普通
 * JNI 库加载（链接器直接从 APK 内 mmap，无需解压），由 native 侧的 `fork()`
 * 入口在子进程里调用 ncat 的 `main()`，从而保留“一次运行一个独立进程”的语义。
 *
 * native 实现见 `jni/ncat/ncat_jni.c`。
 */
internal object NativeNcat {

    init {
        System.loadLibrary("ncat")
    }

    /** 主动触发 `System.loadLibrary("ncat")`，便于调用方捕获加载失败。 */
    fun ensureLoaded() {
        // 触碰一次对象即可确保 init 已执行；加载失败会抛 UnsatisfiedLinkError。
    }

    /**
     * 启动一次 ncat 运行。
     *
     * @param argv 传给 ncat 的参数，argv[0] 应为 "ncat"
     * @param stdinReadFd  子进程 stdin（管道读端）
     * @param stdinWriteFd 管道写端，子进程会关闭它
     * @param stdoutReadFd 管道读端，子进程会关闭它
     * @param stdoutWriteFd 子进程 stdout/stderr（管道写端）
     * @param statusWriteFd 子进程回传退出码的管道写端
     * @return 子进程 pid（>0），失败时返回负的 errno
     */
    external fun fork(
        argv: Array<String>,
        stdinReadFd: Int,
        stdinWriteFd: Int,
        stdoutReadFd: Int,
        stdoutWriteFd: Int,
        statusWriteFd: Int
    ): Int

    /** 向子进程发送信号（SIGTERM / SIGKILL）。 */
    external fun sendSignal(pid: Int, sig: Int)
}
