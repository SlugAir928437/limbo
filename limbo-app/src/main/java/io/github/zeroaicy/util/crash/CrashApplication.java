package io.github.zeroaicy.util.crash;

import android.app.Application;
import android.content.Context;

import com.limbo.emu.BuildConfig;
import com.limbo.emu.local_properties;
import com.limbo.emu.main.LimboApplication;
import com.tencent.bugly.crashreport.CrashReport;

import io.github.zeroaicy.util.DebugUtil;
import io.github.zeroaicy.util.FileUtil;

public class CrashApplication extends LimboApplication {

	static Application mCrashApplication;

	protected static boolean isDebug = BuildConfig.DEBUG;

	@Override
	public void onCreate(){
		super.onCreate();
		// Bugly AppID 不再硬编码在源码里：构建时由 build.gradle 从
		// local.properties(bugly.appId) 或环境变量 BUGLY_APP_ID 注入 BuildConfig，
		// 避免 AppID 随仓库上传到 GitHub。未配置时跳过初始化，构建照常通过。
        String BuglyAppid = local_properties.get("bugly.appId", "");
		if ( !BuglyAppid.isEmpty() ) {
            CrashReport.initCrashReport(getApplicationContext(), BuglyAppid, BuildConfig.DEBUG);
        }
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

