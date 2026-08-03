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
    private static volatile int reqCount = 0;   // 请求计数(限制单次会话记录量)

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
            hookAudioConfig(lpparam);
            log("AudioConfig hook 完成");
        } catch (Throwable t) {
            log("AudioConfig hook 失败: " + t);
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
                            String method = String.valueOf(XposedHelpers.callMethod(req, "method"));
                            int code = (Integer) XposedHelpers.callMethod(resp, "code");
                            String ct = String.valueOf(XposedHelpers.callMethod(resp, "header", "Content-Type"));

                            // 记录所有请求(排除纯静态资源), 每条 URL 只记一次 BODY
                            if (!shouldSkip(url, ct)) {
                                log("REQ: [" + method + " " + code + "] " + url + " CT=" + ct);
                            }
                            if (!shouldSkip(url, ct) && shouldLogBody(url)) {
                                Object peek = XposedHelpers.callMethod(resp, "peekBody", 1024L * 1024L);
                                byte[] body = (byte[]) XposedHelpers.callMethod(
                                        XposedHelpers.callMethod(peek, "source"), "readByteArray");
                                logResponse("BODY", url, body);
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                });
    }

    // ==================== AudioConfig 音色数据 hook ====================

    private void hookAudioConfig(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        String cls = "com.dragon.read.component.audio.impl.api.AudioConfigApi";
        // O()/P(): 全局音色列表(离线/在线默认)
        hookListMethod(lpparam, cls, "O", "O()");
        hookListMethod(lpparam, cls, "P", "P()");
        // r(bookId): 每本书的 AudioConfig(含音色列表)
        try {
            XposedHelpers.findAndHookMethod(cls, lpparam.classLoader, "r", String.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (!cfgEnabled) return;
                    Object result = param.getResult();
                    log("[AC-r] bookId=" + param.args[0] + " -> " + dumpObject(result, 0));
                }
            });
        } catch (Throwable t) {
            log("hook r 失败: " + t);
        }
        // v(bookId): 返回音色 ID
        try {
            XposedHelpers.findAndHookMethod(cls, lpparam.classLoader, "v", String.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (!cfgEnabled) return;
                    log("[AC-v] bookId=" + param.args[0] + " -> toneId=" + param.getResult());
                }
            });
        } catch (Throwable t) {
            log("hook v 失败: " + t);
        }
    }

    private void hookListMethod(final XC_LoadPackage.LoadPackageParam lpparam, String cls, String name, String tag) {
        try {
            XposedHelpers.findAndHookMethod(cls, lpparam.classLoader, name, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (!cfgEnabled) return;
                    log("[AC-" + tag + "] -> " + dumpObject(param.getResult(), 0));
                }
            });
        } catch (Throwable t) {
            log("hook " + tag + " 失败: " + t);
        }
    }

    /** 反射打印对象(含 List/Map/数组, 深度限制 3) */
    private String dumpObject(Object o, int depth) {
        if (o == null) return "null";
        if (depth > 3) return "{...}";
        try {
            if (o instanceof String || o instanceof Number || o instanceof Boolean || o instanceof Character) {
                return String.valueOf(o);
            }
            if (o instanceof java.util.List) {
                java.util.List<?> list = (java.util.List<?>) o;
                StringBuilder sb = new StringBuilder("[");
                for (int i = 0; i < list.size() && i < 20; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(dumpObject(list.get(i), depth + 1));
                }
                if (list.size() > 20) sb.append(", ...(" + list.size() + "项)");
                return sb.append("]").toString();
            }
            if (o instanceof java.util.Map) {
                java.util.Map<?, ?> map = (java.util.Map<?, ?>) o;
                StringBuilder sb = new StringBuilder("{");
                int i = 0;
                for (java.util.Map.Entry<?, ?> e : map.entrySet()) {
                    if (i > 0) sb.append(", ");
                    sb.append(e.getKey()).append("=").append(dumpObject(e.getValue(), depth + 1));
                    if (++i >= 20) { sb.append(", ..."); break; }
                }
                return sb.append("}").toString();
            }
            if (o.getClass().isArray()) {
                int len = java.lang.reflect.Array.getLength(o);
                StringBuilder sb = new StringBuilder("[");
                for (int i = 0; i < len && i < 20; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(dumpObject(java.lang.reflect.Array.get(o, i), depth + 1));
                }
                return sb.append("]").toString();
            }
            // 普通对象: 反射字段
            StringBuilder sb = new StringBuilder(o.getClass().getSimpleName() + "{");
            java.lang.reflect.Field[] fields = o.getClass().getDeclaredFields();
            boolean first = true;
            for (java.lang.reflect.Field f : fields) {
                try {
                    f.setAccessible(true);
                    Object v = f.get(o);
                    if (!first) sb.append(", ");
                    first = false;
                    sb.append(f.getName()).append("=").append(dumpObject(v, depth + 1));
                } catch (Throwable ignored) {
                }
            }
            return sb.append("}").toString();
        } catch (Throwable t) {
            return "<" + t + ">";
        }
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
                            if (!shouldSkip(url, null)) log("WEB-REQ: " + url);
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
                            if (!shouldSkip(url, null)) log("WEB-REQ: " + url);
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
                            if (!shouldSkip(url, null)) log("X5-REQ: " + url);
                        }
                    });
        } catch (Throwable ignored) {
        }
    }

    /**
     * 排除: 纯静态资源/媒体流/埋点统计
     */
    private boolean shouldSkip(String url, String ct) {
        if (url == null) return true;
        String u = url.toLowerCase(Locale.US);
        if (u.contains(".js") || u.contains(".css") || u.contains(".png")
                || u.contains(".jpg") || u.contains(".jpeg") || u.contains(".gif")
                || u.contains(".webp") || u.contains(".ico") || u.contains(".woff")
                || u.contains(".ttf") || u.contains(".svg")) return true;
        // 媒体流(音频/视频文件本体)
        if (u.contains(".mp3") || u.contains(".mp4") || u.contains(".m4a")
                || u.contains(".aac") || u.contains(".wav") || u.contains(".flac")
                || u.contains(".ts") || u.contains(".m3u8") || u.contains(".opus")) return true;
        // 埋点/统计/日志上报
        if (u.contains("/log/") || u.contains("logupload") || u.contains("monitor")
                || u.contains("metrics") || u.contains("pgc/monitor") || u.contains("_staging_")) return false;
        // Content-Type 二进制
        if (ct != null && (ct.contains("image") || ct.contains("audio") || ct.contains("video")
                || ct.contains("octet-stream") || ct.contains("protobuf"))) return true;
        return false;
    }

    /**
     * 是否需要记录 BODY(控制日志量): 只记录 JSON 类接口
     */
    private boolean shouldLogBody(String url) {
        if (url == null) return false;
        String u = url.toLowerCase(Locale.US);
        // 音色/朗读/书籍目录 优先
        if (u.contains("tone") || u.contains("tts") || u.contains("speaker")
                || u.contains("voice") || u.contains("audio") || u.contains("listen")
                || u.contains("sound") || u.contains("directory") || u.contains("bookinfo")
                || u.contains("book_info") || u.contains("reader") || u.contains("read")) return true;
        // 其余接口也记录 body, 但限流: 每 URL 只记一次
        return true;
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
            // gzip 解压(番茄手动设置 Accept-Encoding: gzip, OkHttp 不解压)
            byte[] data = body;
            if (body.length > 2 && (body[0] & 0xFF) == 0x1f && (body[1] & 0xFF) == 0x8b) {
                java.util.zip.GZIPInputStream gis = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(body));
                data = readAll(gis, MAX_BODY * 4);
            }
            bodyStr = new String(data, "UTF-8");
        } catch (Throwable t) {
            bodyStr = "<binary:" + body.length + ">";
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