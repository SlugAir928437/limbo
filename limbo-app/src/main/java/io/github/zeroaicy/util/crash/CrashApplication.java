package io.github.zeroaicy.util.crash;

import android.app.Application;
import android.content.Context;

import com.limbo.emu.BuildConfig;
import com.limbo.emu.main.LimboApplication;

import io.github.zeroaicy.util.DebugUtil;
import io.github.zeroaicy.util.FileUtil;

public class CrashApplication extends LimboApplication {

	static Application mCrashApplication;

	protected static boolean isDebug = BuildConfig.DEBUG;

	@Override
	public void onCreate(){
		super.onCreate();
		//闪退日志
        mCrashApplication = CrashApplication.this;
		//注册
        CrashApphandler.getInstance().onCreated();
    }

	@Override
	protected void attachBaseContext(Context base){
		if ( isDebug ){
			DebugUtil.debug(base);
		}
		CrashApphandler.getInstance().onCreated();
		super.attachBaseContext(base);
		
	}

    public static void CrashInit(){
        CrashApphandler instance = CrashApphandler.getInstance();
        if (FileUtil.CrashLogPath != null) {
            instance.setCAHCE_CRASH_LOG(FileUtil.CrashLogPath);
        }
        instance.setLIMIT_LOG_COUNT(5);
        instance.init();
    }
    public static void CrashInit(Context base){
        CrashApphandler instance = CrashApphandler.getInstance();
		// CrashLogPath 可能为 null（外部储存不可用且内部 cache 也解析失败），
		// 此时保留 CrashAppLog 的默认目录，别把 null 塞进去。
		if (FileUtil.CrashLogPath != null) {
			instance.setCAHCE_CRASH_LOG(FileUtil.CrashLogPath);
		}
		instance.setLIMIT_LOG_COUNT(5);
		instance.init(base);
    }
}

