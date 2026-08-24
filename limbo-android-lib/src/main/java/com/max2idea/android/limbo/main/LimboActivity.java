/*
 Copyright (C) Max Kastanas 2012

 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 *
 */
package com.max2idea.android.limbo.main;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Point;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.StrictMode;
import android.util.Log;
import android.view.Display;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.hjq.permissions.XXPermissions;
import com.hjq.permissions.permission.PermissionLists;
import com.limbo.emu.lib.R;
import com.max2idea.android.limbo.dialog.DialogUtils;
import com.max2idea.android.limbo.files.FileInstaller;
import com.max2idea.android.limbo.files.FileUtils;
import com.max2idea.android.limbo.install.Installer;
import com.max2idea.android.limbo.keyboard.KeyboardUtils;
import com.max2idea.android.limbo.log.Logger;
import com.max2idea.android.limbo.machine.ArchDefinitions;
import com.max2idea.android.limbo.machine.BIOSImporter;
import com.max2idea.android.limbo.machine.Machine;
import com.max2idea.android.limbo.machine.Machine.FileType;
import com.max2idea.android.limbo.machine.MachineAction;
import com.max2idea.android.limbo.machine.MachineController;
import com.max2idea.android.limbo.machine.MachineController.MachineStatus;
import com.max2idea.android.limbo.machine.MachineExporter;
import com.max2idea.android.limbo.machine.MachineFilePaths;
import com.max2idea.android.limbo.machine.MachineImporter;
import com.max2idea.android.limbo.machine.MachineProperty;
import com.max2idea.android.limbo.network.NetworkUtils;
import com.max2idea.android.limbo.toast.ToastUtils;
import com.max2idea.android.limbo.ui.LimboComposeBridge;
import com.max2idea.android.limbo.ui.LimboUiCallbacks;
import com.max2idea.android.limbo.ui.LimboUiState;
import com.max2idea.android.limbo.ui.StorageDeviceEditorActivity;
import com.max2idea.android.limbo.ui.StorageDeviceUiState;
import com.max2idea.android.limbo.updates.UpdateChecker;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Observable;
import java.util.Observer;

/**
 * Main configuration activity for the Limbo PC emulator.
 * The layout is rendered with Jetpack Compose + Material Design 3
 * (see ui/LimboMainScreen.kt); all business logic stays in Java.
 */
public class LimboActivity extends AppCompatActivity implements
        MachineController.OnMachineStatusChangeListener,
        MachineController.OnEventListener,
        Observer,
        LimboUiCallbacks {

    private static final String TAG = "LimboActivity";

    private static final int QUIT = 1;
    private static final int INSTALL = 2;
    private static final int DELETE = 3;
    private static final int EXPORT = 4;
    private static final int IMPORT = 5;
    private static final int CHANGELOG = 6;
    private static final int LICENSE = 7;
    private static final int VIEWLOG = 8;
    private static final int CREATE = 9;
    private static final int DISCARD_VM_STATE = 11;
    private static final int SETTINGS = 13;
    private static final int IMPORT_BIOS_FILE = 15;

    // disk mapping
    private final Hashtable<FileType, DiskInfo> diskMapping = new Hashtable<>();

    private boolean libLoaded = false;
    public View parent;
    private boolean machineLoaded = false;
    private FileType browseFileType = null;
    // device (row) being edited in StorageDeviceEditorActivity; -1 = adding a new device
    private int pendingStorageEditTag = -1;

    // Compose UI state
    private final LimboUiState uiState = new LimboUiState();

    // storage device bookkeeping (mirrors the old View-backed entries)
    private final ArrayList<StorageEntry> storageEntries = new ArrayList<>();

    private boolean firstMTTCGCheck = false;
    private ViewListener viewListener;

    // Debounce for text fields: the old Java UI committed on focus loss,
    // Compose commits on every keystroke which floods the dispatcher/database.
    private final Handler debounceHandler = new Handler(Looper.getMainLooper());
    private static final long DEBOUNCE_MS = 800;
    private final Runnable appendCommit = () -> {
        if (getMachine() != null)
            notifyFieldChange(MachineProperty.APPEND, uiState.getAppend());
    };
    private final Runnable dnsCommit = () -> {
        if (getMachine() != null) {
            setDNSServer(uiState.getDns());
            LimboSettingsManager.setDNSServer(LimboActivity.this, uiState.getDns());
        }
    };
    private final Runnable hostFwdCommit = () -> {
        if (getMachine() != null)
            notifyFieldChange(MachineProperty.HOSTFWD, uiState.getHostFwd());
    };
    private final Runnable extraParamsCommit = () -> {
        if (getMachine() != null)
            notifyFieldChange(MachineProperty.EXTRA_PARAMS, uiState.getExtraParams());
    };
    private final Runnable cpuNumCommit = () -> {
        if (getMachine() != null) {
            int cpuNum = parseIntSafe(uiState.getCpuNumValue(), 1);
            notifyFieldChange(MachineProperty.CPUNUM, "" + cpuNum);
        }
    };
    private final Runnable ramCommit = () -> {
        if (getMachine() != null) {
            int ram = parseIntSafe(uiState.getRamValue(), 512);
            notifyFieldChange(MachineProperty.MEMORY, "" + ram);
        }
    };

    private static int parseIntSafe(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (Exception e) {
            return fallback;
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        checkAllowedPermission();
        setupAppEnvironment();
        clearNotifications();
        setupStrictMode();
        setupController();
        setupDiskMapping();
        checkFirstLaunch();
        checkUpdate();
        checkLog();
        checkAndLoadLibs();
        restore();
        setupListeners();

        populateAttributesUI();
        setupDefaultValues();

        LimboComposeBridge.setContent(this, uiState, this);
    }

    private void setupDefaultValues() {
        List<String> machines = new ArrayList<>();
        machines.add("None");
        machines.add("New");
        uiState.setMachines(machines);
        uiState.setMachineSel(0);
        if (MachineController.getInstance().isRunning())
            uiState.setMachineEnabled(false);

        if (!Config.enable_SDL)
            uiState.setUiEnabled(false);

        uiState.setStatusText(getString(R.string.Stopped));
        uiState.setStatusRunning(false);
        uiState.setStatusKind(0);

        setDefaultDNServer();

        enableRemovableDeviceOptions(false);
        enableNonRemovableDeviceOptions(false);
    }

    // ============================================================
    // Status / state helpers
    // ============================================================

    public void changeStatus(MachineStatus statusChanged) {
        runOnUiThread(() -> {
            if (MachineController.getInstance().isRunning() || statusChanged == MachineStatus.Running) {
                uiState.setStatusRunning(true);
                if (uiState.getUiSel() == 0) {
                    // VNC
                    uiState.setStatusText(getString(R.string.Running));
                    uiState.setStatusKind(1);
                    enableRemovableDiskValues(true);
                } else {
                    // SDL is always suspended in the background
                    uiState.setStatusText(getString(R.string.Suspended));
                    uiState.setStatusKind(1);
                    enableRemovableDiskValues(false);
                }
                unlockRemovableDevices(false);
                enableNonRemovableDeviceOptions(false);
                uiState.setMachineEnabled(false);
            } else if (statusChanged == MachineStatus.Ready || statusChanged == MachineStatus.Stopped) {
                uiState.setStatusRunning(false);
                uiState.setStatusText(getString(R.string.Stopped));
                uiState.setStatusKind(0);
                unlockRemovableDevices(true);
                enableRemovableDiskValues(true);
                enableNonRemovableDeviceOptions(true);
                // re-enable the machine selector (it was disabled while running)
                uiState.setMachineEnabled(true);
            } else if (statusChanged == MachineStatus.Saving) {
                uiState.setStatusRunning(true);
                uiState.setStatusText(getString(R.string.savingState));
                uiState.setStatusKind(3);
                unlockRemovableDevices(false);
                enableRemovableDiskValues(false);
                enableNonRemovableDeviceOptions(false);
            } else if (statusChanged == MachineStatus.Paused) {
                uiState.setStatusRunning(true);
                uiState.setStatusText(getString(R.string.paused));
                uiState.setStatusKind(2);
                unlockRemovableDevices(false);
                enableRemovableDiskValues(false);
                enableNonRemovableDeviceOptions(false);
            }
        });
    }

    private void onTap() {
        String userid = LimboApplication.getUserId(this);
        if (!new File("/dev/net/tun").exists()) {
            LimboActivityCommon.tapNotSupported(this, userid);
            return;
        }
        LimboActivityCommon.promptTap(this, userid);
    }

    public void setUserPressed(boolean pressed) {
        // Listeners are wired directly to Compose state in this implementation.
    }

    private long getSelectedSizeBytes(StorageEntry entry) {
        long value = 1L;
        try {
            value = Long.parseLong(entry.ui.getSizeValue().trim());
        } catch (NumberFormatException e) {
            value = 1;
        }
        if (value < 1)
            value = 1;
        String unit = entry.ui.getSizeUnitOptions().size() > entry.ui.getSizeUnitSel()
                ? entry.ui.getSizeUnitOptions().get(entry.ui.getSizeUnitSel()) : "GB";
        long multiplier = 1024L * 1024L * 1024L; // default GB
        if (unit.equals(getString(R.string.size_unit_mb)))
            multiplier = 1024L * 1024L;
        else if (unit.equals(getString(R.string.size_unit_tb)))
            multiplier = 1024L * 1024L * 1024L * 1024L;
        return value * multiplier;
    }

    private void notifyDriveChanged(StorageEntry entry, String value) {
        if (entry.removable) {
            notifyFieldChange(MachineProperty.REMOVABLE_DRIVE, new Object[]{entry.property, value});
        } else {
            notifyFieldChange(MachineProperty.NON_REMOVABLE_DRIVE, new Object[]{entry.property, value});
        }
    }

    // ============================================================
    // Storage device dynamic rows
    // ============================================================

    private void refreshStorageDevices() {
        for (StorageEntry entry : new ArrayList<>(storageEntries)) {
            diskMapping.remove(entry.fileType);
        }
        storageEntries.clear();
        uiState.setStorageDevices(new ArrayList<>());
        if (getMachine() == null)
            return;

        // Hard disks: each non-empty HDA..HDD slot becomes one hard disk row
        addHardDiskRow(0, getMachine().getHdaImagePath());
        addHardDiskRow(1, getMachine().getHdbImagePath());
        addHardDiskRow(2, getMachine().getHdcImagePath());
        addHardDiskRow(3, getMachine().getHddImagePath());

        addRowForDrive(DeviceType.CDROM, getMachine().getCdImagePath());
        if (Config.enableEmulatedFloppy) {
            addRowForDrive(DeviceType.FDA, getMachine().getFdaImagePath());
            addRowForDrive(DeviceType.FDB, getMachine().getFdbImagePath());
        }
        if (Config.enableEmulatedSDCard) {
            addRowForDrive(DeviceType.SD, getMachine().getSdImagePath());
        }
        if (Config.enableSharedFolder) {
            addRowForDrive(DeviceType.SHARED_DIR, getMachine().getSharedFolderPath());
        }
    }

    private void addHardDiskRow(int slot, String path) {
        if (path != null && !path.isEmpty() && !path.equals("None")) {
            addStorageDeviceRow(DeviceType.HARD_DISK, slot);
        }
    }

    private void addRowForDrive(DeviceType type, String path) {
        if (path != null && !path.isEmpty() && !path.equals("None")) {
            addStorageDeviceRow(type);
        }
    }

    private void addStorageDeviceRow(DeviceType type) {
        addStorageDeviceRow(type, -1);
    }

    private void addStorageDeviceRow(DeviceType type, int hardDiskSlot) {
        if (getMachine() == null)
            return;
        DeviceType devType = type;
        if (devType == null) {
            devType = getFirstUnusedType();
            if (devType == null) {
                ToastUtils.toastShort(this, getString(R.string.device_add_failed));
                return;
            }
        }
        // check if type already reached max count
        if (countDeviceType(devType) >= devType.maxCount) {
            ToastUtils.toastShort(this, getString(R.string.device_already_exists));
            return;
        }

        StorageEntry entry = new StorageEntry();
        entry.ui = new StorageDeviceUiState();
        entry.ui.setTag(storageEntries.size());
        entry.deviceType = devType;
        entry.removable = devType.removable;
        entry.createImage = devType.createImage;
        entry.sharedFolder = devType.sharedFolder;
        if (devType == DeviceType.HARD_DISK) {
            if (hardDiskSlot >= 0) {
                entry.hardDiskSlot = hardDiskSlot;
                assignHardDiskSlotProperty(entry, hardDiskSlot);
            } else {
                assignHardDiskSlot(entry);
            }
        } else {
            entry.property = devType.property;
            entry.fileType = devType.fileType;
        }

        // type spinner adapter
        DeviceType[] types = getAvailableDeviceTypes();
        List<String> typeLabels = new ArrayList<>();
        for (DeviceType t : types)
            typeLabels.add(getString(t.labelRes));
        entry.ui.setTypeOptions(typeLabels);
        entry.ui.setTypeSel(getTypePosition(devType));

        // size unit spinner adapter (MB/GB/TB)
        List<String> units = new ArrayList<>();
        units.add(getString(R.string.size_unit_gb));
        units.add(getString(R.string.size_unit_mb));
        units.add(getString(R.string.size_unit_tb));
        entry.ui.setSizeUnitOptions(units);
        entry.ui.setSizeUnitSel(0); // default GB
        entry.ui.setSizeValue("4");

        storageEntries.add(entry);
        syncStorageDeviceUi();

        // register disk mapping
        if (entry.fileType != null) {
            diskMapping.put(entry.fileType, new DiskInfo(null, entry.property));
        }

        // For removable devices: enable empty devices so image selection can be saved
        if (entry.removable) {
            String existing = getMachineDriveValue(entry.fileType);
            if (existing == null || existing.isEmpty() || existing.equals("None")) {
                if (entry.property != null) {
                    notifyFieldChange(MachineProperty.DRIVE_ENABLED, new Object[]{entry.property, true});
                }
            }
        }

        updateStorageDeviceSizeVisibility(entry);

        // set value from machine once the image adapter is ready
        populateStorageDeviceImageAdapter(entry, () -> {
            String value = getMachineDriveValue(entry.fileType);
            if (value != null && !value.isEmpty() && !value.equals("None")) {
                seMachineDriveValue(entry.fileType, value);
            }
        });
    }

    private void syncStorageDeviceUi() {
        List<StorageDeviceUiState> list = new ArrayList<>();
        for (StorageEntry entry : storageEntries)
            list.add(entry.ui);
        uiState.setStorageDevices(list);
    }

    private void removeStorageDeviceRow(StorageEntry entry) {
        if (entry.removable) {
            if (entry.property != null) {
                notifyFieldChange(MachineProperty.DRIVE_ENABLED, new Object[]{entry.property, false});
            }
        } else {
            clearDrive(entry);
        }
        storageEntries.remove(entry);
        diskMapping.remove(entry.fileType);
        syncStorageDeviceUi();
        // re-compact hard disk slots (HDA..HDD stay contiguous)
        reassignHardDiskSlots();
        updateSummary();
    }

    private void reassignHardDiskSlots() {
        int slot = 0;
        for (StorageEntry entry : storageEntries) {
            if (entry.deviceType == DeviceType.HARD_DISK) {
                if (entry.hardDiskSlot != slot) {
                    String oldValue = getMachineDriveValue(entry.fileType);
                    diskMapping.remove(entry.fileType);
                    clearDrive(entry);
                    entry.hardDiskSlot = slot;
                    assignHardDiskSlotProperty(entry, slot);
                    if (entry.fileType != null) {
                        diskMapping.put(entry.fileType, new DiskInfo(null, entry.property));
                    }
                    if (oldValue != null && !oldValue.isEmpty() && !oldValue.equals("None")) {
                        if (entry.property != null) {
                            notifyFieldChange(MachineProperty.NON_REMOVABLE_DRIVE, new Object[]{entry.property, oldValue});
                        }
                        seMachineDriveValue(entry.fileType, oldValue);
                    } else {
                        seMachineDriveValue(entry.fileType, null);
                    }
                }
                slot++;
            }
        }
    }

    private void clearDrive(StorageEntry entry) {
        if (entry.removable) {
            if (entry.property != null)
                notifyFieldChange(MachineProperty.REMOVABLE_DRIVE, new Object[]{entry.property, "None"});
        } else {
            if (entry.property != null)
                notifyFieldChange(MachineProperty.NON_REMOVABLE_DRIVE, new Object[]{entry.property, "None"});
        }
    }

    private String getMachineDriveValue(FileType fileType) {
        if (fileType == null)
            return null;
        switch (fileType) {
            case HDA:
                return getMachine() != null ? getMachine().getHdaImagePath() : null;
            case HDB:
                return getMachine() != null ? getMachine().getHdbImagePath() : null;
            case HDC:
                return getMachine() != null ? getMachine().getHdcImagePath() : null;
            case HDD:
                return getMachine() != null ? getMachine().getHddImagePath() : null;
            case CDROM:
                return getMachine() != null ? getMachine().getCdImagePath() : null;
            case FDA:
                return getMachine() != null ? getMachine().getFdaImagePath() : null;
            case FDB:
                return getMachine() != null ? getMachine().getFdbImagePath() : null;
            case SD:
                return getMachine() != null ? getMachine().getSdImagePath() : null;
            case SHARED_DIR:
                return getMachine() != null ? getMachine().getSharedFolderPath() : null;
            default:
                return null;
        }
    }

    private DeviceType[] getAvailableDeviceTypes() {
        ArrayList<DeviceType> types = new ArrayList<>();
        types.add(DeviceType.HARD_DISK);
        types.add(DeviceType.CDROM);
        if (Config.enableEmulatedFloppy) {
            types.add(DeviceType.FDA);
            types.add(DeviceType.FDB);
        }
        if (Config.enableEmulatedSDCard) {
            types.add(DeviceType.SD);
        }
        if (Config.enableSharedFolder) {
            types.add(DeviceType.SHARED_DIR);
        }
        return types.toArray(new DeviceType[0]);
    }

    private int getTypePosition(DeviceType type) {
        DeviceType[] types = getAvailableDeviceTypes();
        for (int i = 0; i < types.length; i++) {
            if (types[i] == type)
                return i;
        }
        return 0;
    }

    private DeviceType getFirstUnusedType() {
        DeviceType[] types = getAvailableDeviceTypes();
        for (DeviceType type : types) {
            int count = countDeviceType(type);
            if (count < type.maxCount) {
                if (type == DeviceType.HARD_DISK && getFreeHardDiskSlot() < 0)
                    continue;
                return type;
            }
        }
        return null;
    }

    private int countDeviceType(DeviceType type) {
        int count = 0;
        for (StorageEntry entry : storageEntries) {
            if (entry.deviceType == type)
                count++;
        }
        return count;
    }

    private int getFreeHardDiskSlot() {
        for (int slot = 0; slot < 4; slot++) {
            boolean used = false;
            for (StorageEntry entry : storageEntries) {
                if (entry.deviceType == DeviceType.HARD_DISK && entry.hardDiskSlot == slot) {
                    used = true;
                    break;
                }
            }
            if (!used)
                return slot;
        }
        return -1;
    }

    private void assignHardDiskSlot(StorageEntry entry) {
        int slot = getFreeHardDiskSlot();
        if (slot < 0)
            return;
        entry.hardDiskSlot = slot;
        assignHardDiskSlotProperty(entry, slot);
    }

    private void assignHardDiskSlotProperty(StorageEntry entry, int slot) {
        switch (slot) {
            case 0:
                entry.property = MachineProperty.HDA;
                entry.fileType = FileType.HDA;
                break;
            case 1:
                entry.property = MachineProperty.HDB;
                entry.fileType = FileType.HDB;
                break;
            case 2:
                entry.property = MachineProperty.HDC;
                entry.fileType = FileType.HDC;
                break;
            case 3:
                entry.property = MachineProperty.HDD;
                entry.fileType = FileType.HDD;
                break;
        }
    }

    private void populateStorageDeviceImageAdapter(StorageEntry entry, Runnable onComplete) {
        populateDiskOptions(entry.fileType, entry.createImage, (options, index) -> {
            entry.ui.setImageOptions(options);
            entry.ui.setImageSel(index);
            if (onComplete != null)
                onComplete.run();
        });
    }

    private void updateStorageDeviceSizeVisibility(StorageEntry entry) {
        entry.ui.setShowSize(entry.createImage);
    }

    private void unlockRemovableDevices(boolean flag) {
        for (StorageEntry entry : storageEntries) {
            if (entry.removable) {
                entry.ui.setEnabled(flag);
            }
        }
    }

    private void enableRemovableDeviceOptions(boolean flag) {
        unlockRemovableDevices(flag);
        enableRemovableDiskValues(flag);
    }

    private void enableRemovableDiskValues(boolean flag) {
        for (StorageEntry entry : storageEntries) {
            if (entry.removable) {
                entry.ui.setEnabled(flag);
            }
        }
    }

    private void enableNonRemovableDeviceOptions(boolean flagIn) {
        boolean flag = flagIn;
        if (MachineController.getInstance().isRunning())
            flag = false;

        // ui
        uiState.setUiEnabled(flag);
        uiState.setKeyboardEnabled(Config.enableKeyboardLayoutOption && flag);
        uiState.setMouseEnabled(Config.enableMouseOption && flag);

        // board
        uiState.setMachineTypeEnabled(flag);
        uiState.setCpuEnabled(flag);
        uiState.setCpuNumEnabled(flag);
        uiState.setRamEnabled(flag);
        uiState.setEnableKVMEnabled(flag && Config.enableKVM);
        uiState.setDisableI8042Enabled(flag && (LimboApplication.arch == Config.Arch.ia64
                || LimboApplication.arch == Config.Arch.ia64w));
        uiState.setEnableNvramEnabled(flag && (LimboApplication.arch == Config.Arch.ia64
                || LimboApplication.arch == Config.Arch.ia64w));
        uiState.setNvramEnabled(flag && (LimboApplication.arch == Config.Arch.ia64
                || LimboApplication.arch == Config.Arch.ia64w));
        uiState.setEnableMTTCGEnabled(flag && Config.enableMTTCG);

        // drives
        for (StorageEntry entry : storageEntries) {
            if (!entry.removable) {
                entry.ui.setEnabled(flag);
            }
        }

        // boot
        uiState.setBootEnabled(flag);
        uiState.setBiosEnabled(flag);
        uiState.setKernelEnabled(flag);
        uiState.setInitrdEnabled(flag);
        uiState.setAppendEnabled(flag);

        // graphics
        uiState.setVgaEnabled(flag);

        // audio
        if (Config.enableSDLSound && getMachine() != null
                && getMachine().getEnableVNC() != 1
                && getMachine().getPaused() == 0)
            uiState.setSoundEnabled(flag);
        else
            uiState.setSoundEnabled(false);

        // net
        uiState.setNetEnabled(flag);
        uiState.setNicEnabled(flag && uiState.getNetSel() > 0);
        uiState.setDnsEnabled(flag && uiState.getNetSel() > 0);
        uiState.setHostFwdEnabled(flag && uiState.getNetSel() > 0);

        // advanced
        uiState.setDisableACPIEnabled(flag);
        uiState.setDisableHPETEnabled(flag);
        uiState.setDisableTSCEnabled(flag);
        uiState.setExtraParamsEnabled(flag);
    }

    private void setCPUOptions() {
        if (MachineController.getInstance().getCurrStatus() != MachineStatus.Running &&
                (LimboApplication.arch == Config.Arch.x86 || LimboApplication.arch == Config.Arch.x86_64)) {
            uiState.setDisableACPIEnabled(true);
            uiState.setDisableHPETEnabled(true);
            uiState.setDisableTSCEnabled(true);
        } else {
            uiState.setDisableACPIEnabled(false);
            uiState.setDisableHPETEnabled(false);
            uiState.setDisableTSCEnabled(false);
        }
    }

    private void setArchOptions() {
        if (!machineLoaded) {
            populateMachineType(getMachine() != null ? getMachine().getMachineType() : null);
            populateCPUs(getMachine() != null ? getMachine().getCpu() : null);
            populateNetDevices(getMachine() != null ? getMachine().getNetworkCard() : null);
        }
    }

    private void promptKVM() {
        DialogInterface.OnClickListener okListener = (dialog, which) -> {
            notifyFieldChange(MachineProperty.ENABLE_KVM, true);
            uiState.setEnableMTTCG(false);
        };

        DialogInterface.OnClickListener cancelListener = (dialog, which) -> {
            uiState.setEnableKVM(false);
            notifyFieldChange(MachineProperty.ENABLE_KVM, false);
        };

        DialogUtils.UIAlert(this, getString(R.string.EnableKVM),
                getString(R.string.EnableKVMWarning),
                16, false, getString(android.R.string.ok),
                okListener, getString(android.R.string.cancel),
                cancelListener, null, null);
    }

    private void promptEnableMTTCG() {
        DialogInterface.OnClickListener okListener = (dialog, which) -> {
            notifyFieldChange(MachineProperty.ENABLE_MTTCG, true);
            uiState.setEnableKVM(false);
        };
        DialogInterface.OnClickListener cancelListener = (dialog, which) -> {
            notifyFieldChange(MachineProperty.ENABLE_MTTCG, false);
            uiState.setEnableMTTCG(false);
        };
        DialogUtils.UIAlert(this, getString(R.string.enableMTTCG),
                getString(R.string.enableMTTCGWarning),
                16, false, getString(android.R.string.ok), okListener,
                getString(android.R.string.cancel), cancelListener, null, null);
    }

    private void promptMultiCPU(String cpuNum) {
        DialogInterface.OnClickListener okListener = (dialog, which) ->
                notifyFieldChange(MachineProperty.CPUNUM, cpuNum);
        DialogInterface.OnClickListener cancelListener = (dialog, which) -> uiState.setCpuNumValue("1");
        DialogUtils.UIAlert(this, getString(R.string.multipleVCPU),
                getString(R.string.multipleVCPUWarning)
                        + (LimboApplication.arch == Config.Arch.x86_64
                        ? getString(R.string.disableTSCInstructions) : "")
                        + " " + getString(R.string.DoYouWantToContinue),
                16, false, getString(android.R.string.ok), okListener,
                getString(android.R.string.cancel), cancelListener, null, null);
    }

    private void promptDriveInterface(MachineProperty machineDriveName) {
        if (getMachine() == null)
            return;

        final String[] items = {"ide", "scsi", "virtio"};
        AlertDialog.Builder mBuilder = new AlertDialog.Builder(this);
        mBuilder.setTitle(machineDriveName + " " + getString(R.string.Interface));
        final int driveInterface = getMachineInterface(machineDriveName, items);
        mBuilder.setSingleChoiceItems(items, driveInterface, (dialog, i) -> {
            notifyFieldChange(MachineProperty.MEDIA_INTERFACE, new Object[]{machineDriveName, items[i]});
            dialog.dismiss();
        });
        AlertDialog alertDialog = mBuilder.create();
        alertDialog.show();
    }

    private int getMachineInterface(MachineProperty machineDriveName, String[] items) {
        String hdInterfaceStr = null;
        switch (machineDriveName) {
            case HDA:
                hdInterfaceStr = getMachine() != null ? getMachine().getHdaInterface() : null;
                break;
            case HDB:
                hdInterfaceStr = getMachine() != null ? getMachine().getHdbInterface() : null;
                break;
            case HDC:
                hdInterfaceStr = getMachine() != null ? getMachine().getHdcInterface() : null;
                break;
            case HDD:
                hdInterfaceStr = getMachine() != null ? getMachine().getHddInterface() : null;
                break;
            case CDROM:
                hdInterfaceStr = getMachine() != null ? getMachine().getCDInterface() : null;
                break;
            default:
                break;
        }
        for (int i = 0; i < items.length; i++) {
            if (items[i].equals(hdInterfaceStr))
                return i;
        }
        return 0;
    }

    protected void setDNSServer(String string) {
        File resolvConf = new File(LimboApplication.getBasefileDir() + "/etc/resolv.conf");
        FileOutputStream fileStream = null;
        try {
            fileStream = new FileOutputStream(resolvConf);
            String str = "nameserver " + string + "\n\n";
            byte[] data = str.getBytes();
            fileStream.write(data);
        } catch (Exception ex) {
            Log.e(TAG, "Could not write DNS to file: " + ex);
        } finally {
            if (fileStream != null) {
                try {
                    fileStream.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    // ============================================================
    // App environment
    // ============================================================

    private void checkAndLoadLibs() {
        if (Config.loadNativeLibsEarly)
            if (Config.loadNativeLibsMainThread)
                setupNativeLibs();
            else
                setupNativeLibsAsync();
    }

    private void clearNotifications() {
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.cancelAll();
    }

    private void setupDiskMapping() {
        diskMapping.clear();
        addDiskMapping(FileType.KERNEL, MachineProperty.KERNEL);
        addDiskMapping(FileType.INITRD, MachineProperty.INITRD);
    }

    private void addDiskMapping(FileType fileType, MachineProperty dbColName) {
        diskMapping.put(fileType, new DiskInfo(null, dbColName));
    }

    private void setupNativeLibsAsync() {
        Thread t = new Thread(() -> setupNativeLibs());
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    //XXX: this needs to be called from the main thread otherwise
    //  qemu crashes when it is started later
    public synchronized void setupNativeLibs() {
        if (libLoaded)
            return;
        //Compatibility lib
        System.loadLibrary("compat-limbo");

        //Glib deps
        System.loadLibrary("compat-musl");

        //Glib
        System.loadLibrary("glib-2.0");

        //Pixman for qemu
        // System.loadLibrary("pixman-1");

        // SDL library
        if (Config.enable_SDL) {
            if (Build.VERSION.SDK_INT >= 26)
                System.loadLibrary("compat-SDL2-addons");
            System.loadLibrary("SDL2");
        }

        System.loadLibrary("compat-SDL2-ext");

        //Limbo needed for vmexecutor
        System.loadLibrary("limbo");

        // qemu arch specific lib
        loadQEMULib();

        libLoaded = true;
    }

    protected void loadQEMULib() {
    }

    public void checkUpdate() {
        new Thread(() -> UpdateChecker.checkNewVersion(LimboActivity.this)).start();
    }

    private void setupStrictMode() {
        if (Config.debugStrictMode) {
            StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads().detectDiskWrites().detectNetwork()
                    .penaltyLog().build());
            StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder()
                    .detectLeakedSqlLiteObjects()
                    .detectLeakedClosableObjects().penaltyLog()
                    .build());
        }
    }

    private void populateAttributesUI() {
        populateMachines(null);
        populateMachineType(null);
        populateCPUs(null);
        populateCPUNum();
        populateRAM();
        populateDisks();
        populateBootDevices();
        populateNet();
        populateNetDevices(null);
        populateVGA();
        populateSoundcardConfig();
        populateUI();
        populateKeyboardLayout();
        populateMouse();
    }

    private void populateDisks() {
        populateDiskOptions(FileType.KERNEL, false, (options, index) -> {
            uiState.setKernelOptions(options);
            uiState.setKernelSel(index);
        });
        populateDiskOptions(FileType.INITRD, false, (options, index) -> {
            uiState.setInitrdOptions(options);
            uiState.setInitrdSel(index);
        });
        populateBiosOptions();
    }

    /**
     * Populates the BIOS dropdown with the firmware files (*.bin) shipped in
     * the app assets under roms/. "None" leaves the QEMU default firmware
     * resolution in place (bios-256k.bin fallback chain in VMExecutor).
     */
    private void populateBiosOptions() {
        Thread t = new Thread(() -> {
            ArrayList<String> options = new ArrayList<>();
            options.add("None");
            try {
                String[] roms = getAssets().list("roms");
                if (roms != null) {
                    java.util.Arrays.sort(roms);
                    for (String rom : roms) {
                        if (rom.toLowerCase().endsWith(".bin") || rom.toLowerCase().endsWith(".rom")) {
                            options.add(rom);
                        }
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "Could not list assets/roms: " + e.getMessage());
            }
            List<String> finalOptions = options;
            new Handler(Looper.getMainLooper()).post(() -> {
                uiState.setBiosOptions(finalOptions);
                // restore the machine's saved BIOS selection, if any
                String savedBios = getMachine() != null ? getMachine().getBios() : null;
                int sel = savedBios != null ? finalOptions.indexOf(savedBios) : -1;
                uiState.setBiosSel(Math.max(sel, 0));
            });
        });
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    /**
     * Populates the NVRAM file dropdown (ia64 only):
     * "Default" uses the app-managed nvram file, "New" creates a fresh one,
     * "Open" launches the file picker, followed by recent nvram paths.
     */
    private void populateNvramOptions() {
        Thread t = new Thread(() -> {
            ArrayList<String> options = new ArrayList<>();
            options.add(getString(R.string.label_nvram_default));
            options.add(getString(R.string.label_nvram_new));
            options.add(getString(R.string.open));
            ArrayList<String> recent = MachineFilePaths.getRecentFilePaths(Machine.FileType.NVRAM);
            if (recent != null) {
                for (String file : recent) {
                    if (file != null && !options.contains(file)) {
                        options.add(file);
                    }
                }
            }
            List<String> finalOptions = options;
            new Handler(Looper.getMainLooper()).post(() -> {
                uiState.setNvramOptions(finalOptions);
                String saved = getMachine() != null ? getMachine().getNvramPath() : null;
                int sel = saved != null ? finalOptions.indexOf(saved) : -1;
                uiState.setNvramSel(Math.max(sel, 0));
            });
        });
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private void setDefaultDNServer() {
        Thread t = new Thread(() -> {
            String defaultDNSServer = LimboSettingsManager.getDNSServer(LimboActivity.this);
            new Handler(Looper.getMainLooper()).post(() -> uiState.setDns(defaultDNSServer));
        });
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    // ============================================================
    // populating option lists
    // ============================================================

    private void populateRAM() {
        // RAM size is now a free-form user input (MB)
        if (uiState.getRamValue().isEmpty())
            uiState.setRamValue("512");
    }

    private void populateCPUNum() {
        // CPU core count is now a free-form user input
        if (uiState.getCpuNumValue().isEmpty())
            uiState.setCpuNumValue("1");
    }

    private void populateBootDevices() {
        ArrayList<String> bootDevicesList = new ArrayList<>();
        bootDevicesList.add("Default");
        bootDevicesList.add("CDROM");
        bootDevicesList.add("Hard Disk");
        if (Config.enableEmulatedFloppy)
            bootDevicesList.add("Floppy");
        uiState.setBootOptions(bootDevicesList);
        uiState.setBootSel(0);
    }

    private void populateNet() {
        List<String> list = new ArrayList<>();
        list.add("None");
        list.add("User");
        list.add("TAP");
        uiState.setNetOptions(list);
        uiState.setNetSel(0);
    }

    private void populateVGA() {
        ArrayList<String> arrList = ArchDefinitions.getVGAValues(this);
        uiState.setVgaOptions(arrList);
        uiState.setVgaSel(0);
    }

    private void populateKeyboardLayout() {
        ArrayList<String> arrList = ArchDefinitions.getKeyboardValues(this);
        uiState.setKeyboardOptions(arrList);
        uiState.setKeyboardSel(0);
    }

    private void populateMouse() {
        ArrayList<String> arrList = ArchDefinitions.getMouseValues(this);
        uiState.setMouseOptions(arrList);
        uiState.setMouseSel(0);
    }

    private void populateSoundcardConfig() {
        ArrayList<String> soundCards = new ArrayList<>();
        soundCards.add("None");
        soundCards.addAll(ArchDefinitions.getSoundcards(this));
        uiState.setSoundOptions(soundCards);
        uiState.setSoundSel(0);
    }

    private void populateNetDevices(String nic) {
        ArrayList<String> networkCards = ArchDefinitions.getNetworkDevices(this);
        uiState.setNicOptions(networkCards);
        int pos = 0;
        if (nic != null) {
            pos = networkCards.indexOf(nic);
            if (pos < 0) pos = 0;
        }
        uiState.setNicSel(pos);
    }

    private void populateMachines(String machineValue) {
        Thread t = new Thread(() -> {
            ArrayList<String> machinesList = ArchDefinitions.getMachineValues(LimboActivity.this);
            ArrayList<String> machinesDB = MachineController.getInstance().getStoredMachines();
            machinesList.addAll(machinesDB);
            new Handler(Looper.getMainLooper()).post(() -> {
                uiState.setMachines(new ArrayList<>(machinesList));
                uiState.setMachineSel(0);
                if (machineValue != null) {
                    int pos = uiState.getMachines().indexOf(machineValue);
                    uiState.setMachineSel(Math.max(pos, 0));
                }
            });
        });
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private void seMachineDriveValue(FileType fileType, String diskValue) {
        if (fileType == null)
            return;
        switch (fileType) {
            case KERNEL:
                if (diskValue != null) {
                    int pos = uiState.getKernelOptions().indexOf(diskValue);
                    uiState.setKernelSel(Math.max(pos, 0));
                } else {
                    uiState.setKernelSel(0);
                }
                break;
            case INITRD:
                if (diskValue != null) {
                    int pos = uiState.getInitrdOptions().indexOf(diskValue);
                    uiState.setInitrdSel(Math.max(pos, 0));
                } else {
                    uiState.setInitrdSel(0);
                }
                break;
            case NVRAM:
                if (diskValue != null) {
                    int pos = uiState.getNvramOptions().indexOf(diskValue);
                    uiState.setNvramSel(Math.max(pos, 0));
                } else {
                    uiState.setNvramSel(0);
                }
                break;
            default:
                // storage device rows
                StorageEntry entry = null;
                for (StorageEntry e : storageEntries) {
                    if (e.fileType == fileType) {
                        entry = e;
                        break;
                    }
                }
                if (entry != null) {
                    if (diskValue != null) {
                        int pos = entry.ui.getImageOptions().indexOf(diskValue);
                        entry.ui.setImageSel(Math.max(pos, 0));
                    } else {
                        entry.ui.setImageSel(0);
                    }
                }
                break;
        }
    }

    private void populateCPUs(String cpu) {
        ArrayList<String> arrList = ArchDefinitions.getCpuValues(this);
        uiState.setCpuOptions(arrList);
        int pos = 0;
        if (cpu != null) {
            pos = arrList.indexOf(cpu);
            if (pos < 0) pos = 0;
        }
        uiState.setCpuSel(pos);
    }

    private void populateMachineType(String machineType) {
        ArrayList<String> arrList = ArchDefinitions.getMachineTypeValues(this);
        uiState.setMachineTypeOptions(arrList);
        int pos = 0;
        if (machineType != null) {
            pos = arrList.indexOf(machineType);
            if (pos < 0) pos = 0;
        }
        uiState.setMachineTypeSel(pos);
    }

    private void populateUI() {
        ArrayList<String> arrList = ArchDefinitions.getUIValues();
        uiState.setUiOptions(arrList);
        uiState.setUiSel(0);
    }

    public void populateDiskOptions(FileType fileType, boolean createOption, PopulateCallback onComplete) {
        Thread t = new Thread(() -> {
            ArrayList<String> oldHDs = MachineFilePaths.getRecentFilePaths(fileType);
            ArrayList<String> arraySpinner = new ArrayList<>();
            arraySpinner.add("None");
            if (createOption)
                arraySpinner.add("New");
            arraySpinner.add(getString(R.string.open));
            if (oldHDs != null) {
                for (String file : oldHDs) {
                    if (file != null) {
                        arraySpinner.add(file);
                    }
                }
            }
            new Handler(Looper.getMainLooper()).post(() -> onComplete.onResult(arraySpinner, 0));
        });
        t.start();
    }

    public interface PopulateCallback {
        void onResult(List<String> options, int index);
    }

    // ============================================================
    // Machine control
    // ============================================================

    private void loadMachine() {
        setUserPressed(false);
        if (getMachine() == null) {
            return;
        }
        // cancel pending debounced commits before switching machines
        debounceHandler.removeCallbacks(appendCommit);
        debounceHandler.removeCallbacks(dnsCommit);
        debounceHandler.removeCallbacks(hostFwdCommit);
        debounceHandler.removeCallbacks(extraParamsCommit);
        debounceHandler.removeCallbacks(cpuNumCommit);
        debounceHandler.removeCallbacks(ramCommit);
        new Handler(Looper.getMainLooper()).post(() -> {
            loadMachineUI();
            new Handler(Looper.getMainLooper()).postDelayed(this::postLoadMachineUI, 1000);
            setCPUOptions();
            getMachine().addObserver(LimboActivity.this);
        });
    }

    private void postLoadMachineUI() {
        if (getMachine() == null) {
            return;
        }
        changeStatus(MachineController.getInstance().getCurrStatus());
        if (getMachine().getPaused() == 1) {
            enableNonRemovableDeviceOptions(false);
            enableRemovableDeviceOptions(false);
        } else {
            enableNonRemovableDeviceOptions(true);
            enableRemovableDeviceOptions(true);
        }
        setUserPressed(true);
        machineLoaded = false;
        uiState.setMachineEnabled(!MachineController.getInstance().isRunning());
    }

    private void loadMachineUI() {
        populateMachineType(getMachine() != null ? getMachine().getMachineType() : null);
        populateCPUs(getMachine() != null ? getMachine().getCpu() : null);
        populateNetDevices(getMachine() != null ? getMachine().getNetworkCard() : null);
        uiState.setCpuNumValue(getMachine() != null ? "" + getMachine().getCpuNum() : "1");
        uiState.setRamValue(getMachine() != null ? "" + getMachine().getMemory() : "512");
        seMachineDriveValue(FileType.KERNEL, getMachine() != null ? getMachine().getKernel() : null);
        seMachineDriveValue(FileType.INITRD, getMachine() != null ? getMachine().getInitRd() : null);
        uiState.setAppend(getMachine() != null && getMachine().getAppend() != null ? getMachine().getAppend() : "");
        uiState.setHostFwd(getMachine() != null && getMachine().getHostFwd() != null ? getMachine().getHostFwd() : "");
        uiState.setExtraParams(getMachine() != null && getMachine().getExtraParams() != null ? getMachine().getExtraParams() : "");

        // Storage devices are loaded dynamically
        refreshStorageDevices();

        // Advance
        setSpinnerSel(uiState.getBootOptions(), getMachine() != null ? getMachine().getBootDevice() : null,
                idx -> uiState.setBootSel(idx));
        setSpinnerSel(uiState.getBiosOptions(), getMachine() != null ? getMachine().getBios() : null,
                idx -> uiState.setBiosSel(idx));
        setSpinnerSel(uiState.getNetOptions(), getMachine() != null ? getMachine().getNetwork() : null,
                idx -> uiState.setNetSel(idx));
        setSpinnerSel(uiState.getVgaOptions(), getMachine() != null ? getMachine().getVga() : null,
                idx -> uiState.setVgaSel(idx));
        setSpinnerSel(uiState.getSoundOptions(), getMachine() != null ? getMachine().getSoundCard() : null,
                idx -> uiState.setSoundSel(idx));
        setSpinnerSel(uiState.getUiOptions(), getMachine() != null ? getMachine().getUI() : "SDL",
                idx -> uiState.setUiSel(idx));
        setSpinnerSel(uiState.getMouseOptions(), fixMouseValue(getMachine() != null ? getMachine().getMouse() : null),
                idx -> uiState.setMouseSel(idx));
        setSpinnerSel(uiState.getKeyboardOptions(), getMachine() != null ? getMachine().getKeyboard() : null,
                idx -> uiState.setKeyboardSel(idx));

        // motherboard settings
        uiState.setDisableACPI(getMachine() != null && getMachine().getDisableAcpi() == 1);
        uiState.setDisableHPET(getMachine() != null && getMachine().getDisableHPET() == 1);
        if (LimboApplication.arch == Config.Arch.x86 || LimboApplication.arch == Config.Arch.x86_64)
            uiState.setDisableTSC(getMachine() != null && getMachine().getDisableTSC() == 1);
        uiState.setEnableKVM(getMachine() != null && getMachine().getEnableKVM() == 1);
        uiState.setEnableMTTCG(getMachine() != null && getMachine().getEnableMTTCG() == 1);
        if (LimboApplication.arch == Config.Arch.ia64 || LimboApplication.arch == Config.Arch.ia64w)
            uiState.setDisableI8042(getMachine() != null && getMachine().getDisableI8042() == 1);
        if (LimboApplication.arch == Config.Arch.ia64 || LimboApplication.arch == Config.Arch.ia64w)
            uiState.setEnableNvram(getMachine() != null && getMachine().getEnableNvram() == 1);
        populateNvramOptions();

        enableNonRemovableDeviceOptions(true);
        enableRemovableDeviceOptions(!MachineController.getInstance().isRunning());

        if (Config.enableSDLSound) {
            uiState.setSoundEnabled(getMachine() != null && getMachine().getEnableVNC() != 1 && getMachine().getPaused() == 0);
        } else
            uiState.setSoundEnabled(false);

        uiState.setMachineEnabled(false);
    }

    private void setSpinnerSel(List<String> options, String value, java.util.function.IntConsumer setter) {
        if (value != null) {
            int pos = options.indexOf(value);
            setter.accept(Math.max(pos, 0));
        } else {
            setter.accept(0);
        }
    }

    private String fixMouseValue(String mouse) {
        String m = mouse;
        if (m != null) {
            if (m.startsWith("usb-tablet"))
                m += " " + getString(R.string.fixesMouseParen);
        }
        return m;
    }

    private void updateSummary() {
        updateUISummary(false);
        updateCPUSummary(false);
        updateStorageDevicesSummary(false);
        updateGraphicsSummary(false);
        updateAudioSummary(false);
        updateNetworkSummary(false);
        updateBootSummary(false);
        updateAdvancedSummary(false);
    }

    public void updateUISummary(boolean clear) {
        uiState.setUiSummary(clear || getMachine() == null || uiState.getMachineSel() < 2 ? "" : buildUISummary());
    }

    private String buildUISummary() {
        String ui = getMachine().getUI();
        String text = getString(R.string.display) + ": " + ui;
        if ("VNC".equals(ui)) {
            text += ", " + getString(R.string.server);
            text += ": " + NetworkUtils.getVNCAddress(this) + ":" + Config.defaultVNCPort;
        }
        if (getMachine().getKeyboard() != null) {
            text += ", " + getString(R.string.keyboard) + ": " + getMachine().getKeyboard();
        }
        if (getMachine().getMouse() != null) {
            text += ", " + getString(R.string.mouse) + ": " + getMachine().getMouse();
        }
        return text;
    }

    private Machine getMachine() {
        return MachineController.getInstance().getMachine();
    }

    public void updateCPUSummary(boolean clear) {
        uiState.setBoardSummary(clear || getMachine() == null || uiState.getMachineSel() < 2 ? "" : buildCPUSummary());
    }

    private String buildCPUSummary() {
        String text = "Machine Type: " + getMachine().getMachineType()
                + ", CPU: " + getMachine().getCpu()
                + ", " + getMachine().getCpuNum() + " CPU" + (getMachine().getCpuNum() > 1 ? "s" : "")
                + ", " + getMachine().getMemory() + " MB";
        if (uiState.getEnableMTTCG())
            text = appendOption("Enable MTTCG", text);
        if (uiState.getEnableKVM())
            text = appendOption("Enable KVM", text);
        if (uiState.getDisableACPI())
            text = appendOption("Disable ACPI", text);
        if (uiState.getDisableHPET())
            text = appendOption("Disable HPET", text);
        if (uiState.getDisableTSC())
            text = appendOption("Disable TSC", text);
        return text;
    }

    public void updateStorageDevicesSummary(boolean clear) {
        uiState.setStorageSummary(clear || getMachine() == null || uiState.getMachineSel() < 2 ? "" : buildStorageSummary());
    }

    private String buildStorageSummary() {
        String text = null;
        text = appendDriveFilename(getMachine().getHdaImagePath(), text, "HDA", false);
        text = appendDriveFilename(getMachine().getHdbImagePath(), text, "HDB", false);
        text = appendDriveFilename(getMachine().getHdcImagePath(), text, "HDC", false);
        text = appendDriveFilename(getMachine().getHddImagePath(), text, "HDD", false);
        text = appendDriveFilename(getMachine().getCdImagePath(), text, "CDROM", true);
        text = appendDriveFilename(getMachine().getFdaImagePath(), text, "FDA", true);
        text = appendDriveFilename(getMachine().getFdbImagePath(), text, "FDB", true);
        text = appendDriveFilename(getMachine().getSdImagePath(), text, "SD", true);

        if (Config.enableSharedFolder)
            text = appendDriveFilename(getMachine().getSharedFolderPath(), text,
                    getString(R.string.SharedFolder), false);

        if (text == null || text.equals("'") || text.isEmpty())
            text = "None";
        return text;
    }

    public void updateBootSummary(boolean clear) {
        uiState.setBootSummary(clear || getMachine() == null || uiState.getMachineSel() < 2 ? "" : buildBootSummary());
    }

    private String buildBootSummary() {
        String text = "Boot from: " + getMachine().getBootDevice();
        text = appendDriveFilename(getMachine().getBios(), text, "bios", false);
        text = appendDriveFilename(getMachine().getKernel(), text, "kernel", false);
        text = appendDriveFilename(getMachine().getInitRd(), text, "initrd", false);
        text = appendDriveFilename(getMachine().getAppend(), text, "append", false);
        return text;
    }

    private String appendDriveFilename(String driveFile, String text, String drive, boolean allowEmptyDrive) {
        String file = null;
        if (driveFile != null) {
            if ((driveFile.isEmpty() || driveFile.equals("None")) && allowEmptyDrive) {
                file = drive + ": Empty";
            } else if (!driveFile.isEmpty() && !driveFile.equals("None"))
                file = drive + ": " + FileUtils.getFilenameFromPath(driveFile);
        }
        if (text == null && file != null) return file;
        else if (file != null) return text + ", " + file;
        else return text;
    }

    public void updateGraphicsSummary(boolean clear) {
        uiState.setGraphicsSummary(clear || getMachine() == null || uiState.getMachineSel() < 2
                ? "" : "Video Card: " + getMachine().getVga());
    }

    public void updateAudioSummary(boolean clear) {
        uiState.setAudioSummary(clear || getMachine() == null || uiState.getMachineSel() < 2
                ? "" : getString(R.string.AudioCard) + ": " + (getMachine().getSoundCard() != null ? getMachine().getSoundCard() : "None"));
    }

    public void updateNetworkSummary(boolean clear) {
        uiState.setNetworkSummary(clear || getMachine() == null || uiState.getMachineSel() < 2 ? "" : buildNetworkSummary());
    }

    private String buildNetworkSummary() {
        String netCfg = getMachine().getNetwork();
        String text = getString(R.string.Network) + ": " + (netCfg != null ? netCfg : "None");
        if (netCfg != null && !netCfg.equals("None")) {
            String nicCard = getMachine().getNetworkCard();
            text += ", " + getString(R.string.NicCard) + ": " + (nicCard != null ? nicCard : "None");
            text += ", " + getString(R.string.DNSServer) + ": " + uiState.getDns();
            String hostFWD = getMachine().getHostFwd();
            if (hostFWD != null && !hostFWD.isEmpty())
                text += ", " + getString(R.string.HostForward) + ": " + hostFWD;
        }
        return text;
    }

    public void updateAdvancedSummary(boolean clear) {
        uiState.setAdvancedSummary(clear || getMachine() == null || uiState.getMachineSel() < 2 ? "" : buildAdvancedSummary());
    }

    private String buildAdvancedSummary() {
        String text = "";
        if (getMachine().getExtraParams() != null
                && !getMachine().getExtraParams().isEmpty())
            text = getString(R.string.ExtraParams) + ": " + getMachine().getExtraParams();
        return text;
    }

    private String appendOption(String option, String text) {
        return text.isEmpty() && !option.isEmpty() ? option : text + ", " + option;
    }

    // ============================================================
    // Start / Stop / Pause / Restart
    // ============================================================

    private void onStartButton() {
        if (uiState.getMachineSel() == 0 || getMachine() == null) {
            ToastUtils.toastShort(this, getString(R.string.SelectOrCreateVirtualMachineFirst));
            return;
        }

        if (!validateFiles()) {
            return;
        }

        try {
            createMachineDir(MachineController.getInstance().getMachineSaveDir());
        } catch (Exception ex) {
            ToastUtils.toastLong(this, getString(R.string.Error) + ": " + ex);
            return;
        }

        // XXX: save the user defined dns server before we start the vm
        LimboSettingsManager.setDNSServer(this, uiState.getDns());

        //XXX: make sure that bios files are installed in case we ran out of space in the last run
        FileInstaller.installFiles(this, false);

        String ui = getMachine().getUI();
        if ("GTK".equals(ui)) {
            startGtk();
        } else if (getMachine().getEnableVNC() == 1) {
            startVNC();
        } else {
            startSDL();
        }
    }

    /**
     * Starts the GTK Activity which initializes the GTK4 android backend and
     * starts the native process via the controller.
     */
    public void startGtk() {
        Intent intent = new Intent(this, LimboGtkActivity.class);
        startActivityForResult(intent, Config.SDL_REQUEST_CODE);
    }

    private void createMachineDir(String dir) {
        File destDir = new File(dir);
        if (!destDir.exists()) {
            if (!destDir.mkdirs())
                throw new RuntimeException(getString(R.string.failToCreateMachineDirError));
        }
    }

    /**
     * Starts the SDL Activity that will later start the native process via the service.
     */
    public void startSDL() {
        Intent intent = new Intent(this, LimboSDLActivity.class);
        startActivityForResult(intent, Config.SDL_REQUEST_CODE);
    }

    /**
     * Start the vm with VNC Support via the Controller.
     */
    public void startVNC() {
        if (LimboSettingsManager.getEnableExternalVNC(this)) {
            LimboActivityCommon.promptVNCServer(this,
                    getString(R.string.ExternalVNCEnabledWarning), viewListener);
        } else if (!LimboSettingsManager.getVNCEnablePassword(this)) {
            LimboActivityCommon.promptVNCServer(this,
                    getString(R.string.VNCPasswordNotEnabledWarning), viewListener);
        } else if (LimboSettingsManager.getVNCEnablePassword(this)
                && LimboSettingsManager.getVNCPass(this) == null) {
            ToastUtils.toastShort(this, getString(R.string.VNCPasswordMissing));
        } else {
            notifyAction(MachineAction.START_VM, null);
        }
    }

    private boolean validateFiles() {
        return FileUtils.fileValid(getMachine() != null ? getMachine().getHdaImagePath() : null)
                && FileUtils.fileValid(getMachine() != null ? getMachine().getHdbImagePath() : null)
                && FileUtils.fileValid(getMachine() != null ? getMachine().getHdcImagePath() : null)
                && FileUtils.fileValid(getMachine() != null ? getMachine().getHddImagePath() : null)
                && FileUtils.fileValid(getMachine() != null ? getMachine().getFdaImagePath() : null)
                && FileUtils.fileValid(getMachine() != null ? getMachine().getFdbImagePath() : null)
                && FileUtils.fileValid(getMachine() != null ? getMachine().getSdImagePath() : null)
                && FileUtils.fileValid(getMachine() != null ? getMachine().getCdImagePath() : null)
                && FileUtils.fileValid(getMachine() != null ? getMachine().getKernel() : null)
                && FileUtils.fileValid(getMachine() != null ? getMachine().getInitRd() : null);
    }

    private void onStopButton(boolean exitApp) {
        KeyboardUtils.hideKeyboard(this, parent);
        if (MachineController.getInstance().isRunning()) {
            if (MachineController.getInstance().isVNCEnabled())
                LimboActivityCommon.promptStopVM(this, viewListener);
            else {
                LimboSDLActivity.pendingStop = true;
                startSDL();
            }
        } else {
            if (getMachine() != null
                    && MachineController.getInstance().isPaused() && !exitApp) {
                promptDiscardVMState();
            } else {
                ToastUtils.toastShort(this, getString(R.string.vmNotRunning));
            }
        }
    }

    private void onPauseButton() {
        if (MachineController.getInstance().isRunning()) {
            if (MachineController.getInstance().isVNCEnabled())
                LimboActivityCommon.promptPause(this, viewListener);
            else {
                LimboSDLActivity.pendingPause = true;
                startSDL();
            }
        }
    }

    private void onRestartButton() {
        if (!MachineController.getInstance().isRunning()) {
            if (getMachine() != null && getMachine().getPaused() == 1) {
                promptDiscardVMState();
            } else {
                ToastUtils.toastShort(this, getString(R.string.VMNotRunning));
            }
        }
        LimboActivityCommon.promptResetVM(this, viewListener);
    }

    // ============================================================
    // Dialog prompts
    // ============================================================

    public void promptMachineName(Activity activity) {
        AlertDialog alertDialog = new MaterialAlertDialogBuilder(activity).create();
        alertDialog.setTitle(getString(R.string.NewMachineName));
        final EditText vmNameTextView = new EditText(activity);
        vmNameTextView.setPadding(20, 20, 20, 20);
        vmNameTextView.setEnabled(true);
        vmNameTextView.setVisibility(View.VISIBLE);
        vmNameTextView.setSingleLine();
        alertDialog.setView(vmNameTextView);
        alertDialog.setCanceledOnTouchOutside(false);
        alertDialog.setButton(DialogInterface.BUTTON_POSITIVE, getString(R.string.Create),(DialogInterface.OnClickListener) null);

        alertDialog.show();

        Button button = alertDialog.getButton(AlertDialog.BUTTON_POSITIVE);
        button.setOnClickListener(v -> {
            if (vmNameTextView.getText().toString().trim().isEmpty())
                ToastUtils.toastShort(activity, getString(R.string.MachineNameCannotBeEmpty));
            else {
                createMachine(vmNameTextView.getText().toString());
                alertDialog.dismiss();
            }
        });
        alertDialog.setOnDismissListener(dialog -> {
            InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(vmNameTextView.getWindowToken(), 0);
        });
    }

    public void promptImageName(Activity activity, FileType fileType) {
        promptImageName(activity, fileType, -1L);
    }

    public void promptImageName(Activity activity, FileType fileType, int sizeIndex) {
        promptImageName(activity, fileType, -1L);
    }

    public void promptImageName(Activity activity, FileType fileType, long sizeBytes) {
        AlertDialog alertDialog = new MaterialAlertDialogBuilder(activity).create();
        alertDialog.setTitle(getString(R.string.ImageName));

        LinearLayout mLayout = new LinearLayout(this);
        mLayout.setPadding(20, 20, 20, 20);
        mLayout.setOrientation(LinearLayout.VERTICAL);

        final EditText imageNameView = new EditText(activity);
        imageNameView.setEnabled(true);
        imageNameView.setVisibility(View.VISIBLE);
        imageNameView.setSingleLine();
        mLayout.addView(imageNameView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // size selection (custom size in MB/GB/TB)
        LinearLayout sizeLayout = new LinearLayout(this);
        sizeLayout.setOrientation(LinearLayout.HORIZONTAL);

        final EditText sizeValueView = new EditText(activity);
        sizeValueView.setEnabled(true);
        sizeValueView.setSingleLine();
        sizeValueView.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        sizeValueView.setText("4");
        sizeLayout.addView(sizeValueView, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        final Spinner sizeUnit = new Spinner(this);
        String[] units = new String[]{getString(R.string.size_unit_gb), getString(R.string.size_unit_mb),
                getString(R.string.size_unit_tb)};
        android.widget.ArrayAdapter<String> unitAdapter =
                new android.widget.ArrayAdapter<>(this, R.layout.custom_spinner_item, units);
        unitAdapter.setDropDownViewResource(R.layout.custom_spinner_dropdown_item);
        sizeUnit.setAdapter(unitAdapter);
        if (sizeBytes > 0) {
            formatSizeValue(sizeValueView, sizeUnit, sizeBytes);
        }
        sizeLayout.addView(sizeUnit, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        mLayout.addView(sizeLayout);

        alertDialog.setView(mLayout);

        alertDialog.setButton(DialogInterface.BUTTON_POSITIVE, getString(R.string.Create),(DialogInterface.OnClickListener) null);
        alertDialog.setButton(DialogInterface.BUTTON_NEGATIVE, getString(R.string.ChangeDirectory),(DialogInterface.OnClickListener) null);

        alertDialog.show();

        Button positiveButton = alertDialog.getButton(AlertDialog.BUTTON_POSITIVE);
        positiveButton.setOnClickListener(v -> {
            if (LimboSettingsManager.getImagesDir(this) == null) {
                changeImagesDir();
                return;
            }

            long bytes = parseSizeBytes(sizeValueView, sizeUnit);

            String image = imageNameView.getText().toString();
            if (image.trim().isEmpty())
                ToastUtils.toastShort(activity, getString(R.string.ImageFilenameCannotBeEmpty));
            else {
                String templateImage = getTemplateForSize(bytes);
                String filePath;
                if (templateImage != null) {
                    if (!image.endsWith(".qcow2")) {
                        image += ".qcow2";
                    }
                    filePath = FileUtils.createImgFromTemplate(this, templateImage, image, fileType);
                } else {
                    if (!image.endsWith(".img") && !image.endsWith(".raw")) {
                        image += ".img";
                    }
                    filePath = FileUtils.createRawImage(this, bytes, image, fileType);
                }
                if (filePath != null) {
                    updateDrive(fileType, filePath);
                    alertDialog.dismiss();
                }
            }
        });

        Button negativeButton = alertDialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        negativeButton.setOnClickListener(v -> changeImagesDir());
    }

    private void formatSizeValue(EditText sizeValueView, Spinner sizeUnit, long sizeBytes) {
        long gb = 1024L * 1024L * 1024L;
        long mb = 1024L * 1024L;
        if (sizeBytes % gb == 0L) {
            sizeValueView.setText("" + (sizeBytes / gb));
            sizeUnit.setSelection(0); // GB
        } else if (sizeBytes % mb == 0L) {
            sizeValueView.setText("" + (sizeBytes / mb));
            sizeUnit.setSelection(1); // MB
        } else {
            sizeValueView.setText("" + (sizeBytes / mb));
            sizeUnit.setSelection(1); // MB (rounded)
        }
    }

    private long parseSizeBytes(EditText sizeValueView, Spinner sizeUnit) {
        long value = 1L;
        try {
            value = Long.parseLong(sizeValueView.getText().toString().trim());
        } catch (NumberFormatException e) {
            value = 1;
        }
        if (value < 1)
            value = 1;
        String unit = (String) sizeUnit.getSelectedItem();
        long multiplier = 1024L * 1024L * 1024L; // GB default
        if (unit != null) {
            if (unit.equals(getString(R.string.size_unit_mb)))
                multiplier = 1024L * 1024L;
            else if (unit.equals(getString(R.string.size_unit_tb)))
                multiplier = 1024L * 1024L * 1024L * 1024L;
        }
        return value * multiplier;
    }

    private String getTemplateForSize(long sizeBytes) {
        long gb = 1024L * 1024L * 1024L;
        if (sizeBytes == gb) return "hd1g.qcow2";
        else if (sizeBytes == 2 * gb) return "hd2g.qcow2";
        else if (sizeBytes == 4 * gb) return "hd4g.qcow2";
        else if (sizeBytes == 10 * gb) return "hd10g.qcow2";
        else if (sizeBytes == 20 * gb) return "hd20g.qcow2";
        else return null;
    }

    public void changeImagesDir() {
        ToastUtils.toastLong(this, getString(R.string.chooseDirToCreateImage));
        LimboFileManager.browse(this, FileType.IMAGE_DIR, Config.OPEN_IMAGE_DIR_REQUEST_CODE);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            moveTaskToBack(true);
            return true;
        }
        return false;
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (resultCode == Config.SDL_QUIT_RESULT_CODE) {
            if (parent != null) {
                // parent.finish();
            }
            finish();
            if (MachineController.getInstance().isRunning()) {
                notifyAction(MachineAction.STOP_VM, null);
            }
        } else if (requestCode == StorageDeviceEditorActivity.REQUEST_CODE) {
            if (resultCode == RESULT_OK) {
                applyStorageDeviceEditorResult(data, pendingStorageEditTag);
                pendingStorageEditTag = -1;
            }
        } else if (requestCode == Config.OPEN_IMPORT_FILE_REQUEST_CODE || requestCode == Config.OPEN_IMPORT_FILE_ASF_REQUEST_CODE) {
            String file = requestCode == Config.OPEN_IMPORT_FILE_ASF_REQUEST_CODE
                    ? FileUtils.getFileUriFromIntent(this, data, false)
                    : FileUtils.getFilePathFromIntent(this, data);
            if (file != null)
                importMachines(file);
        } else if (requestCode == Config.OPEN_EXPORT_DIR_REQUEST_CODE || requestCode == Config.OPEN_EXPORT_DIR_ASF_REQUEST_CODE) {
            String exportDir = requestCode == Config.OPEN_EXPORT_DIR_ASF_REQUEST_CODE
                    ? FileUtils.getFileUriFromIntent(this, data, true)
                    : FileUtils.getDirPathFromIntent(this, data);
            if (exportDir != null)
                LimboSettingsManager.setExportDir(this, exportDir);
        } else if (requestCode == Config.OPEN_IMAGE_FILE_REQUEST_CODE || requestCode == Config.OPEN_IMAGE_FILE_ASF_REQUEST_CODE) {
            String file;
            if (requestCode == Config.OPEN_IMAGE_FILE_ASF_REQUEST_CODE) {
                file = FileUtils.getFileUriFromIntent(this, data, true);
            } else {
                browseFileType = FileUtils.getFileTypeFromIntent(this, data);
                file = FileUtils.getFilePathFromIntent(this, data);
            }
            if (file != null)
                updateDrive(browseFileType, file);
        } else if (requestCode == Config.OPEN_IMAGE_DIR_REQUEST_CODE || requestCode == Config.OPEN_IMAGE_DIR_ASF_REQUEST_CODE) {
            String imageDir = requestCode == Config.OPEN_IMAGE_DIR_ASF_REQUEST_CODE
                    ? FileUtils.getFileUriFromIntent(this, data, true)
                    : FileUtils.getDirPathFromIntent(this, data);
            if (imageDir != null)
                LimboSettingsManager.setImagesDir(this, imageDir);
        } else if (requestCode == Config.OPEN_SHARED_DIR_REQUEST_CODE || requestCode == Config.OPEN_SHARED_DIR_ASF_REQUEST_CODE) {
            String file;
            if (requestCode == Config.OPEN_SHARED_DIR_ASF_REQUEST_CODE) {
                file = FileUtils.getFileUriFromIntent(this, data, true);
            } else {
                browseFileType = FileUtils.getFileTypeFromIntent(this, data);
                file = FileUtils.getDirPathFromIntent(this, data);
            }
            if (file != null) {
                updateDrive(browseFileType, file);
                LimboSettingsManager.setSharedDir(this, file);
            }
        } else if (requestCode == Config.OPEN_LOG_FILE_DIR_REQUEST_CODE || requestCode == Config.OPEN_LOG_FILE_DIR_ASF_REQUEST_CODE) {
            String file = requestCode == Config.OPEN_LOG_FILE_DIR_ASF_REQUEST_CODE
                    ? FileUtils.getFileUriFromIntent(this, data, true)
                    : FileUtils.getDirPathFromIntent(this, data);
            if (file != null) {
                FileUtils.saveLogToFile(this, file);
            }
        } else if (requestCode == Config.OPEN_IMPORT_BIOS_FILE_REQUEST_CODE || requestCode == Config.OPEN_IMPORT_BIOS_FILE_ASF_REQUEST_CODE) {
            String file = requestCode == Config.OPEN_IMPORT_BIOS_FILE_ASF_REQUEST_CODE
                    ? FileUtils.getFileUriFromIntent(this, data, false)
                    : FileUtils.getFilePathFromIntent(this, data);
            if (file != null)
                BIOSImporter.importBIOSFile(this, file);
        }
    }

    private void updateDrive(FileType fileType, String diskValue) {
        if (fileType == null || diskValue == null) {
            return;
        }
        if (!diskValue.trim().isEmpty()) {
            // add to options if not present
            switch (fileType) {
                case KERNEL:
                    if (!uiState.getKernelOptions().contains(diskValue)) {
                        List<String> opts = new ArrayList<>(uiState.getKernelOptions());
                        opts.add(diskValue);
                        uiState.setKernelOptions(opts);
                    }
                    notifyAction(MachineAction.INSERT_FAV, new Object[]{diskValue, fileType});
                    // persist the newly opened file into the machine (and DB),
                    // otherwise the VM would start with the old/empty path
                    notifyFieldChange(MachineProperty.KERNEL, diskValue);
                    seMachineDriveValue(fileType, diskValue);
                    break;
                case INITRD:
                    if (!uiState.getInitrdOptions().contains(diskValue)) {
                        List<String> opts = new ArrayList<>(uiState.getInitrdOptions());
                        opts.add(diskValue);
                        uiState.setInitrdOptions(opts);
                    }
                    notifyAction(MachineAction.INSERT_FAV, new Object[]{diskValue, fileType});
                    // persist the newly opened file into the machine (and DB)
                    notifyFieldChange(MachineProperty.INITRD, diskValue);
                    seMachineDriveValue(fileType, diskValue);
                    break;
                case NVRAM:
                    if (!uiState.getNvramOptions().contains(diskValue)) {
                        List<String> opts = new ArrayList<>(uiState.getNvramOptions());
                        opts.add(diskValue);
                        uiState.setNvramOptions(opts);
                    }
                    // record in recent paths directly: Dispatcher.addDriveToList
                    // expects {FileType, path} while callers pass {path, FileType},
                    // so the INSERT_FAV action would throw a ClassCastException.
                    MachineFilePaths.insertRecentFilePath(Machine.FileType.NVRAM, diskValue);
                    notifyFieldChange(MachineProperty.NVRAM_PATH, diskValue);
                    seMachineDriveValue(fileType, diskValue);
                    break;
                default: {
                    StorageEntry entry = null;
                    for (StorageEntry e : storageEntries) {
                        if (e.fileType == fileType) {
                            entry = e;
                            break;
                        }
                    }
                    if (entry != null) {
                        if (!entry.ui.getImageOptions().contains(diskValue)) {
                            List<String> opts = new ArrayList<>(entry.ui.getImageOptions());
                            opts.add(diskValue);
                            entry.ui.setImageOptions(opts);
                        }
                        notifyAction(MachineAction.INSERT_FAV, new Object[]{diskValue, fileType});
                        // persist the newly opened file into the machine (and DB),
                        // otherwise the VM would start with the old/empty path
                        notifyDriveChanged(entry, diskValue);
                        seMachineDriveValue(fileType, diskValue);
                    }
                    break;
                }
            }
        }
    }

    @Override
    public void onStop() {
        super.onStop();
    }

    @Override
    public void onDestroy() {
        MachineController.getInstance().removeOnStatusChangeListener(this);
        Machine machine = getMachine();
        if (machine != null) {
            machine.deleteObserver(this);
        }
        viewListener = null;
        super.onDestroy();
    }

    private void checkFirstLaunch() {
        Thread t = new Thread(() -> {
            if (LimboSettingsManager.isFirstLaunch(LimboActivity.this)) {
                onFirstLaunch();
            }
        });
        t.start();
    }

    private void checkLog() {
        Thread t = new Thread(() -> {
            if (LimboSettingsManager.getExitCode(LimboActivity.this) != Config.EXIT_SUCCESS) {
                if (MachineController.getInstance().isRunning())
                    LimboSettingsManager.setExitCode(LimboActivity.this, Config.EXIT_UNKNOWN);
                else
                    LimboSettingsManager.setExitCode(LimboActivity.this, Config.EXIT_SUCCESS);
                runOnUiThread(() -> Logger.promptShowLog(LimboActivity.this));
            }
        });
        t.start();
    }

    public void onFirstLaunch() {
        promptLicense();
    }

    private void createMachine(String machineName) {
        notifyAction(MachineAction.CREATE_VM, machineName);
    }

    private void machineCreated() {
        runOnUiThread(() -> {
            populateMachines(getMachine() != null ? getMachine().getName() : null);
            refreshStorageDevices();
            enableNonRemovableDeviceOptions(true);
            enableRemovableDeviceOptions(true);
            setArchOptions();
        });
    }

    private void onDeleteMachine() {
        if (getMachine() == null) {
            ToastUtils.toastShort(this, getString(R.string.SelectAMachineFirst));
            return;
        }
        Thread t = new Thread(() -> {
            String name = getMachine().getName();
            notifyAction(MachineAction.DELETE_VM, getMachine());
            runOnUiThread(() -> {
                uiState.setMachineSel(0);
                notifyAction(MachineAction.LOAD_VM, null);
                populateAttributesUI();
                ToastUtils.toastShort(this, getString(R.string.MachineDeleted) + ": " + name);
            });
        });
        t.start();
    }

    public void importMachines(String importFilePath) {
        uiState.setMachineSel(0);
        notifyAction(MachineAction.IMPORT_VMS, importFilePath);
    }

    private void promptLicense() {
        runOnUiThread(() -> {
            try {
                LimboActivityCommon.promptLicense(LimboActivity.this,
                        Config.APP_NAME + " " + LimboApplication.getLimboVersionString()
                                + " " + "QEMU" + " " + LimboApplication.getQemuVersionString(),
                        FileUtils.LoadFile(LimboActivity.this, "LICENSE", false));
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }

    public void exit() {
        if (MachineController.getInstance().isRunning())
            onStopButton(true);
        else
            System.exit(0);
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
    }

    // ============================================================
    // Menu actions
    // ============================================================

    public void handleMenuAction(int itemId) {
        switch (itemId) {
            case INSTALL:
                Installer.installFiles(this, true);
                break;
            case DELETE:
                promptDeleteMachine();
                break;
            case DISCARD_VM_STATE:
                promptDiscardVMState();
                break;
            case CREATE:
                promptMachineName(this);
                break;
            case SETTINGS:
                showSettings();
                break;
            case EXPORT:
                MachineExporter.promptExport(this);
                break;
            case IMPORT:
                MachineImporter.promptImportMachines(this);
                break;
            case IMPORT_BIOS_FILE:
                BIOSImporter.promptImportBIOSFile(this);
                break;
            case VIEWLOG:
                Logger.viewLimboLog(this);
                break;
            case CHANGELOG:
                LimboActivityCommon.showChangelog(this);
                break;
            case LICENSE:
                promptLicense();
                break;
            case QUIT:
                exit();
                break;
        }
    }

    public int getMenuItemId(@NonNull String label) {
        if (label.equals(getString(R.string.InstallRoms))) return INSTALL;
        else if (label.equals(getString(R.string.CreateMachine))) return CREATE;
        else if (label.equals(getString(R.string.DeleteMachine))) return DELETE;
        else if (label.equals(getString(R.string.DiscardSavedState))) return DISCARD_VM_STATE;
        else if (label.equals(getString(R.string.ExportMachines))) return EXPORT;
        else if (label.equals(getString(R.string.ImportMachines))) return IMPORT;
        else if (label.equals(getString(R.string.ImportBIOSFile))) return IMPORT_BIOS_FILE;
        else if (label.equals(getString(R.string.Settings))) return SETTINGS;
        else if (label.equals(getString(R.string.ViewLog))) return VIEWLOG;
        else if (label.equals(getString(R.string.Changelog))) return CHANGELOG;
        else if (label.equals(getString(R.string.License))) return LICENSE;
        else if (label.equals(getString(R.string.Exit))) return QUIT;
        else return -1;
    }

    /** Shows the app menu as a Material dialog (replaces the old toolbar overflow menu). */
    private void showMenuDialog() {
        ArrayList<String> items = new ArrayList<>();
        items.add(getString(R.string.InstallRoms));
        if (!MachineController.getInstance().isRunning()) {
            items.add(getString(R.string.CreateMachine));
            items.add(getString(R.string.DeleteMachine));
            if (getMachine() != null && getMachine().getPaused() == 1)
                items.add(getString(R.string.DiscardSavedState));
            items.add(getString(R.string.ExportMachines));
            items.add(getString(R.string.ImportMachines));
        }
        items.add(getString(R.string.ImportBIOSFile));
        items.add(getString(R.string.Settings));
        items.add(getString(R.string.ViewLog));
        items.add(getString(R.string.Changelog));
        items.add(getString(R.string.License));
        items.add(getString(R.string.Exit));

        ArrayList<String> labels = new ArrayList<>(new LinkedHashSet<>(items));
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(getString(R.string.app_name));
        builder.setItems(labels.toArray(new String[0]), (dialog, which) -> {
            String label = labels.get(which);
            int id = getMenuItemId(label);
            if (id >= 0)
                handleMenuAction(id);
        });
        builder.show();
    }

    private void showSettings() {
        Intent i = new Intent(this, LimboSettingsManager.class);
        startActivity(i);
    }

    public void promptDeleteMachine() {
        if (getMachine() == null) {
            ToastUtils.toastShort(this, getString(R.string.NoMachineSelected));
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.DeleteVM) + ": " + getMachine().getName())
                .setMessage(R.string.deleteVMWarning)
                .setPositiveButton(getString(android.R.string.yes), (dialog, which) -> onDeleteMachine())
                .setNegativeButton(getString(android.R.string.no), (dialog, which) -> {
                })
                .show();
    }

    public void promptDiscardVMState() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.discardVMState)
                .setMessage(R.string.discardVMInstructions)
                .setPositiveButton(android.R.string.yes, (dialog, which) -> {
                    notifyFieldChange(MachineProperty.PAUSED, 0);
                    changeStatus(MachineStatus.Ready);
                    enableNonRemovableDeviceOptions(true);
                    enableRemovableDeviceOptions(true);
                })
                .setNegativeButton(getString(android.R.string.no), (dialog, which) -> {
                })
                .show();
    }

    @Override
    public void onPause() {
        super.onPause();
    }

    @Override
    public void onResume() {
        super.onResume();
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            updateValues();
            if (libLoaded)
                notifyAction(MachineAction.IGNORE_BREAKPOINT_INVALIDATION,
                        LimboSettingsManager.getIgnoreBreakpointInvalidation(LimboActivity.this));
        }, 1000);
    }

    private void updateValues() {
        Thread t = new Thread(() -> runOnUiThread(() -> {
            changeStatus(MachineController.getInstance().getCurrStatus());
            updateRemovableDiskValues();
            updateSummary();
        }));
        t.start();
    }

    private void updateRemovableDiskValues() {
        if (getMachine() != null) {
            for (StorageEntry entry : storageEntries) {
                if (entry.removable) {
                    String value = getMachineDriveValue(entry.fileType);
                    seMachineDriveValue(entry.fileType, value);
                }
            }
        }
    }

    public boolean isLandscapeOrientation(Activity activity) {
        Display display = activity.getWindowManager().getDefaultDisplay();
        Point screenSize = new Point();
        display.getSize(screenSize);
        return screenSize.x >= screenSize.y;
    }

    @Override
    public void onMachineStatusChanged(Machine machine, MachineStatus status, Object o) {
        switch (status) {
            case SaveFailed:
                LimboActivityCommon.promptPausedErrorVM(this, (String) o, viewListener);
                break;
            case SaveCompleted:
                LimboActivityCommon.promptPausedVM(this, viewListener);
                break;
            default:
                changeStatus(status);
                break;
        }
    }

    @Override
    public void onEvent(Machine machine, MachineController.Event event, Object o) {
        switch (event) {
            case MachineCreateFailed:
                if (o instanceof Integer) {
                    ToastUtils.toastShort(this, getString((Integer) o));
                } else if (o instanceof String) {
                    ToastUtils.toastShort(this, (String) o);
                }
                break;
            case MachineCreated:
                machineCreated();
                break;
            case MachineLoaded:
                loadMachine();
                break;
            case MachineContinued:
                new Handler(Looper.getMainLooper()).postDelayed(() ->
                        changeStatus(MachineController.getInstance().getCurrStatus()), 1000);
                break;
            case MachinesImported:
                if (o instanceof ArrayList) {
                    @SuppressWarnings("unchecked")
                    ArrayList<Machine> machines = (ArrayList<Machine>) o;
                    onMachinesImported(machines);
                }
                break;
            default:
                break;
        }
        runOnUiThread(this::updateSummary);
    }

    private void onMachinesImported(ArrayList<Machine> machines) {
        populateAttributesUI();
        LimboActivityCommon.promptMachinesImported(this, machines);
    }

    @Override
    public void update(Observable observable, Object o) {
        runOnUiThread(() -> {
            if (!(o instanceof Object[]))
                return;
            Object[] params = (Object[]) o;
            if (params[0] instanceof MachineProperty) {
                MachineProperty property = (MachineProperty) params[0];
                if (property == MachineProperty.UI) {
                    uiState.setSoundEnabled(true);
                }
            }
            updateSummary();
        });
    }

    public void notifyFieldChange(MachineProperty property, Object value) {
        if (viewListener != null)
            viewListener.onFieldChange(property, value);
    }

    public void notifyAction(MachineAction action, Object value) {
        if (viewListener != null)
            viewListener.onAction(action, value);
    }

    public void setViewListener(ViewListener viewListener) {
        this.viewListener = viewListener;
    }

    private void checkAllowedPermission() {
        XXPermissions.with(this)
                .permission(PermissionLists.getManageExternalStoragePermission())
                .permission(PermissionLists.getPostNotificationsPermission())
                .request((permissions, deniedList) -> {
                    boolean allGranted = deniedList.isEmpty();
                    if (!allGranted) {
                        boolean doNotAskAgain = XXPermissions.isDoNotAskAgainPermissions(this, deniedList);
                        if (doNotAskAgain) {
                            return;
                        }
                    }
                });
    }

    private void setupAppEnvironment() {
        LimboApplication.setupEnv(this);
    }

    private void setupController() {
        viewListener = LimboApplication.getViewListener();
    }

    private void setupListeners() {
        MachineController.getInstance().addOnStatusChangeListener(this);
        MachineController.getInstance().addOnEventListener(this);
    }

    private void restore() {
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (MachineController.getInstance().isRunning()) {
                restoreUI(MachineController.getInstance().getMachineName());
            }
        }, 1000);
    }

    private void restoreUI(String machine) {
        if (machine != null) {
            int pos = uiState.getMachines().indexOf(machine);
            uiState.setMachineSel(Math.max(pos, 0));
        }
    }

    // ============================================================
    // Compose callbacks (implemented via LimboUiCallbacks)
    // ============================================================

    @Override
    public void onMachineSelected(int index) {
        if (index == 0) {
            uiState.setMachineSel(0);
            enableNonRemovableDeviceOptions(false);
            enableRemovableDeviceOptions(false);
            if (!MachineController.getInstance().isRunning())
                notifyAction(MachineAction.LOAD_VM, null);
        } else if (index == 1) {
            uiState.setMachineSel(0);
            promptMachineName(this);
        } else {
            if (index >= uiState.getMachines().size())
                return;
            String machine = uiState.getMachines().get(index);
            machineLoaded = true;
            uiState.setMachineSel(index);
            notifyAction(MachineAction.LOAD_VM, machine);
        }
    }

    @Override
    public void onStartVm() {
        if (!Config.loadNativeLibsEarly && Config.loadNativeLibsMainThread) {
            setupNativeLibs();
        }
        Thread t = new Thread(() -> {
            if (!Config.loadNativeLibsEarly && !Config.loadNativeLibsMainThread) {
                setupNativeLibs();
            }
            onStartButton();
        });
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    @Override
    public void onPauseVm() {
        onPauseButton();
    }

    @Override
    public void onStopVm() {
        onStopButton(false);
    }

    @Override
    public void onRestartVm() {
        onRestartButton();
    }

    @Override
    public void onAddStorageDevice() {
        openStorageEditor(-1);
    }

    @Override
    public void onStorageDeviceClicked(int deviceTag) {
        openStorageEditor(deviceTag);
    }

    /**
     * Opens the single-device editor ([StorageDeviceEditorActivity]).
     * @param deviceTag the row being edited, or -1 to add a new device.
     */
    private void openStorageEditor(int deviceTag) {
        if (MachineController.getInstance().isRunning()) {
            ToastUtils.toastShort(this, getString(R.string.VMRunning));
            return;
        }
        if (getMachine() == null || uiState.getMachineSel() < 2) {
            ToastUtils.toastShort(this, getString(R.string.SelectOrCreateVirtualMachineFirst));
            return;
        }
        boolean edit = deviceTag >= 0;
        Intent intent = new Intent(this, StorageDeviceEditorActivity.class);
        intent.putExtra(StorageDeviceEditorActivity.EXTRA_MODE,
                edit ? StorageDeviceEditorActivity.MODE_EDIT : StorageDeviceEditorActivity.MODE_NEW);
        intent.putExtra(StorageDeviceEditorActivity.EXTRA_EDIT_TAG, deviceTag);

        DeviceType[] types = getAvailableDeviceTypes();
        String[] labels = new String[types.length];
        String[] fileTypes = new String[types.length];
        boolean[] createImage = new boolean[types.length];
        boolean[] canIf = new boolean[types.length];
        for (int i = 0; i < types.length; i++) {
            labels[i] = getString(types[i].labelRes);
            // HARD_DISK's static fileType is null (it maps to HDA..HDD at runtime),
            // so fall back to HDA to enable browsing/creating disk images in the editor.
            String ftName = "";
            if (types[i].fileType != null) {
                ftName = types[i].fileType.name();
            } else if (types[i] == DeviceType.HARD_DISK) {
                ftName = FileType.HDA.name();
            }
            fileTypes[i] = ftName;
            createImage[i] = types[i].createImage;
            // only hard disks and CD-ROMs expose -drive if=/format=
            canIf[i] = types[i] == DeviceType.HARD_DISK || types[i] == DeviceType.CDROM;
        }
        intent.putExtra(StorageDeviceEditorActivity.EXTRA_TYPE_LABELS, labels);
        intent.putExtra(StorageDeviceEditorActivity.EXTRA_TYPE_FILE_TYPES, fileTypes);
        intent.putExtra(StorageDeviceEditorActivity.EXTRA_TYPE_CREATE_IMAGE, createImage);
        intent.putExtra(StorageDeviceEditorActivity.EXTRA_TYPE_CAN_IF, canIf);

        // -drive if=/format= selector options (empty value = keep the default)
        String[] ifValues = {"", "ide", "scsi", "virtio", "sata", "nvme"};
        String[] ifLabels = {getString(R.string.label_default), getString(R.string.storage_if_ide),
                "SCSI", getString(R.string.storage_if_virtio), "SATA", "NVMe"};
        String[] formatValues = {"", "raw", "qcow2", "qcow", "vmdk", "vhdx", "vdi", "vpc", "parallels"};
        String[] formatLabels = {getString(R.string.label_auto), "RAW", "QCOW2", "QCOW", "VMDK",
                "VHDX", "VDI", "VPC", getString(R.string.storage_fmt_parallels)};
        intent.putExtra(StorageDeviceEditorActivity.EXTRA_IF_LABELS, ifLabels);
        intent.putExtra(StorageDeviceEditorActivity.EXTRA_IF_VALUES, ifValues);
        intent.putExtra(StorageDeviceEditorActivity.EXTRA_FORMAT_LABELS, formatLabels);
        intent.putExtra(StorageDeviceEditorActivity.EXTRA_FORMAT_VALUES, formatValues);

        if (edit && deviceTag < storageEntries.size()) {
            StorageEntry e = storageEntries.get(deviceTag);
            intent.putExtra(StorageDeviceEditorActivity.EXTRA_INIT_TYPE_SEL, getTypePosition(e.deviceType));
            intent.putExtra(StorageDeviceEditorActivity.EXTRA_INIT_SIZE,
                    e.ui.getSizeValue() != null ? e.ui.getSizeValue() : "4");
            intent.putExtra(StorageDeviceEditorActivity.EXTRA_INIT_SIZE_UNIT_SEL, e.ui.getSizeUnitSel());
            intent.putExtra(StorageDeviceEditorActivity.EXTRA_INIT_IMAGE, getMachineDriveValue(e.fileType));
            intent.putExtra(StorageDeviceEditorActivity.EXTRA_INIT_IF_SEL,
                    indexOfValue(ifValues, driveInterfaceValue(e)));
            intent.putExtra(StorageDeviceEditorActivity.EXTRA_INIT_FORMAT_SEL,
                    indexOfValue(formatValues, driveFormatValue(e)));
        } else {
            intent.putExtra(StorageDeviceEditorActivity.EXTRA_INIT_TYPE_SEL, 0);
            intent.putExtra(StorageDeviceEditorActivity.EXTRA_INIT_SIZE, "4");
            intent.putExtra(StorageDeviceEditorActivity.EXTRA_INIT_SIZE_UNIT_SEL, 0);
            intent.putExtra(StorageDeviceEditorActivity.EXTRA_INIT_IMAGE, "");
            intent.putExtra(StorageDeviceEditorActivity.EXTRA_INIT_IF_SEL, 0);
            intent.putExtra(StorageDeviceEditorActivity.EXTRA_INIT_FORMAT_SEL, 0);
        }
        pendingStorageEditTag = edit ? deviceTag : -1;
        startActivityForResult(intent, StorageDeviceEditorActivity.REQUEST_CODE);
    }

    private void applyStorageDeviceEditorResult(Intent data, int editTag) {
        if (data == null || getMachine() == null)
            return;
        if (data.getBooleanExtra(StorageDeviceEditorActivity.EXTRA_REMOVE, false)) {
            if (editTag >= 0 && editTag < storageEntries.size())
                removeStorageDeviceRow(storageEntries.get(editTag));
            updateSummary();
            return;
        }

        int typeSel = data.getIntExtra(StorageDeviceEditorActivity.EXTRA_TYPE_SEL, 0);
        DeviceType[] types = getAvailableDeviceTypes();
        if (typeSel < 0 || typeSel >= types.length)
            return;
        DeviceType devType = types[typeSel];

        String image = data.getStringExtra(StorageDeviceEditorActivity.EXTRA_IMAGE);
        if (image == null || image.isEmpty())
            image = "None";

        // file type used for browsing/creating disk images (HARD_DISK resolves to HDA here)
        String ftName = data.getStringExtra(StorageDeviceEditorActivity.EXTRA_FILE_TYPE);
        FileType targetFtype = (ftName != null && !ftName.isEmpty())
                ? Machine.FileType.valueOf(ftName) : null;

        // create a brand new disk image when requested
        String newImageName = data.getStringExtra(StorageDeviceEditorActivity.EXTRA_NEW_IMAGE_NAME);
        if (newImageName != null && !newImageName.isEmpty() && targetFtype != null) {
            long bytes = data.getLongExtra(StorageDeviceEditorActivity.EXTRA_NEW_IMAGE_SIZE_BYTES, -1L);
            if (LimboSettingsManager.getImagesDir(this) == null) {
                ToastUtils.toastLong(this, getString(R.string.chooseDirToCreateImage));
                changeImagesDir();
                return;
            }
            String created = createImageFile(newImageName, bytes, targetFtype);
            if (created == null)
                return;
            image = created;
        }

        if (editTag >= 0 && editTag < storageEntries.size()) {
            StorageEntry entry = storageEntries.get(editTag);
            // apply type change to the target device type
            if (entry.deviceType != devType) {
                if (!applyDeviceType(entry, devType)) {
                    return; // type rejected (e.g. max count reached); leave entry untouched
                }
            }
            entry.ui.setSizeValue(data.getStringExtra(StorageDeviceEditorActivity.EXTRA_SIZE) != null
                    ? data.getStringExtra(StorageDeviceEditorActivity.EXTRA_SIZE) : entry.ui.getSizeValue());
            entry.ui.setSizeUnitSel(data.getIntExtra(StorageDeviceEditorActivity.EXTRA_SIZE_UNIT_SEL,
                    entry.ui.getSizeUnitSel()));
            applyDriveSettings(entry, data);
            applyImageToEntry(entry, image);
        } else {
            // adding a new device row, then set its image/size
            int before = storageEntries.size();
            addStorageDeviceRow(devType);
            if (storageEntries.size() == before)
                return; // not added (type at max count)
            StorageEntry entry = storageEntries.get(storageEntries.size() - 1);
            entry.ui.setSizeValue(data.getStringExtra(StorageDeviceEditorActivity.EXTRA_SIZE) != null
                    ? data.getStringExtra(StorageDeviceEditorActivity.EXTRA_SIZE) : entry.ui.getSizeValue());
            entry.ui.setSizeUnitSel(data.getIntExtra(StorageDeviceEditorActivity.EXTRA_SIZE_UNIT_SEL,
                    entry.ui.getSizeUnitSel()));
            applyDriveSettings(entry, data);
            applyImageToEntry(entry, image);
        }
        updateSummary();
    }

    /** Returns the safe search index of {@code v} in {@code values}, defaulting to 0. */
    private static int indexOfValue(String[] values, String v) {
        if (values == null)
            return 0;
        if (v != null) {
            for (int i = 0; i < values.length; i++) {
                if (v.equals(values[i]))
                    return i;
            }
        }
        return 0;
    }

    /** Current interface (-drive if=) of an entry's drive, or null when it has none. */
    private String driveInterfaceValue(StorageEntry e) {
        if (getMachine() == null)
            return null;
        if (e.deviceType == DeviceType.HARD_DISK) {
            switch (e.hardDiskSlot) {
                case 0: return getMachine().getHdaInterface();
                case 1: return getMachine().getHdbInterface();
                case 2: return getMachine().getHdcInterface();
                case 3: return getMachine().getHddInterface();
            }
        } else if (e.deviceType == DeviceType.CDROM) {
            return getMachine().getCDInterface();
        }
        return null;
    }

    /** Current image format (-drive format=) of an entry's drive, or null. */
    private String driveFormatValue(StorageEntry e) {
        if (getMachine() == null)
            return null;
        if (e.deviceType == DeviceType.HARD_DISK) {
            switch (e.hardDiskSlot) {
                case 0: return getMachine().getHdaFormat();
                case 1: return getMachine().getHdbFormat();
                case 2: return getMachine().getHdcFormat();
                case 3: return getMachine().getHddFormat();
            }
        } else if (e.deviceType == DeviceType.CDROM) {
            return getMachine().getCDFormat();
        }
        return null;
    }

    /** The MachineProperty identifying the drive, used for -drive if=. Null when unsupported. */
    private MachineProperty driveInterfaceProp(StorageEntry e) {
        if (e.deviceType == DeviceType.HARD_DISK) {
            switch (e.hardDiskSlot) {
                case 0: return MachineProperty.HDA;
                case 1: return MachineProperty.HDB;
                case 2: return MachineProperty.HDC;
                case 3: return MachineProperty.HDD;
            }
        } else if (e.deviceType == DeviceType.CDROM) {
            return MachineProperty.CDROM;
        }
        return null;
    }

    /** The MachineProperty identifying the drive, used for -drive format=. Null when unsupported. */
    private MachineProperty driveFormatProp(StorageEntry e) {
        if (e.deviceType == DeviceType.HARD_DISK) {
            switch (e.hardDiskSlot) {
                case 0: return MachineProperty.HDA_FORMAT;
                case 1: return MachineProperty.HDB_FORMAT;
                case 2: return MachineProperty.HDC_FORMAT;
                case 3: return MachineProperty.HDD_FORMAT;
            }
        } else if (e.deviceType == DeviceType.CDROM) {
            return MachineProperty.CDROM_FORMAT;
        }
        return null;
    }

    /**
     * Persists the -drive if=/format= chosen in the editor onto the machine.
     * An empty value means "keep default" and clears the per-drive setting.
     */
    private void applyDriveSettings(StorageEntry e, Intent data) {
        if (e.deviceType != DeviceType.HARD_DISK && e.deviceType != DeviceType.CDROM)
            return;
        MachineProperty ifProp = driveInterfaceProp(e);
        if (ifProp != null) {
            String ifValue = data.getStringExtra(StorageDeviceEditorActivity.EXTRA_IF);
            notifyFieldChange(MachineProperty.MEDIA_INTERFACE,
                    new Object[]{ifProp, (ifValue == null || ifValue.trim().isEmpty() ? null : ifValue)});
        }
        MachineProperty fmtProp = driveFormatProp(e);
        if (fmtProp != null) {
            String fmtValue = data.getStringExtra(StorageDeviceEditorActivity.EXTRA_FORMAT);
            notifyFieldChange(fmtProp, fmtValue == null || fmtValue.trim().isEmpty() ? null : fmtValue);
        }
    }

    /** Applies an already-resolved type change to an existing entry. Returns false if rejected. */
    private boolean applyDeviceType(StorageEntry entry, DeviceType type) {
        if (type == entry.deviceType)
            return true;
        if (countDeviceType(type) >= type.maxCount) {
            ToastUtils.toastShort(this, getString(R.string.device_already_exists));
            return false;
        }
        if (type == DeviceType.HARD_DISK && getFreeHardDiskSlot() < 0) {
            ToastUtils.toastShort(this, getString(R.string.device_already_exists));
            return false;
        }
        // release old drive
        clearDrive(entry);
        diskMapping.remove(entry.fileType);
        entry.deviceType = type;
        entry.removable = type.removable;
        entry.createImage = type.createImage;
        entry.sharedFolder = type.sharedFolder;
        entry.hardDiskSlot = -1;
        if (type == DeviceType.HARD_DISK) {
            assignHardDiskSlot(entry);
        } else {
            entry.property = type.property;
            entry.fileType = type.fileType;
        }
        if (entry.fileType != null) {
            diskMapping.put(entry.fileType, new DiskInfo(null, entry.property));
        }
        if (entry.removable && entry.property != null) {
            notifyFieldChange(MachineProperty.DRIVE_ENABLED, new Object[]{entry.property, true});
        }
        entry.ui.setTypeSel(getTypePosition(type));
        updateStorageDeviceSizeVisibility(entry);
        reassignHardDiskSlots();
        populateStorageDeviceImageAdapter(entry, null);
        return true;
    }

    /** Unconditionally sets the drive image value (path or "None") on an entry. */
    private void applyImageToEntry(StorageEntry entry, String image) {
        if ("None".equals(image)) {
            clearDrive(entry);
            seMachineDriveValue(entry.fileType, null);
            return;
        }
        List<String> opts = new ArrayList<>(entry.ui.getImageOptions());
        if (!opts.contains(image)) {
            opts.add(image);
        }
        entry.ui.setImageOptions(opts);
        entry.ui.setImageSel(opts.indexOf(image));
        notifyDriveChanged(entry, image);
        seMachineDriveValue(entry.fileType, image);
        if (entry.fileType != null) {
            MachineFilePaths.insertRecentFilePath(entry.fileType, image);
        }
    }

    /** Creates a disk image file from a name + size, reusing the template/raw logic. */
    private String createImageFile(String imageName, long bytes, FileType fileType) {
        String image = imageName;
        String templateImage = getTemplateForSize(bytes < 0 ? -1 : bytes);
        String filePath;
        if (templateImage != null) {
            if (!image.endsWith(".qcow2")) {
                image += ".qcow2";
            }
            filePath = FileUtils.createImgFromTemplate(this, templateImage, image, fileType);
        } else {
            if (!image.endsWith(".img") && !image.endsWith(".raw")) {
                image += ".img";
            }
            filePath = FileUtils.createRawImage(this, bytes, image, fileType);
        }
        return filePath;
    }

    @Override
    public void onOpenMenu() {
        showMenuDialog();
    }

    @Override
    public void onUiSelected(int index) {
        if (index < 0 || index >= uiState.getUiOptions().size())
            return;
        // keep the dropdown responsive even before a machine is loaded
        uiState.setUiSel(index);
        if (getMachine() == null)
            return;
        String ui = uiState.getUiOptions().get(index);
        notifyFieldChange(MachineProperty.UI, ui);
    }

    @Override
    public void onKeyboardSelected(int index) {
        if (getMachine() == null)
            return;
        if (index < 0 || index >= uiState.getKeyboardOptions().size())
            return;
        uiState.setKeyboardSel(index);
        notifyFieldChange(MachineProperty.KEYBOARD, uiState.getKeyboardOptions().get(index));
    }

    @Override
    public void onMouseSelected(int index) {
        if (getMachine() == null)
            return;
        if (index < 0 || index >= uiState.getMouseOptions().size())
            return;
        uiState.setMouseSel(index);
        notifyFieldChange(MachineProperty.MOUSE, uiState.getMouseOptions().get(index));
    }

    @Override
    public void onMachineTypeSelected(int index) {
        if (getMachine() == null)
            return;
        if (index < 0 || index >= uiState.getMachineTypeOptions().size())
            return;
        uiState.setMachineTypeSel(index);
        notifyFieldChange(MachineProperty.MACHINETYPE, uiState.getMachineTypeOptions().get(index));
    }

    @Override
    public void onCpuSelected(int index) {
        if (getMachine() == null)
            return;
        if (index < 0 || index >= uiState.getCpuOptions().size())
            return;
        uiState.setCpuSel(index);
        notifyFieldChange(MachineProperty.CPU, uiState.getCpuOptions().get(index));
    }

    @Override
    public void onCpuNumChanged(@NonNull String value) {
        // keep local state responsive; validate digits only
        String cleaned = value.replaceAll("[^0-9]", "");
        if (cleaned.length() > 3) cleaned = cleaned.substring(0, 3);
        uiState.setCpuNumValue(cleaned);

        if (getMachine() == null)
            return;
        int cpuNum = parseIntSafe(cleaned, 1);
        if (cpuNum < 1)
            return;
        if (cpuNum > 1 && getMachine().getEnableMTTCG() != 1 && getMachine().getEnableKVM() != 1 && !firstMTTCGCheck) {
            firstMTTCGCheck = true;
            promptMultiCPU(cleaned);
        }
        uiState.setDisableTSC(cpuNum > 1 && (LimboApplication.arch == Config.Arch.x86
                || LimboApplication.arch == Config.Arch.x86_64));
        debounceHandler.removeCallbacks(cpuNumCommit);
        debounceHandler.postDelayed(cpuNumCommit, DEBOUNCE_MS);
    }

    @Override
    public void onRamChanged(@NonNull String value) {
        // keep local state responsive; validate digits only
        String cleaned = value.replaceAll("[^0-9]", "");
        if (cleaned.length() > 6) cleaned = cleaned.substring(0, 6);
        uiState.setRamValue(cleaned);

        if (getMachine() == null)
            return;
        int ram = parseIntSafe(cleaned, 512);
        if (ram < 1)
            return;
        debounceHandler.removeCallbacks(ramCommit);
        debounceHandler.postDelayed(ramCommit, DEBOUNCE_MS);
    }

    @Override
    public void onDisableI8042Changed(boolean checked) {
        if (getMachine() == null)
            return;
        uiState.setDisableI8042(checked);
        notifyFieldChange(MachineProperty.DISABLE_I8042, checked);
    }

    @Override
    public void onEnableNvramChanged(boolean checked) {
        if (getMachine() == null)
            return;
        uiState.setEnableNvram(checked);
        notifyFieldChange(MachineProperty.ENABLE_NVRAM, checked);
    }

    @Override
    public void onNvramSelected(int index) {
        if (getMachine() == null)
            return;
        List<String> options = uiState.getNvramOptions();
        if (index < 0 || index >= options.size())
            return;
        if (index == 0) {
            // Default: clear the path so VMExecutor falls back to the
            // app-managed nvram file.
            uiState.setNvramSel(0);
            notifyFieldChange(MachineProperty.NVRAM_PATH, null);
        } else if (index == 1) {
            // New: create a fresh nvram file and select it.
            String path = createNewNvramFile();
            if (path == null)
                return;
            List<String> opts = new ArrayList<>(uiState.getNvramOptions());
            if (!opts.contains(path)) {
                opts.add(path);
                uiState.setNvramOptions(opts);
            }
            uiState.setNvramSel(Math.max(opts.indexOf(path), 0));
            MachineFilePaths.insertRecentFilePath(Machine.FileType.NVRAM, path);
            notifyFieldChange(MachineProperty.NVRAM_PATH, path);
        } else if (index == 2) {
            // Open: launch the file picker.
            browseFileType = Machine.FileType.NVRAM;
            LimboFileManager.browse(this, browseFileType, Config.OPEN_IMAGE_FILE_REQUEST_CODE);
            uiState.setNvramSel(0);
        } else {
            // Recent nvram path.
            uiState.setNvramSel(index);
            notifyFieldChange(MachineProperty.NVRAM_PATH, options.get(index));
        }
    }

    private String createNewNvramFile() {
        String path = LimboApplication.getNvramFile();
        if (path == null)
            return null;
        try {
            File nvramFile = new File(path);
            if (!nvramFile.exists()) {
                nvramFile.createNewFile();
            }
            return nvramFile.getAbsolutePath();
        } catch (IOException e) {
            Log.e(TAG, "Could not create NVRAM file: " + e.getMessage());
            return null;
        }
    }

    @Override
    public void onEnableMTTCGChanged(boolean checked) {
        if (getMachine() == null)
            return;
        if (checked) {
            promptEnableMTTCG();
        } else {
            notifyFieldChange(MachineProperty.ENABLE_MTTCG, checked);
        }
    }

    @Override
    public void onEnableKVMChanged(boolean checked) {
        if (getMachine() == null)
            return;
        if (checked) {
            promptKVM();
        } else {
            notifyFieldChange(MachineProperty.ENABLE_KVM, checked);
        }
    }

    @Override
    public void onDisableHPETChanged(boolean checked) {
        if (getMachine() == null)
            return;
        notifyFieldChange(MachineProperty.DISABLE_HPET, checked);
    }

    @Override
    public void onDisableTSCChanged(boolean checked) {
        if (getMachine() == null)
            return;
        notifyFieldChange(MachineProperty.DISABLE_TSC, checked);
    }

    @Override
    public void onDisableACPIChanged(boolean checked) {
        if (getMachine() == null)
            return;
        notifyFieldChange(MachineProperty.DISABLE_ACPI, checked);
    }

    @Override
    public void onBootSelected(int index) {
        if (getMachine() == null)
            return;
        if (index < 0 || index >= uiState.getBootOptions().size())
            return;
        uiState.setBootSel(index);
        notifyFieldChange(MachineProperty.BOOT_CONFIG, uiState.getBootOptions().get(index));
    }

    @Override
    public void onBiosSelected(int index) {
        if (getMachine() == null)
            return;
        if (index < 0 || index >= uiState.getBiosOptions().size())
            return;
        uiState.setBiosSel(index);
        String bios = uiState.getBiosOptions().get(index);
        // "None" clears the selection so QEMU falls back to its default firmware
        notifyFieldChange(MachineProperty.BIOS, index == 0 ? null : bios);
    }

    @Override
    public void onKernelSelected(int index) {
        if (getMachine() == null)
            return;
        if (index == 0) {
            uiState.setKernelSel(0);
            notifyFieldChange(MachineProperty.KERNEL, null);
        } else if (index == 1) {
            browseFileType = FileType.KERNEL;
            LimboFileManager.browse(this, browseFileType, Config.OPEN_IMAGE_FILE_REQUEST_CODE);
            uiState.setKernelSel(0);
        } else if (index > 1) {
            if (index >= uiState.getKernelOptions().size())
                return;
            uiState.setKernelSel(index);
            notifyFieldChange(MachineProperty.KERNEL, uiState.getKernelOptions().get(index));
        }
    }

    @Override
    public void onInitrdSelected(int index) {
        if (getMachine() == null)
            return;
        if (index == 0) {
            uiState.setInitrdSel(0);
            notifyFieldChange(MachineProperty.INITRD, uiState.getInitrdOptions().get(0));
        } else if (index == 1) {
            browseFileType = FileType.INITRD;
            LimboFileManager.browse(this, browseFileType, Config.OPEN_IMAGE_FILE_REQUEST_CODE);
            uiState.setInitrdSel(0);
        } else if (index > 1) {
            if (index >= uiState.getInitrdOptions().size())
                return;
            uiState.setInitrdSel(index);
            notifyFieldChange(MachineProperty.INITRD, uiState.getInitrdOptions().get(index));
        }
    }

    @Override
    public void onAppendChanged(@NonNull String value) {
        uiState.setAppend(value);
        debounceHandler.removeCallbacks(appendCommit);
        debounceHandler.postDelayed(appendCommit, DEBOUNCE_MS);
    }

    @Override
    public void onVgaSelected(int index) {
        if (getMachine() == null)
            return;
        if (index < 0 || index >= uiState.getVgaOptions().size())
            return;
        uiState.setVgaSel(index);
        notifyFieldChange(MachineProperty.VGA, uiState.getVgaOptions().get(index));
    }

    @Override
    public void onSoundSelected(int index) {
        if (getMachine() == null)
            return;
        if (index < 0 || index >= uiState.getSoundOptions().size())
            return;
        uiState.setSoundSel(index);
        notifyFieldChange(MachineProperty.SOUNDCARD, uiState.getSoundOptions().get(index));
    }

    @Override
    public void onNetSelected(int index) {
        if (getMachine() == null)
            return;
        if (index < 0 || index >= uiState.getNetOptions().size())
            return;
        String netcfg = uiState.getNetOptions().get(index);
        uiState.setNetSel(index);
        notifyFieldChange(MachineProperty.NETCONFIG, netcfg);
        if (index > 0 && getMachine().getPaused() == 0
                && MachineController.getInstance().getCurrStatus() != MachineStatus.Running) {
            uiState.setNicEnabled(true);
            uiState.setDnsEnabled(true);
            uiState.setHostFwdEnabled(true);
        } else {
            uiState.setNicEnabled(false);
            uiState.setDnsEnabled(false);
            uiState.setHostFwdEnabled(false);
        }

        if (netcfg.equals("TAP")) {
            onTap();
        } else if (netcfg.equals("User")) {
            LimboActivityCommon.onNetworkUser(this);
        }
    }

    @Override
    public void onNicSelected(int index) {
        if (getMachine() == null)
            return;
        if (index < 0 || index >= uiState.getNicOptions().size()) {
            uiState.setNicSel(0);
            return;
        }
        uiState.setNicSel(index);
        notifyFieldChange(MachineProperty.NICCONFIG, uiState.getNicOptions().get(index));
    }

    @Override
    public void onDnsChanged(@NonNull String value) {
        // keep UI responsive; commit to dispatcher/db after typing pauses
        uiState.setDns(value);
        debounceHandler.removeCallbacks(dnsCommit);
        debounceHandler.postDelayed(dnsCommit, DEBOUNCE_MS);
    }

    @Override
    public void onHostFwdChanged(@NonNull String value) {
        uiState.setHostFwd(value);
        debounceHandler.removeCallbacks(hostFwdCommit);
        debounceHandler.postDelayed(hostFwdCommit, DEBOUNCE_MS);
    }

    @Override
    public void onExtraParamsChanged(@NonNull String value) {
        uiState.setExtraParams(value);
        debounceHandler.removeCallbacks(extraParamsCommit);
        debounceHandler.postDelayed(extraParamsCommit, DEBOUNCE_MS);
    }

    // ============================================================
    // Internal helper classes
    // ============================================================

    static class DiskInfo {
        @SuppressWarnings("unused")
        Spinner spinner;
        MachineProperty colName;

        DiskInfo(Spinner spinner, MachineProperty colName) {
            this.spinner = spinner;
            this.colName = colName;
        }
    }

    class StorageEntry {
        StorageDeviceUiState ui = new StorageDeviceUiState();
        DeviceType deviceType;
        MachineProperty property;
        FileType fileType;
        boolean removable;
        boolean createImage;
        boolean sharedFolder;
        int hardDiskSlot = -1;
    }

    enum DeviceType {
        HARD_DISK(null, null, false, true, false, R.string.type_hard_disk, 4),
        CDROM(MachineProperty.CDROM, FileType.CDROM, true, false, false, R.string.type_cdrom, 1),
        FDA(MachineProperty.FDA, FileType.FDA, true, false, false, R.string.type_floppy_a, 1),
        FDB(MachineProperty.FDB, FileType.FDB, true, false, false, R.string.type_floppy_b, 1),
        SD(MachineProperty.SD, FileType.SD, true, false, false, R.string.type_sd_card, 1),
        SHARED_DIR(MachineProperty.SHARED_FOLDER, FileType.SHARED_DIR, false, false, true, R.string.type_shared_folder, 1);

        final MachineProperty property;
        final FileType fileType;
        final boolean removable;
        final boolean createImage;
        final boolean sharedFolder;
        final int labelRes;
        final int maxCount;

        DeviceType(MachineProperty property, FileType fileType, boolean removable,
                   boolean createImage, boolean sharedFolder, int labelRes, int maxCount) {
            this.property = property;
            this.fileType = fileType;
            this.removable = removable;
            this.createImage = createImage;
            this.sharedFolder = sharedFolder;
            this.labelRes = labelRes;
            this.maxCount = maxCount;
        }
    }
}
