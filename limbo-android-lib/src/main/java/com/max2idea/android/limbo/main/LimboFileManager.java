package com.max2idea.android.limbo.main;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.max2idea.android.limbo.machine.Machine;

import org.jetbrains.annotations.Contract;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import me.rosuh.filepicker.config.FilePickerConfig;
import me.rosuh.filepicker.config.FilePickerManager;
import me.rosuh.filepicker.filetype.FileType;

public class LimboFileManager extends Activity {
    private static final String EXTRA_FILE_TYPE = "com.max2idea.android.limbo.main.EXTRA_FILE_TYPE";

    // 结果 extras 的键与 FileUtils.getFilePathFromIntent() / getDirPathFromIntent() /
    // getFileTypeFromIntent() 的约定保持一致
    private static final String EXTRA_FILE = "file";
    private static final String EXTRA_CURR_DIR = "currDir";
    private static final String EXTRA_FILE_TYPE_RESULT = "fileType";

    private Machine.FileType fileType;

    /**
     * 启动文件选择器,选择结果通过调用方 Activity 的 onActivityResult(requestCode, ...) 返回。
     * 结果 Intent 的 extras 中携带:
     * <ul>
     *     <li>"file" - 选中文件的完整路径</li>
     *     <li>"currDir" - 当前目录(目录选择类请求为选中文件所在目录)</li>
     *     <li>"fileType" - Machine.FileType 枚举</li>
     * </ul>
     */
    public static void browse(Activity activity, Machine.FileType fileType, int requestCode) {
        if (activity == null || fileType == null) {
            return;
        }
        Intent intent = new Intent(activity, LimboFileManager.class);
        intent.putExtra(EXTRA_FILE_TYPE, fileType);
        activity.startActivityForResult(intent, requestCode);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        fileType = getFileTypeFromIntent();
        if (fileType == null) {
            // 非法入口:未通过 LimboFileManager.browse() 启动,直接取消返回
            setResult(Activity.RESULT_CANCELED);
            finish();
            return;
        }

        startFilePicker();
    }

    private void startFilePicker() {
        CustomFileType customFileType = new CustomFileType(fileType);
        FilePickerConfig config = FilePickerManager
                .from(this)
                .maxSelectable(1)
                .registerFileType(new ArrayList<>() {{
                    add(customFileType);
                }}, false);

        // 目录选择类请求: 文件夹选择模式,确认后返回当前目录路径
        // 文件选择类请求: 允许选中文件夹,确认后返回勾选的文件或文件夹路径
        if (isDirPicker(fileType)) {
            config.folderPicker();
        } else {
            config.skipDirWhenSelect(true);
        }

        config.forResult(FilePickerManager.REQUEST_CODE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != FilePickerManager.REQUEST_CODE) {
            return;
        }
        if (resultCode == Activity.RESULT_OK) {
            Bundle bundle = new Bundle();

            if (isDirPicker(fileType)) {
                // 文件夹选择模式:
                // 优先取已勾选的文件夹; 未勾选时取当前目录
                List<String> list = FilePickerManager.obtainData();
                String dir;
                if (!list.isEmpty()) {
                    dir = list.get(0);
                } else {
                    dir = FilePickerManager.obtainCurrDir();
                }
                if (dir != null && !dir.isEmpty()) {
                    bundle.putString(EXTRA_CURR_DIR, dir);
                    bundle.putString(EXTRA_FILE, dir);
                    bundle.putSerializable(EXTRA_FILE_TYPE_RESULT, fileType);
                } else {
                    setResult(Activity.RESULT_CANCELED);
                    finish();
                    return;
                }
            } else {
                List<String> list = FilePickerManager.obtainData();
                if (!list.isEmpty()) {
                    String file = list.get(0);
                    bundle.putString(EXTRA_CURR_DIR, resolveCurrDir(file));
                    bundle.putString(EXTRA_FILE, file);
                    bundle.putSerializable(EXTRA_FILE_TYPE_RESULT, fileType);
                } else {
                    Toast.makeText(this,
                            "You didn't choose anything~", Toast.LENGTH_SHORT).show();
                    setResult(Activity.RESULT_CANCELED);
                    finish();
                    return;
                }
            }

            Intent result = new Intent();
            result.putExtras(bundle);
            setResult(Activity.RESULT_OK, result);
        } else {
            setResult(Activity.RESULT_CANCELED);
        }
        finish();
    }

    /**
     * 计算返回给调用方的当前目录。
     * 目录选择类请求(导出/镜像/日志/共享目录)从所选文件推导其父目录;
     * 文件选择类请求回落到文件选择器的初始根路径。
     */
    private String resolveCurrDir(String file) {
        if (isDirPicker(fileType)) {
            if (file != null) {
                File parent = new File(file).getParentFile();
                if (parent != null) {
                    return parent.getAbsolutePath();
                }
            }
            return file;
        }
        return FilePickerManager.config.getCustomRootPath();
    }

    @Contract(pure = true)
    private static boolean isDirPicker(@NonNull Machine.FileType type) {
        switch (type) {
            case EXPORT_DIR:
            case IMAGE_DIR:
            case LOG_DIR:
            case SHARED_DIR:
                return true;
            default:
                return false;
        }
    }

    @SuppressWarnings("deprecation")
    private Machine.FileType getFileTypeFromIntent() {
        return (Machine.FileType) getIntent().getSerializableExtra(EXTRA_FILE_TYPE);
    }
}

class CustomFileType implements FileType {
    private final Machine.FileType fileType;
    private final int fileIconResId = me.rosuh.filepicker.R.drawable.ic_unknown_file_picker;

    CustomFileType(Machine.FileType type) {
        fileType = type;
    }

    @NonNull
    @Override
    public String getFileType() {
        return fileType.name();
    }

    @Override
    public int getFileIconResId() {
        return fileIconResId;
    }

    @Override
    public boolean verify(@NonNull String fileName) {
        String[] extensions = getExtensionsByType(fileType);
        if (extensions == null || extensions.length == 0) {
            // KERNEL / INITRD 以及目录选择场景没有固定的扩展名,放行所有文件
            return true;
        }
        String lowerName = fileName.toLowerCase(Locale.ROOT);
        for (String extension : extensions) {
            if (lowerName.endsWith("." + extension)) {
                return true;
            }
        }
        return false;
    }

    // 根据文件类型返回对应的扩展名数组(小写,不含点号);null 表示不限制扩展名
    public String[] getExtensionsByType(@NonNull Machine.FileType type) {
        switch (type) {
            case CDROM:
                return new String[]{"iso", "bin", "img", "cue", "mdf", "nrg"};
            case FDA:
            case FDB:
                return new String[]{"ima", "img", "flp", "fd", "raw", "dsk"};
            case SD:
                return new String[]{"img", "raw"};
            case HDA:
            case HDB:
            case HDC:
            case HDD:
                return new String[]{"qcow2", "qcow", "img", "raw", "vmdk", "vdi", "vhd", "vhdx", "vpc"};
            case IMPORT_FILE:
                return new String[]{"csv"};
            case IMPORT_BIOS_FILE:
                return new String[]{"bin", "fd", "rom", "bios"};
            case KERNEL:
            case INITRD:
            case EXPORT_DIR:
            case IMAGE_DIR:
            case LOG_DIR:
            case SHARED_DIR:
            default:
                return null;
        }
    }
}
