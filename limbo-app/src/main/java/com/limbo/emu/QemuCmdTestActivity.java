package com.limbo.emu;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 测试用 Activity：执行通过 Intent 传入的 QEMU 命令行。
 *
 * <p>行为约定：
 * <ul>
 *     <li>命令行由 Intent 的 {@link #EXTRA_COMMAND} 传入，<b>原样执行</b>，
 *         不追加、不拼接、不包装任何其他参数或命令；</li>
 *     <li>最长执行时间 {@link #MAX_EXECUTION_TIME_MS}（5 秒），超时即终止进程；</li>
 *     <li>执行结束后弹出 Dialog 展示执行结果（是否超时、退出码、耗时、命令输出）；</li>
 *     <li>Intent 中没有该值时<b>直接抛出异常</b>，不做兜底。</li>
 * </ul>
 *
 * <p>启动示例（adb，需要本 Activity 处于 exported 状态）：
 * <pre>
 * adb shell am start -n com.limbo.emu/com.limbo.emu.QemuCommandTestActivity \
 *     --es qemu_command "qemu-system-x86_64 -m 512 -display none"
 * </pre>
 */
public class QemuCmdTestActivity extends AppCompatActivity {

    private static final String TAG = "QemuCommandTest";

    /** Intent 中携带 QEMU 命令行的 key。 */
    public static final String EXTRA_COMMAND = "qemu_command";

    /** 执行命令行使用的 shell。 */
    private static final String SHELL = "/system/bin/sh";

    /** 命令最大执行时间：5 秒。 */
    private static final long MAX_EXECUTION_TIME_MS = 5000L;

    /** 终止进程后最多等待其退出的时间。 */
    private static final long KILL_WAIT_MS = 500L;

    /** 轮询进程是否结束的间隔。 */
    private static final long POLL_INTERVAL_MS = 20L;

    /** 输出最多保留的字符数，避免超长输出占满内存。 */
    private static final int MAX_OUTPUT_CHARS = 32 * 1024;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** 构造一个携带 QEMU 命令行的 Intent。 */
    @NonNull
    public static Intent newIntent(@NonNull Context context, @NonNull String commandLine) {
        return new Intent(context, QemuCmdTestActivity.class)
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

        // 保持命令行原样，只做一次本地引用
        final String command = commandLine;
        showRunningView(command);

        Thread worker = new Thread(() -> {
            ExecResult result = execute(command);
            mainHandler.post(() -> showResultDialog(result));
        }, "qemu-command-test");
        worker.setDaemon(true);
        worker.start();
    }

    /** 执行期间只显示一行提示，真正的结果在 Dialog 里展示。 */
    private void showRunningView(@NonNull String commandLine) {
        TextView textView = new TextView(this);
        textView.setGravity(Gravity.CENTER);
        textView.setPadding(32, 32, 32, 32);
        textView.setText("正在执行 QEMU 命令行（最长 " + (MAX_EXECUTION_TIME_MS / 1000) + " 秒）...\n\n" + commandLine);
        setContentView(textView);
    }

    /**
     * 执行命令行并等待结果，最多 {@link #MAX_EXECUTION_TIME_MS} 毫秒。
     *
     * <p>注意：这里使用的是 Intent 传入的原始命令行，没有附加任何参数或命令。
     */
    @NonNull
    private ExecResult execute(@NonNull String commandLine) {
        long startTime = System.currentTimeMillis();
        StringBuilder output = new StringBuilder();
        Process process = null;
        try {
            // 原样执行：不追加参数、不拼接其他命令、不做重定向
            ProcessBuilder builder = new ProcessBuilder(SHELL, "-c", commandLine);
            builder.redirectErrorStream(true);
            process = builder.start();

            final InputStream stdout = process.getInputStream();
            Thread reader = new Thread(() -> drain(stdout, output), "qemu-command-reader");
            reader.setDaemon(true);
            reader.start();

            boolean finished = waitFor(process, MAX_EXECUTION_TIME_MS);
            long elapsedMs = System.currentTimeMillis() - startTime;

            if (!finished) {
                kill(process);
                joinQuietly(reader, KILL_WAIT_MS);
                return new ExecResult(commandLine, outputText(output),
                        timeoutStatus(elapsedMs));
            }

            joinQuietly(reader, KILL_WAIT_MS);
            int exitCode = process.exitValue();
            return new ExecResult(commandLine, outputText(output),
                    finishStatus(exitCode, elapsedMs));
        } catch (IOException e) {
            Log.w(TAG, "无法执行命令行: " + commandLine, e);
            long elapsedMs = System.currentTimeMillis() - startTime;
            if (process != null) {
                kill(process);
            }
            return new ExecResult(commandLine, outputText(output),
                    String.format(Locale.US, "启动命令失败（耗时 %d ms）: %s", elapsedMs, e));
        }
    }

    @NonNull
    private static String timeoutStatus(long elapsedMs) {
        return String.format(Locale.US, "执行超时（超过 %d ms），进程已被终止，耗时 %d ms",
                MAX_EXECUTION_TIME_MS, elapsedMs);
    }

    @NonNull
    private static String finishStatus(int exitCode, long elapsedMs) {
        return String.format(Locale.US, "执行结束，退出码 %d，耗时 %d ms", exitCode, elapsedMs);
    }

    /** 持续读取进程输出，超过 {@link #MAX_OUTPUT_CHARS} 后只继续读取不再累积。 */
    private static void drain(@NonNull InputStream in, @NonNull StringBuilder out) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                synchronized (out) {
                    if (out.length() < MAX_OUTPUT_CHARS) {
                        out.append(buffer, 0, read);
                    }
                }
            }
        } catch (IOException ignored) {
            // 进程被强杀时读取会抛异常，忽略即可
        }
    }

    /** 轮询等待进程结束，返回 true 表示进程在超时前已结束。 */
    private static boolean waitFor(@NonNull Process process, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            if (!isAlive(process)) {
                return true;
            }
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                return false;
            }
            try {
                Thread.sleep(Math.min(POLL_INTERVAL_MS, remaining));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    private static boolean isAlive(@NonNull Process process) {
        try {
            process.exitValue();
            return false;
        } catch (IllegalThreadStateException stillRunning) {
            return true;
        }
    }

    /** 结束进程：API 26 起用 destroyForcibly()（SIGKILL），低版本退化为 destroy()。 */
    private static void kill(@NonNull Process process) {
        try {
            Process.class.getMethod("destroyForcibly").invoke(process);
        } catch (Throwable ignored) {
            process.destroy();
        }
    }

    private static void joinQuietly(@NonNull Thread thread, long timeoutMs) {
        try {
            thread.join(timeoutMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @NonNull
    private static String outputText(@NonNull StringBuilder output) {
        synchronized (output) {
            return output.toString().trim();
        }
    }

    /** 弹出 Dialog 展示执行结果，关闭 Dialog 后结束本 Activity。 */
    private void showResultDialog(@NonNull ExecResult result) {
        if (isFinishing() || isDestroyed()) {
            return;
        }

        StringBuilder body = new StringBuilder();
        body.append("命令:\n").append(result.commandLine).append("\n\n");
        body.append("结果: ").append(result.status).append("\n\n");
        body.append("输出:\n").append(result.output.isEmpty() ? "(无输出)" : result.output);

        TextView textView = new TextView(this);
        textView.setText(body.toString());
        textView.setTextIsSelectable(true);
        textView.setPadding(24, 24, 24, 24);

        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(textView);

        new MaterialAlertDialogBuilder(this)
                .setTitle("QEMU 命令行执行结果")
                .setView(scrollView)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> finish())
                .setOnDismissListener(dialog -> finish())
                .show();
    }

    /** 命令行执行结果。 */
    private static final class ExecResult {
        /** 执行的命令行原文。 */
        final String commandLine;
        /** 命令输出（stdout + stderr）。 */
        final String output;
        /** 结果状态描述（退出码 / 是否超时等）。 */
        final String status;

        ExecResult(@NonNull String commandLine, @NonNull String output, @NonNull String status) {
            this.commandLine = commandLine;
            this.output = output;
            this.status = status;
        }
    }
}
