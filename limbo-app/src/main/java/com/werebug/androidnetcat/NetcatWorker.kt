package com.werebug.androidnetcat

import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.lang.ref.WeakReference
import java.util.LinkedList

/**
 * 在一个“进程内 fork 出来的子进程”里运行 ncat，并通过管道与 UI 交互。
 *
 * 与旧实现的区别：旧实现用 ProcessBuilder 执行 `nativeLibraryDir/libncat.so`
 * （依赖安装时把 JNI 库解压到 nativeLibraryDir）；现在改为
 * [NativeNcat.fork]，由 libncat.so 直接调用 ncat 的 `main()`，
 * 因此 APK 可以关闭安装时解压（`useLegacyPackaging = false`）。
 */
class NetcatWorker(
    private val ncArgv: List<String>,
    private val sessionActivityRef: WeakReference<NetcatSession>
) : Thread() {

    private val sendQueue = LinkedList<String>()
    private val updateUIHandler: Handler = Handler(Looper.getMainLooper())

    @Volatile
    private var isStopped = false

    @Volatile
    private var exited = false

    @Volatile
    private var ncatPid = -1

    private var processStdin: OutputStream? = null

    private fun updateMainView(message: String) {
        updateUIHandler.post { sessionActivityRef.get()?.appendToOutputView(message) }
    }

    private fun disableMessageViews() {
        updateUIHandler.post { sessionActivityRef.get()?.disableMessageViews() }
    }

    override fun run() {
        execNcatInProcess()
    }

    private fun execNcatInProcess() {
        val stdinPipe: Array<ParcelFileDescriptor>
        val stdoutPipe: Array<ParcelFileDescriptor>
        val statusPipe: Array<ParcelFileDescriptor>
        try {
            stdinPipe = ParcelFileDescriptor.createPipe()
            stdoutPipe = ParcelFileDescriptor.createPipe()
            statusPipe = ParcelFileDescriptor.createPipe()
        } catch (t: Throwable) {
            updateMainView("\nFailed to create pipes: ${t.message}\n")
            return
        }

        try {
            val pid = NativeNcat.fork(
                ncArgv.toTypedArray(),
                stdinPipe[0].fd, stdinPipe[1].fd,
                stdoutPipe[0].fd, stdoutPipe[1].fd,
                statusPipe[1].fd
            )
            if (pid <= 0) {
                updateMainView("\nFailed to start ncat (pid=$pid).\n")
                closeAllQuietly(
                    stdinPipe[0], stdinPipe[1],
                    stdoutPipe[0], stdoutPipe[1],
                    statusPipe[0], statusPipe[1]
                )
                return
            }
            ncatPid = pid

            if (isStopped) {
                // 对话框在 fork 完成前已被关闭：直接结束子进程。
                try {
                    NativeNcat.sendSignal(pid, OsConstants.SIGKILL)
                } catch (_: Throwable) {
                    // ignore
                }
                closeAllQuietly(
                    stdinPipe[0], stdinPipe[1],
                    stdoutPipe[0], stdoutPipe[1],
                    statusPipe[0], statusPipe[1]
                )
                return
            }

            // 父进程只保留 stdin 写端、stdout 读端、退出码读端。其余端必须关闭，
            // 否则写端一直被持有，读端永远等不到 EOF（子进程退出后 UI 不会结束）。
            closeAllQuietly(stdinPipe[0], stdoutPipe[1], statusPipe[1])

            val processStdout = ParcelFileDescriptor.AutoCloseInputStream(stdoutPipe[0])
            val statusInput = ParcelFileDescriptor.AutoCloseInputStream(statusPipe[0])
            processStdin = ParcelFileDescriptor.AutoCloseOutputStream(stdinPipe[1])

            Thread { readStdoutUntilEof(processStdout, statusInput) }.start()

            writeLoop()
        } catch (t: Throwable) {
            updateMainView("\nFailed to start ncat: ${t.message}\n")
            closeAllQuietly(
                stdinPipe[0], stdinPipe[1],
                stdoutPipe[0], stdoutPipe[1],
                statusPipe[0], statusPipe[1]
            )
        } finally {
            closeQuietly(processStdin)
            processStdin = null
        }
    }

    /** 把待发送内容写进子进程 stdin。 */
    private fun writeLoop() {
        while (!isStopped && !exited) {
            val message = synchronized(sendQueue) { sendQueue.poll() }
            if (message != null) {
                val out = processStdin ?: break
                try {
                    val line = "$message\n"
                    out.write(line.toByteArray())
                    out.flush()
                    updateMainView(line)
                } catch (_: Throwable) {
                    // 子进程已退出 / 管道断开
                    break
                }
            } else {
                try {
                    sleep(50)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
    }

    /** 持续读取子进程 stdout/stderr，读到 EOF 说明子进程已结束。 */
    private fun readStdoutUntilEof(input: InputStream, statusInput: InputStream) {
        val buffer = ByteArray(4096)
        try {
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) updateMainView(String(buffer, 0, read))
            }
        } catch (_: Throwable) {
            // ignore
        } finally {
            closeQuietly(input)
        }

        val exitValue = readExitValue(statusInput)
        closeQuietly(statusInput)
        exited = true
        if (!isStopped) {
            updateMainView("\n\nNcat command finished. Exit value: $exitValue.")
        }
        disableMessageViews()
    }

    /** 读取 native 侧写回的 4 字节小端退出码；子进程被信号杀死时无数据，返回 -1。 */
    private fun readExitValue(input: InputStream): Int {
        val bytes = ByteArray(4)
        var offset = 0
        while (offset < 4) {
            val read = try {
                input.read(bytes, offset, 4 - offset)
            } catch (_: Throwable) {
                -1
            }
            if (read <= 0) return -1
            offset += read
        }
        return (bytes[0].toInt() and 0xff) or
                ((bytes[1].toInt() and 0xff) shl 8) or
                ((bytes[2].toInt() and 0xff) shl 16) or
                ((bytes[3].toInt() and 0xff) shl 24)
    }

    fun addToSendQueue(message: String) {
        synchronized(sendQueue) { sendQueue.add(message) }
    }

    fun halt() {
        isStopped = true
        val pid = ncatPid
        if (pid > 0 && !exited) {
            try {
                NativeNcat.sendSignal(pid, OsConstants.SIGTERM)
            } catch (_: Throwable) {
                // ignore
            }
            // SIGTERM 之后仍未退出则强制结束，避免残留子进程。
            Thread {
                try {
                    sleep(1000)
                } catch (_: InterruptedException) {
                    // ignore
                }
                if (!exited) {
                    try {
                        NativeNcat.sendSignal(pid, OsConstants.SIGKILL)
                    } catch (_: Throwable) {
                        // ignore
                    }
                }
            }.start()
        }
        closeQuietly(processStdin)
        processStdin = null
    }

    private fun closeAllQuietly(vararg descriptors: ParcelFileDescriptor?) {
        for (descriptor in descriptors) {
            closeQuietly(descriptor)
        }
    }

    private fun closeQuietly(closeable: Closeable?) {
        try {
            closeable?.close()
        } catch (_: Throwable) {
            // ignore
        }
    }
}
