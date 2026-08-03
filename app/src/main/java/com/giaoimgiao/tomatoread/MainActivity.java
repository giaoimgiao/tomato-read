package com.giaoimgiao.tomatoread;

import android.app.Activity;
import android.os.Bundle;
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

/**
 * TomatoRead v1.0 —— 模块设置界面
 * 配置: /sdcard/Download/tomatoread.conf (enabled=1/0)
 */
public class MainActivity extends Activity {

    private static final String CONF_PATH = "/sdcard/Download/tomatoread.conf";

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

        TextView tip = new TextView(this);
        tip.setText("v1.0 抓包版\n\n目标: 番茄小说 com.dragon.read\n\n当前阶段: 抓取智能朗读音色列表接口\n(tts_tones / offline_tts_tones)\n\n日志: /sdcard/Download/tomatoread.log\n\n操作: 打开任意在线书 → 智能朗读\n→ 音色列表页停留几秒\n\n抓包完成后告诉我, 我会分析数据\n并实现本地书在线音色解锁。");
        tip.setTextSize(13);
        tip.setPadding(0, 16, 0, 24);
        root.addView(tip);

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
        setContentView(sv);
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
                    "enabled=" + (en ? "1" : "0") + "\n").getBytes("UTF-8"));
            fos.close();
        } catch (Throwable ignored) {
        }
    }
}