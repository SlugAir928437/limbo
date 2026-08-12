package com.limbo.emu.main;

import android.os.Bundle;

import com.max2idea.android.limbo.log.Logger;
import com.max2idea.android.limbo.main.Config;
import com.max2idea.android.limbo.main.LimboActivity;
import com.max2idea.android.limbo.links.LinksManager;
import com.max2idea.android.limbo.main.LimboApplication;

public class LimboEmuActivity extends LimboActivity {

    public void onCreate(Bundle bundle){
        LimboApplication.arch = Config.Arch.ia64;
        Config.clientClass = this.getClass();
        Config.enableKVM = false;
        Config.enableEmulatedFloppy = false;
        Config.enableEmulatedSDCard = true;
        //XXX; only for 64bit hosts, also make sure you have qemu 2.9.1 arm-softmmu and above compiled
        Config.enableMTTCG = LimboApplication.isHost64Bit() && Config.enableMTTCG;
        Config.machineFolder = Config.machineFolder + "other/ia64_machines/";
        Config.osImages.put(getString(R.string.WindowsXPIA64), new LinksManager.LinkInfo(getString(R.string.WindowsXPIA64),
                getString(R.string.WindowsXPIA64),
                "https://betawiki.net/wiki/Windows_XP_64-Bit_Edition",
                LinksManager.LinkType.ISO));
        super.onCreate(bundle);
        //TODO: change location to something that the user will have access outside of limbo
        //  like internal storage
        Logger.setupLogFile("/limbo/limbo-ia64-log.txt");
    }

    protected void loadQEMULib(){
        try {
            System.loadLibrary("qemu-system-ia64");
        } catch (Error ex) {
            System.loadLibrary("qemu-system-ia64w");
        }
    }
}
