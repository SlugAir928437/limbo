package io.github.zeroaicy.util;
import android.content.Context;
import io.github.zeroaicy.util.crash.CrashApplication;

public class DebugUtil{
	
	public static void debug(){
		debug(ContextUtil.getContext(), false);
	}
	
	public static void debug(Context context){
		debug(context, false);
	}
	
	public static void debug(Context context, boolean isSetSystemOut){
		// 先把真实 Context 交给 ContextUtil：FileUtil 的静态初始化靠它解析日志目录，
		// 否则会退化成反射构造的假 Context（拿到的外部储存路径可能根本不可用）。
		if (context != null) {
			ContextUtil.setApplicationContext(context);
		}
		Log.setSystemOut(isSetSystemOut);

		try {
			Log.enable(FileUtil.LogCatPath);
			CrashApplication.CrashInit(context);
		} catch (Throwable e) {
			// 文件日志/崩溃上报初始化失败不能影响 App 启动：
			// 这里曾被放在 CrashApplication 的静态初始化块里，异常会直接变成
			// ExceptionInInitializerError，App 每次启动都闪退。
			e.printStackTrace();
		}
	}
	
	public static void notDebug(){
		Log.disable();
	}

}
