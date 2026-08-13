package com.limbo.emu.main;

import android.os.Bundle;

import com.max2idea.android.limbo.log.Logger;
import com.max2idea.android.limbo.main.Config;
import com.max2idea.android.limbo.main.LimboActivity;
import com.max2idea.android.limbo.main.LimboApplication;

public class LimboEmuActivity extends LimboActivity {

    public void onCreate(Bundle bundle) {
        LimboApplication.arch = Config.Arch.x86_64;
        Config.clientClass = this.getClass();
        Config.enableKVM = true;
        //XXX; only for 64bit hosts, also make sure you have qemu 3.1.0 x86_64-softmmu and above compiled
        Config.enableMTTCG = LimboApplication.isHost64Bit() && Config.enableMTTCG;
        super.onCreate(bundle);
        //TODO: change location to something that the user will have access outside of limbo
        //  like internal storage
        Logger.setupLogFile("/limbo/limbo-x86-log.txt");
    }

    protected void loadQEMULib() {
        try {
            System.loadLibrary("qemu-system-i386");
        } catch (Error ex) {
            System.loadLibrary("qemu-system-x86_64");
        }
    }
}
