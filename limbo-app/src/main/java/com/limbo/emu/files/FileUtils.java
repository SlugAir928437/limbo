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
package com.limbo.emu.files;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.webkit.MimeTypeMap;

import androidx.annotation.NonNull;
import androidx.documentfile.provider.DocumentFile;

import com.limbo.emu.R;
import com.limbo.emu.dialog.DialogUtils;
import com.limbo.emu.machine.Machine.FileType;
import com.limbo.emu.main.Config;
import com.limbo.emu.main.LimboApplication;
import com.limbo.emu.main.LimboSettingsManager;
import com.limbo.emu.toast.ToastUtils;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * @author dev
 */
public class FileUtils {
    private final static String TAG = "FileUtils";
    private static final Object fdsLock = new Object();
    private static HashMap<Integer, FileInfo> fds = new HashMap<>();

    public static String getNativeLibDir(Context context) {
        return context.getApplicationInfo().nativeLibraryDir;
    }

    /**
     * 返回当前应该从哪个位置加载 native 库（包括 QEMU 引擎）。
     *
     * <p>优先用 APK 自己那份：现代打包（extractNativeLibs=false /
     * useLegacyPackaging=false）会把库以未压缩、页对齐的形式留在 APK 里，链接器
     * 可以直接 mmap 加载，形如
     * {@code /data/app/.../base.apk!/lib/arm64-v8a}。反过来，
     * {@code nativeLibraryDir}（{@code /data/app/.../lib/&lt;abi&gt;}）只保证“目录”
     * 存在：新装时它是空的；但**旧版本安装时解压出来的库可能还留在那个目录里**，
     * 一旦优先使用它，就会拿旧引擎去跑新代码 —— 例如旧引擎没有 AGL 后端，App 一
     * 发 {@code -display agl}，QEMU 就以
     * {@code Parameter 'type' does not accept value 'agl'} 退出。
     *
     * <p>只有 APK 里的库是压缩存放（老式 extractNativeLibs=true，链接器无法从 APK
     * 内加载）时，才退回 {@code nativeLibraryDir}。
     */
    public static String getNativeLibSearchDir(Context context) {
        if (context == null) {
            return null;
        }
        String apkLibDir = apkLibDir(context);
        if (apkLibDir != null && apkShipsUncompressedLibs(context)) {
            Log.d(TAG, "Using native libs from the APK: " + apkLibDir);
            return apkLibDir;
        }
        String nativeLibDir = context.getApplicationInfo().nativeLibraryDir;
        if (nativeLibDir != null) {
            Log.d(TAG, "Using native libs from " + nativeLibDir
                    + (hasExtractedNativeLibs(nativeLibDir) ? "" : " (no extracted .so there yet)"));
            return nativeLibDir;
        }
        return apkLibDir;
    }

    /** APK 内的 native 库目录，形如 {@code /data/app/.../base.apk!/lib/arm64-v8a}。 */
    public static String apkLibDir(Context context) {
        if (context == null) {
            return null;
        }
        return context.getApplicationInfo().sourceDir + "!/lib/" + currentAbi();
    }

    /** 设备首选 ABI（与 APK 打包时用的 abiFilters 一致）。 */
    @NonNull
    private static String currentAbi() {
        String[] supportedAbis = Build.SUPPORTED_ABIS;
        if (supportedAbis != null && supportedAbis.length > 0 && supportedAbis[0] != null
                && !supportedAbis[0].isEmpty()) {
            return supportedAbis[0];
        }
        return "arm64-v8a";
    }

    /** 缓存：APK 内是否带未压缩的 native 库（按 APK 路径缓存，换 APK 会重新判一次）。 */
    private static String uncompressedLibsApk;
    private static boolean uncompressedLibs;

    /**
     * APK 的 {@code lib/<abi>/} 下有没有 **未压缩（STORED）** 的 .so。
     *
     * <p>现代打包（extractNativeLibs=false）就是这种：库未压缩、页对齐，可以直接
     * 从 APK 里 mmap 加载。老式打包（extractNativeLibs=true）则会把库压缩进 APK，
     * 这时只能靠安装时解压出来的副本，必须退回 {@code nativeLibraryDir}。
     */
    private static synchronized boolean apkShipsUncompressedLibs(Context context) {
        String apkPath = context.getApplicationInfo().sourceDir;
        if (apkPath == null) {
            return false;
        }
        if (apkPath.equals(uncompressedLibsApk)) {
            return uncompressedLibs;
        }
        boolean result = false;
        String prefix = "lib/" + currentAbi() + "/";
        try (ZipFile apk = new ZipFile(apkPath)) {
            java.util.Enumeration<? extends ZipEntry> entries = apk.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name != null && name.startsWith(prefix) && name.endsWith(".so")) {
                    // 只看一个条目即可：同一个 APK 的打包方式是一致的。
                    result = entry.getMethod() == ZipEntry.STORED;
                    break;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Could not inspect native libs in " + apkPath, t);
        }
        uncompressedLibsApk = apkPath;
        uncompressedLibs = result;
        return result;
    }

    /**
     * 按 {@link #getNativeLibSearchDir} 的同一优先级打开某个 native 库。
     *
     * <p>AGL 后端探测要用它：探测的必须是虚拟机真正会加载的那一份，否则会出现
     * “探测说没有、其实有”或者反过来的误导。返回 null 表示两边都没有该库
     * （调用方自行决定怎么处理）；调用方负责关闭返回的流。
     */
    public static InputStream openNativeLib(Context context, String libName) throws IOException {
        String searchDir = getNativeLibSearchDir(context);
        if (searchDir == null || libName == null) {
            return null;
        }
        int bang = searchDir.indexOf("!/");
        if (bang < 0) {
            File file = new File(searchDir, libName);
            return file.isFile() ? new FileInputStream(file) : null;
        }
        String apkPath = searchDir.substring(0, bang);
        String entryName = searchDir.substring(bang + 2) + "/" + libName;
        final ZipFile apk = new ZipFile(apkPath);
        ZipEntry entry = apk.getEntry(entryName);
        if (entry == null) {
            apk.close();
            return null;
        }
        // 流的生命周期由调用方控制：关流时连带把 ZipFile 关掉。
        return new FilterInputStream(apk.getInputStream(entry)) {
            @Override
            public void close() throws IOException {
                try {
                    super.close();
                } finally {
                    apk.close();
                }
            }
        };
    }

    /**
     * 判断 {@code nativeLibraryDir} 里是否真的躺着解压出来的 native 库。
     *
     * <p>{@code extractNativeLibs=false}（现代 Android 的默认打包方式）时，
     * installd 仍然会把 {@code /data/app/.../lib/&lt;abi&gt;} 这个目录建出来，
     * 只是里面**一个文件都没有** —— 库全部以未压缩、页对齐的形式留在 APK 内。
     * 所以「目录存在」远不等于「目录里有库」：只看 {@code isDirectory()} 会把
     * 应用引导到那个空目录上，随后 {@code new File(dir, "libX.so").exists()}
     * 一律为 false（root 子进程就是这样报
     * {@code UnsatisfiedLinkError: Missing libcompat-limbo.so} 的）。
     *
     * <p>这里用 {@code isFile()} 而不是 {@code exists()}：某些实现会在该目录里放
     * 指向 APK 内部的悬空符号链接，{@code exists()} 对悬空链接同样返回 false，
     * 而 {@code isFile()}（跟随符号链接后要求是普通文件）判断更严格。
     *
     * @param nativeLibDir {@code ApplicationInfo#nativeLibraryDir}
     * @return 目录里存在真正的 .so 文件时返回 true
     */
    private static boolean hasExtractedNativeLibs(String nativeLibDir) {
        File dir = new File(nativeLibDir);
        if (!dir.isDirectory()) {
            return false;
        }
        String[] entries;
        try {
            entries = dir.list();
        } catch (SecurityException e) {
            return false;
        }
        if (entries == null) {
            return false;
        }
        for (String entry : entries) {
            if (entry != null && entry.endsWith(".so") && new File(dir, entry).isFile()) {
                return true;
            }
        }
        return false;
    }

    public static String getFullPathFromDocumentFilePath(String filePath) {

        filePath = filePath.replaceAll("%3A", "^3A");
        int index = filePath.lastIndexOf("^3A");
        if (index > 0)
            filePath = filePath.substring(index + 3);
        if (!filePath.startsWith("/"))
            filePath = "/" + filePath;

        //remove any spaces encoded by the ASF
        try {
            filePath = URLDecoder.decode(filePath, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }

        return filePath;
    }

    public static String getFilenameFromPath(String filePath) {
        filePath = filePath.replaceAll("%2F", "/");
        filePath = filePath.replaceAll("%3A", "/");
        filePath = filePath.replaceAll("\\^2F", "/");
        filePath = filePath.replaceAll("\\^3A", "/");


        int index = filePath.lastIndexOf("/");
        if (index > 0)
            return filePath.substring(index + 1);
        return filePath;
    }

    public static String decodeDocumentFilePath(String filePath) {
        if (filePath != null && filePath.startsWith("/content//")) {
            filePath = filePath.replace("/content//", "content://");
            filePath = filePath.replaceAll("\\^\\^\\^", "%");
        }
        return filePath;
    }

    /**
     * Encode the path % chars to ^
     *
     * @param filePath
     * @return
     */
    public static String encodeDocumentFilePath(String filePath) {
        if (filePath != null && filePath.startsWith("content://")) {
            filePath = filePath.replace("content://", "/content//");
            filePath = filePath.replaceAll("%", "\\^\\^\\^");
            ;

        }
        return filePath;
    }

    public static void saveFileContents(String filePath, String contents) {
        // TODO: we assume that the contents are of small size so we keep in an array
        byteArrayToFile(contents.getBytes(), new File(filePath));
    }

    public static void byteArrayToFile(byte[] byteData, File filePath) {

        try {
            FileOutputStream fos = new FileOutputStream(filePath);
            fos.write(byteData);
            fos.close();

        } catch (FileNotFoundException ex) {
            System.out.println("FileNotFoundException : " + ex);
        } catch (IOException ioe) {
            System.out.println("IOException : " + ioe);
        }

    }

    public static InputStream getStreamFromFilePath(String filePath) throws FileNotFoundException {
        if (filePath.startsWith("content://")) {
            Uri uri = Uri.parse(filePath);
            String mode = "rw";
            ParcelFileDescriptor pfd = LimboApplication.getInstance().getContentResolver().openFileDescriptor(uri, mode);
            return new FileInputStream(pfd.getFileDescriptor());
        } else {
            return new FileInputStream(filePath);
        }
    }

    public static void closeFileDescriptor(String filePath) throws IOException {
        if (filePath.startsWith("content://")) {
            Uri uri = Uri.parse(filePath);
            String mode = "rw";
            ParcelFileDescriptor pfd = LimboApplication.getInstance().getContentResolver().openFileDescriptor(uri, mode);
            pfd.close();
        }
    }

    public static String getFileContents(String filePath) {
        File file = new File(filePath);
        if (!file.exists())
            return "";
        StringBuilder builder = new StringBuilder("");
        try {
            FileInputStream stream = new FileInputStream(file);
            byte[] buff = new byte[32768];
            int bytesRead = 0;
            while ((bytesRead = stream.read(buff, 0, buff.length)) > 0) {
                //FIXME: log file can be too large appending might fail with out of memory
                builder.append(new String(buff, 0, bytesRead));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        String contents = builder.toString();
        return contents;
    }

    public static boolean fileValid(String path) {
        if (path == null || path.isEmpty())
            return true;
        if (path.startsWith("content://") || path.startsWith("/content/")) {
            int fd = get_fd(path);
            if (fd <= 0)
                return false;
        } else {
            File file = new File(path);
            file.setWritable(true);
            return file.exists();
        }
        return true;
    }

    //TODO: we should pass the modes from the backend and translate them
    // instead of blindly using "rw". ie ISOs should be read only.
    public static int get_fd(String path) {
        synchronized (fdsLock) {
            int fd = 0;
            if (path == null)
                return 0;
            if (path.startsWith("/content//") || path.startsWith("content://")) {
                String npath = decodeDocumentFilePath(path);
                try {
                    Uri uri = Uri.parse(npath);
                    String mode = "rw";
                    if (path.toLowerCase().endsWith(".iso"))
                        mode = "r";
                    ParcelFileDescriptor pfd = LimboApplication.getInstance().getContentResolver().openFileDescriptor(uri, mode);
                    fd = pfd.getFd();
                    fds.put(fd, new FileInfo(path, npath, pfd));
                    Log.d(TAG, "Opening Content Uri: " + npath + ", FD: " + fd);
                } catch (Exception e) {
                    String msg = LimboApplication.getInstance().getString(R.string.CouldNotOpenDocFile) + " "
                            + FileUtils.getFullPathFromDocumentFilePath(npath)
                            + "\n" + LimboApplication.getInstance().getString(R.string.PleaseReassingYourDiskFiles);
                    ToastUtils.toastLong(LimboApplication.getInstance(), msg);
                    e.printStackTrace();
                }
            } else {
                try {
                    int mode = ParcelFileDescriptor.MODE_READ_WRITE;
                    if (path.toLowerCase().endsWith(".iso"))
                        mode = ParcelFileDescriptor.MODE_READ_ONLY;
                    File file = new File(path);
                    if (!file.exists())
                        file.createNewFile();
                    ParcelFileDescriptor pfd = ParcelFileDescriptor.open(file, mode);
                    fd = pfd.getFd();
                    fds.put(fd, new FileInfo(path, path, pfd));
                    Log.d(TAG, "Opening File: " + path + ", FD: " + fd);
                } catch (Exception e) {
                    Log.e(TAG, "Could not open File: " + path + ", FD: " + fd);
                    if (Config.debug)
                        e.printStackTrace();
                }
            }
            return fd;
        }
    }

    public static void close_fds() {
        synchronized (fds) {
            Integer[] fds = FileUtils.fds.keySet().toArray(new Integer[FileUtils.fds.keySet().size()]);
            for (int i = 0; i < fds.length; i++) {
                FileUtils.close_fd(fds[i]);
            }
        }
    }

    /**
     * Closing File Descriptors
     *
     * @param fd File Descriptro to be closed
     * @return Returns 0 if fd is closed successfully otherwise -1
     */
    public static int close_fd(int fd) {
        if (!Config.closeFileDescriptors) {
            return 0;
        }
        synchronized (fds) {
            if (FileUtils.fds.containsKey(fd)) {
                FileInfo info = FileUtils.fds.get(fd);
                try {
                    ParcelFileDescriptor pfd = info.pfd;
                    if(Config.syncFilesOnClose) {
                        try {
                            pfd.getFileDescriptor().sync();
                        } catch (IOException e) {
                            if (Config.debug) {
                                Log.w(TAG, "Syncing DocumentFile: " + info.path + ": " + fd + " : " + e);
                                e.printStackTrace();
                            }
                        }
                    }
                    pfd.close();
                    FileUtils.fds.remove(fd);
                    return 0;
                } catch (IOException e) {
                    Log.e(TAG, "Error Closing DocumentFile: " + info.path + ": " + fd + " : " + e);
                    if (Config.debug)
                        e.printStackTrace();
                }
            } else {
                ParcelFileDescriptor pfd = null;

                try {
                    String path = "";
                    FileInfo info = FileUtils.fds.get(fd);
                    if (info != null) {
                        pfd = info.pfd;
                        path = info.path;
                    }
                    if (pfd == null)
                        pfd = ParcelFileDescriptor.fromFd(fd);
                    if(Config.syncFilesOnClose) {
                        try {
                            pfd.getFileDescriptor().sync();
                        } catch (IOException e) {
                            if (Config.debug) {
                                Log.w(TAG, "Error Syncing File: " + path + ": " + fd + " : " + e);
                                e.printStackTrace();
                            }
                        }
                    }
                    pfd.close();
                    return 0;
                } catch (Exception e) {
                    Log.e(TAG, "Error Closing File FD: " + fd + " : " + e);
                    if (Config.debug)
                        e.printStackTrace();
                }
            }
            return -1;
        }
    }


    public static void startLogging() {
        if (Config.logFilePath == null) {
            Log.w(TAG, "Log file is not ready");
            return;
        }
        Thread t = new Thread(new Runnable() {
            public void run() {
                FileOutputStream os = null;
                File logFile = null;
                try {
                    logFile = new File(Config.logFilePath);
                    if (logFile.exists()) {
                        if (!logFile.delete()) {
                            Log.w(TAG, "Could not delete previous log file!");
                        }
                    }
                    logFile.createNewFile();
                    Runtime.getRuntime().exec("logcat -c");
                    Process process = Runtime.getRuntime().exec("logcat v main");
                    os = new FileOutputStream(logFile);
                    BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(process.getInputStream()));

                    StringBuilder log = new StringBuilder("");
                    String line = "";
                    while ((line = bufferedReader.readLine()) != null) {
                        log.setLength(0);
                        log.append(line).append("\n");
                        os.write(log.toString().getBytes("UTF-8"));
                        os.flush();
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                } finally {
                    try {
                        if (os != null) {
                            os.flush();
                            os.close();
                        }
                    } catch (IOException e) {

                        e.printStackTrace();
                    }

                }

            }
        });
        t.setName("LimboLogger");
        t.start();
    }

    public static String LoadFile(Context context, String fileName, boolean loadFromRawFolder) throws IOException {
        // Create a InputStream to read the file into
        InputStream iS;
        if (loadFromRawFolder) {
            // get the resource id from the file name
            int rID = context.getResources().getIdentifier(LimboApplication.getInstance().getClass().getPackage().getName() + ":raw/" + fileName,
                    null, null);
            // get the file as a stream
            iS = context.getResources().openRawResource(rID);
        } else {
            // get the file as a stream
            iS = context.getResources().getAssets().open(fileName);
        }

        ByteArrayOutputStream oS = new ByteArrayOutputStream();
        byte[] buffer = new byte[iS.available()];
        int bytesRead = 0;
        while ((bytesRead = iS.read(buffer)) > 0) {
            oS.write(buffer);
        }
        oS.close();
        iS.close();

        // return the output stream as a String
        return oS.toString();
    }

    public static String getExtensionFromFilename(String fileName) {
        if (fileName == null)
            return "";

        int index = fileName.lastIndexOf(".");
        if (index >= 0) {
            return fileName.substring(index + 1);
        } else
            return "";
    }

    public static int getIconForFile(String file) {
        file = file.toLowerCase();
        String ext = FileUtils.getExtensionFromFilename(file).toLowerCase();

        if (ext.equals("img") || ext.equals("qcow")
                || ext.equals("qcow2") || ext.equals("vmdk") || ext.equals("vdi") || ext.equals("cow")
                || ext.equals("dmg") || ext.equals("bochs") || ext.equals("vpc")
                || ext.equals("vhd") || ext.equals("fs"))
            return R.drawable.harddisk;
        else if (ext.equals("iso"))
            return R.drawable.cd;
        else if (ext.equals("ima"))
            return R.drawable.floppy;
        else if (ext.equals("csv"))
            return R.drawable.importvms;
        else if (file.contains("kernel") || file.contains("vmlinuz") || file.contains("initrd"))
            return R.drawable.sysfile;
        else
            return R.drawable.close;

    }


    public static Uri saveLogFileSDCard(Activity activity, Uri destDir) {
        DocumentFile destFileF;
        OutputStream os = null;
        Uri uri = null;

        try {
            String logFileContents = getFileContents(Config.logFilePath);
            DocumentFile dir = DocumentFile.fromTreeUri(activity, destDir);
            if (dir == null)
                throw new Exception("Could not get log path directory");

            //Create the file if doesn't exist
            destFileF = dir.findFile(Config.destLogFilename);
            if (destFileF == null) {
                destFileF = dir.createFile(MimeTypeMap.getSingleton().getMimeTypeFromExtension("txt"), Config.destLogFilename);
            }

            //Write to the dest
            os = activity.getContentResolver().openOutputStream(destFileF.getUri());
            os.write(logFileContents.getBytes());

            //success
            uri = destFileF.getUri();

        } catch (Exception ex) {
            ToastUtils.toastShort(activity, activity.getString(R.string.FailedToSaveLogFile) + ex.getMessage());
        } finally {
            if (os != null) {
                try {
                    os.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }

        }
        return uri;
    }

    public static Uri exportFileContents(Activity activity,
                                         Uri destDir, String destFile, byte[] contents) {

        DocumentFile destFileF = null;
        OutputStream os = null;
        Uri uri = null;

        try {
            DocumentFile dir = DocumentFile.fromTreeUri(activity, destDir);

            //Create the file if doesn't exist
            destFileF = dir.findFile(destFile);
            if (destFileF == null) {
                destFileF = dir.createFile(MimeTypeMap.getSingleton().getMimeTypeFromExtension("csv"), destFile);
            } else {
                ToastUtils.toastShort(activity, activity.getString(R.string.FileExistsChooseAnotherFilename));
                return null;
            }

            //Write to the dest
            os = activity.getContentResolver().openOutputStream(destFileF.getUri());
            os.write(contents);

            //success
            uri = destFileF.getUri();

        } catch (Exception ex) {
            ToastUtils.toastShort(activity, activity.getString(R.string.FailedToExportFile) +": " + destFile + ", " + activity.getString(R.string.Error) + ":" + ex.getMessage());
        } finally {
            if (os != null) {
                try {
                    os.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }

        }
        return uri;
    }

    public static String saveLogFileLegacy(Activity activity,
                                           String destLogFilePath) {

        String filePath = null;
        File destFileF = new File(destLogFilePath, Config.destLogFilename);

        try {
            String logFileContents = getFileContents(Config.logFilePath);
            FileUtils.saveFileContents(destFileF.getAbsolutePath(), logFileContents);

            //success
            filePath = destFileF.getAbsolutePath();

        } catch (Exception ex) {
            ToastUtils.toastShort(activity, activity.getString(R.string.FailedSaveLogFile) + ": " + destFileF.getAbsolutePath() + ", " + activity.getString(R.string.Error)+ ": " + ex.getMessage());
        } finally {
        }
        return filePath;
    }


    @SuppressLint("WrongConstant")
    public static String getFileUriFromIntent(Activity activity, Intent data, boolean write) {
        if (data == null)
            return null;

        Uri uri = data.getData();
        DocumentFile pickedFile = DocumentFile.fromSingleUri(activity, uri);
        String file = uri.toString();
        if (!file.contains("com.android.externalstorage.documents")) {
            showFileNotSupported(activity);
            return null;
        }
        activity.grantUriPermission(activity.getPackageName(), uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (write)
            activity.grantUriPermission(activity.getPackageName(), uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        activity.grantUriPermission(activity.getPackageName(), uri, Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);

        int takeFlags = data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
        if (write)
            takeFlags = takeFlags | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;

        activity.getContentResolver().takePersistableUriPermission(uri, takeFlags);
        return file;
    }

    public static String getDirPathFromIntent(Activity activity, Intent data) {
        if (data == null)
            return null;
        Bundle b = data.getExtras();
        String file = b.getString("currDir");
        return file;
    }


    public static String getFilePathFromIntent(Activity activity, Intent data) {
        if (data == null)
            return null;
        Bundle b = data.getExtras();
        String file = b.getString("file");
        return file;
    }

    public static FileType getFileTypeFromIntent(Activity activity, Intent data) {
        if (data == null)
            return null;
        Bundle b = data.getExtras();
        FileType fileType = (FileType) b.getSerializable("fileType");
        return fileType;
    }

    public static void showFileNotSupported(Activity context) {
        DialogUtils.UIAlert(context, context.getString(R.string.Error), context.getString(R.string.FilePathNotSupportedWarning));
    }

    public static void saveLogToFile(final Activity activity, final String logFileDestDir) {
        new Thread(new Runnable() {
            public void run() {

                String displayName = null;
                if (logFileDestDir.startsWith("content://")) {
                    Uri exportDirUri = Uri.parse(logFileDestDir);
                    Uri fileCreatedUri = FileUtils.saveLogFileSDCard(activity,
                            exportDirUri);
                    displayName = FileUtils.getFullPathFromDocumentFilePath(fileCreatedUri.toString());
                } else {
                    String filePath = FileUtils.saveLogFileLegacy(activity, logFileDestDir);
                    displayName = filePath;
                }

                if (displayName != null) {

                    ToastUtils.toastShort(activity, activity.getString(R.string.LogfileSaved));
                }
            }
        }).start();
    }

    public static String convertFilePath(String text, int position) {
        try {
            text = FileUtils.getFullPathFromDocumentFilePath(text);
        } catch (Exception ex) {
            if (Config.debug)
                ex.printStackTrace();
        }

        return text;
    }

    public static class FileInfo {
        public String path;
        public String npath;
        public ParcelFileDescriptor pfd;

        public FileInfo(String path, String npath, ParcelFileDescriptor pfd) {
            this.npath = npath;
            this.path = path;
            this.pfd = pfd;
        }
    }

    // Creates an empty raw disk image of the given size (bytes).
    public static String createRawImage(Context context, long sizeBytes, String destImage, FileType imgType) {
        String imagesDir = LimboSettingsManager.getImagesDir(context);
        if (imagesDir == null)
            return null;
        String displayName = null;
        String filePath = null;
        try {
            if (imagesDir.startsWith("content://")) {
                Uri imagesDirUri = Uri.parse(imagesDir);
                DocumentFile dir = DocumentFile.fromTreeUri(context, imagesDirUri);
                if (dir == null)
                    return null;
                DocumentFile file = dir.findFile(destImage);
                if (file == null) {
                    file = dir.createFile("application/octet-stream", destImage);
                } else {
                    ToastUtils.toastShort(context, context.getString(R.string.FileExistsChooseAnotherFilename));
                    return null;
                }
                if (file != null) {
                    if (android.os.Build.VERSION.SDK_INT >= 26) {
                        ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(file.getUri(), "rw");
                        if (pfd != null) {
                            FileOutputStream fos = new FileOutputStream(pfd.getFileDescriptor());
                            fos.getChannel().truncate(sizeBytes);
                            fos.close();
                            pfd.close();
                        }
                    }
                    filePath = file.getUri().toString();
                    displayName = FileUtils.getFullPathFromDocumentFilePath(filePath);
                }
            } else {
                File file = new File(imagesDir, destImage);
                if (file.exists()) {
                    ToastUtils.toastShort(context, context.getString(R.string.FileExistsChooseAnotherFilename));
                    return null;
                }
                java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "rw");
                raf.setLength(sizeBytes);
                raf.close();
                filePath = file.getAbsolutePath();
                displayName = filePath;
            }
        } catch (Exception ex) {
            Log.e(TAG, "failed to create raw image: " + destImage + ", Error:" + ex.getMessage());
            return null;
        }
        if (displayName != null) {
            ToastUtils.toastShort(context, context.getString(R.string.ImageCreated) + ": " + displayName);
            return filePath;
        }
        return null;
    }


    public static String downloadAndRead(String urlString, Context context) throws Exception {
        URL url = new URL(urlString);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(15000);

        try {
            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new Exception("下载失败: " + responseCode);
            }

            // 保存到缓存目录
            File file = new File(context.getCacheDir(), "downloaded_temp.txt");

            try (InputStream inputStream = connection.getInputStream();
                 FileOutputStream outputStream = new FileOutputStream(file)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                }
            }

            return readFileContent(file);

        } finally {
            connection.disconnect();
        }
    }

    @NonNull private static String readFileContent(File file) throws Exception {
        StringBuilder content = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new java.io.FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append("\n");
            }
        }
        return content.toString();
    }
}
