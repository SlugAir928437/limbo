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
import android.app.AlertDialog;
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
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemSelectedListener;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.EditText;
import com.google.android.material.button.MaterialButton;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.widget.NestedScrollView;

import com.limbo.emu.lib.R;
import com.max2idea.android.limbo.dialog.DialogUtils;
import com.max2idea.android.limbo.files.FileInstaller;
import com.max2idea.android.limbo.files.FileUtils;
import com.max2idea.android.limbo.help.Help;
import com.max2idea.android.limbo.install.Installer;
import com.max2idea.android.limbo.keyboard.KeyboardUtils;
import com.max2idea.android.limbo.links.LinksManager;
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
import com.max2idea.android.limbo.ui.SpinnerAdapter;
import com.max2idea.android.limbo.updates.UpdateChecker;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.Observable;
import java.util.Observer;

public class LimboActivity extends AppCompatActivity
        implements MachineController.OnMachineStatusChangeListener,
        MachineController.OnEventListener, Observer {

    private static final String TAG = "LimboActivity";

    private static final int HELP = 0;
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
    private static final int TOOLS = 14;
    private static final int IMPORT_BIOS_FILE = 15;

    // disk mapping
    private static final Hashtable<FileType, DiskInfo> diskMapping = new Hashtable<>();

    private static boolean libLoaded;
    public View parent;
    private boolean machineLoaded;
    private FileType browseFileType = null;

    //Widgets
    private ImageView mStatus;
    private EditText mDNS;
    private EditText mHOSTFWD;
    private EditText mAppend;
    private EditText mExtraParams;
    private TextView mStatusText;
    private Spinner mMachine;
    private Spinner mCPU;
    private Spinner mMachineType;
    private Spinner mCPUNum;
    private Spinner mKernel;
    private Spinner mInitrd;

    // Storage devices (dynamic rows)
    private LinearLayout mStorageDevicesContainer;
    private MaterialButton mAddStorageDeviceBtn;
    private final List<StorageDeviceEntry> mStorageDeviceEntries = new ArrayList<>();

    // misc
    private Spinner mRamSize;
    private Spinner mBootDevices;
    private Spinner mNetworkCard;
    private Spinner mNetConfig;
    private Spinner mVGAConfig;
    private Spinner mSoundCard;
    private Spinner mUI;
    private CheckBox mDisableACPI;
    private CheckBox mDisableHPET;
    private CheckBox mDisableTSC;
    private CheckBox mEnableKVM;
    private CheckBox mEnableMTTCG;
    private Spinner mKeyboard;
    private Spinner mMouse;

    // buttons
    private MaterialButton mStart;
    private MaterialButton mPause;
    private MaterialButton mStop;
    private MaterialButton mRestart;

    //sections
    private LinearLayout mCPUSectionDetails;
    private LinearLayout mStorageDevicesSectionDetails;
    private LinearLayout mUserInterfaceSectionDetails;
    private LinearLayout mAdvancedSectionDetails;
    private LinearLayout mBootSectionDetails;
    private LinearLayout mGraphicsSectionDetails;
    private LinearLayout mNetworkSectionDetails;
    private LinearLayout mAudioSectionDetails;

    //summary
    private TextView mUISectionSummary;
    private TextView mCPUSectionSummary;
    private TextView mStorageDevicesSectionSummary;
    private TextView mGraphicsSectionSummary;
    private TextView mAudioSectionSummary;
    private TextView mNetworkSectionSummary;
    private TextView mBootSectionSummary;
    private TextView mAdvancedSectionSummary;

    //layouts
    private NestedScrollView mScrollView;
    private boolean firstMTTCGCheck;
    private ViewListener viewListener;

    public void changeStatus(final MachineStatus status_changed) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (MachineController.getInstance().isRunning() || status_changed == MachineStatus.Running) {
                    mStatus.setImageResource(R.drawable.on);
                    if (mUI.getSelectedItemPosition() == 0) {
                        // VNC
                        mStatusText.setText(R.string.Running);
                        //XXX: we block the user from changing the drives
                        // from this activitybecause sdl is suspended and the thread will block
                        // so they have to change it from within the SDL Activity
                        enableRemovableDiskValues(true);
                    } else {
                        // SDL is always suspend in the background
                        mStatusText.setText(R.string.Suspended);
                        enableRemovableDiskValues(false);
                    }
                    unlockRemovableDevices(false);
                    enableNonRemovableDeviceOptions(false);
                    mMachine.setEnabled(false);
                } else if (status_changed == MachineStatus.Ready || status_changed == MachineStatus.Stopped) {
                    mStatus.setImageResource(R.drawable.off);
                    mStatusText.setText(R.string.Stopped);
                    unlockRemovableDevices(true);
                    enableRemovableDiskValues(true);
                    enableNonRemovableDeviceOptions(true);
                } else if (status_changed == MachineStatus.Saving) {
                    mStatus.setImageResource(R.drawable.on);
                    mStatusText.setText(R.string.savingState);
                    unlockRemovableDevices(false);
                    enableRemovableDiskValues(false);
                    enableNonRemovableDeviceOptions(false);
                } else if (status_changed == MachineStatus.Paused) {
                    mStatus.setImageResource(R.drawable.on);
                    mStatusText.setText(R.string.paused);
                    unlockRemovableDevices(false);
                    enableRemovableDiskValues(false);
                    enableNonRemovableDeviceOptions(false);
                }
            }
        });
    }

    private void onTap() {
        String userid = LimboApplication.getUserId(this);
        if (!(new File("/dev/net/tun")).exists()) {
            LimboActivityCommon.tapNotSupported(this, userid);
            return;
        }
        LimboActivityCommon.promptTap(this, userid);
    }

    public void setUserPressed(boolean pressed) {
        if (pressed) {
            setupMiscOptions();
            setupStorageDeviceListeners();
        } else {
            disableListeners();
            disableStorageDeviceListeners();
        }
    }

    private void disableStorageDeviceListeners() {
        for (StorageDeviceEntry entry : mStorageDeviceEntries) {
            entry.typeSpinner.setOnItemSelectedListener(null);
            entry.sizeUnitSpinner.setOnItemSelectedListener(null);
            entry.imageSpinner.setOnItemSelectedListener(null);
        }
    }

    private void setupStorageDeviceListeners() {
        for (StorageDeviceEntry entry : mStorageDeviceEntries) {
            setupStorageDeviceRowListeners(entry);
        }
    }

    private void setupStorageDeviceRowListeners(final StorageDeviceEntry entry) {
        entry.typeSpinner.setOnItemSelectedListener(new OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parentView, View selectedItemView, int position, long id) {
                if (getMachine() == null)
                    return;
                DeviceType[] types = getAvailableDeviceTypes();
                if (position < 0 || position >= types.length)
                    return;
                DeviceType type = types[position];
                if (type == entry.deviceType)
                    return;
                // check if the selected type already reached max count
                if (countDeviceType(type) >= type.maxCount) {
                    ToastUtils.toastShort(LimboActivity.this, getString(R.string.device_already_exists));
                    entry.typeSpinner.setSelection(getTypePosition(entry.deviceType));
                    return;
                }
                if (type == DeviceType.HARD_DISK && getFreeHardDiskSlot() < 0) {
                    ToastUtils.toastShort(LimboActivity.this, getString(R.string.device_already_exists));
                    entry.typeSpinner.setSelection(getTypePosition(entry.deviceType));
                    return;
                }
                // release old drive
                clearDrive(entry);
                // update disk mapping
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
                diskMapping.put(entry.fileType, new DiskInfo(entry.imageSpinner, null, entry.property));
                if (entry.removable) {
                    notifyFieldChange(MachineProperty.DRIVE_ENABLED, new Object[]{entry.property, true});
                }
                updateStorageDeviceSizeVisibility(entry);
                reassignHardDiskSlots();
                populateStorageDeviceImageAdapter(entry, new Runnable() {
                    @Override
                    public void run() {
                        setupStorageDeviceImageListener(entry);
                    }
                });
            }

            public void onNothingSelected(AdapterView<?> parentView) {
            }
        });

        setupStorageDeviceImageListener(entry);
    }

    private void setupStorageDeviceImageListener(final StorageDeviceEntry entry) {
        entry.imageSpinner.setOnItemSelectedListener(new OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parentView, View selectedItemView, int position, long id) {
                if (getMachine() == null)
                    return;
                String value = (String) ((ArrayAdapter<?>) entry.imageSpinner.getAdapter()).getItem(position);
                if (entry.createImage && position == 1) {
                    // New -> create image with custom size
                    long sizeBytes = getSelectedSizeBytes(entry);
                    promptImageName(LimboActivity.this, entry.fileType, sizeBytes);
                    entry.imageSpinner.setSelection(0);
                } else if (position == (entry.createImage ? 2 : 1)) {
                    // Open...
                    browseFileType = entry.fileType;
                    if (entry.sharedFolder) {
                        LimboFileManager.browse(LimboActivity.this, browseFileType, Config.OPEN_SHARED_DIR_REQUEST_CODE);
                    } else {
                        LimboFileManager.browse(LimboActivity.this, browseFileType, Config.OPEN_IMAGE_FILE_REQUEST_CODE);
                    }
                    entry.imageSpinner.setSelection(0);
                } else if (position == 0) {
                    // None
                    if (entry.removable) {
                        notifyFieldChange(MachineProperty.REMOVABLE_DRIVE, new Object[]{entry.property, value});
                    } else if (entry.property == MachineProperty.SHARED_FOLDER) {
                        notifyFieldChange(MachineProperty.NON_REMOVABLE_DRIVE,
                                new Object[]{entry.property, value});
                    } else {
                        notifyFieldChange(MachineProperty.NON_REMOVABLE_DRIVE, new Object[]{entry.property, value});
                    }
                } else {
                    // recent files
                    if (entry.removable) {
                        notifyFieldChange(MachineProperty.REMOVABLE_DRIVE, new Object[]{entry.property, value});
                    } else if (entry.property == MachineProperty.SHARED_FOLDER) {
                        notifyFieldChange(MachineProperty.NON_REMOVABLE_DRIVE,
                                new Object[]{entry.property, value});
                    } else {
                        notifyFieldChange(MachineProperty.NON_REMOVABLE_DRIVE, new Object[]{entry.property, value});
                    }
                }
            }

            public void onNothingSelected(AdapterView<?> parentView) {
            }
        });
    }

    private long getSelectedSizeBytes(StorageDeviceEntry entry) {
        long value = 1;
        try {
            value = Long.parseLong(entry.sizeEditText.getText().toString().trim());
        } catch (NumberFormatException e) {
            value = 1;
        }
        if (value < 1)
            value = 1;
        String unit = (String) entry.sizeUnitSpinner.getSelectedItem();
        long multiplier = 1024L * 1024L * 1024L; // default GB
        if (unit != null) {
            if (unit.equals(getString(R.string.size_unit_mb)))
                multiplier = 1024L * 1024L;
            else if (unit.equals(getString(R.string.size_unit_tb)))
                multiplier = 1024L * 1024L * 1024L * 1024L;
        }
        return value * multiplier;
    }


    private void setupMiscOptions() {

        mCPU.setOnItemSelectedListener(new OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parentView, View selectedItemView, int position, long id) {
                if (getMachine() == null)
                    return;
                String cpu = (String) ((ArrayAdapter<?>) mCPU.getAdapter()).getItem(position);
                notifyFieldChange(MachineProperty.CPU, cpu);
            }

            public void onNothingSelected(AdapterView<?> parentView) {
            }
        });

        mMachineType.setOnItemSelectedListener(new OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parentView, View selectedItemView, int position, long id) {
                if (getMachine() == null)
                    return;
                String machineType = (String) ((ArrayAdapter<?>) mMachineType.getAdapter()).getItem(position);
                notifyFieldChange(MachineProperty.MACHINETYPE, machineType);
            }

            public void onNothingSelected(AdapterView<?> parentView) {
            }
        });

        mUI.setOnItemSelectedListener(new OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parentView, View selectedItemView, int position, long id) {
                if (getMachine() == null)
                    return;
                String ui = (String) ((ArrayAdapter<?>) mUI.getAdapter()).getItem(position);
                notifyFieldChange(MachineProperty.UI, ui);
            }

            public void onNothingSelected(AdapterView<?> parentView) {
            }
        });

        mCPUNum.setOnItemSelectedListener(new OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parentView, View selectedItemView, int position, long id) {
                if (getMachine() == null)
                    return;
                final String cpuNum = (String) ((ArrayAdapter<?>) mCPUNum.getAdapter()).getItem(position);
                if (position > 0 && getMachine().getEnableMTTCG() != 1 && getMachine().getEnableKVM() != 1 && !firstMTTCGCheck) {
                    firstMTTCGCheck = true;
                    promptMultiCPU(cpuNum);
                } else {
                    notifyFieldChange(MachineProperty.CPUNUM, cpuNum);
                }
                mDisableTSC.setChecked(position > 0 && (LimboApplication.arch == Config.Arch.x86 ||
                        LimboApplication.arch == Config.Arch.x86_64));
            }

            public void onNothingSelected(AdapterView<?> parentView) {
            }
        });

        mRamSize.setOnItemSelectedListener(new OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parentView, View selectedItemView, int position, long id) {
                if (getMachine() == null)
                    return;
                String ram = (String) ((ArrayAdapter<?>) mRamSize.getAdapter()).getItem(position);
                notifyFieldChange(MachineProperty.MEMORY, ram);
            }

            public void onNothingSelected(AdapterView<?> parentView) {

            }
        });

        mKernel.setOnItemSelectedListener(new OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parentView, View selectedItemView, int position, long id) {
                if (getMachine() == null)
                    return;
                String kernel = (String) ((ArrayAdapter<?>) mKernel.getAdapter()).getItem(position);
                if (position == 0) {
                    notifyFieldChange(MachineProperty.KERNEL, null);
                } else if (position == 1) {
                    browseFileType = FileType.KERNEL;
                    LimboFileManager.browse(LimboActivity.this, browseFileType, Config.OPEN_IMAGE_FILE_REQUEST_CODE);
                    mKernel.setSelection(0);
                } else if (position > 1) {
                    notifyFieldChange(MachineProperty.KERNEL, kernel);
                }
            }

            public void onNothingSelected(AdapterView<?> parentView) {
            }
        });

        mInitrd.setOnItemSelectedListener(new OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parentView, View selectedItemView, int position, long id) {
                if (getMachine() == null)
                    return;
                String initrd = (String) ((ArrayAdapter<?>) mInitrd.getAdapter()).getItem(position);
                if (position == 0) {
                    notifyFieldChange(MachineProperty.INITRD, initrd);
                } else if (position == 1) {
                    browseFileType = FileType.INITRD;
                    LimboFileManager.browse(LimboActivity.this, browseFileType, Config.OPEN_IMAGE_FILE_REQUEST_CODE);
                    mInitrd.setSelection(0);
                } else if (position > 1) {
                    notifyFieldChange(MachineProperty.INITRD, initrd);
                }
            }

            public void onNothingSelected(AdapterView<?> parentView) {
            }
        });

        mBootDevices.setOnItemSelectedListener(new OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parentView, View selectedItemView, int position, long id) {
                if (getMachine() == null)
                    return;

                String bootDev = (String) ((ArrayAdapter<?>) mBootDevices.getAdapter()).getItem(position);
                notifyFieldChange(MachineProperty.BOOT_CONFIG, bootDev);
            }

            public void onNothingSelected(AdapterView<?> parentView) {
            }
        });

        mNetConfig.setOnItemSelectedListener(new OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parentView, View selectedItemView, int position, long id) {
                if (getMachine() == null)
                    return;

                String netcfg = (String) ((ArrayAdapter<?>) mNetConfig.getAdapter()).getItem(position);
                notifyFieldChange(MachineProperty.NETCONFIG, netcfg);
                if (position > 0 && getMachine().getPaused() == 0
                        && MachineController.getInstance().getCurrStatus() != MachineStatus.Running) {
                    mNetworkCard.setEnabled(true);
                    mDNS.setEnabled(true);
                    mHOSTFWD.setEnabled(true);
                } else {
                    mNetworkCard.setEnabled(false);
                    mDNS.setEnabled(false);
                    mHOSTFWD.setEnabled(false);
                }

                if (netcfg.equals("TAP")) {
                    onTap();
                } else if (netcfg.equals("User")) {
                    LimboActivityCommon.onNetworkUser(LimboActivity.this);
                }
            }

            public void onNothingSelected(AdapterView<?> parentView) {
            }
        });

        mNetworkCard.setOnItemSelectedListener(new OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parentView, View selectedItemView, int position, long id) {
                if (getMachine() == null)
                    return;
                if (position < 0 || position >= mNetworkCard.getCount()) {
                    mNetworkCard.setSelection(0);
                    return;
                }
                String niccfg = (String) ((ArrayAdapter<?>) mNetworkCard.getAdapter()).getItem(position);
                notifyFieldChange(MachineProperty.NICCONFIG, niccfg);
            }

            public void onNothingSelected(final AdapterView<?> parentView) {
            }
        });

        mVGAConfig.setOnItemSelectedListener(new OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parentView, View selectedItemView, int position, long id) {
                if (getMachine() == null)
                    return;
                String vgacfg = (String) ((ArrayAdapter<?>) mVGAConfig.getAdapter()).getItem(position);
                notifyFieldChange(MachineProperty.VGA, vgacfg);
            }

            public void onNothingSelected(AdapterView<?> parentView) {
            }
        });

        mSoundCard.setOnItemSelectedListener(new OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parentView, View selectedItemView, int position, long id) {
                if (getMachine() == null)
                    return;
                String sndcfg = (String) ((ArrayAdapter<?>) mSoundCard.getAdapter()).getItem(position);
                notifyFieldChange(MachineProperty.SOUNDCARD, sndcfg);
            }

            public void onNothingSelected(AdapterView<?> parentView) {
            }
        });

        mDisableACPI.setOnCheckedChangeListener(new OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton viewButton, boolean isChecked) {
                if (getMachine() == null)
                    return;
                notifyFieldChange(MachineProperty.DISABLE_ACPI, isChecked);
            }
        });

        mDisableHPET.setOnCheckedChangeListener(new OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton viewButton, boolean isChecked) {
                if (getMachine() == null)
                    return;
                notifyFieldChange(MachineProperty.DISABLE_HPET, isChecked);
            }
        });

        mDisableTSC.setOnCheckedChangeListener(new OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton viewButton, boolean isChecked) {
                if (getMachine() == null)
                    return;
                notifyFieldChange(MachineProperty.DISABLE_TSC, isChecked);
            }
        });

        mDNS.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View view, boolean hasFocus) {
                if (getMachine() == null)
                    return;
                if (!hasFocus) {
                    setDNSServer(mDNS.getText().toString());
                    LimboSettingsManager.setDNSServer(LimboActivity.this, mDNS.getText().toString());
                }
            }
        });

        mHOSTFWD.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View view, boolean hasFocus) {
                if (getMachine() == null)
                    return;
                if (!hasFocus) {
                    notifyFieldChange(MachineProperty.HOSTFWD, mHOSTFWD.getText().toString());
                }
            }
        });

        mAppend.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View view, boolean hasFocus) {
                if (getMachine() == null)
                    return;
                if (!hasFocus) {
                    notifyFieldChange(MachineProperty.APPEND, mAppend.getText().toString());
                }
            }
        });

        mExtraParams.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View view, boolean hasFocus) {
                if (getMachine() == null)
                    return;
                if (!hasFocus) {
                    notifyFieldChange(MachineProperty.EXTRA_PARAMS, mExtraParams.getText().toString());
                }
            }
        });

        OnClickListener resetClickListener = new OnClickListener() {
            @Override
            public void onClick(View view) {
                view.setFocusableInTouchMode(true);
                view.setFocusable(true);
            }
        };

        mDNS.setOnClickListener(resetClickListener);
        mAppend.setOnClickListener(resetClickListener);
        mHOSTFWD.setOnClickListener(resetClickListener);
        mExtraParams.setOnClickListener(resetClickListener);
        mEnableKVM.setOnCheckedChangeListener(new OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton viewButton, boolean isChecked) {
                if (getMachine() == null)
                    return;
                if (isChecked) {
                    promptKVM();
                } else {
                    notifyFieldChange(MachineProperty.ENABLE_KVM, isChecked);
                }

            }

        });

        mEnableMTTCG.setOnCheckedChangeListener(new OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton viewButton, boolean isChecked) {
                if (getMachine() == null)
                    return;
                if (isChecked) {
                    promptEnableMTTCG();
                } else {
                    notifyFieldChange(MachineProperty.ENABLE_MTTCG, isChecked);
                }
            }
        });

        mKeyboard.setOnItemSelectedListener(new OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parentView, View selectedItemView, int position, long id) {
                if (getMachine() == null)
                    return;
                String keyboardCfg = (String) ((ArrayAdapter<?>) mKeyboard.getAdapter()).getItem(position);
                notifyFieldChange(MachineProperty.KEYBOARD, keyboardCfg);
            }

            public void onNothingSelected(AdapterView<?> parentView) {
            }
        });

        mMouse.setOnItemSelectedListener(new OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parentView, View selectedItemView, int position, long id) {
                String mouseCfg = (String) ((ArrayAdapter<?>) mMouse.getAdapter()).getItem(position);
                notifyFieldChange(MachineProperty.MOUSE, mouseCfg);
            }

            public void onNothingSelected(AdapterView<?> parentView) {
            }
        });
    }

    private void setCPUOptions() {
        if (MachineController.getInstance().getCurrStatus() != MachineStatus.Running &&
                (LimboApplication.arch == Config.Arch.x86 || LimboApplication.arch == Config.Arch.x86_64)) {
            mDisableACPI.setEnabled(true);
            mDisableHPET.setEnabled(true);
            mDisableTSC.setEnabled(true);
        } else {
            mDisableACPI.setEnabled(false);
            mDisableHPET.setEnabled(false);
            mDisableTSC.setEnabled(false);
        }
    }

    private void setArchOptions() {
        if (!machineLoaded) {
            populateMachineType(getMachine().getMachineType());
            populateCPUs(getMachine().getCpu());
            populateNetDevices(getMachine().getNetworkCard());
        }
    }

    private void promptKVM() {
        DialogInterface.OnClickListener okListener = new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                notifyFieldChange(MachineProperty.ENABLE_KVM, true);
                mEnableMTTCG.setChecked(false);
            }
        };

        DialogInterface.OnClickListener cancelListener =
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        mEnableKVM.setChecked(false);
                        notifyFieldChange(MachineProperty.ENABLE_KVM, false);
                    }
                };

        DialogInterface.OnClickListener helpListener =
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        mEnableKVM.setChecked(false);
                        notifyFieldChange(MachineProperty.ENABLE_KVM, false);
                        LimboActivityCommon.goToURL(LimboActivity.this, Config.kvmLink);
                    }
                };

        DialogUtils.UIAlert(LimboActivity.this, getString(R.string.EnableKVM),
                getString(R.string.EnableKVMWarning),
                16, false, getString(android.R.string.ok),
                okListener, getString(android.R.string.cancel),
                cancelListener, getString(R.string.KVMHelp), helpListener);
    }

    private void promptEnableMTTCG() {
        DialogInterface.OnClickListener okListener = new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                notifyFieldChange(MachineProperty.ENABLE_MTTCG, true);
                mEnableKVM.setChecked(false);
            }
        };
        DialogInterface.OnClickListener cancelListener =
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        notifyFieldChange(MachineProperty.ENABLE_MTTCG, false);
                        mEnableMTTCG.setChecked(false);
                    }
                };
        DialogInterface.OnClickListener helpListener =
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        mEnableMTTCG.setChecked(false);
                        notifyFieldChange(MachineProperty.ENABLE_MTTCG, false);
                        LimboActivityCommon.goToURL(LimboActivity.this, Config.faqLink);
                    }
                };
        DialogUtils.UIAlert(LimboActivity.this, getString(R.string.enableMTTCG),
                getString(R.string.enableMTTCGWarning),
                16, false, getString(android.R.string.ok), okListener,
                getString(android.R.string.cancel)
                , cancelListener, getString(R.string.mttcgHelp), helpListener);
    }

    private void promptMultiCPU(final String cpuNum) {
        DialogInterface.OnClickListener okListener = new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                notifyFieldChange(MachineProperty.CPUNUM, cpuNum);
            }
        };
        DialogInterface.OnClickListener cancelListener =
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        mCPUNum.setSelection(0);
                    }
                };
        DialogInterface.OnClickListener helpListener =
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        mCPUNum.setSelection(0);
                        LimboActivityCommon.goToURL(LimboActivity.this, Config.faqLink);
                    }
                };
        DialogUtils.UIAlert(LimboActivity.this, getString(R.string.multipleVCPU),
                getString(R.string.multipleVCPUWarning)
                        + ((LimboApplication.arch == Config.Arch.x86_64) ?
                        getString(R.string.disableTSCInstructions) : "")
                        + " " + getString(R.string.DoYouWantToContinue),
                16, false, getString(android.R.string.ok), okListener,
                getString(android.R.string.cancel), cancelListener, getString(R.string.vCPUHelp), helpListener);
    }

    private void promptDriveInterface(final MachineProperty machineDriveName) {
        if(getMachine() == null)
            return;

        final String[] items = {
                "ide",
                "scsi",
                "virtio"
        };
        final AlertDialog.Builder mBuilder = new AlertDialog.Builder(this);
        mBuilder.setTitle(machineDriveName + " " + getString(R.string.Interface));
        int driveInterface = getMachineInterface(machineDriveName, items);
        mBuilder.setSingleChoiceItems(items, driveInterface, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int i) {
                notifyFieldChange(MachineProperty.MEDIA_INTERFACE, new Object[] {machineDriveName, items[i]});
                dialog.dismiss();
            }
        });
        final AlertDialog alertDialog = mBuilder.create();
        alertDialog.show();
    }

    private int getMachineInterface(MachineProperty machineDriveName, String[] items) {
        String hdInterfaceStr = null;
        switch(machineDriveName) {
            case HDA:
                hdInterfaceStr = getMachine().getHdaInterface();
                break;
            case HDB:
                hdInterfaceStr = getMachine().getHdbInterface();
                break;
            case HDC:
                hdInterfaceStr = getMachine().getHdcInterface();
                break;
            case HDD:
                hdInterfaceStr = getMachine().getHddInterface();
                break;
            case CDROM:
                hdInterfaceStr = getMachine().getCDInterface();
                break;
        }
        for(int i=0; i<items.length; i++) {
            if(items[i].equals(hdInterfaceStr))
                return i;
        }
        return 0;
    }

    protected synchronized void setDNSServer(String string) {

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
            if (fileStream != null)
                try {
                    fileStream.close();
                } catch (IOException e) {

                    e.printStackTrace();
                }
        }
    }

    private void disableListeners() {
        if (mMachine == null)
            return;
        mUI.setOnItemSelectedListener(null);
        mKeyboard.setOnItemSelectedListener(null);
        mMouse.setOnItemSelectedListener(null);
        mMachineType.setOnItemSelectedListener(null);
        mCPU.setOnItemSelectedListener(null);
        mCPUNum.setOnItemSelectedListener(null);
        mRamSize.setOnItemSelectedListener(null);
        mDisableACPI.setOnCheckedChangeListener(null);
        mDisableHPET.setOnCheckedChangeListener(null);
        mDisableTSC.setOnCheckedChangeListener(null);
        mEnableKVM.setOnCheckedChangeListener(null);
        mEnableMTTCG.setOnCheckedChangeListener(null);
        disableStorageDeviceListeners();
        mBootDevices.setOnItemSelectedListener(null);
        mKernel.setOnItemSelectedListener(null);
        mInitrd.setOnItemSelectedListener(null);
        mAppend.setOnFocusChangeListener(null);
        mVGAConfig.setOnItemSelectedListener(null);
        mSoundCard.setOnItemSelectedListener(null);
        mNetConfig.setOnItemSelectedListener(null);
        mNetworkCard.setOnItemSelectedListener(null);
        mDNS.setOnFocusChangeListener(null);
        mHOSTFWD.setOnFocusChangeListener(null);
        mExtraParams.setOnFocusChangeListener(null);
    }

    /**
     * Called when the activity is first created.
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupAppEnvironment();
        clearNotifications();
        setupStrictMode();
        setContentView(R.layout.limbo_main);
        setupWidgets();
        setupController();
        setupDiskMapping();
        createListeners();
        populateAttributesUI();
        checkFirstLaunch();
        setupToolbar();
        checkUpdate();
        checkLog();
        checkAndLoadLibs();
        restore();
        setupListeners();
        addGenericOperatingSystems();
    }

    private void setupAppEnvironment() {
        LimboApplication.setupEnv(this);
    }

    private void setupController() {
        setViewListener(LimboApplication.getViewListener());
    }

    public void setViewListener(ViewListener viewListener) {
        this.viewListener = viewListener;
    }

    private void setupListeners() {
        MachineController.getInstance().addOnStatusChangeListener(this);
        MachineController.getInstance().addOnEventListener(this);
    }

    private void restoreUI(final String machine) {
        int position = SpinnerAdapter.getItemPosition(mMachine, machine);
        mMachine.setSelection(position);
    }

    private void restore() {
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                if (MachineController.getInstance().isRunning()) {
                    restoreUI(MachineController.getInstance().getMachineName());
                }
            }
        }, 1000);
    }

    private void checkAndLoadLibs() {
        if (Config.loadNativeLibsEarly)
            if (Config.loadNativeLibsMainThread)
                setupNativeLibs();
            else
                setupNativeLibsAsync();
    }

    private void clearNotifications() {
        NotificationManager notificationManager = (NotificationManager) getApplicationContext().getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.cancelAll();
    }

    private void setupDiskMapping() {
        diskMapping.clear();
        // Storage devices are mapped dynamically in addStorageDeviceRow
        addDiskMapping(FileType.KERNEL, mKernel, null, MachineProperty.KERNEL);
        addDiskMapping(FileType.INITRD, mInitrd, null, MachineProperty.INITRD);
    }

    private void addDiskMapping(FileType fileType, Spinner spinner,
                                CheckBox enableCheckBox, MachineProperty dbColName) {
        spinner.setTag(fileType);

        diskMapping.put(fileType, new DiskInfo(spinner, enableCheckBox, dbColName));
    }

    private void setupNativeLibsAsync() {

        Thread thread = new Thread(new Runnable() {
            public void run() {
                setupNativeLibs();
            }
        });
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();

    }

    private void createListeners() {

        mMachine.setOnItemSelectedListener(new OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parentView, View selectedItemView, int position, long id) {

                if (position == 0) {
                    enableNonRemovableDeviceOptions(false);
                    enableRemovableDeviceOptions(false);
                    if (!MachineController.getInstance().isRunning())
                        notifyAction(MachineAction.LOAD_VM, null);
                } else if (position == 1) {
                    mMachine.setSelection(0);
                    promptMachineName(LimboActivity.this);
                } else {
                    final String machine = (String) ((ArrayAdapter<?>) mMachine.getAdapter()).getItem(position);
                    setUserPressed(false);
                    machineLoaded = true;
                    notifyAction(MachineAction.LOAD_VM, machine);
                }
            }

            public void onNothingSelected(AdapterView<?> parentView) {
            }
        });

        mScrollView.setOnScrollChangeListener(new NestedScrollView.OnScrollChangeListener() {
            @Override
            public void onScrollChange(NestedScrollView v, int scrollX, int scrollY, int oldScrollX, int oldScrollY) {
                savePendingEditText();
            }
        });

        mStart.setOnClickListener(new OnClickListener() {
            public void onClick(View view) {
                if (!Config.loadNativeLibsEarly && Config.loadNativeLibsMainThread) {
                    setupNativeLibs();
                }
                Thread thread = new Thread(new Runnable() {
                    public void run() {
                        if (!Config.loadNativeLibsEarly && !Config.loadNativeLibsMainThread) {
                            setupNativeLibs();
                        }
                        onStartButton();
                    }
                });
                thread.setPriority(Thread.MIN_PRIORITY);
                thread.start();
            }
        });
        mPause.setOnClickListener(new OnClickListener() {
            public void onClick(View view) {
                onPauseButton();
            }
        });
        mStop.setOnClickListener(new OnClickListener() {
            public void onClick(View view) {
                onStopButton(false);
            }
        });
        mRestart.setOnClickListener(new OnClickListener() {
            public void onClick(View view) {
                onRestartButton();
            }
        });
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

    private void savePendingEditText() {
        View currentView = getCurrentFocus();
        if (currentView instanceof EditText) {
            currentView.setFocusable(false);
        }
    }

    private void checkFirstLaunch() {
        Thread t = new Thread(new Runnable() {
            public void run() {
                if (LimboSettingsManager.isFirstLaunch(LimboActivity.this)) {
                    onFirstLaunch();
                }
            }
        });
        t.start();
    }

    private void checkLog() {
        Thread t = new Thread(new Runnable() {
            public void run() {
                if (LimboSettingsManager.getExitCode(LimboActivity.this) != Config.EXIT_SUCCESS) {
                    if (MachineController.getInstance().isRunning())
                        LimboSettingsManager.setExitCode(LimboActivity.this, Config.EXIT_UNKNOWN);
                    else
                        LimboSettingsManager.setExitCode(LimboActivity.this, Config.EXIT_SUCCESS);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Logger.promptShowLog(LimboActivity.this);
                        }
                    });
                }
            }
        });
        t.start();
    }

    //XXX: this needs to be called from the main thread otherwise
    //  qemu crashes when it is started later
    public void setupNativeLibs() {
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

    public void setupToolbar() {
        Toolbar tb = findViewById(R.id.toolbar);
        setSupportActionBar(tb);

        final ActionBar ab = getSupportActionBar();
        if (ab != null) {
            ab.setHomeAsUpIndicator(R.drawable.limbo);
            ab.setDisplayShowHomeEnabled(true);
            ab.setDisplayHomeAsUpEnabled(true);
            ab.setDisplayShowCustomEnabled(true);
            ab.setDisplayShowTitleEnabled(true);
            ab.setTitle(R.string.app_name);
        }
    }

    public void checkUpdate() {
        Thread tsdl = new Thread(new Runnable() {
            public void run() {
                UpdateChecker.checkNewVersion(LimboActivity.this);
            }
        });
        tsdl.start();
    }

    private void setupStrictMode() {
        if (Config.debugStrictMode) {
            StrictMode.setThreadPolicy(
                    new StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites().detectNetwork()
                            .penaltyLog().build());
            StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder().detectLeakedSqlLiteObjects()
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

        //disks (dynamic storage device rows are populated by refreshStorageDevices)
        //boot
        populateDiskAdapter(mKernel, FileType.KERNEL, false);
        populateDiskAdapter(mInitrd, FileType.INITRD, false);

    }

    public void onFirstLaunch() {
        promptLicense();
    }

    private void createMachine(String machineName) {
        notifyAction(MachineAction.CREATE_VM, machineName);
    }

    private void machineCreated() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                showOperatingSystems();
                populateMachines(getMachine().getName());
                refreshStorageDevices();
                enableNonRemovableDeviceOptions(true);
                enableRemovableDeviceOptions(true);
                setArchOptions();
            }
        });
    }

    protected void showOperatingSystems() {
        if (!Config.osImages.isEmpty()) {
            LinksManager manager = new LinksManager(this);
            manager.show();
        }
    }

    private void onDeleteMachine() {
        if (getMachine() == null) {
            ToastUtils.toastShort(this, getString(R.string.SelectAMachineFirst));
            return;
        }
        Thread t = new Thread(new Runnable() {
            public void run() {
                final String name = getMachine().getName();
                notifyAction(MachineAction.DELETE_VM, getMachine());
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        disableListeners();
                        disableStorageDeviceListeners();
                        mMachine.setSelection(0);
                        notifyAction(MachineAction.LOAD_VM, null);
                        populateAttributesUI();
                        ToastUtils.toastShort(LimboActivity.this, getString(R.string.MachineDeleted) + ": " + name);
                        setupMiscOptions();
                        setupStorageDeviceListeners();
                    }
                });
            }
        });
        t.start();

    }

    public void importMachines(String importFilePath) {
        disableListeners();
        disableStorageDeviceListeners();
        mMachine.setSelection(0);
        notifyAction(MachineAction.IMPORT_VMS, importFilePath);
    }


    private void promptLicense() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    LimboActivityCommon.promptLicense(LimboActivity.this,
                            Config.APP_NAME + " " + LimboApplication.getLimboVersionString()
                            + " " + "QEMU" + " " + LimboApplication.getQemuVersionString() ,
                            FileUtils.LoadFile(LimboActivity.this, "LICENSE", false));
                } catch (IOException e) {

                    e.printStackTrace();
                }
            }
        });

    }

    public void exit() {
        if (MachineController.getInstance().isRunning())
            onStopButton(true);
        else
            System.exit(0);
    }

    private void unlockRemovableDevices(boolean flag) {
        for (StorageDeviceEntry entry : mStorageDeviceEntries) {
            if (entry.removable) {
                entry.typeSpinner.setEnabled(flag);
                entry.sizeEditText.setEnabled(flag);
                entry.sizeUnitSpinner.setEnabled(flag);
                entry.removeBtn.setEnabled(flag);
            }
        }
    }

    private void enableRemovableDeviceOptions(boolean flag) {
        unlockRemovableDevices(flag);
        enableRemovableDiskValues(flag);
    }

    private void enableRemovableDiskValues(boolean flag) {
        for (StorageDeviceEntry entry : mStorageDeviceEntries) {
            if (entry.removable) {
                entry.imageSpinner.setEnabled(flag);
            }
        }
    }

    private void enableNonRemovableDeviceOptions(boolean flag) {
        if (MachineController.getInstance().isRunning())
            flag = false;

        //ui
        mUI.setEnabled(flag);
        mKeyboard.setEnabled(Config.enableKeyboardLayoutOption && flag);
        mMouse.setEnabled(Config.enableMouseOption && flag);

        // Enable everything except removable devices
        mMachineType.setEnabled(flag);
        mCPU.setEnabled(flag);
        mCPUNum.setEnabled(flag);
        mRamSize.setEnabled(flag);
        mEnableKVM.setEnabled(flag && Config.enableKVM);
        mEnableMTTCG.setEnabled(flag && Config.enableMTTCG);

        //drives
        for (StorageDeviceEntry entry : mStorageDeviceEntries) {
            if (!entry.removable) {
                entry.typeSpinner.setEnabled(flag);
                entry.sizeEditText.setEnabled(flag);
                entry.sizeUnitSpinner.setEnabled(flag);
                entry.imageSpinner.setEnabled(flag);
                entry.removeBtn.setEnabled(flag);
            }
        }

        //boot
        mBootDevices.setEnabled(flag);
        mKernel.setEnabled(flag);
        mInitrd.setEnabled(flag);
        mAppend.setEnabled(flag);

        //graphics
        mVGAConfig.setEnabled(flag);

        //audio
        if (Config.enableSDLSound && getMachine() != null
                && getMachine().getEnableVNC() != 1
                && getMachine().getPaused() == 0)
            mSoundCard.setEnabled(flag);
        else
            mSoundCard.setEnabled(false);

        //net
        mNetConfig.setEnabled(flag);
        mNetworkCard.setEnabled(flag && mNetConfig.getSelectedItemPosition() > 0);
        mDNS.setEnabled(flag && mNetConfig.getSelectedItemPosition() > 0);
        mHOSTFWD.setEnabled(flag && mNetConfig.getSelectedItemPosition() > 0);

        //advanced
        mDisableACPI.setEnabled(flag);
        mDisableHPET.setEnabled(flag);
        mDisableTSC.setEnabled(flag);
        mExtraParams.setEnabled(flag);

    }

    // Main event function
    // Retrives values from saved preferences
    private void onStartButton() {

        if (mMachine.getSelectedItemPosition() == 0 || getMachine() == null) {
            ToastUtils.toastShort(LimboActivity.this, getString(R.string.SelectOrCreateVirtualMachineFirst));
            return;
        }
        // focus out of edit texts to make sure they are applied to the db
        mStart.requestFocus();

        if (!validateFiles()) {
            return;
        }

        try {
            createMachineDir(MachineController.getInstance().getMachineSaveDir());
        } catch (Exception ex) {
            ToastUtils.toastLong(LimboActivity.this, getString(R.string.Error) + ": " + ex);
            return;
        }

        // XXX: save the user defined dns server before we start the vm
        LimboSettingsManager.setDNSServer(this, mDNS.getText().toString());

        //XXX: make sure that bios files are installed in case we ran out of space in the last run
        FileInstaller.installFiles(LimboActivity.this, false);

        if (getMachine().getEnableVNC() == 1) {
            startVNC();
        } else {
            startSDL();
        }
    }

    private void createMachineDir(String dir) throws Exception {
        File destDir = new File(dir);
        if (!destDir.exists()) {
            if (!destDir.mkdirs())
                throw new Exception(getString(R.string.failToCreateMachineDirError));
        }
    }

    /**
     * Starts the SDL Activity that will later start the native process via the service.
     * This is done so that the java SDL part is initialized prior to starting the vm.
     */
    public void startSDL() {
        Intent intent = new Intent(LimboActivity.this, LimboSDLActivity.class);
        startActivityForResult(intent, Config.SDL_REQUEST_CODE);
    }

    /**
     * Start the vm with VNC Suport via the Controller which will later call the native process
     * via the service. We do this since we don't have a built-in VNC client anymore.
     */
    public void startVNC() {
        if (LimboSettingsManager.getEnableExternalVNC(this)) {
            // VNC external connections
            LimboActivityCommon.promptVNCServer(this,
                    getString(R.string.ExternalVNCEnabledWarning), viewListener);
        } else if (!LimboSettingsManager.getVNCEnablePassword(this)) {
            // VNC Password is not enabled
            LimboActivityCommon.promptVNCServer(this,
                    getString(R.string.VNCPasswordNotEnabledWarning), viewListener);
        } else if (LimboSettingsManager.getVNCEnablePassword(this)
                && LimboSettingsManager.getVNCPass(this) == null) {
            // VNC Password is missing
            ToastUtils.toastShort(this, getString(R.string.VNCPasswordMissing));
        } else {
            notifyAction(MachineAction.START_VM, null);
        }
    }

    private boolean validateFiles() {
        return FileUtils.fileValid(getMachine().getHdaImagePath())
                && FileUtils.fileValid(getMachine().getHdbImagePath())
                && FileUtils.fileValid(getMachine().getHdcImagePath())
                && FileUtils.fileValid(getMachine().getHddImagePath())
                && FileUtils.fileValid(getMachine().getFdaImagePath())
                && FileUtils.fileValid(getMachine().getFdbImagePath())
                && FileUtils.fileValid(getMachine().getSdImagePath())
                && FileUtils.fileValid(getMachine().getCdImagePath())
                && FileUtils.fileValid(getMachine().getKernel())
                && FileUtils.fileValid(getMachine().getInitRd());
    }

    private void onStopButton(boolean exitApp) {
        KeyboardUtils.hideKeyboard(this, mScrollView);
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
                ToastUtils.toastShort(LimboActivity.this, getString(R.string.vmNotRunning));
            }
        }
    }

    private void onRestartButton() {
        if (!MachineController.getInstance().isRunning()) {
            if (getMachine() != null && getMachine().getPaused() == 1) {
                promptDiscardVMState();
            } else {
                ToastUtils.toastShort(LimboActivity.this, getString(R.string.VMNotRunning));
            }
        }
        LimboActivityCommon.promptResetVM(this, viewListener);
    }

    public void toggleSectionVisibility(View view) {
        if (view.getVisibility() == View.VISIBLE) {
            view.setVisibility(View.GONE);
        } else if (view.getVisibility() == View.GONE || view.getVisibility() == View.INVISIBLE) {
            view.setVisibility(View.VISIBLE);
        }
    }

    public void setupWidgets() {
        setupSections();
        mScrollView = findViewById(R.id.scroll_view);
        mStatus = findViewById(R.id.statusVal);
        mStatus.setImageResource(R.drawable.off);
        mStatusText = findViewById(R.id.statusStr);

        mStart = findViewById(R.id.startvm);
        mPause = findViewById(R.id.pausevm);
        mStop = findViewById(R.id.stopvm);
        mRestart = findViewById(R.id.restartvm);

        //Machine
        mMachine = findViewById(R.id.machineval);
        if (MachineController.getInstance().isRunning())
            mMachine.setEnabled(false);

        //ui
        if (!Config.enable_SDL)
            mUI.setEnabled(false);

        mKeyboard = findViewById(R.id.keyboardval);
        mMouse = findViewById(R.id.mouseval);

        //cpu/board
        mCPU = findViewById(R.id.cpuval);
        mMachineType = findViewById(R.id.machinetypeval);
        mCPUNum = findViewById(R.id.cpunumval);
        mUI = findViewById(R.id.uival);
        mRamSize = findViewById(R.id.rammemval);
        mEnableKVM = findViewById(R.id.enablekvmval);
        mEnableMTTCG = findViewById(R.id.enablemttcgval);
        mDisableACPI = findViewById(R.id.acpival);
        mDisableHPET = findViewById(R.id.hpetval);
        mDisableTSC = findViewById(R.id.tscval);

        //disks
        mStorageDevicesContainer = findViewById(R.id.storageDevicesContainer);
        mAddStorageDeviceBtn = findViewById(R.id.addStorageDeviceBtn);
        mAddStorageDeviceBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (MachineController.getInstance().isRunning()) {
                    ToastUtils.toastShort(LimboActivity.this, getString(R.string.VMRunning));
                    return;
                }
                if (getMachine() == null || mMachine.getSelectedItemPosition() < 2) {
                    ToastUtils.toastShort(LimboActivity.this, getString(R.string.SelectOrCreateVirtualMachineFirst));
                    return;
                }
                addStorageDeviceRow(null);
            }
        });

        //boot
        mBootDevices = findViewById(R.id.bootfromval);
        mKernel = findViewById(R.id.kernelval);
        mInitrd = findViewById(R.id.initrdval);
        mAppend = findViewById(R.id.appendval);

        //display
        mVGAConfig = findViewById(R.id.vgacfgval);

        //sound
        mSoundCard = findViewById(R.id.soundcfgval);

        //network
        mNetConfig = findViewById(R.id.netcfgval);
        mNetworkCard = findViewById(R.id.netDevicesVal);
        mDNS = findViewById(R.id.dnsval);
        setDefaultDNServer();
        mHOSTFWD = findViewById(R.id.hostfwdval);

        // advanced
        mExtraParams = findViewById(R.id.extraparamsval);

        disableFeatures();
        enableRemovableDeviceOptions(false);
        enableNonRemovableDeviceOptions(false);
    }

    private void disableFeatures() {

        View mAudioSectionLayout = findViewById(R.id.audiosectionl);
        if (!Config.enableSDLSound) {
            mAudioSectionLayout.setVisibility(View.GONE);
        }

        LinearLayout mDisableTSCLayout = findViewById(R.id.tscl);
        LinearLayout mDisableACPILayout = findViewById(R.id.acpil);
        LinearLayout mDisableHPETLayout = findViewById(R.id.hpetl);
        LinearLayout mEnableKVMLayout = findViewById(R.id.kvml);

        if (LimboApplication.arch != Config.Arch.x86 && LimboApplication.arch != Config.Arch.x86_64) {
            mDisableTSCLayout.setVisibility(View.GONE);
            mDisableACPILayout.setVisibility(View.GONE);
            mDisableHPETLayout.setVisibility(View.GONE);
        }
        if (LimboApplication.arch != Config.Arch.x86 && LimboApplication.arch != Config.Arch.x86_64
                && LimboApplication.arch != Config.Arch.arm && LimboApplication.arch != Config.Arch.arm64) {
            mEnableKVMLayout.setVisibility(View.GONE);
        }
    }

    private void setDefaultDNServer() {

        Thread thread = new Thread(new Runnable() {
            public void run() {
                final String defaultDNSServer = LimboSettingsManager.getDNSServer(LimboActivity.this);
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    public void run() {
                        // Code here will run in UI thread
                        mDNS.setText(defaultDNSServer);
                    }
                });
            }
        });
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();

    }

    private void setupSections() {

        if (Config.collapseSections) {
            mCPUSectionDetails = findViewById(R.id.cpusectionDetails);
            mCPUSectionDetails.setVisibility(View.GONE);
            mCPUSectionSummary = findViewById(R.id.cpusectionsummaryStr);
            LinearLayout mCPUSectionHeader = findViewById(R.id.cpusectionheaderl);
            mCPUSectionHeader.setOnClickListener(new OnClickListener() {
                public void onClick(View view) {
                    disableListeners();
                    disableStorageDeviceListeners();
                    toggleSectionVisibility(mCPUSectionDetails);
                    enableListenersDelayed();
                }
            });

            mStorageDevicesSectionDetails = findViewById(R.id.storagedevicessectionDetails);
            mStorageDevicesSectionDetails.setVisibility(View.GONE);
            mStorageDevicesSectionSummary = findViewById(R.id.storagedevicessummaryStr);
            LinearLayout mStorageDevicesSectionHeader = findViewById(R.id.storagedevicesheaderl);
            mStorageDevicesSectionHeader.setOnClickListener(new OnClickListener() {
                public void onClick(View view) {
                    disableListeners();
                    disableStorageDeviceListeners();
                    toggleSectionVisibility(mStorageDevicesSectionDetails);
                    enableListenersDelayed();
                }
            });

            mUserInterfaceSectionDetails = findViewById(R.id.userInterfaceDetails);
            mUserInterfaceSectionDetails.setVisibility(View.GONE);
            mUISectionSummary = findViewById(R.id.uisectionsummaryStr);
            LinearLayout mUserInterfaceSectionHeader = findViewById(R.id.userinterfaceheaderl);
            mUserInterfaceSectionHeader.setOnClickListener(new OnClickListener() {
                public void onClick(View view) {
                    disableListeners();
                    disableStorageDeviceListeners();
                    toggleSectionVisibility(mUserInterfaceSectionDetails);
                    enableListenersDelayed();
                }
            });


            mGraphicsSectionDetails = findViewById(R.id.graphicssectionDetails);
            mGraphicsSectionDetails.setVisibility(View.GONE);
            mGraphicsSectionSummary = findViewById(R.id.graphicssectionsummaryStr);
            LinearLayout mGraphicsSectionHeader = findViewById(R.id.graphicsheaderl);
            mGraphicsSectionHeader.setOnClickListener(new OnClickListener() {
                public void onClick(View view) {
                    disableListeners();
                    disableStorageDeviceListeners();
                    toggleSectionVisibility(mGraphicsSectionDetails);
                    enableListenersDelayed();
                }
            });
            mAudioSectionDetails = findViewById(R.id.audiosectionDetails);
            mAudioSectionDetails.setVisibility(View.GONE);
            mAudioSectionSummary = findViewById(R.id.audiosectionsummaryStr);
            LinearLayout mAudioSectionHeader = findViewById(R.id.audioheaderl);
            mAudioSectionHeader.setOnClickListener(new OnClickListener() {
                public void onClick(View view) {
                    disableListeners();
                    disableStorageDeviceListeners();
                    toggleSectionVisibility(mAudioSectionDetails);
                    enableListenersDelayed();
                }
            });

            mNetworkSectionDetails = findViewById(R.id.networksectionDetails);
            mNetworkSectionDetails.setVisibility(View.GONE);
            mNetworkSectionSummary = findViewById(R.id.networksectionsummaryStr);
            View mNetworkSectionHeader = findViewById(R.id.networkheaderl);
            mNetworkSectionHeader.setOnClickListener(new OnClickListener() {
                public void onClick(View view) {
                    disableListeners();
                    disableStorageDeviceListeners();
                    toggleSectionVisibility(mNetworkSectionDetails);
                    enableListenersDelayed();
                }
            });

            mBootSectionDetails = findViewById(R.id.bootsectionDetails);
            mBootSectionDetails.setVisibility(View.GONE);
            mBootSectionSummary = findViewById(R.id.bootsectionsummaryStr);
            View mBootSectionHeader = findViewById(R.id.bootheaderl);
            mBootSectionHeader.setOnClickListener(new OnClickListener() {
                public void onClick(View view) {
                    disableListeners();
                    disableStorageDeviceListeners();
                    toggleSectionVisibility(mBootSectionDetails);
                    enableListenersDelayed();
                }
            });

            mAdvancedSectionDetails = findViewById(R.id.advancedSectionDetails);
            mAdvancedSectionDetails.setVisibility(View.GONE);
            mAdvancedSectionSummary = findViewById(R.id.advancedsectionsummaryStr);
            LinearLayout mAdvancedSectionHeader = findViewById(R.id.advancedheaderl);
            mAdvancedSectionHeader.setOnClickListener(new OnClickListener() {
                public void onClick(View view) {
                    disableListeners();
                    disableStorageDeviceListeners();
                    toggleSectionVisibility(mAdvancedSectionDetails);
                    enableListenersDelayed();
                }
            });
        }
    }

    private void enableListenersDelayed() {
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                setupMiscOptions();
                setupStorageDeviceListeners();
            }
        }, 500);
    }

    public void updateUISummary(boolean clear) {
        if (clear || getMachine() == null || mMachine.getSelectedItemPosition() < 2)
            mUISectionSummary.setText("");
        else {
            String text = getString(R.string.display) + ": " + (getMachine().getEnableVNC() == 1 ? "VNC" : "SDL");
            if (getMachine().getEnableVNC() == 1) {
                text += ", " + getString(R.string.server);
                text += ": " + NetworkUtils.getVNCAddress(this) + ":" + Config.defaultVNCPort;
            }
            if (getMachine().getKeyboard() != null) {
                text += ", " + getString(R.string.keyboard) + ": " + getMachine().getKeyboard();
            }
            if (getMachine().getMouse() != null) {
                text += ", " + getString(R.string.mouse) + ": " + getMachine().getMouse();
            }
            mUISectionSummary.setText(text);
        }
    }

    private Machine getMachine() {
        return MachineController.getInstance().getMachine();
    }

    public void updateCPUSummary(boolean clear) {
        if (clear || getMachine() == null || mMachine.getSelectedItemPosition() < 2)
            mCPUSectionSummary.setText("");
        else {
            String text = "Machine Type: " + getMachine().getMachineType()
                    + ", CPU: " + getMachine().getCpu()
                    + ", " + getMachine().getCpuNum() + " CPU" + ((getMachine().getCpuNum() > 1) ? "s" : "")
                    + ", " + getMachine().getMemory() + " MB";
            if (mEnableMTTCG.isChecked())
                text = appendOption("Enable MTTCG", text);
            if (mEnableKVM.isChecked())
                text = appendOption("Enable KVM", text);
            if (mDisableACPI.isChecked())
                text = appendOption("Disable ACPI", text);
            if (mDisableHPET.isChecked())
                text = appendOption("Disable HPET", text);
            if (mDisableTSC.isChecked())
                text = appendOption("Disable TSC", text);
            mCPUSectionSummary.setText(text);
        }
    }

    public void updateStorageDevicesSummary(boolean clear) {
        if (clear || getMachine() == null || mMachine.getSelectedItemPosition() < 2)
            mStorageDevicesSectionSummary.setText("");
        else {
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

            if (text == null || text.equals("'") || text.equals(""))
                text = "None";
            mStorageDevicesSectionSummary.setText(text);
        }
    }

    public void updateBootSummary(boolean clear) {
        if (clear || getMachine() == null || mMachine.getSelectedItemPosition() < 2)
            mBootSectionSummary.setText("");
        else {
            String text = "Boot from: " + getMachine().getBootDevice();
            text = appendDriveFilename(getMachine().getKernel(), text, "kernel", false);
            text = appendDriveFilename(getMachine().getInitRd(), text, "initrd", false);
            text = appendDriveFilename(getMachine().getAppend(), text, "append", false);
            mBootSectionSummary.setText(text);
        }
    }

    private String appendDriveFilename(String driveFile, String text, String drive, boolean allowEmptyDrive) {

        String file = null;
        if (driveFile != null) {
            if ((driveFile.equals("") || driveFile.equals("None")) && allowEmptyDrive) {
                file = drive + ": Empty";
            } else if (!driveFile.equals("") && !driveFile.equals("None"))
                file = drive + ": " + FileUtils.getFilenameFromPath(driveFile);
        }
        if (text == null && file != null)
            text = file;
        else if (file != null)
            text += (", " + file);
        return text;
    }

    public void updateGraphicsSummary(boolean clear) {
        if (clear || getMachine() == null || mMachine.getSelectedItemPosition() < 2)
            mGraphicsSectionSummary.setText("");
        else {
            String text = "Video Card: " + getMachine().getVga();
            mGraphicsSectionSummary.setText(text);
        }
    }

    public void updateAudioSummary(boolean clear) {
        if (clear || getMachine() == null
                || mMachine.getSelectedItemPosition() < 2)
            mAudioSectionSummary.setText("");
        else {
            String soundCard = getMachine().getSoundCard();
            String text = getString(R.string.AudioCard) + ": " + (soundCard != null ? soundCard : "None");
            mAudioSectionSummary.setText(text);
        }
    }

    public void updateNetworkSummary(boolean clear) {
        if (clear || getMachine() == null
                || mMachine.getSelectedItemPosition() < 2)
            mNetworkSectionSummary.setText("");
        else {
            String netCfg = getMachine().getNetwork();
            String text = getString(R.string.Network) + ": " + (netCfg != null ? netCfg : "None");
            if (netCfg != null && !netCfg.equals("None")) {
                String nicCard = getMachine().getNetworkCard();
                text += ", " + getString(R.string.NicCard) + ": " + (nicCard != null ? nicCard : "None");
                text += ", " + getString(R.string.DNSServer) + ": " + mDNS.getText();
                String hostFWD = getMachine().getHostFwd();
                if (hostFWD != null && !hostFWD.equals(""))
                    text += ", " + getString(R.string.HostForward) + ": " + hostFWD;
            }
            mNetworkSectionSummary.setText(text);
        }
    }

    public void updateAdvancedSummary(boolean clear) {
        if (clear || getMachine() == null || mMachine.getSelectedItemPosition() < 2)
            mAdvancedSectionSummary.setText("");
        else {
            String text = "";
            if (getMachine().getExtraParams() != null
                    && !getMachine().getExtraParams().equals(""))
                text = getString(R.string.ExtraParams) + ": " + getMachine().getExtraParams();
            mAdvancedSectionSummary.setText(text);
        }
    }

    private String appendOption(String option, String text) {

        if (text == null && option != null)
            text = option;
        else if (option != null)
            text += (", " + option);
        return text;
    }

    private void triggerUpdateSpinner(final Spinner spinner) {

        final int position = (int) spinner.getSelectedItemId();
        spinner.setSelection(0);

        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                spinner.setSelection(position);
            }
        }, 100);
    }

    private void loadMachine() {

        setUserPressed(false);
        if (getMachine() == null) {
            return;
        }
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            public void run() {
                loadMachineUI();
                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        postLoadMachineUI();
                    }
                }, 1000);
                setCPUOptions();
                getMachine().addObserver(LimboActivity.this);
            }
        });
    }

    private void postLoadMachineUI() {

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
        mMachine.setEnabled(!MachineController.getInstance().isRunning());
    }

    private void loadMachineUI() {
        populateMachineType(getMachine().getMachineType());
        populateCPUs(getMachine().getCpu());
        populateNetDevices(getMachine().getNetworkCard());
        SpinnerAdapter.setDiskAdapterValue(mCPUNum, getMachine().getCpuNum() + "");
        SpinnerAdapter.setDiskAdapterValue(mRamSize, getMachine().getMemory() + "");
        seMachineDriveValue(FileType.KERNEL, getMachine().getKernel());
        seMachineDriveValue(FileType.INITRD, getMachine().getInitRd());
        if (getMachine().getAppend() != null)
            mAppend.setText(getMachine().getAppend());
        else
            mAppend.setText("");

        if (getMachine().getHostFwd() != null)
            mHOSTFWD.setText(getMachine().getHostFwd());
        else
            mHOSTFWD.setText("");

        if (getMachine().getExtraParams() != null)
            mExtraParams.setText(getMachine().getExtraParams());
        else
            mExtraParams.setText("");

        // Storage devices are loaded dynamically
        refreshStorageDevices();

        // Advance
        SpinnerAdapter.setDiskAdapterValue(mBootDevices, getMachine().getBootDevice());
        SpinnerAdapter.setDiskAdapterValue(mNetConfig, getMachine().getNetwork());
        SpinnerAdapter.setDiskAdapterValue(mVGAConfig, getMachine().getVga());
        SpinnerAdapter.setDiskAdapterValue(mSoundCard, getMachine().getSoundCard());
        SpinnerAdapter.setDiskAdapterValue(mUI, getMachine().getEnableVNC() == 1 ? "VNC" : "SDL");
        SpinnerAdapter.setDiskAdapterValue(mMouse, fixMouseValue(getMachine().getMouse()));
        SpinnerAdapter.setDiskAdapterValue(mKeyboard, getMachine().getKeyboard());

        // motherboard settings
        mDisableACPI.setChecked(getMachine().getDisableAcpi() == 1);
        mDisableHPET.setChecked(getMachine().getDisableHPET() == 1);
        if (LimboApplication.arch == Config.Arch.x86 || LimboApplication.arch == Config.Arch.x86_64)
            mDisableTSC.setChecked(getMachine().getDisableTSC() == 1);
        mEnableKVM.setChecked(getMachine().getEnableKVM() == 1);
        mEnableMTTCG.setChecked(getMachine().getEnableMTTCG() == 1);

        enableNonRemovableDeviceOptions(true);
        enableRemovableDeviceOptions(!MachineController.getInstance().isRunning());

        if (Config.enableSDLSound) {
            mSoundCard.setEnabled(getMachine().getEnableVNC() != 1 && getMachine().getPaused() == 0);
        } else
            mSoundCard.setEnabled(false);

        mMachine.setEnabled(false);
    }

    private String fixMouseValue(String mouse) {
        if (mouse != null) {
            if (mouse.startsWith("usb-tablet"))
                mouse += " " + getString(R.string.fixesMouseParen);
        }
        return mouse;
    }

    private synchronized void updateSummary() {
        updateUISummary(false);
        updateCPUSummary(false);
        updateStorageDevicesSummary(false);
        updateGraphicsSummary(false);
        updateAudioSummary(false);
        updateNetworkSummary(false);
        updateBootSummary(false);
        updateAdvancedSummary(false);
    }

    public void promptMachineName(final Activity activity) {
        final AlertDialog alertDialog;
        alertDialog = new AlertDialog.Builder(activity).create();
        alertDialog.setTitle(getString(R.string.NewMachineName));
        final EditText vmNameTextView = new EditText(activity);
        vmNameTextView.setPadding(20, 20, 20, 20);
        vmNameTextView.setEnabled(true);
        vmNameTextView.setVisibility(View.VISIBLE);
        vmNameTextView.setSingleLine();
        alertDialog.setView(vmNameTextView);
        alertDialog.setCanceledOnTouchOutside(false);
        alertDialog.setButton(DialogInterface.BUTTON_POSITIVE, getString(R.string.Create), (DialogInterface.OnClickListener) null);

        alertDialog.show();

        Button button = alertDialog.getButton(DialogInterface.BUTTON_POSITIVE);
        button.setOnClickListener(new OnClickListener() {
            public void onClick(View view) {
                if (vmNameTextView.getText().toString().trim().equals(""))
                    ToastUtils.toastShort(activity, getString(R.string.MachineNameCannotBeEmpty));
                else {
                    createMachine(vmNameTextView.getText().toString());
                    alertDialog.dismiss();
                }
            }
        });
        alertDialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialog) {
                InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
                imm.hideSoftInputFromWindow(vmNameTextView.getWindowToken(), 0);
            }
        });
    }

    public void promptImageName(final Activity activity, final FileType fileType) {
        promptImageName(activity, fileType, -1L);
    }

    public void promptImageName(final Activity activity, final FileType fileType, final int sizeIndex) {
        promptImageName(activity, fileType, -1L);
    }

    public void promptImageName(final Activity activity, final FileType fileType, final long sizeBytes) {

        final AlertDialog alertDialog;
        alertDialog = new AlertDialog.Builder(activity).create();
        alertDialog.setTitle(getString(R.string.ImageName));

        LinearLayout mLayout = new LinearLayout(this);
        mLayout.setPadding(20, 20, 20, 20);
        mLayout.setOrientation(LinearLayout.VERTICAL);

        final EditText imageNameView = new EditText(activity);
        imageNameView.setEnabled(true);
        imageNameView.setVisibility(View.VISIBLE);
        imageNameView.setSingleLine();
        LinearLayout.LayoutParams imageNameViewParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        mLayout.addView(imageNameView, imageNameViewParams);

        // size selection (custom size in MB/GB/TB)
        LinearLayout sizeLayout = new LinearLayout(this);
        sizeLayout.setOrientation(LinearLayout.HORIZONTAL);

        final EditText sizeValueView = new EditText(activity);
        sizeValueView.setEnabled(true);
        sizeValueView.setSingleLine();
        sizeValueView.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        LinearLayout.LayoutParams sizeValueParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        sizeValueView.setText("4");
        sizeLayout.addView(sizeValueView, sizeValueParams);

        final Spinner sizeUnit = new Spinner(this);
        String[] units = {getString(R.string.size_unit_gb), getString(R.string.size_unit_mb),
                getString(R.string.size_unit_tb)};
        ArrayAdapter<String> unitAdapter = new ArrayAdapter<>(this, R.layout.custom_spinner_item, units);
        unitAdapter.setDropDownViewResource(R.layout.custom_spinner_dropdown_item);
        sizeUnit.setAdapter(unitAdapter);
        if (sizeBytes > 0) {
            formatSizeValue(sizeValueView, sizeUnit, sizeBytes);
        }
        LinearLayout.LayoutParams unitParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sizeLayout.addView(sizeUnit, unitParams);
        mLayout.addView(sizeLayout);

        alertDialog.setView(mLayout);

        alertDialog.setButton(DialogInterface.BUTTON_POSITIVE, getString(R.string.Create), (DialogInterface.OnClickListener) null);
        alertDialog.setButton(DialogInterface.BUTTON_NEGATIVE, getString(R.string.ChangeDirectory), (DialogInterface.OnClickListener) null);

        alertDialog.show();

        Button positiveButton = alertDialog.getButton(AlertDialog.BUTTON_POSITIVE);
        positiveButton.setOnClickListener(new OnClickListener() {

            @Override
            public void onClick(View view) {
                if (LimboSettingsManager.getImagesDir(LimboActivity.this) == null) {
                    changeImagesDir();
                    return;
                }

                long bytes = parseSizeBytes(sizeValueView, sizeUnit);

                String image = imageNameView.getText().toString();
                if (image.trim().equals(""))
                    ToastUtils.toastShort(activity, getString(R.string.ImageFilenameCannotBeEmpty));
                else {
                    String templateImage = getTemplateForSize(bytes);
                    String filePath = null;
                    if (templateImage != null) {
                        if (!image.endsWith(".qcow2")) {
                            image += ".qcow2";
                        }
                        filePath = FileUtils.createImgFromTemplate(LimboActivity.this, templateImage, image, fileType);
                    } else {
                        if (!image.endsWith(".img") && !image.endsWith(".raw")) {
                            image += ".img";
                        }
                        filePath = FileUtils.createRawImage(LimboActivity.this, bytes, image, fileType);
                    }
                    if (filePath != null) {
                        updateDrive(fileType, filePath);
                        alertDialog.dismiss();
                    }
                }
            }
        });

        Button negativeButton = alertDialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        negativeButton.setOnClickListener(new OnClickListener() {

            @Override
            public void onClick(View view) {
                changeImagesDir();

            }
        });
    }

    private void formatSizeValue(EditText sizeValueView, Spinner sizeUnit, long sizeBytes) {
        long gb = 1024L * 1024L * 1024L;
        long mb = 1024L * 1024L;
        if (sizeBytes % gb == 0) {
            sizeValueView.setText((sizeBytes / gb) + "");
            sizeUnit.setSelection(0); // GB
        } else if (sizeBytes % mb == 0) {
            sizeValueView.setText((sizeBytes / mb) + "");
            sizeUnit.setSelection(1); // MB
        } else {
            sizeValueView.setText((sizeBytes / mb) + "");
            sizeUnit.setSelection(1); // MB (rounded)
        }
    }

    private long parseSizeBytes(EditText sizeValueView, Spinner sizeUnit) {
        long value = 1;
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
        if (sizeBytes == gb)
            return "hd1g.qcow2";
        else if (sizeBytes == 2 * gb)
            return "hd2g.qcow2";
        else if (sizeBytes == 4 * gb)
            return "hd4g.qcow2";
        else if (sizeBytes == 10 * gb)
            return "hd10g.qcow2";
        else if (sizeBytes == 20 * gb)
            return "hd20g.qcow2";
        return null;
    }

    public void changeImagesDir() {
        ToastUtils.toastLong(LimboActivity.this, getString(R.string.chooseDirToCreateImage));
        LimboFileManager.browse(LimboActivity.this, FileType.IMAGE_DIR, Config.OPEN_IMAGE_DIR_REQUEST_CODE);
    }

    public boolean onKeyDown(int keyCode, KeyEvent event) {

        if (keyCode == KeyEvent.KEYCODE_BACK) {
            moveTaskToBack(true);
            return true; // return
        }

        return false;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (resultCode == Config.SDL_QUIT_RESULT_CODE) {
            if (getParent() != null) {
                getParent().finish();
            }
            finish();
            if (MachineController.getInstance().isRunning()) {
                notifyAction(MachineAction.STOP_VM, null);
            }
        } else if (requestCode == Config.OPEN_IMPORT_FILE_REQUEST_CODE || requestCode == Config.OPEN_IMPORT_FILE_ASF_REQUEST_CODE) {
            String file;
            if (requestCode == Config.OPEN_IMPORT_FILE_ASF_REQUEST_CODE) {
                file = FileUtils.getFileUriFromIntent(this, data, false);
            } else {
                file = FileUtils.getFilePathFromIntent(this, data);
            }
            if (file != null)
                importMachines(file);
        } else if (requestCode == Config.OPEN_EXPORT_DIR_REQUEST_CODE || requestCode == Config.OPEN_EXPORT_DIR_ASF_REQUEST_CODE) {
            String exportDir;
            if (requestCode == Config.OPEN_EXPORT_DIR_ASF_REQUEST_CODE) {
                exportDir = FileUtils.getFileUriFromIntent(this, data, true);
            } else {
                exportDir = FileUtils.getDirPathFromIntent(this, data);
            }
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
            String imageDir;
            if (requestCode == Config.OPEN_IMAGE_DIR_ASF_REQUEST_CODE) {
                imageDir = FileUtils.getFileUriFromIntent(this, data, true);
            } else {
                imageDir = FileUtils.getDirPathFromIntent(this, data);
            }
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
            String file;
            if (requestCode == Config.OPEN_LOG_FILE_DIR_ASF_REQUEST_CODE) {
                file = FileUtils.getFileUriFromIntent(this, data, true);
            } else {
                file = FileUtils.getDirPathFromIntent(this, data);
            }
            if (file != null) {
                FileUtils.saveLogToFile(LimboActivity.this, file);
            }
        } else if (requestCode == Config.OPEN_IMPORT_BIOS_FILE_REQUEST_CODE || requestCode == Config.OPEN_IMPORT_BIOS_FILE_ASF_REQUEST_CODE) {
            String file;
            if (requestCode == Config.OPEN_IMPORT_BIOS_FILE_ASF_REQUEST_CODE) {
                file = FileUtils.getFileUriFromIntent(this, data, false);
            } else {
                file = FileUtils.getFilePathFromIntent(this, data);
            }
            if (file != null)
                BIOSImporter.importBIOSFile(this, file);
        }
    }

    private void updateDrive(FileType fileType, String diskValue) {
        //FIXME: sometimes the array adapters try to set invalid values
        if (fileType == null || diskValue == null) {
            return;
        }
        Spinner spinner = getSpinner(fileType);
        if (!diskValue.trim().isEmpty()) {
            if (SpinnerAdapter.getPositionFromSpinner(spinner, diskValue) < 0) {
                android.widget.SpinnerAdapter adapter = spinner.getAdapter();
                if (adapter instanceof ArrayAdapter) {
                    SpinnerAdapter.addItem(spinner, diskValue);
                }
            }
            notifyAction(MachineAction.INSERT_FAV, new Object[]{diskValue, fileType});
            seMachineDriveValue(fileType, diskValue);
        }
        int res = spinner.getSelectedItemPosition();
        if (res == 1) {
            spinner.setSelection(0);
        }
    }

    private ArrayAdapter getAdapter(FileType fileType) {
        Spinner spinner = getSpinner(fileType);
        return (ArrayAdapter) spinner.getAdapter();
    }

    private Spinner getSpinner(FileType fileType) {
        if (diskMapping.containsKey(fileType))
            return diskMapping.get(fileType).spinner;
        return null;
    }

    private MachineProperty getProperty(FileType fileType) {
        if (diskMapping.containsKey(fileType))
            return diskMapping.get(fileType).colName;
        return null;
    }

    @Override
    public void onStop() {
        super.onStop();
    }

    @Override
    public void onDestroy() {
        savePendingEditText();
        MachineController.getInstance().removeOnStatusChangeListener(this);
        getMachine().deleteObserver(LimboActivity.this);
        setViewListener(null);
        super.onDestroy();
    }

    private void populateRAM() {
        String[] arraySpinner = new String[4 * 256];
        arraySpinner[0] = 4 + "";
        for (int i = 1; i < arraySpinner.length; i++) {
            arraySpinner[i] = i * 8 + "";
        }
        ArrayAdapter<String> ramAdapter = new ArrayAdapter<>(this, R.layout.custom_spinner_item, arraySpinner);
        ramAdapter.setDropDownViewResource(R.layout.custom_spinner_dropdown_item);
        mRamSize.setAdapter(ramAdapter);
        mRamSize.invalidate();
    }

    private void populateCPUNum() {
        String[] arraySpinner = new String[Config.MAX_CPU_NUM];
        for (int i = 0; i < arraySpinner.length; i++) {
            arraySpinner[i] = (i + 1) + "";
        }
        ArrayAdapter<String> cpuNumAdapter = new ArrayAdapter<>(this, R.layout.custom_spinner_item, arraySpinner);
        cpuNumAdapter.setDropDownViewResource(R.layout.custom_spinner_dropdown_item);
        mCPUNum.setAdapter(cpuNumAdapter);
        mCPUNum.invalidate();
    }

    private void populateBootDevices() {
        ArrayList<String> bootDevicesList = new ArrayList<>();
        bootDevicesList.add("Default");
        bootDevicesList.add("CDROM");
        bootDevicesList.add("Hard Disk");
        if (Config.enableEmulatedFloppy)
            bootDevicesList.add("Floppy");

        String[] arraySpinner = bootDevicesList.toArray(new String[0]);

        ArrayAdapter<String> bootDevAdapter = new ArrayAdapter<>(this, R.layout.custom_spinner_item, arraySpinner);
        bootDevAdapter.setDropDownViewResource(R.layout.custom_spinner_dropdown_item);
        mBootDevices.setAdapter(bootDevAdapter);
        mBootDevices.invalidate();
    }

    private void populateNet() {
        String[] arraySpinner = {"None", "User", "TAP"};
        ArrayAdapter<String> netAdapter = new ArrayAdapter<>(this, R.layout.custom_spinner_item, arraySpinner);
        netAdapter.setDropDownViewResource(R.layout.custom_spinner_dropdown_item);
        mNetConfig.setAdapter(netAdapter);
        mNetConfig.invalidate();
    }

    private void populateVGA() {
        ArrayList<String> arrList = ArchDefinitions.getVGAValues(this);
        ArrayAdapter<String> vgaAdapter = new ArrayAdapter<>(this, R.layout.custom_spinner_item, arrList);
        vgaAdapter.setDropDownViewResource(R.layout.custom_spinner_dropdown_item);
        mVGAConfig.setAdapter(vgaAdapter);
        mVGAConfig.invalidate();
    }

    private void populateKeyboardLayout() {
        ArrayList<String> arrList = ArchDefinitions.getKeyboardValues(this);
        ArrayAdapter<String> keyboardAdapter = new ArrayAdapter<>(this, R.layout.custom_spinner_item, arrList);
        keyboardAdapter.setDropDownViewResource(R.layout.custom_spinner_dropdown_item);
        mKeyboard.setAdapter(keyboardAdapter);
        mKeyboard.invalidate();
        //TODO: for now we use only English keyboard, add more layouts
        mKeyboard.setSelection(0);
    }

    private void populateMouse() {
        ArrayList<String> arrList = ArchDefinitions.getMouseValues(this);
        ArrayAdapter<String> mouseAdapter = new ArrayAdapter<>(this, R.layout.custom_spinner_item, arrList);
        mouseAdapter.setDropDownViewResource(R.layout.custom_spinner_dropdown_item);
        mMouse.setAdapter(mouseAdapter);
        mMouse.invalidate();
    }

    private void populateSoundcardConfig() {
        ArrayList<String> soundCards = new ArrayList<>();
        soundCards.add("None");
        soundCards.addAll(ArchDefinitions.getSoundcards(this));
        ArrayAdapter<String> sndAdapter = new ArrayAdapter<>(this, R.layout.custom_spinner_item, soundCards);
        sndAdapter.setDropDownViewResource(R.layout.custom_spinner_dropdown_item);
        mSoundCard.setAdapter(sndAdapter);
        mSoundCard.invalidate();
    }

    private void populateNetDevices(String nic) {
        ArrayList<String> networkCards = ArchDefinitions.getNetworkDevices(this);
        ArrayAdapter<String> nicCfgAdapter = new ArrayAdapter<>(this, R.layout.custom_spinner_item, networkCards);
        nicCfgAdapter.setDropDownViewResource(R.layout.custom_spinner_dropdown_item);
        mNetworkCard.setAdapter(nicCfgAdapter);
        mNetworkCard.invalidate();

        int pos = nicCfgAdapter.getPosition(nic);
        if (pos >= 0) {
            mNetworkCard.setSelection(pos);
        }
    }

    private void populateMachines(final String machineValue) {
        Thread thread = new Thread(new Runnable() {
            public void run() {
                final ArrayList<String> machinesList = ArchDefinitions.getMachineValues(LimboActivity.this);
                ArrayList<String> machinesDB = MachineController.getInstance().getStoredMachines();
                machinesList.addAll(machinesDB);
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    public void run() {
                        ArrayAdapter<String> machineAdapter = new ArrayAdapter<>(LimboActivity.this, R.layout.custom_spinner_item, machinesList);
                        machineAdapter.setDropDownViewResource(R.layout.custom_spinner_dropdown_item);
                        mMachine.setAdapter(machineAdapter);
                        mMachine.invalidate();
                        if (machineValue != null)
                            SpinnerAdapter.setDiskAdapterValue(mMachine, machineValue);
                    }
                });
            }
        });
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    private void seMachineDriveValue(FileType fileType, final String diskValue) {
        Spinner spinner = getSpinner(fileType);
        if (spinner != null)
            SpinnerAdapter.setDiskAdapterValue(spinner, diskValue);
    }

    private void populateCPUs(String cpu) {
        ArrayList<String> arrList = ArchDefinitions.getCpuValues(this);
        ArrayAdapter<String> cpuAdapter = new ArrayAdapter<>(this, R.layout.custom_spinner_item, arrList);
        cpuAdapter.setDropDownViewResource(R.layout.custom_spinner_dropdown_item);
        mCPU.setAdapter(cpuAdapter);
        mCPU.invalidate();
        int pos = cpuAdapter.getPosition(cpu);
        if (pos >= 0) {
            mCPU.setSelection(pos);
        }
    }

    private void populateMachineType(String machineType) {
        ArrayList<String> arrList = ArchDefinitions.getMachineTypeValues(this);

        ArrayAdapter<String> machineTypeAdapter = new ArrayAdapter<>(this, R.layout.custom_spinner_item, arrList);
        machineTypeAdapter.setDropDownViewResource(R.layout.custom_spinner_dropdown_item);
        mMachineType.setAdapter(machineTypeAdapter);

        mMachineType.invalidate();
        int pos = machineTypeAdapter.getPosition(machineType);
        mMachineType.setSelection(Math.max(pos, 0));

    }

    private void populateUI() {
        ArrayList<String> arrList = ArchDefinitions.getUIValues();
        ArrayAdapter<String> uiAdapter = new ArrayAdapter<>(this, R.layout.custom_spinner_item, arrList);
        uiAdapter.setDropDownViewResource(R.layout.custom_spinner_dropdown_item);
        mUI.setAdapter(uiAdapter);
        mUI.invalidate();
    }

    public void populateDiskAdapter(final Spinner spinner, final FileType fileType, final boolean createOption) {
        populateDiskAdapter(spinner, fileType, createOption, null);
    }

    public void populateDiskAdapter(final Spinner spinner, final FileType fileType, final boolean createOption,
                                    final Runnable onComplete) {
        Thread t = new Thread(new Runnable() {
            public void run() {
                ArrayList<String> oldHDs = MachineFilePaths.getRecentFilePaths(fileType);
                final ArrayList<String> arraySpinner = new ArrayList<>();
                arraySpinner.add("None");
                if (createOption)
                    arraySpinner.add("New");
                arraySpinner.add(getString(R.string.open));
                final int index = arraySpinner.size();
                if (oldHDs != null) {
                    for (String file : oldHDs) {
                        if (file != null) {
                            arraySpinner.add(file);
                        }
                    }
                }
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    public void run() {
                        SpinnerAdapter adapter = new SpinnerAdapter(LimboActivity.this, R.layout.custom_spinner_item, arraySpinner, index);
                        adapter.setDropDownViewResource(R.layout.custom_spinner_dropdown_item);
                        spinner.setAdapter(adapter);
                        spinner.invalidate();
                        if (onComplete != null)
                            onComplete.run();
                    }
                });
            }
        });
        t.start();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        invalidateOptionsMenu();
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        menu.clear();
        menu.add(0, HELP, 0, R.string.help).setIcon(R.drawable.help);
        menu.add(0, INSTALL, 0, R.string.InstallRoms).setIcon(R.drawable.install);
        if(!MachineController.getInstance().isRunning()) {
            menu.add(0, CREATE, 0, R.string.CreateMachine).setIcon(R.drawable.machinetype);
            menu.add(0, DELETE, 0, R.string.DeleteMachine).setIcon(R.drawable.delete);
            if (getMachine() != null && getMachine().getPaused() == 1)
                menu.add(0, DISCARD_VM_STATE, 0, R.string.DiscardSavedState).setIcon(R.drawable.close);
            menu.add(0, EXPORT, 0, R.string.ExportMachines).setIcon(R.drawable.exportvms);
            menu.add(0, IMPORT, 0, R.string.ImportMachines).setIcon(R.drawable.importvms);
        }
        menu.add(0, IMPORT_BIOS_FILE, 0, R.string.ImportBIOSFile).setIcon(R.drawable.importvms);
        menu.add(0, SETTINGS, 0, R.string.Settings).setIcon(R.drawable.settings);
        menu.add(0, TOOLS, 0, R.string.advancedTools).setIcon(R.drawable.advanced);
        menu.add(0, VIEWLOG, 0, R.string.ViewLog).setIcon(android.R.drawable.ic_menu_view);
        menu.add(0, HELP, 0, R.string.help).setIcon(R.drawable.help);
        menu.add(0, CHANGELOG, 0, R.string.Changelog).setIcon(android.R.drawable.ic_menu_help);
        menu.add(0, LICENSE, 0, R.string.License).setIcon(android.R.drawable.ic_menu_help);
        menu.add(0, QUIT, 0, R.string.Exit).setIcon(android.R.drawable.ic_lock_power_off);

        for (int i = 0; i < 2; i++) {
            menu.getItem(i).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {

        super.onOptionsItemSelected(item);
        if (item.getItemId() == INSTALL) {
            Installer.installFiles(this, true);
        } else if (item.getItemId() == DELETE) {
            promptDeleteMachine();
        } else if (item.getItemId() == DISCARD_VM_STATE) {
            promptDiscardVMState();
        } else if (item.getItemId() == CREATE) {
            promptMachineName(this);
        } else if (item.getItemId() == SETTINGS) {
            showSettings();
        } else if (item.getItemId() == TOOLS) {
            LimboActivityCommon.goToURL(this, Config.toolsLink);
        } else if (item.getItemId() == EXPORT) {
            MachineExporter.promptExport(this);
        } else if (item.getItemId() == IMPORT) {
            MachineImporter.promptImportMachines(this);
        } else if (item.getItemId() == IMPORT_BIOS_FILE) {
            BIOSImporter.promptImportBIOSFile(this);
        } else if (item.getItemId() == HELP) {
            Help.showHelp(this);
        } else if (item.getItemId() == VIEWLOG) {
            Logger.viewLimboLog(LimboActivity.this);
        } else if (item.getItemId() == CHANGELOG) {
            LimboActivityCommon.showChangelog(LimboActivity.this);
        } else if (item.getItemId() == LICENSE) {
            promptLicense();
        } else if (item.getItemId() == QUIT) {
            exit();
        }
        return true;
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
        new AlertDialog.Builder(this).setTitle(getString(R.string.DeleteVM) + ": " + getMachine().getName())
                .setMessage(R.string.deleteVMWarning)
                .setPositiveButton(getString(android.R.string.yes), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        onDeleteMachine();
                    }
                }).setNegativeButton(getString(android.R.string.no), new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
            }
        }).show();
    }

    public void promptDiscardVMState() {
        new AlertDialog.Builder(this).setTitle(R.string.discardVMState)
                .setMessage(R.string.discardVMInstructions)
                .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        notifyFieldChange(MachineProperty.PAUSED, 0);
                        changeStatus(MachineStatus.Ready);
                        enableNonRemovableDeviceOptions(true);
                        enableRemovableDeviceOptions(true);

                    }
                }).setNegativeButton(getString(android.R.string.no), new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
            }
        }).show();
    }

    public void onPause() {
        View currentView = getCurrentFocus();
        if (currentView instanceof EditText) {
            currentView.setFocusable(false);
        }
        super.onPause();
    }

    public void onResume() {
        super.onResume();
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                updateValues();
                if(libLoaded)
                    notifyAction(MachineAction.IGNORE_BREAKPOINT_INVALIDATION, LimboSettingsManager.getIgnoreBreakpointInvalidation(LimboActivity.this));
            }
        }, 1000);

    }

    private void updateValues() {
        Thread t = new Thread(new Runnable() {
            public void run() {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        changeStatus(MachineController.getInstance().getCurrStatus());
                        updateRemovableDiskValues();
                        updateSummary();
                    }
                });
            }
        });
        t.start();
    }

    private void updateRemovableDiskValues() {
        if (getMachine() != null) {
            disableStorageDeviceListeners();
            for (StorageDeviceEntry entry : mStorageDeviceEntries) {
                if (entry.removable) {
                    String value = getMachineDriveValue(entry.fileType);
                    seMachineDriveValue(entry.fileType, value);
                }
            }
            setupStorageDeviceListeners();
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
        }
    }

    @Override
    public void onEvent(Machine machine, MachineController.Event event, Object o) {
        switch (event) {
            case MachineCreateFailed:
                if (o instanceof Integer) {
                    ToastUtils.toastShort(LimboActivity.this, getString((int) o));
                } else if (o instanceof String) {
                    ToastUtils.toastShort(LimboActivity.this, (String) o);
                }
                break;
            case MachineCreated:
                machineCreated();
                break;
            case MachineLoaded:
                loadMachine();
                break;
            case MachineContinued:
                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        invalidateOptionsMenu();
                        changeStatus(MachineController.getInstance().getCurrStatus());
                    }
                }, 1000);
                break;
            case MachinesImported:
                onMachinesImported((ArrayList<Machine>) o);
                break;
        }
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                updateSummary();
            }
        });
    }


    private void onMachinesImported(ArrayList<Machine> machines) {
        populateAttributesUI();
        setupMiscOptions();
        setupStorageDeviceListeners();
        updateFavAdapters();
        LimboActivityCommon.promptMachinesImported(this, machines);
    }

    private void updateFavAdapters() {
        for (StorageDeviceEntry entry : mStorageDeviceEntries) {
            entry.imageSpinner.getAdapter().getCount();
        }
        mKernel.getAdapter().getCount();
        mInitrd.getAdapter().getCount();
    }

    private void addGenericOperatingSystems() {
        Config.osImages.put(getString(R.string.other), new LinksManager.LinkInfo("Other",
                getString(R.string.otherOperatingSystem),
                Config.otherOSLink,
                LinksManager.LinkType.OTHER));
                Config.osImages.put(getString(R.string.custom), new LinksManager.LinkInfo("Custom",
                getString(R.string.customOperatingSystem),
                null,
                LinksManager.LinkType.OTHER));
    }

    @Override
    public void update(Observable observable, final Object o) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Object [] params = (Object[] ) o;
                if (params[0] instanceof MachineProperty) {
                    MachineProperty property = (MachineProperty) params[0];
                    if (property == MachineProperty.UI) {
                        if (getMachine().getEnableVNC() != 1)
                            mSoundCard.setEnabled(true);
                        else
                            mSoundCard.setEnabled(true);
                    }
                }
                updateSummary();
            }
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

    // ================= Storage Devices (dynamic rows) =================

    private void refreshStorageDevices() {
        // remove all existing rows
        for (StorageDeviceEntry entry : new ArrayList<>(mStorageDeviceEntries)) {
            mStorageDevicesContainer.removeView(entry.rowLayout);
            diskMapping.remove(entry.fileType);
        }
        mStorageDeviceEntries.clear();
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
        if (path != null && !path.equals("") && !path.equals("None")) {
            addStorageDeviceRow(DeviceType.HARD_DISK, slot);
        }
    }

    private void addRowForDrive(DeviceType type, String path) {
        if (path != null && !path.equals("") && !path.equals("None")) {
            addStorageDeviceRow(type);
        }
    }

    private void addStorageDeviceRow(DeviceType type) {
        addStorageDeviceRow(type, -1);
    }

    private void addStorageDeviceRow(DeviceType type, int hardDiskSlot) {
        if (getMachine() == null)
            return;
        if (type == null) {
            type = getFirstUnusedType();
            if (type == null) {
                ToastUtils.toastShort(this, getString(R.string.device_add_failed));
                return;
            }
        }
        // check if type already reached max count
        if (countDeviceType(type) >= type.maxCount) {
            ToastUtils.toastShort(this, getString(R.string.device_already_exists));
            return;
        }

        LayoutInflater inflater = LayoutInflater.from(this);
        View row = inflater.inflate(R.layout.storage_device_row_layout, mStorageDevicesContainer, false);
        Spinner typeSpinner = row.findViewById(R.id.storageDeviceTypeSpinner);
        EditText sizeEditText = row.findViewById(R.id.storageDeviceSizeValue);
        Spinner sizeUnitSpinner = row.findViewById(R.id.storageDeviceSizeUnit);
        Spinner imageSpinner = row.findViewById(R.id.storageDeviceImageSpinner);
        ImageButton removeBtn = row.findViewById(R.id.storageDeviceRemoveBtn);

        StorageDeviceEntry entry = new StorageDeviceEntry();
        entry.rowLayout = (LinearLayout) row;
        entry.typeSpinner = typeSpinner;
        entry.sizeEditText = sizeEditText;
        entry.sizeUnitSpinner = sizeUnitSpinner;
        entry.imageSpinner = imageSpinner;
        entry.removeBtn = removeBtn;
        entry.deviceType = type;
        entry.removable = type.removable;
        entry.createImage = type.createImage;
        entry.sharedFolder = type.sharedFolder;
        if (type == DeviceType.HARD_DISK) {
            if (hardDiskSlot >= 0) {
                entry.hardDiskSlot = hardDiskSlot;
                assignHardDiskSlotProperty(entry, hardDiskSlot);
            } else {
                assignHardDiskSlot(entry);
            }
        } else {
            entry.property = type.property;
            entry.fileType = type.fileType;
        }

        // type spinner adapter
        DeviceType[] types = getAvailableDeviceTypes();
        String[] typeLabels = new String[types.length];
        for (int i = 0; i < types.length; i++)
            typeLabels[i] = getString(types[i].labelRes);
        ArrayAdapter<String> typeAdapter = new ArrayAdapter<>(this, R.layout.custom_spinner_item, typeLabels);
        typeAdapter.setDropDownViewResource(R.layout.custom_spinner_dropdown_item);
        typeSpinner.setAdapter(typeAdapter);
        typeSpinner.setSelection(getTypePosition(type));

        // size unit spinner adapter (MB/GB/TB)
        String[] units = {getString(R.string.size_unit_gb), getString(R.string.size_unit_mb),
                getString(R.string.size_unit_tb)};
        ArrayAdapter<String> unitAdapter = new ArrayAdapter<>(this, R.layout.custom_spinner_item, units);
        unitAdapter.setDropDownViewResource(R.layout.custom_spinner_dropdown_item);
        sizeUnitSpinner.setAdapter(unitAdapter);
        sizeUnitSpinner.setSelection(0); // default GB
        sizeEditText.setText("4");

        // remove button
        removeBtn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                removeStorageDeviceRow(entry);
            }
        });

        mStorageDevicesContainer.addView(row);
        mStorageDeviceEntries.add(entry);

        // register disk mapping
        diskMapping.put(entry.fileType, new DiskInfo(imageSpinner, null, entry.property));

        // For removable devices: if this is a new (empty) device, enable it so
        // image selection can be saved. Devices loaded from a machine with an
        // existing image already have their enable flag set.
        if (entry.removable) {
            String existing = getMachineDriveValue(entry.fileType);
            if (existing == null || existing.equals("") || existing.equals("None")) {
                notifyFieldChange(MachineProperty.DRIVE_ENABLED, new Object[]{entry.property, true});
            }
        }

        updateStorageDeviceSizeVisibility(entry);

        // set value from machine once the image adapter is ready
        populateStorageDeviceImageAdapter(entry, new Runnable() {
            @Override
            public void run() {
                String value = getMachineDriveValue(entry.fileType);
                if (value != null && !value.equals("") && !value.equals("None")) {
                    seMachineDriveValue(entry.fileType, value);
                }
                setupStorageDeviceRowListeners(entry);
            }
        });
    }

    private void removeStorageDeviceRow(StorageDeviceEntry entry) {
        if (entry.removable) {
            notifyFieldChange(MachineProperty.DRIVE_ENABLED, new Object[]{entry.property, false});
        } else {
            clearDrive(entry);
        }
        mStorageDevicesContainer.removeView(entry.rowLayout);
        mStorageDeviceEntries.remove(entry);
        diskMapping.remove(entry.fileType);
        // re-compact hard disk slots (HDA..HDD stay contiguous)
        reassignHardDiskSlots();
        updateSummary();
    }

    // Reassigns contiguous HDA..HDD slots to the remaining hard disk rows so
    // that slots are always compact (e.g. removing HDB shifts HDC->HDB).
    private void reassignHardDiskSlots() {
        int slot = 0;
        for (StorageDeviceEntry entry : mStorageDeviceEntries) {
            if (entry.deviceType == DeviceType.HARD_DISK) {
                if (entry.hardDiskSlot != slot) {
                    // keep the image path from the old slot
                    String oldValue = getMachineDriveValue(entry.fileType);
                    // release the old slot
                    diskMapping.remove(entry.fileType);
                    clearDrive(entry);
                    // assign the new slot
                    entry.hardDiskSlot = slot;
                    assignHardDiskSlotProperty(entry, slot);
                    diskMapping.put(entry.fileType, new DiskInfo(entry.imageSpinner, null, entry.property));
                    // move the image path to the new slot
                    if (oldValue != null && !oldValue.equals("") && !oldValue.equals("None")) {
                        notifyFieldChange(MachineProperty.NON_REMOVABLE_DRIVE,
                                new Object[]{entry.property, oldValue});
                        seMachineDriveValue(entry.fileType, oldValue);
                    } else {
                        seMachineDriveValue(entry.fileType, null);
                    }
                }
                slot++;
            }
        }
    }

    private void clearDrive(StorageDeviceEntry entry) {
        if (entry.removable) {
            notifyFieldChange(MachineProperty.REMOVABLE_DRIVE, new Object[]{entry.property, "None"});
        } else {
            // includes SHARED_FOLDER which goes through NON_REMOVABLE_DRIVE
            notifyFieldChange(MachineProperty.NON_REMOVABLE_DRIVE, new Object[]{entry.property, "None"});
        }
    }

    private String getMachineDriveValue(FileType fileType) {
        switch (fileType) {
            case HDA:
                return getMachine().getHdaImagePath();
            case HDB:
                return getMachine().getHdbImagePath();
            case HDC:
                return getMachine().getHdcImagePath();
            case HDD:
                return getMachine().getHddImagePath();
            case CDROM:
                return getMachine().getCdImagePath();
            case FDA:
                return getMachine().getFdaImagePath();
            case FDB:
                return getMachine().getFdbImagePath();
            case SD:
                return getMachine().getSdImagePath();
            case SHARED_DIR:
                return getMachine().getSharedFolderPath();
            default:
                return null;
        }
    }

    private DeviceType[] getAvailableDeviceTypes() {
        List<DeviceType> types = new ArrayList<>();
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
        for (StorageDeviceEntry entry : mStorageDeviceEntries) {
            if (entry.deviceType == type)
                count++;
        }
        return count;
    }

    // Hard disks are assigned to fixed slots HDA..HDD (0..3), matching the
    // underlying Machine fields. Returns the first free slot or -1 if full.
    private int getFreeHardDiskSlot() {
        for (int slot = 0; slot < 4; slot++) {
            boolean used = false;
            for (StorageDeviceEntry entry : mStorageDeviceEntries) {
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

    private void assignHardDiskSlot(StorageDeviceEntry entry) {
        int slot = getFreeHardDiskSlot();
        if (slot < 0)
            return;
        entry.hardDiskSlot = slot;
        assignHardDiskSlotProperty(entry, slot);
    }

    private void assignHardDiskSlotProperty(StorageDeviceEntry entry, int slot) {
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

    private void populateStorageDeviceImageAdapter(StorageDeviceEntry entry) {
        populateStorageDeviceImageAdapter(entry, null);
    }

    private void populateStorageDeviceImageAdapter(StorageDeviceEntry entry, Runnable onComplete) {
        populateDiskAdapter(entry.imageSpinner, entry.fileType, entry.createImage, onComplete);
    }

    private void updateStorageDeviceSizeVisibility(StorageDeviceEntry entry) {
        int visibility = entry.createImage ? View.VISIBLE : View.GONE;
        entry.sizeEditText.setVisibility(visibility);
        entry.sizeUnitSpinner.setVisibility(visibility);
    }

    static class DiskInfo {
        public CheckBox enableCheckBox;
        public Spinner spinner;
        public MachineProperty colName;

        public DiskInfo(Spinner spinner, CheckBox enableCheckbox, MachineProperty dbColName) {
            this.spinner = spinner;
            this.enableCheckBox = enableCheckbox;
            this.colName = dbColName;
        }
    }

    static class StorageDeviceEntry {
        public LinearLayout rowLayout;
        public Spinner typeSpinner;
        public EditText sizeEditText;
        public Spinner sizeUnitSpinner;
        public Spinner imageSpinner;
        public ImageButton removeBtn;
        public DeviceType deviceType;
        public MachineProperty property;
        public FileType fileType;
        public boolean removable;
        public boolean createImage;
        public boolean sharedFolder;
        public int hardDiskSlot = -1; // -1 = not a hard disk, 0..3 = HDA..HDD
    }

    enum DeviceType {
        HARD_DISK(null, null, false, true, false, R.string.type_hard_disk, 4),
        CDROM(MachineProperty.CDROM, Machine.FileType.CDROM, true, false, false, R.string.type_cdrom, 1),
        FDA(MachineProperty.FDA, Machine.FileType.FDA, true, false, false, R.string.type_floppy_a, 1),
        FDB(MachineProperty.FDB, Machine.FileType.FDB, true, false, false, R.string.type_floppy_b, 1),
        SD(MachineProperty.SD, Machine.FileType.SD, true, false, false, R.string.type_sd_card, 1),
        SHARED_DIR(MachineProperty.SHARED_FOLDER, Machine.FileType.SHARED_DIR, false, false, true, R.string.type_shared_folder, 1);

        public final MachineProperty property;
        public final Machine.FileType fileType;
        public final boolean removable;
        public final boolean createImage;
        public final boolean sharedFolder;
        public final int labelRes;
        public final int maxCount;

        DeviceType(MachineProperty property, Machine.FileType fileType, boolean removable,
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
