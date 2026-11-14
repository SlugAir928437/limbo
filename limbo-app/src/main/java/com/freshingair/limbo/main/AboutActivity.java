package com.freshingair.limbo.main;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.freshingair.limbo.R;
import com.freshingair.limbo.files.FileUtils;
import com.freshingair.limbo.toast.ToastUtils;
import com.freshingair.limbo.updates.UpdateChecker;

import java.io.IOException;

import io.noties.markwon.Markwon;

/**
 * 关于页：应用图标/名称/版本号、当前版本更新日志，以及 QQ 群、GitHub 源码、
 * 检查更新几个入口。原先散落在主菜单里的「更新日志」和启动时的自动检查更新
 * 收敛到这里。
 */
public class AboutActivity extends AppCompatActivity {
    /** QEMU 虚拟机镜像交流 1 群（1046981534）的加群 key，由 QQ 群官网生成。 */
    private static final String QQ_GROUP_KEY = "xvUBuXfg1GcN15hsztOMlibQa0Ubt17-";
    private static final String SOURCE_URL = "https://github.com/SlugAir928437/limbo";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);

        findViewById(R.id.about_back).setOnClickListener(v -> finish());

        ((TextView) findViewById(R.id.about_app_name)).setText(R.string.app_name);
        String version = LimboApplication.getLimboVersionString();
        ((TextView) findViewById(R.id.about_version)).setText(
                getString(R.string.about_version, version == null ? "" : version));

        TextView changelogView = findViewById(R.id.about_changelog);
        try {
            changelogView.setText(Markwon.create(this)
                    .toMarkdown(FileUtils.LoadFile(this, "Changelog.md", false)));
        } catch (IOException e) {
            changelogView.setText(R.string.about_changelog_unavailable);
        }

        findViewById(R.id.about_qq_group).setOnClickListener(v -> {
            if (!joinQQGroup(QQ_GROUP_KEY))
                ToastUtils.toastShort(this, getString(R.string.about_qq_not_installed));
        });
        findViewById(R.id.about_github).setOnClickListener(
                v -> LimboActivityCommon.goToURL(this, SOURCE_URL));
        findViewById(R.id.about_check_update).setOnClickListener(
                v -> new Thread(() -> UpdateChecker.checkNewVersion(this, true)).start());
    }

    /**
     * 发起添加群流程：QEMU虚拟机镜像交流1群(1046981534)。
     *
     * @param key 由 QQ 群官网生成的 key
     * @return true 表示呼起手 Q 成功，false 表示未安装或版本不支持
     */
    public boolean joinQQGroup(String key) {
        Intent intent = new Intent();
        intent.setData(Uri.parse("mqqopensdkapi://bizAgent/qm/qr?url=http%3A%2F%2Fqm.qq.com"
                + "%2Fcgi-bin%2Fqm%2Fqr%3Ffrom%3Dapp%26p%3Dandroid%26jump_from%3Dwebapi%26k%3D"
                + key));
        try {
            startActivity(intent);
            return true;
        } catch (Exception e) {
            // 未安装手 Q 或安装的版本不支持
            return false;
        }
    }
}
