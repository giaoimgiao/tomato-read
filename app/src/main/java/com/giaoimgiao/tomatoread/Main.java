package com.giaoimgiao.tomatoread;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * TomatoRead v1.0 —— 抓包版
 * 目标: 番茄小说 com.dragon.read 6.7.3.32
 *
 * 目的: 抓取「智能朗读」音色列表接口(tts_tones/offline_tts_tones/BookToneInfo),
 *       为后续"本地书解锁在线音色"提供数据依据.
 *
 * hook 层:
 *  1. OkHttp RealCall.getResponseWithInterceptorChain —— 记录含 tone/tts/audio/voice 的接口响应
 *
 * 日志: /sdcard/Download/tomatoread.log
 * 配置: /sdcard/Download/tomatoread.conf (enabled=1/0)
 */
public class Main implements IXposedHookLoadPackage {

    private static final String TARGET = "com.dragon.read";
    private static final String LOG_PATH = "/data/data/com.dragon.read/files/tomatoread.log";
    private static final String LOG_PATH2 = "/sdcard/Download/tomatoread.log";
    private static final String CONF_PATH = "/sdcard/Download/tomatoread.conf";

    private static volatile boolean cfgEnabled = true;
    private static final Object LOG_LOCK = new Object();
    private static final int MAX_BODY = 80000; // 单响应体记录上限

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!lpparam.packageName.equals(TARGET)) return;

        log("========== TomatoRead v1.0 注入成功 ==========");
        log("target=" + lpparam.packageName + " ver=" + lpparam.processName);
        loadConfig();

        try {
            hookOkHttp(lpparam);
            log("OkHttp hook 完成");
        } catch (Throwable t) {
            log("OkHttp hook 失败: " + t);
        }
        try {
            hookWebView(lpparam);
            log("WebView hook 完成");
        } catch (Throwable t) {
            log("WebView hook 失败: " + t);
        }
        log("全部 hook 注册完毕");
    }

    // ==================== OkHttp 抓包 ====================

    private void hookOkHttp(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        XposedHelpers.findAndHookMethod("okhttp3.RealCall", lpparam.classLoader,
                "getResponseWithInterceptorChain", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (!cfgEnabled) return;
                        try {
                            Object resp = param.getResult();
                            if (resp == null) return;
                            Object req = XposedHelpers.callMethod(resp, "request");
                            String url = String.valueOf(XposedHelpers.callMethod(req, "url"));
                            if (!isInteresting(url)) return;

                            String method = String.valueOf(XposedHelpers.callMethod(req, "method"));
                            int code = (Integer) XposedHelpers.callMethod(resp, "code");
                            log("REQ: [" + method + " " + code + "] " + url);

                            // 记录响应体(peekBody 不消费原流)
                            Object peek = XposedHelpers.callMethod(resp, "peekBody", 1024L * 1024L);
                            byte[] body = (byte[]) XposedHelpers.callMethod(
                                    XposedHelpers.callMethod(peek, "source"), "readByteArray");
                            logResponse("BODY", url, body);
                        } catch (Throwable ignored) {
                        }
                    }
                });
    }

    // ==================== WebView (系统/X5) 抓包 ====================

    private void hookWebView(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        // 系统 WebViewClient
        try {
            XposedHelpers.findAndHookMethod("android.webkit.WebViewClient", lpparam.classLoader,
                    "shouldInterceptRequest", android.webkit.WebView.class, String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (!cfgEnabled) return;
                            String url = String.valueOf(param.args[1]);
                            if (isInteresting(url)) log("WEB-REQ: " + url);
                        }
                    });
        } catch (Throwable ignored) {
        }
        // 系统 WebViewClient (WebResourceRequest 重载)
        try {
            XposedHelpers.findAndHookMethod("android.webkit.WebViewClient", lpparam.classLoader,
                    "shouldInterceptRequest", android.webkit.WebView.class, android.webkit.WebResourceRequest.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (!cfgEnabled) return;
                            String url = String.valueOf(XposedHelpers.callMethod(param.args[1], "getUrl"));
                            if (isInteresting(url)) log("WEB-REQ: " + url);
                        }
                    });
        } catch (Throwable ignored) {
        }
        // X5 WebViewClient
        try {
            ClassLoader cl = lpparam.classLoader;
            Class<?> wv = XposedHelpers.findClass("com.tencent.smtt.sdk.WebView", cl);
            Class<?> req = XposedHelpers.findClass("com.tencent.smtt.export.external.interfaces.WebResourceRequest", cl);
            XposedHelpers.findAndHookMethod("com.tencent.smtt.sdk.WebViewClient", cl,
                    "shouldInterceptRequest", wv, req, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (!cfgEnabled) return;
                            String url = String.valueOf(XposedHelpers.callMethod(param.args[1], "getUrl"));
                            if (isInteresting(url)) log("X5-REQ: " + url);
                        }
                    });
        } catch (Throwable ignored) {
        }
    }

    /**
     * 音色/朗读相关接口过滤
     */
    private boolean isInteresting(String url) {
        if (url == null) return false;
        String u = url.toLowerCase(Locale.US);
        // 静态资源排除
        if (u.contains(".js") || u.contains(".css") || u.contains(".png")
                || u.contains(".jpg") || u.contains(".jpeg") || u.contains(".gif")
                || u.contains(".webp") || u.contains(".ico") || u.contains(".woff")
                || u.contains(".ttf") || u.contains(".svg") || u.contains(".mp3")
                || u.contains(".mp4") || u.contains(".m4a")) return false;
        // 音色/朗读/语音相关
        if (u.contains("tone") || u.contains("tts") || u.contains("speaker")
                || u.contains("voice") || u.contains("audio") || u.contains("read")
                || u.contains("listen") || u.contains("sound")) return true;
        // 目录/书籍信息(可能携带 tts_tones)
        if (u.contains("directory") || u.contains("bookinfo") || u.contains("book_info")
                || u.contains("reader")) return true;
        return false;
    }

    // ==================== 日志/配置 ====================

    private void loadConfig() {
        try {
            File f = new File(CONF_PATH);
            if (!f.exists()) return;
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String k = line.substring(0, eq).trim();
                String v = line.substring(eq + 1).trim();
                if ("enabled".equals(k)) cfgEnabled = !"0".equals(v);
            }
            br.close();
        } catch (Throwable ignored) {
        }
        log("配置: enabled=" + cfgEnabled);
    }

    private void logResponse(String tag, String url, byte[] body) {
        if (body == null || body.length == 0) return;
        String bodyStr;
        try {
            bodyStr = new String(body, "UTF-8");
        } catch (Throwable t) {
            bodyStr = "<binary>";
        }
        if (bodyStr.length() > MAX_BODY) bodyStr = bodyStr.substring(0, MAX_BODY) + "...(截断)";
        log("[" + tag + "] " + url + " BODY(" + body.length + "): " + bodyStr);
    }

    private byte[] readAll(InputStream in, int max) throws Exception {
        if (in == null) return new byte[0];
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        int total = 0;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
            total += n;
            if (total > max) break;
        }
        return bos.toByteArray();
    }

    private static void log(String msg) {
        String ts = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
        String line = "[" + ts + "] " + msg + "\n";
        synchronized (LOG_LOCK) {
            try {
                File f = new File(LOG_PATH);
                if (!f.getParentFile().exists()) f.getParentFile().mkdirs();
                FileOutputStream fos = new FileOutputStream(f, true);
                fos.write(line.getBytes("UTF-8"));
                fos.close();
            } catch (Throwable ignored) {
            }
            try {
                File f2 = new File(LOG_PATH2);
                FileOutputStream fos2 = new FileOutputStream(f2, true);
                fos2.write(line.getBytes("UTF-8"));
                fos2.close();
            } catch (Throwable ignored) {
            }
        }
    }
}