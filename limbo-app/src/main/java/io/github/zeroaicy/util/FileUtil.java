package io.github.zeroaicy.util;
import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileAttribute;
import java.util.ArrayList;
import java.util.List;
import java.util.Stack;
import java.util.function.Consumer;
import java.util.stream.Stream;

public class FileUtil {

	private static final String LogDir = "/CrashLog";

	public static String CrashLogPath;
	public static String LogCatPath;

	static {
		try {
			init();
		} catch (Throwable e) {
			// 解析日志目录失败绝不能再往上抛：这段代码在 Application 初始化期间就会被
			// 触发（CrashApplication -> DebugUtil -> FileUtil），异常会变成
			// ExceptionInInitializerError 让 App 完全起不来，连崩溃页都弹不出来。
			// 路径置空后 Log.enable(null) 会安全地关掉文件日志。
			e.printStackTrace();
			FileUtil.CrashLogPath = null;
			FileUtil.LogCatPath = null;
		}
	}

	private static void init() {
		FileUtil.CrashLogPath = getCrashLogPath(FileUtil.LogDir);

		FileUtil.LogCatPath = getLogCatPath(FileUtil.CrashLogPath);
	}

	public static String getLogCatPath(String crashLogPath) {

		if (crashLogPath == null) {
			return null;
		}
		StringBuilder logCatPathBuilder = new StringBuilder();
		logCatPathBuilder.append(crashLogPath);

		logCatPathBuilder.append(File.separator);

		String logcatFileName = ContextUtil.getProcessName();
		int colonIndex = logcatFileName.lastIndexOf(':');
		if (colonIndex > 0) {
			// 只用进程名
			logcatFileName = logcatFileName.substring(colonIndex + 1, logcatFileName.length());
		}
		logCatPathBuilder.append(logcatFileName);
		logCatPathBuilder.append(".txt");

		return logCatPathBuilder.toString();
	}

	private static String getCrashLogPath(String CrashDir) {

		File logRootDirectory = getLogRootDirectory();
		if (logRootDirectory == null) {
			return null;
		}
		String crashDir = logRootDirectory.getAbsolutePath() + CrashDir;
		return crashDir;
	}

	/**
	 * 取写日志用的根目录：优先外部储存（/内置储存器/Android/data/${PackageName}/cache），
	 * 外部储存不可用时退回内部 cache（/data/data/${PackageName}/cache）。
	 *
	 * <p>不能直接相信 getExternalCacheDir()：储存卷没准备好时它可能返回 null，
	 * 也可能返回指向无效卷的路径（曾出现 {@code /dev/null/Android/data/com.limbo.emu/cache}），
	 * 拼出来的日志文件打不开（open failed: ENOTDIR），而写日志失败会一路抛到
	 * Application 初始化里把 App 干掉。所以这里必须做兜底。
	 *
	 * @return 可用的目录；实在拿不到时返回 null
	 */
	private static File getLogRootDirectory() {
		try {
			Context context = ContextUtil.getContext();
			if (context == null) {
				return null;
			}
			try {
				File external = context.getExternalCacheDir();
				if (external != null) {
					return external;
				}
			} catch (Throwable e) {
				// 外部储存不可用，继续尝试内部目录
			}
			return context.getCacheDir();
		} catch (Throwable e) {
			e.printStackTrace();
			return null;
		}
	}

	public static List<String> Files2Strings(List<File> files) {
		List<String> strings = new ArrayList<String>();
		for (File file : files) {
			strings.add(file.getAbsolutePath());
		}
		return strings;
	}

	public static ArrayList<File> findJavaFile(String filePath) {
		return findFile(new File(filePath), ".java");
	}
	public static ArrayList<File> findJavaFile(File file) {
		return findFile(file, ".java");
	}

	//基于栈
	public static ArrayList<File> findFile(File mFile, String suffix) {
		ArrayList<File> mFiles = new ArrayList<File>();
		if (mFile.isFile()) {
			if (suffix == null) {
				mFiles.add(mFile);
			} else {
				String name = mFile.getName();
				if (name.endsWith(suffix) && !name.startsWith(".")) {
					mFiles.add(mFile);
				}
			}
			return mFiles;
		}

		if (mFile.isDirectory()) {
			Stack<File> list = new Stack<>();
			list.push(mFile);//进栈
			while (!list.isEmpty()) {
				File file = list.pop();//出栈
				File[] listFiles = file.listFiles();

				if (file != null && file.isDirectory() && listFiles != null) {
					for (File file_temp : listFiles) {

						if (file_temp.isDirectory()) {
							//过滤隐藏文件夹
							if (!file_temp.getName().startsWith(".")) {
								list.push(file_temp);//进栈
							}
						} else if (file_temp.isFile()) {
							if (suffix == null || (file_temp.getName().toLowerCase().endsWith(suffix))) {
								mFiles.add(file_temp);//进栈
							}
						}
					}
				}
			}
		}
		return mFiles;
	}

	public static void copy(final String source, final String target, final boolean isCover) {
		try {
			Path sourcePath = Paths.get(source);
			Stream<Path> walk = Files.walk(sourcePath);
			walk.forEach(new Consumer<Path>() {
					@Override
					public void accept(Path path) {
						try {
							Path path2 = Paths.get(path.toString().replace(source, target));
							if (Files.isDirectory(path) && !Files.exists(path2)) {
								Files.createDirectory(path2, new FileAttribute[0]);
							} else if (Files.isRegularFile(path)) {
								if (Files.exists(path2) && (!isCover || Files.getLastModifiedTime(path)
									.compareTo(Files.getLastModifiedTime(path2)) < 0)) {
									return;
								}
								Files.copy(path, path2, StandardCopyOption.REPLACE_EXISTING);
							}
						} catch (Exception e) {
							e.printStackTrace();
						}
					}
				});
		} catch (IOException e) {
		}
	}
	public static byte[] readAllBytes(InputStream inputStream) throws IOException {
		return readAllBytes(inputStream, true);
	}

	public static byte[] readAllBytes(InputStream inputStream, boolean autoClose) throws IOException {
		byte[] data = new byte[4096];
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		int count;
		while ((count = inputStream.read(data)) > 0) {
			baos.write(data, 0, count);
		}
		byte[] readAllBytes = baos.toByteArray();
		baos.close();
		if (autoClose) {
			inputStream.close();
		}
		return readAllBytes;
	}

	public static void deleteFolder(String folder) {
		deleteFolder(new File(folder));
	}

	public static void deleteFolder(File file) {
		deleteFolder(file, true);
	}

	public static void deleteFolder(File folder, boolean deleteRootDir) {
		if (folder.isFile()) {
			folder.delete();
		}
		File[] childFiles = folder.listFiles();

		if (childFiles != null) {
			for (File file : childFiles) {
				if (file.isDirectory()) {
					deleteFolder(file); // 递归删除子文件夹  
				} else {
					file.delete(); // 删除文件  
				}
			}
		}

		if (deleteRootDir) {
			// 删除空文件夹  
			folder.delete();
		}
	}

	public static void copyNotCover(String source, String target) {
		copy(source, target, false);
	}
}

