package com.limbo.emu.debug;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.limbo.emu.jni.QemuCommandLineRunner;
import com.limbo.emu.log.Logger;

import java.util.Arrays;
import java.util.Locale;

/**
 * 测试用 Activity：把通过 Intent 传入的 QEMU 命令行交给 JNI 的
 * {@code VMExecutor} 原生引导流程执行（loadLib + qemu_init + qemu_main_loop），
 * 最长执行 5 秒，执行结果<b>输出到 logcat</b>（{@link Logger}）。
 *
 * <p>行为约定：
 * <ul>
 *     <li>命令行由 Intent 的 {@link #EXTRA_COMMAND} 传入，解析成参数数组后
 *         <b>原样</b>交给原生层，不追加、不拼接、不改写任何参数；</li>
 *     <li>原生库按 {@code LimboActivity.setupNativeLibs()} 的顺序在主线程加载，
 *         QEMU 则在后台线程启动；</li>
 *     <li>最长执行时间 {@link #MAX_EXECUTION_TIME_MS}（5 秒），超时后请求 QEMU 关机；</li>
 *     <li>执行信息（命令行、参数、QEMU 库、结果、原生返回值）统一写入 logcat，
 *         不再用 Dialog 展示；执行结束后<b>自动退出</b>本 Activity；</li>
 *     <li>Intent 中没有该值时<b>直接抛出异常</b>，不做兜底。</li>
 * </ul>
 *
 * <p>启动示例（adb，需要本 Activity 处于 exported 状态）：
 * <pre>
 * adb shell am start -n com.limbo.emu/com.limbo.emu.debug.QemuCommandTestActivity \
 *     --es qemu_command "qemu-system-x86_64 -display none -m 512"
 * </pre>
 *
 * <p>查看结果（TAG 为 {@link #TAG}）：
 * <pre>
 * adb logcat -s QemuCommandTest
 * </pre>
 */
public class QemuCommandTestActivity extends AppCompatActivity {

    /** logcat 过滤用的 TAG。 */
    private static final String TAG = "QemuCommandTest";

    /** Intent 中携带 QEMU 命令行的 key。 */
    public static final String EXTRA_COMMAND = "qemu_command";

    /** 命令最大执行时间：5 秒。 */
    private static final long MAX_EXECUTION_TIME_MS = 5000L;

    /** 超时后请求关机，再等原生调用返回的时间。 */
    private static final long STOP_GRACE_MS = 2000L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** 构造一个携带 QEMU 命令行的 Intent。 */
    @NonNull
    public static Intent newIntent(@NonNull Context context, @NonNull String commandLine) {
        return new Intent(context, QemuCommandTestActivity.class)
                .putExtra(EXTRA_COMMAND, commandLine);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent intent = getIntent();
        String commandLine = intent == null ? null : intent.getStringExtra(EXTRA_COMMAND);

        // 无 Intent 值（或为空）时不做任何兜底，直接抛出异常
        if (TextUtils.isEmpty(commandLine) || commandLine.trim().isEmpty()) {
            throw new IllegalArgumentException("QemuCommandTestActivity: Intent 缺少 \""
                    + EXTRA_COMMAND + "\" 参数，无法执行 QEMU 命令行");
        }

        final String command = commandLine;
        // 原样解析成参数数组，不追加任何参数
        final String[] params = QemuCommandLineRunner.parseCommandLine(command);
        if (params.length == 0) {
            throw new IllegalArgumentException("QemuCommandTestActivity: \"" + EXTRA_COMMAND
                    + "\" 解析后没有任何参数: " + command);
        }

        showRunningView(command);

        // 原生库必须在主线程加载（否则之后启动 QEMU 会崩溃），失败也要走到日志
        Throwable loadError = null;
        try {
            QemuCommandLineRunner.loadNativeLibs();
        } catch (Throwable t) {
            Log.e(TAG, "原生库加载失败", t);
            loadError = t;
        }
        final Throwable initError = loadError;
        final String libFilename = initError == null ? safeLibFilename(params) : "-";

        // 先记录开始信息：QEMU 内部可能直接 exit() 结束进程，日志要提前落盘
        Logger.logInfo(TAG, "===== QEMU 命令行测试开始 =====");
        Logger.logInfo(TAG, "命令行: " + command);
        Logger.logInfo(TAG, "参数(" + params.length + "): " + Arrays.toString(params));
        if (initError == null) {
            Logger.logInfo(TAG, "QEMU 库: " + libFilename);
        } else {
            Logger.logWarn(TAG, "初始化失败（原生库未加载）: " + initError);
        }

        Thread worker = new Thread(() -> {
            ExecResult result = execute(params, initError);
            logResult(result);
            mainHandler.post(this::finishAfterResult);
        }, "qemu-command-test");
        worker.setDaemon(true);
        worker.start();
    }

    /** 执行期间只显示一行提示，真正的结果输出到 logcat。 */
    private void showRunningView(@NonNull String commandLine) {
        TextView textView = new TextView(this);
        textView.setGravity(Gravity.CENTER);
        textView.setPadding(32, 32, 32, 32);
        textView.setText("正在通过 JNI 执行 QEMU 命令行（最长 "
                + (MAX_EXECUTION_TIME_MS / 1000) + " 秒）...\n\n" + commandLine);
        setContentView(textView);
    }

    /** 执行结束（结果已写入 logcat）后自动退出本 Activity。 */
    private void finishAfterResult() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        Logger.logInfo(TAG, "执行结束，自动退出 Activity");
        finish();
    }

    /**
     * 在后台线程里调用 {@code VMExecutor} 的原生引导流程，并强制
     * {@link #MAX_EXECUTION_TIME_MS} 的执行时间上限；超时后请求 QEMU 关机。
     */
    @NonNull
    private ExecResult execute(@NonNull String[] params, @Nullable Throwable initError) {
        long startTime = System.currentTimeMillis();

        if (initError != null) {
            return new ExecResult("初始化失败（原生库未加载）",
                    String.valueOf(initError), System.currentTimeMillis() - startTime, true);
        }

        final String[] nativeResult = new String[1];
        final Throwable[] nativeError = new Throwable[1];

        Thread vmThread = new Thread(() -> {
            try {
                nativeResult[0] = QemuCommandLineRunner.start(params);
            } catch (Throwable t) {
                nativeError[0] = t;
            }
        }, "qemu-native-start");
        vmThread.setDaemon(true);
        vmThread.start();

        boolean finished = joinQuietly(vmThread, MAX_EXECUTION_TIME_MS);
        long elapsedMs = System.currentTimeMillis() - startTime;

        if (finished) {
            if (nativeError[0] != null) {
                return new ExecResult("执行失败",
                        String.valueOf(nativeError[0]), elapsedMs, true);
            }
            return new ExecResult("QEMU 已退出", nativeResult[0], elapsedMs, false);
        }

        // 超时：请求 QEMU 关机（等价于界面上的「停止」），再给它一点收尾时间
        String stopMessage;
        try {
            stopMessage = QemuCommandLineRunner.stop();
        } catch (Throwable t) {
            stopMessage = "请求停止失败: " + t;
        }
        boolean exited = joinQuietly(vmThread, STOP_GRACE_MS);
        long totalMs = System.currentTimeMillis() - startTime;

        String status = exited
                ? String.format(Locale.US, "执行超时（超过 %d ms），已请求停止，QEMU 已退出",
                        MAX_EXECUTION_TIME_MS)
                : String.format(Locale.US, "执行超时（超过 %d ms），已请求停止，但原生调用尚未返回",
                        MAX_EXECUTION_TIME_MS);
        String result = nativeResult[0] != null ? nativeResult[0] : stopMessage;
        return new ExecResult(status, result, totalMs, true);
    }

    /** 仅用于展示/记录：解析实际会被 dlopen 的 QEMU 库名，失败时用 "-" 占位。 */
    @NonNull
    private static String safeLibFilename(@NonNull String[] params) {
        try {
            return QemuCommandLineRunner.resolveLibFilename(params);
        } catch (Throwable t) {
            Log.w(TAG, "无法解析 QEMU 库名", t);
            return "-";
        }
    }

    private static boolean joinQuietly(@NonNull Thread thread, long timeoutMs) {
        try {
            thread.join(timeoutMs);
            return !thread.isAlive();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 把执行结果写入 logcat（失败/超时用 WARN，正常结束用 INFO）。 */
    private static void logResult(@NonNull ExecResult result) {
        String nativeResult = TextUtils.isEmpty(result.nativeResult)
                ? "(无)" : result.nativeResult;
        String summary = "结果: " + result.status
                + "，耗时 " + result.elapsedMs + " ms";
        if (result.failed) {
            Logger.logWarn(TAG, summary);
            Logger.logWarn(TAG, "原生返回/异常: " + nativeResult);
        } else {
            Logger.logInfo(TAG, summary);
            Logger.logInfo(TAG, "原生返回: " + nativeResult);
        }
        Logger.logInfo(TAG, "提示: QEMU 自身的 stdout/stderr 也在 logcat 中");
        Logger.logInfo(TAG, "===== QEMU 命令行测试结束 =====");
    }

    /** 命令行执行结果。 */
    private static final class ExecResult {
        /** 结果状态描述（正常退出 / 超时 / 失败）。 */
        final String status;
        /** 原生层返回的结果字符串。 */
        @Nullable
        final String nativeResult;
        /** 耗时。 */
        final long elapsedMs;
        /** 是否属于失败/超时。 */
        final boolean failed;

        ExecResult(@NonNull String status, @Nullable String nativeResult,
                   long elapsedMs, boolean failed) {
            this.status = status;
            this.nativeResult = nativeResult;
            this.elapsedMs = elapsedMs;
            this.failed = failed;
        }
    }
}
