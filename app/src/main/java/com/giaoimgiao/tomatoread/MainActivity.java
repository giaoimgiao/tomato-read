package com.giaoimgiao.tomatoread;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TomatoRead v2.5.6 —— 模块设置界面
 * 功能: 番茄小说 智能朗读音色解锁 (本地书AI音色 / 在线书多角色97 / 流式TTS bookId修正)
 * 配置: /sdcard/Download/tomatoread.conf (enabled=1/0)
 * 更新: 启动时自动检查 GitHub Release, 有新版弹窗提示
 */
public class MainActivity extends Activity {

    private static final String CONF_PATH = "/sdcard/Download/tomatoread.conf";
    private static final String REPO = "giaoimgiao/tomato-read";
    private static final String GITHUB_API = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final String QQ = "3519425997";
    private static final String VERSION = "2.5.6"; // 与 build.gradle versionName 保持一致

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 32, 32, 32);
        sv.addView(root);

        TextView title = new TextView(this);
        title.setText("番茄Show 智能朗读音色解锁");
        title.setTextSize(20);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView ver = new TextView(this);
        ver.setText("当前版本: v" + VERSION);
        ver.setTextSize(14);
        ver.setGravity(Gravity.CENTER);
        ver.setPadding(0, 6, 0, 6);
        root.addView(ver);

        TextView info = new TextView(this);
        info.setText("功能:\n" +
                "  · 本地书 AI/离线音色 UI 展示与播放放行\n" +
                "  · 在线书 多角色对话升级版 (TTS书注入)\n" +
                "  · 流式TTS 本地书 bookId 修正\n" +
                "  · 字幕回调修复\n\n" +
                "日志: /sdcard/Download/tomatoread.log\n" +
                "仓库: https://github.com/" + REPO + "\n" +
                "作者QQ: " + QQ);
        info.setTextSize(13);
        info.setPadding(0, 16, 0, 16);
        root.addView(info);

        final Button toggle = new Button(this);
        toggle.setText(isEnabled() ? "当前状态: 已启用 (点击关闭)" : "当前状态: 已禁用 (点击启用)");
        toggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean en = !isEnabled();
                setEnabled(en);
                toggle.setText(en ? "当前状态: 已启用 (点击关闭)" : "当前状态: 已禁用 (点击启用)");
                Toast.makeText(MainActivity.this, en ? "已启用" : "已禁用", Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(toggle);

        Button check = new Button(this);
        check.setText("检查更新 (GitHub Release)");
        check.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                checkUpdate(true);
            }
        });
        root.addView(check);

        Button qq = new Button(this);
        qq.setText("联系作者 QQ: " + QQ);
        qq.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    Intent it = new Intent(Intent.ACTION_VIEW, Uri.parse("mqqwpa://im/chat?chat_type=wpa&uin=" + QQ));
                    startActivity(it);
                } catch (Throwable t) {
                    Toast.makeText(MainActivity.this, "未安装QQ或无法打开", Toast.LENGTH_SHORT).show();
                }
            }
        });
        root.addView(qq);

        setContentView(sv);
        checkUpdate(false);
    }

    /** 检查 GitHub Release 最新版, 有新版本弹窗提示 */
    private void checkUpdate(final boolean manual) {
        final Handler h = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            @Override
            public void run() {
                String tag = null;
                String body = "";
                try {
                    HttpURLConnection conn = (HttpURLConnection) new URL(GITHUB_API).openConnection();
                    conn.setConnectTimeout(8000);
                    conn.setReadTimeout(8000);
                    conn.setRequestProperty("User-Agent", "TomatoRead");
                    conn.setRequestProperty("Accept", "application/vnd.github+json");
                    int code = conn.getResponseCode();
                    if (code == 200) {
                        BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                        StringBuilder sb = new StringBuilder();
                        String l;
                        while ((l = br.readLine()) != null) sb.append(l);
                        br.close();
                        String json = sb.toString();
                        Matcher m = Pattern.compile("\"tag_name\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
                        if (m.find()) tag = m.group(1);
                        Matcher mb = Pattern.compile("\"body\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
                        if (mb.find()) body = mb.group(1);
                    }
                } catch (Throwable ignored) {
                }
                final String fTag = tag;
                final String fBody = body;
                h.post(new Runnable() {
                    @Override
                    public void run() {
                        if (fTag == null) {
                            if (manual) Toast.makeText(MainActivity.this, "检查失败(网络/接口受限)", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        String cur = "v" + VERSION;
                        String remote = fTag.startsWith("v") ? fTag : "v" + fTag;
                        if (remote.compareTo(cur) > 0) {
                            new AlertDialog.Builder(MainActivity.this)
                                    .setTitle("发现新版本 " + remote)
                                    .setMessage("当前: " + cur + "\n\n更新内容:\n" + fBody + "\n\n点击「去下载」打开 GitHub Release 页面")
                                    .setPositiveButton("去下载", new android.content.DialogInterface.OnClickListener() {
                                        @Override
                                        public void onClick(android.content.DialogInterface d, int w) {
                                            try {
                                                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/" + REPO + "/releases/latest")));
                                            } catch (Throwable ignored) {
                                            }
                                        }
                                    })
                                    .setNegativeButton("取消", null)
                                    .show();
                        } else {
                            if (manual) Toast.makeText(MainActivity.this, "已是最新版本 " + cur, Toast.LENGTH_SHORT).show();
                        }
                    }
                });
            }
        }).start();
    }

    private boolean isEnabled() {
        try {
            File f = new File(CONF_PATH);
            if (!f.exists()) return true;
            BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("enabled=")) {
                    br.close();
                    return !"0".equals(line.substring(8).trim());
                }
            }
            br.close();
        } catch (Throwable ignored) {
        }
        return true;
    }

    private void setEnabled(boolean en) {
        try {
            File f = new File(CONF_PATH);
            if (f.getParentFile() != null) f.getParentFile().mkdirs();
            FileOutputStream fos = new FileOutputStream(f, false);
            fos.write(("# TomatoRead config by giaoimgiao\n" +
                    "# 仓库: https://github.com/giaoimgiao/tomato-read\n" +
                    "# 作者QQ: " + QQ + "\n" +
                    "enabled=" + (en ? "1" : "0") + "\n").getBytes("UTF-8"));
            fos.close();
        } catch (Throwable ignored) {
        }
    }
}