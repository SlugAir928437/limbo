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
		Log.setSystemOut(isSetSystemOut);
		
		Log.enable(FileUtil.LogCatPath);
		CrashApplication.CrashInit(context);
	}
	
	public static void notDebug(){
		Log.disable();
	}

}
