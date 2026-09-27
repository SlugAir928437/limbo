package com.limbo.emu;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.lang.reflect.Method;

/**
 * Limbo 虚拟机调试接口（不公开，仅调试用）
 *
 * 目标类：com.limbo.emu.jni.VMExecutor
 * 方法：native String start(String storage_dir, String base_dir,
 *                           String lib_filename, String lib_path,
 *                           Object[] params)
 *
 * 原理：
 *   1. 反射获取 VMExecutor 单例/实例
 *   2. 完全丢弃原有 params，只填入用户输入的命令行
 *   3. 调用 start()，延时 5 秒弹 Dialog 返回结果
 */
public class LimboDebugInterface extends Activity {

    private static final String TAG = "LimboDebug";

    /** JNI 桥接类（根据实际包名确认） */
    private static final String VM_EXECUTOR_CLASS = "com.limbo.emu.jni.VMExecutor";

    /** 结果延时 */
    private static final long RESULT_DELAY_MS = 5000L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        showCommandInputDialog();
    }

    private void showCommandInputDialog() {
        final EditText input = new EditText(this);
        input.setHint("输入 QEMU 命令行（空格分隔，将替换全部原有参数）");
        input.setMinLines(4);
        input.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, pad);
        box.addView(input);

        new AlertDialog.Builder(this)
                .setTitle("Limbo Debug: VMExecutor.start()")
                .setView(box)
                .setCancelable(false)
                .setPositiveButton("执行", (d, w) -> {
                    String cmd = input.getText().toString().trim();
                    if (cmd.isEmpty()) {
                        Toast.makeText(this, "命令不能为空", Toast.LENGTH_SHORT).show();
                        finish();
                        return;
                    }
                    runDebugStart(cmd);
                })
                .setNegativeButton("取消", (d, w) -> finish())
                .show();
    }

    /**
     * 核心逻辑：
     *   1. 拿到 VMExecutor 实例
     *   2. 构建全新的 params（原有命令全部丢弃）
     *   3. 反射调用 start()
     */
    @SuppressLint("DefaultLocale")
    private void runDebugStart(final String command) {
        new Thread(() -> {
            StringBuilder log = new StringBuilder();
            String result;

            try {
                // 1. 获取 VMExecutor 实例
                Object executor = resolveVMExecutor();
                if (executor == null) {
                    postResult("失败", "无法获取 VMExecutor 实例。\n"
                            + "请确认 VMExecutor 是否有静态字段 instance/sInstance，"
                            + "或在 Limbo 主 Activity 中通过反射获取当前实例。");
                    return;
                }

                // 2. 从 VMExecutor 或 Limbo 主类读取原有 dir 参数（这些不是“命令”，是路径）
                //    如果 VMExecutor 本身没有这些字段，需要从持有它的对象中取
                String storageDir = getStringField(executor, "storage_dir");
                String baseDir    = getStringField(executor, "base_dir");
                String libName    = getStringField(executor, "lib_filename");
                String libPath    = getStringField(executor, "lib_path");

                // 若反射取不到，提供默认路径占位（实际运行时需替换为真实路径）
                if (storageDir.isEmpty()) storageDir = "/data/data/com.limbo.emu.main/files";
                if (baseDir.isEmpty())    baseDir    = "/data/data/com.limbo.emu.main/files";
                if (libName.isEmpty())    libName    = "libqemu-system-x86_64.so";
                if (libPath.isEmpty())    libPath    = "/data/app/com.limbo.emu.main/lib/arm64";

                log.append("=== 环境参数 ===\n");
                log.append("storage_dir : ").append(storageDir).append("\n");
                log.append("base_dir    : ").append(baseDir).append("\n");
                log.append("lib_filename: ").append(libName).append("\n");
                log.append("lib_path    : ").append(libPath).append("\n\n");

                // 3. 构建全新的 params：原有命令全部丢弃，只放用户输入的这段
                //    QEMU 参数是按空格切分的 token 列表
                String[] tokens = command.split("\\s+");
                Object[] params = new Object[tokens.length];
                System.arraycopy(tokens, 0, params, 0, tokens.length);

                log.append("=== 新命令（原参数已全部清除） ===\n");
                log.append("参数个数: ").append(params.length).append("\n");
                for (int i = 0; i < params.length; i++) {
                    log.append(String.format("  %2d: %s%n", i, params[i]));
                }
                log.append("\n");

                // 4. 反射调用 native start
                Method start = findStartMethod(executor.getClass());
                if (start == null) {
                    postResult("失败", "未找到 start(String,String,String,String,Object[]) 方法");
                    return;
                }
                start.setAccessible(true);

                log.append("=== 调用 VMExecutor.start() ===\n");
                Object ret = start.invoke(executor,
                        storageDir, baseDir, libName, libPath, params);
                log.append("返回值: ").append(ret).append("\n");

                result = log.toString();

            } catch (Throwable t) {
                Log.e(TAG, "执行异常", t);
                result = "异常: " + t.getClass().getSimpleName()
                        + "\n" + t.getMessage() + "\n\n" + log;
            }

            // 5. 延时 5 秒返回结果
            final String finalResult = result;
            mainHandler.postDelayed(() ->
                    showResultDialog("执行结果", finalResult), RESULT_DELAY_MS);

        }).start();
    }

    /**
     * 获取 VMExecutor 实例。
     * VMExecutor 通常不是 Activity，而是被 Limbo 主类持有。
     * 尝试常见静态单例字段；若都没有，需要从 Limbo 主 Activity 反射取 mExecutor 之类的字段。
     */
    private Object resolveVMExecutor() {
        try {
            Class<?> clazz = Class.forName(VM_EXECUTOR_CLASS);

            // 尝试静态字段
            for (String name : new String[]{"instance", "mInstance", "sInstance"}) {
                try {
                    java.lang.reflect.Field f = clazz.getDeclaredField(name);
                    f.setAccessible(true);
                    Object v = f.get(null);
                    if (v != null) {
                        Log.d(TAG, "从静态字段 " + name + " 获取 VMExecutor");
                        return v;
                    }
                } catch (Throwable ignored) {}
            }

            // 尝试静态 getter
            for (String name : new String[]{"getInstance", "getExecutor"}) {
                try {
                    Method m = clazz.getDeclaredMethod(name);
                    m.setAccessible(true);
                    Object v = m.invoke(null);
                    if (v != null) {
                        Log.d(TAG, "从静态方法 " + name + " 获取 VMExecutor");
                        return v;
                    }
                } catch (Throwable ignored) {}
            }

            // 兜底：如果 VMExecutor 就是当前 Activity 的一个字段，这里无法直接拿到
            // 建议在 Limbo 主 Activity 中持有 VMExecutor 的字段上反射获取
            Log.w(TAG, "未找到 VMExecutor 静态实例，需要在 Limbo 主 Activity 中获取");
            return null;

        } catch (Throwable t) {
            Log.e(TAG, "resolveVMExecutor 失败", t);
            return null;
        }
    }

    /**
     * 查找 start(String, String, String, String, Object[]) 方法
     */
    private Method findStartMethod(Class<?> clazz) {
        for (Method m : clazz.getDeclaredMethods()) {
            if ("start".equals(m.getName()) && m.getParameterCount() == 5) {
                Class<?>[] p = m.getParameterTypes();
                if (p[0] == String.class && p[1] == String.class
                        && p[2] == String.class && p[3] == String.class
                        && p[4] == Object[].class) {
                    return m;
                }
            }
        }
        return null;
    }

    private String getStringField(Object obj, String name) {
        try {
            java.lang.reflect.Field f = obj.getClass().getDeclaredField(name);
            f.setAccessible(true);
            Object v = f.get(obj);
            return v == null ? "" : v.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private void postResult(String title, String message) {
        mainHandler.post(() -> showResultDialog(title, message));
    }

    private void showResultDialog(String title, String message) {
        if (isFinishing() || isDestroyed()) return;
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton("确定", (d, w) -> {
                    d.dismiss();
                    finish();
                })
                .show();
    }
}