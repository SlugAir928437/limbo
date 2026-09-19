package io.github.zeroaicy.util.crash;


import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.HorizontalScrollView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.graphics.*;
import java.io.*;
import android.widget.*;
import android.os.*;
import android.net.*;
import java.text.*;
import java.util.*;
import android.content.*;
import android.view.*;
import android.provider.*;
import io.github.zeroaicy.util.*;
import android.text.*;
import android.media.*;
import android.view.PixelCopy.*;
//import android.support.v4.content.*;
import android.content.pm.*;

public class CrashActivity extends Activity {

	private static String log;
	public ScrollView mScrollView;
	public HorizontalScrollView mHorizontalScrollView;
	private Uri uri;
	private Uri imageUri;
	private Bitmap bitmapx;

	public static String getErrorLog( ) {
		return log;
	}
	@Override
	protected void onCreate( Bundle savedInstanceState ) {
		setTheme(android.R.style.Theme_Material_Light);
		setTitle("CrashActivity");
		super.onCreate(savedInstanceState);

		 mScrollView=new ScrollView(this);
		 mHorizontalScrollView = new HorizontalScrollView(this);
		TextView mMessage = new TextView(this);

		mHorizontalScrollView.addView(mMessage);
		mScrollView.addView(mHorizontalScrollView, -1, -1);

		setContentView(mScrollView);

		log = getIntent().getStringExtra(CrashApphandler.CrashActivityKey);
		mMessage.setText(log);
		mMessage.setTextIsSelectable(true);

		int padding = 24;
		mMessage.setPadding(padding, padding, padding, padding);

		mScrollView.setFillViewport(true);
	}

	@Override
	public void onBackPressed( ) {
		reset();
	}

	@Override
	public boolean onCreateOptionsMenu( Menu menu ) {
		menu.add(android.R.string.copy).setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener(){
				@Override
				public boolean onMenuItemClick( MenuItem p1 ) {
					copyText(log);
					return false;
				}
			}).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);

		menu.add("重启").setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener(){

				@Override
				public boolean onMenuItemClick( MenuItem p1 ) {
					reset();
					return false;
				}

			}).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
			/** //如果使用 请取消注释
		menu.add("分享").setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener(){

				@Override
				public boolean onMenuItemClick( MenuItem p1 ) {
					generateAndShareImage();
					return false;
				}

			}).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
			/***/

		return super.onCreateOptionsMenu(menu);
	}

	private void reset( ) {
		Intent intent = getPackageManager().getLaunchIntentForPackage(getPackageName());
		intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		startActivity(intent);
		finish();
		android.os.Process.killProcess(android.os.Process.myPid());
		System.exit(-1);
    }

	
	private void copyText( String msg ) {
		final android.content.ClipboardManager clipboardManager = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
		final android.content.ClipData clipData = android.content.ClipData
			.newPlainText(getPackageName(), msg);
		clipboardManager.setPrimaryClip(clipData);
	}
	
	//---++++++---//
	// 截图并分享 沐雨酆臻_myfz2002
	private void generateAndShareImage() {
		mScrollView.post(new Runnable() {
			@Override
			public void run() {

				try {
					//有写读权限时调用分享
					bitmapx = createScrollViewBitmap(mScrollView);
					imageUri = saveBitmapViaMediaStore(bitmapx);
					shareImage(imageUri);
				} catch (Exception e) {
					//当用户不允许写读权限时(有可能未能触发权限申请就崩溃)
					e.printStackTrace();
					Log.e("CrashActivity", "生成失败: " + e.getMessage());
					// 设置文件名和类型
					String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
					String fileName = "IMG_" + timestamp + "_myfz.png";
					// 回退到私有目录保存
					File privateDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
					if (privateDir == null) {
						privateDir = getFilesDir();
					}
					File outputFile = new File(privateDir, fileName);
					Log.d("CrashActivity", outputFile);
					try {
						FileOutputStream out = new FileOutputStream(outputFile);
						if (!bitmapx.compress(Bitmap.CompressFormat.PNG, 100, out)) {
							throw new IOException("私有存储压缩图片失败");
						}
						out.flush();
						uri = Uri.fromFile(outputFile);

						//shareImages(uri);
						//如果使用 请取消注释 下面的也一起
					} catch (IOException ex) {
						Log.e("SaveBitmap", "私有存储保存失败", ex);
					}
				}
			}
		});
	}

	// 生成ScrollView完整截图（优化高度计算）
	private Bitmap createScrollViewBitmap(ScrollView scrollView) {
		scrollView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
		int widthSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
		int heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
		scrollView.measure(widthSpec, heightSpec);

		int maxContentWidth = calculateMaxContentWidth(scrollView);
		
		int totalHeight = calculateTextBasedHeight(scrollView);

		Bitmap bitmap = Bitmap.createBitmap(
			maxContentWidth,
			totalHeight,
			Bitmap.Config.ARGB_8888
		);
		Canvas canvas = new Canvas(bitmap);
		canvas.drawColor(Color.WHITE);
		
		expandAllViews(scrollView, maxContentWidth);
		
		try {
			scrollView.draw(canvas);
		} catch (Exception e) {
			Log.e("CrashActivity", "绘制失败: " + e.getMessage());
		}
		restoreViewStates(scrollView);
		return bitmap;
		
	}

	// 基于文本内容计算高度
	private int calculateTextBasedHeight(ViewGroup root) {
		int totalHeight = 0;
		for (int i = 0; i < root.getChildCount(); i++) {
			View child = root.getChildAt(i);
			if (child instanceof TextView) {
				totalHeight += calculateTextViewHeight((TextView) child);
			} else if (child instanceof ViewGroup) {
				totalHeight += calculateTextBasedHeight((ViewGroup) child);
			}
			totalHeight += child.getPaddingTop() + child.getPaddingBottom();
		}
		return totalHeight;
	}
	//计算文本高度
	private int calculateTextViewHeight(TextView textView) {
    	int textAlignment = textView.getTextAlignment();
  	    Layout.Alignment alignment = Layout.Alignment.ALIGN_NORMAL;
		switch (textAlignment) {
      	  case View.TEXT_ALIGNMENT_CENTER:
			alignment = Layout.Alignment.ALIGN_CENTER;
			break;
      	  case View.TEXT_ALIGNMENT_TEXT_END:
			alignment = Layout.Alignment.ALIGN_OPPOSITE;
			break;
      	  case View.TEXT_ALIGNMENT_VIEW_END:
			alignment = Layout.Alignment.ALIGN_OPPOSITE;
            break;
		}
		StaticLayout.Builder builder = StaticLayout.Builder.obtain(
			textView.getText(),
			0,
			textView.getText().length(),
        textView.getPaint(),
        textView.getWidth()
		)
		.setAlignment(alignment)
		.setLineSpacing(textView.getLineSpacingExtra(), textView.getLineSpacingMultiplier())
 	    .setIncludePad(textView.getIncludeFontPadding());

 	    StaticLayout layout = builder.build();
 	    return layout.getHeight();
	}

	// 递归计算最大内容宽度
	private int calculateMaxContentWidth(ViewGroup viewGroup) {
		int maxWidth = 0;
		for (int i = 0; i < viewGroup.getChildCount(); i++) {
			View child = viewGroup.getChildAt(i);
			if (child instanceof HorizontalScrollView) {
				// 获取横向滚动内容的实际宽度
				HorizontalScrollView hsv = (HorizontalScrollView) child;
				View content = hsv.getChildAt(0);
				maxWidth = Math.max(maxWidth, content.getWidth());
			} else if (child instanceof ViewGroup) {
				// 递归处理嵌套布局
				maxWidth = Math.max(maxWidth, calculateMaxContentWidth((ViewGroup) child));
			} else {
				maxWidth = Math.max(maxWidth, child.getWidth());
			}
		}
		return maxWidth;
	}


	// 递归计算总高度
	private int calculateTotalHeight(ViewGroup viewGroup) {
		int totalHeight = 0;
		for (int i = 0; i < viewGroup.getChildCount(); i++) {
			View child = viewGroup.getChildAt(i);
			totalHeight += child.getHeight() + child.getPaddingTop() + child.getPaddingBottom();
			if (child instanceof ViewGroup) {
				totalHeight += calculateTotalHeight((ViewGroup) child);
			}
		}
		return totalHeight;
	}


	// 强制展开所有子视图
	private void expandAllViews(ViewGroup viewGroup, int maxWidth) {
		for (int i = 0; i < viewGroup.getChildCount(); i++) {
			View child = viewGroup.getChildAt(i);
			ViewGroup.LayoutParams params = child.getLayoutParams();
			
			if (params != null) {
				params.width = maxWidth;
				child.setLayoutParams(params);
			}

			if (child instanceof ViewGroup) {
				expandAllViews((ViewGroup) child, maxWidth);
			}
		}
		viewGroup.requestLayout();
		viewGroup.layout(0, 0, maxWidth, viewGroup.getHeight());
	}

	// 恢复视图原始状态
	private void restoreViewStates(ViewGroup viewGroup) {
		for (int i = 0; i < viewGroup.getChildCount(); i++) {
			View child = viewGroup.getChildAt(i);
			if (child instanceof ViewGroup) {
				restoreViewStates((ViewGroup) child);
			}
		}
		viewGroup.requestLayout();
	}

	// 通过MediaStore保存图片
	private Uri saveBitmapViaMediaStore(Bitmap bitmap) throws IOException {
		
		ContentResolver resolver = getContentResolver();
		ContentValues values = new ContentValues();
		values.put(MediaStore.Images.Media.DISPLAY_NAME, "IMG_" + 
				   new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date()) + "_myfz.png");
		values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
		values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/CrashImg");

		Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
		if (uri == null) throw new IOException("创建文件失败");

		try (OutputStream out = resolver.openOutputStream(uri)) {
			if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
				throw new IOException("压缩图片失败");
			}
		}
		return uri;
	}

	//公共目录分享
	private void shareImage(Uri imageUri) {
		Intent shareIntent = new Intent(Intent.ACTION_SEND)
			.setType("image/png")
			.putExtra(Intent.EXTRA_STREAM, imageUri)
			.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

		startActivity(Intent.createChooser(shareIntent, "分享图片"));
	}
	/**
	//如果使用 请取消注释
	//私有目录分享
	private void shareImages(Uri imageFile) {
		Intent shareIntent = new Intent(Intent.ACTION_SEND);
		shareIntent.setType("image/png");
		File imageFiles=new File(imageFile.getPath());

		// 使用FileProvider获取URI
		Uri contentUri = FileProvider.getUriForFile(this, 
													getPackageName() + ".fileprovider", 
													imageFiles
													);
		// 分享文件时添加完整权限授予
		shareIntent.putExtra(Intent.EXTRA_STREAM, contentUri);
		shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | 
							 Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);

        // 动态授予所有接收应用权限 可有可无
		List<ResolveInfo> resInfo = getPackageManager().queryIntentActivities(
			shareIntent, PackageManager.MATCH_DEFAULT_ONLY);
		for (ResolveInfo info : resInfo) {
			grantUriPermission(
				info.activityInfo.packageName,
				contentUri,
				Intent.FLAG_GRANT_READ_URI_PERMISSION
			);
		}
		
		try{
		    startActivity(Intent.createChooser(shareIntent, "分享图片"));
		}catch (Exception e) {
			Log.e("CRASH_REPORT", "启动分享失败", e);
		}
	}/***/
//---++++++---//

}
