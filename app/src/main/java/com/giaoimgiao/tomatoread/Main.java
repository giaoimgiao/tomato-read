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
 * TomatoRead v1.7 —— 抓包版+注入版
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

        log("========== TomatoRead v2.2 注入成功 ==========");
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
            hookRpc(lpparam);
            log("RPC hook 完成");
        } catch (Throwable t) {
            log("RPC hook 失败: " + t);
        }
        try {
            hookCronetTee(lpparam);
            log("CronetTee hook 完成");
        } catch (Throwable t) {
            log("CronetTee hook 失败: " + t);
        }
        try {
            hookCronetReq(lpparam);
            log("CronetReq hook 完成");
        } catch (Throwable t) {
            log("CronetReq hook 失败: " + t);
        }
        try {
            hookToneInject(lpparam);
            log("ToneInject hook 完成");
        } catch (Throwable t) {
            log("ToneInject hook 失败: " + t);
        }
        try {
            hookSubtitleChain(lpparam);
            log("SubtitleChain hook 完成");
        } catch (Throwable t) {
            log("SubtitleChain hook 失败: " + t);
        }
        try {
            hookSubtitleEntry(lpparam);
        } catch (Throwable t) {
            log("SUB-ENTRY hook 失败: " + t);
        }
        try {
            hookSubtitleProvider(lpparam);
        } catch (Throwable t) {
            log("SUB-PROV hook 失败: " + t);
        }
        try {
            hookCronetResp(lpparam);
            log("CronetResp hook 完成");
        } catch (Throwable t) {
            log("CronetResp hook 失败: " + t);
        }
        try {
            hookWebView(lpparam);
            log("WebView hook 完成");
        } catch (Throwable t) {
            log("WebView hook 失败: " + t);
        }
        try {
            hookOnlineToneInject(lpparam);
            log("OnlineTone hook 完成");
        } catch (Throwable t) {
            log("OnlineTone hook 失败: " + t);
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

        // ==================== RPC 响应 hook (SsResponse.body 万能抓包点) ====================

    private void hookRpc(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        // SsResponse.body(): 所有 RPC 响应模型被读取时触发
        XposedHelpers.findAndHookMethod("com.bytedance.retrofit2.SsResponse", lpparam.classLoader,
                "body", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (!cfgEnabled) return;
                        try {
                            Object body = param.getResult();
                            if (body == null) return;
                            String cn = body.getClass().getName();
                            String low = cn.toLowerCase(Locale.US);
                            // 只按类名判断(避免大对象全文 dump 塞爆日志)
                            if (low.contains("tone") || low.contains("tts") || low.contains("speaker")
                                    || low.contains("audio") || low.contains("voice")) {
                                String dump = dumpObject(body, 0);
                                if (dump.length() > 8000) dump = dump.substring(0, 8000) + "...(截断)";
                                log("[RPC-TONE] " + cn + " -> " + dump);
                            } else {
                                log("[RPC] " + cn);
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                });
    }

    // ==================== 音色注入 (RelativeToneModel getter 补全) ====================

    // 内置在线 AI 音色表 (来自抓包 BookToneInfoResponse.tts_tones)
    private static final Object[][] AI_TONES = {
            {91L, "多角色对话升级版", ""},
            {74L, "成熟大叔音升级版", "https://lf3-reading.fqnovelstatic.com/obj/novel-common/img_601_uncle_pro.png"},
            {4L, "成熟大叔音", "https://lf3-reading.fqnovelstatic.com/obj/novel-common/img_5%E6%88%90%E7%86%9F%E5%A4%A7%E5%8F%94%E9%9F%B3.png"},
            {1L, "甜美少女音", "https://lf3-reading.fqnovelstatic.com/obj/novel-common/img_5%E7%94%9C%E7%BE%8E%E5%B0%91%E5%A5%B3%E9%9F%B3.png"},
            {5L, "开朗青年音", "https://lf3-reading.fqnovelstatic.com/obj/novel-common/img_5%E5%BC%80%E6%9C%97%E9%9D%92%E5%B9%B4%E9%9F%B3.png"},
            {2L, "清亮青叔音", "https://lf3-reading.fqnovelstatic.com/obj/novel-common/img_5%E6%B8%85%E4%BA%AE%E9%9D%92%E5%8F%94%E9%9F%B3.png"},
            {6L, "温柔淑女音", "https://lf3-reading.fqnovelstatic.com/obj/novel-common/img_5%E6%B8%A9%E6%9F%94%E6%B7%91%E5%A5%B3%E9%9F%B3.png"}
    };
    // v2.5.6: 全局 AI 音色表(AudioConfigApi.P() 播放校验数据源) —— AI_TONES + 97多角色对话升级版
    private static final Object[][] AI_TONES_GLOBAL = {
            {97L, "多角色对话升级版", ""},
            {91L, "多角色对话升级版", ""},
            {74L, "成熟大叔音升级版", "https://lf3-reading.fqnovelstatic.com/obj/novel-common/img_601_uncle_pro.png"},
            {4L, "成熟大叔音", "https://lf3-reading.fqnovelstatic.com/obj/novel-common/img_5%E6%88%90%E7%86%9F%E5%A4%A7%E5%8F%94%E9%9F%B3.png"},
            {1L, "甜美少女音", "https://lf3-reading.fqnovelstatic.com/obj/novel-common/img_5%E7%94%9C%E7%BE%8E%E5%B0%91%E5%A5%B3%E9%9F%B3.png"},
            {5L, "开朗青年音", "https://lf3-reading.fqnovelstatic.com/obj/novel-common/img_5%E5%BC%80%E6%9C%97%E9%9D%92%E5%B9%B4%E9%9F%B3.png"},
            {2L, "清亮青叔音", "https://lf3-reading.fqnovelstatic.com/obj/novel-common/img_5%E6%B8%85%E4%BA%AE%E9%9D%92%E5%8F%94%E9%9F%B3.png"},
            {6L, "温柔淑女音", "https://lf3-reading.fqnovelstatic.com/obj/novel-common/img_5%E6%B8%A9%E6%9F%94%E6%B7%91%E5%A5%B3%E9%9F%B3.png"}
    };
    // 内置离线音色表 (来自抓包 offline_tts_tones)
    private static final Object[][] OFFLINE_TONES = {
            {118L, "成熟大叔离线版", "https://lf3-reading.fqnovelstatic.com/obj/novel-common/img_5%E6%88%90%E7%86%9F%E5%A4%A7%E5%8F%94%E9%9F%B3.png"},
            {117L, "甜美少女离线版", "https://lf3-reading.fqnovelstatic.com/obj/novel-common/img_5%E7%94%9C%E7%BE%8E%E5%B0%91%E5%A5%B3%E9%9F%B3.png"},
            {119L, "温柔淑女离线版", "https://lf3-reading.fqnovelstatic.com/obj/novel-common/img_5%E6%B8%A9%E6%9F%94%E6%B7%91%E5%A5%B3%E9%9F%B3.png"},
            {120L, "开朗青年离线版", "https://lf3-reading.fqnovelstatic.com/obj/novel-common/img_5%E5%BC%80%E6%9C%97%E9%9D%92%E5%B9%B4%E9%9F%B3.png"}
    };
    // 内置真人音色表 (来自抓包 audio_tones)
    private static final Object[][] AUDIO_TONES = {
            {7660899274724576281L, "主播：禾熙", "https://lf3-reading.fqnovelstatic.com/obj/novel-common/img_5%E7%9C%9F%E4%BA%BA%E9%9F%B3.png"}
    };

    private static volatile boolean toneInjectedLogged = false;

    private void hookToneInject(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        String cls = "com.dragon.read.component.audio.biz.protocol.core.data.RelativeToneModel";

        // AI 在线音色补全
        XposedHelpers.findAndHookMethod(cls, lpparam.classLoader, "getAiModelsForBook",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (!cfgEnabled) return;
                        try {
                            java.util.List<Object> list = (java.util.List<Object>) param.getResult();
                            if (list == null) return;
                            int added = 0;
                            for (Object[] t : AI_TONES) {
                                long id = (Long) t[0];
                                if (!containsTone(list, id)) {
                                    list.add(newToneItem(param, (String) t[1], id, (String) t[2]));
                                    added++;
                                }
                            }
                            if (added > 0) log("[INJECT-AI] 补全 " + added + " 个AI音色, 当前共 " + list.size() + " 个");
                        } catch (Throwable ignored) {
                        }
                    }
                });

        // 离线音色补全
        XposedHelpers.findAndHookMethod(cls, lpparam.classLoader, "getOfflineModelsForBook",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (!cfgEnabled) return;
                        try {
                            java.util.List<Object> list = (java.util.List<Object>) param.getResult();
                            if (list == null) return;
                            int added = 0;
                            for (Object[] t : OFFLINE_TONES) {
                                long id = (Long) t[0];
                                if (!containsTone(list, id)) {
                                    list.add(newToneItem(param, (String) t[1], id, (String) t[2]));
                                    added++;
                                }
                            }
                            if (added > 0) log("[INJECT-OFF] 补全 " + added + " 个离线音色, 当前共 " + list.size() + " 个");
                        } catch (Throwable ignored) {
                        }
                    }
                });

        // 真人音色补全
        XposedHelpers.findAndHookMethod(cls, lpparam.classLoader, "getVoiceModelsForBook",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (!cfgEnabled) return;
                        try {
                            java.util.List<Object> list = (java.util.List<Object>) param.getResult();
                            if (list == null) return;
                            int added = 0;
                            for (Object[] t : AUDIO_TONES) {
                                long id = (Long) t[0];
                                if (!containsTone(list, id)) {
                                    list.add(newToneItem(param, (String) t[1], id, (String) t[2]));
                                    added++;
                                }
                            }
                            if (added > 0) log("[INJECT-VOICE] 补全 " + added + " 个真人音色, 当前共 " + list.size() + " 个");
                        } catch (Throwable ignored) {
                        }
                    }
                });

        // 打印在线书解析链路 (验证用)
        try {
            XposedHelpers.findAndHookMethod(cls, lpparam.classLoader, "parse",
                    "com.dragon.read.rpc.model.BookToneInfo", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            if (!cfgEnabled) return;
                            try {
                                Object model = param.getResult();
                                if (model == null) return;
                                String ai = summarizeTones(XposedHelpers.callMethod(model, "getAiModelsForBook"));
                                String off = summarizeTones(XposedHelpers.callMethod(model, "getOfflineModelsForBook"));
                                String voice = summarizeTones(XposedHelpers.callMethod(model, "getVoiceModelsForBook"));
                                log("[PARSE] AI=" + ai + " | OFF=" + off + " | VOICE=" + voice);
                            } catch (Throwable ignored) {
                            }
                        }
                    });
        } catch (Throwable t) {
            log("parse hook 失败: " + t);
        }

        hookLocalBookTone(lpparam);
    }

    // ==================== 本地书音色解锁 (LocalPageInfoRepo.X + showAiTone 开关) ====================

    /** v2.5.4: 在线书音色列表注入97(多角色对话升级版) —— 成绩不达标的书 ttsTones 缺97, 强制补上 */
    private void hookOnlineToneInject(final XC_LoadPackage.LoadPackageParam lpparam) {
        // 1. RelativeToneModel.parse(BookToneInfo) —— 音色模型解析主入口
        try {
            XposedHelpers.findAndHookMethod(
                    "com.dragon.read.component.audio.biz.protocol.core.data.RelativeToneModel",
                    lpparam.classLoader, "parse", "com.dragon.read.rpc.model.BookToneInfo",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (!cfgEnabled) return;
                            try {
                                injectTone97(param.args[0]);
                            } catch (Throwable ignored) {
                            }
                        }
                    });
            log("RelativeToneModel.parse hook 完成");
        } catch (Throwable t) {
            log("RelativeToneModel.parse hook 失败: " + t);
        }
        // 2/3. 两个 UI 仓库数据源: a$c$a.a(BookToneInfoResponse) / j$c$a.a(BookToneInfoResponse)
        for (String clsName : new String[]{
                "com.dragon.read.component.audio.impl.ui.repo.datasource.a$c$a",
                "com.dragon.read.component.audio.impl.ui.repo.datasource.j$c$a"}) {
            try {
                XposedHelpers.findAndHookMethod(clsName, lpparam.classLoader, "a",
                        "com.dragon.read.rpc.model.BookToneInfoResponse", new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                if (!cfgEnabled) return;
                                try {
                                    Object resp = param.args[0];
                                    if (resp == null) return;
                                    injectTone97(XposedHelpers.getObjectField(resp, "data"));
                                } catch (Throwable ignored) {
                                }
                            }
                        });
                log(clsName + " hook 完成");
            } catch (Throwable t) {
                log(clsName + " hook 失败: " + t);
            }
        }
    }

    /** 向 BookToneInfo.ttsTones 注入 97(多角色对话升级版), 缺失时才注入 */
    private static void injectTone97(Object info) {
        if (info == null) return;
        try {
            java.util.List list = (java.util.List) XposedHelpers.getObjectField(info, "ttsTones");
            if (list == null) {
                list = new java.util.ArrayList();
                XposedHelpers.setObjectField(info, "ttsTones", list);
            }
            boolean has97 = false;
            for (Object t : list) {
                try {
                    if (XposedHelpers.getLongField(t, "id") == 97L) { has97 = true; break; }
                } catch (Throwable ignored) {
                }
            }
            if (!has97) {
                Class<?> cls = info.getClass().getClassLoader().loadClass("com.dragon.read.rpc.model.TtsToneInfo");
                Object tone = XposedHelpers.newInstance(cls);
                XposedHelpers.setLongField(tone, "id", 97L);
                XposedHelpers.setObjectField(tone, "title", "多角色对话升级版");
                XposedHelpers.setBooleanField(tone, "isMultiTone", true);
                XposedHelpers.setLongField(tone, "parentToneId", 51L);
                XposedHelpers.setObjectField(tone, "description", "自然流畅");
                list.add(tone);
                log("[ONLINE-TONE] 已注入97多角色对话升级版, ttsTones=" + list.size());
            }
        } catch (Throwable t) {
            log("[ONLINE-TONE] 注入异常: " + t);
        }
    }

    private void hookLocalBookTone(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        // 1) showAiTone/showOfflineTone 强制开启: 返回 new LocalBookOfflineTts(true, true)
        try {
            XposedHelpers.findAndHookMethod(
                    "com.dragon.read.component.audio.impl.ui.settings.LocalBookOfflineTts$a",
                    lpparam.classLoader, "a", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            if (!cfgEnabled) return;
                            try {
                                Class<?> cls = lpparam.classLoader.loadClass(
                                        "com.dragon.read.component.audio.impl.ui.settings.LocalBookOfflineTts");
                                Object forced = XposedHelpers.newInstance(cls, true, true);
                                param.setResult(forced);
                                log("[LOCAL] showAiTone=true showOfflineTone=true 已强制开启");
                            } catch (Throwable ignored) {
                            }
                        }
                    });
        } catch (Throwable t) {
            log("LocalBookOfflineTts hook 失败: " + t);
        }

        // 2) 本地书 RelativeToneModel 构造后, 注入内置 AI/离线音色模型
        try {
            XposedHelpers.findAndHookMethod(
                    "com.dragon.read.component.audio.impl.ui.repo.datasource.LocalPageInfoRepo",
                    lpparam.classLoader, "X", String.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            if (!cfgEnabled) return;
                            try {
                                Object model = param.getResult();
                                if (model == null) return;
                                ClassLoader cl = model.getClass().getClassLoader();
                                Class<?> ttsCls = cl.loadClass(
                                        "com.dragon.read.component.audio.biz.protocol.core.data.RelativeToneModel$TtsToneModel");
                                // 补 AI 在线音色
                                java.util.List<Object> aiList = (java.util.List<Object>)
                                        XposedHelpers.getObjectField(model, "ttsToneModels");
                                int addedAi = 0;
                                for (Object[] t : AI_TONES) {
                                    long id = (Long) t[0];
                                    if (!containsTtsModel(aiList, id)) {
                                        aiList.add(XposedHelpers.newInstance(ttsCls, id, (String) t[1], (String) t[2]));
                                        addedAi++;
                                    }
                                }
                                // 补离线音色
                                java.util.List<Object> offList = (java.util.List<Object>)
                                        XposedHelpers.getObjectField(model, "offlineTtsToneModels");
                                int addedOff = 0;
                                for (Object[] t : OFFLINE_TONES) {
                                    long id = (Long) t[0];
                                    if (!containsTtsModel(offList, id)) {
                                        offList.add(XposedHelpers.newInstance(ttsCls, id, (String) t[1], (String) t[2]));
                                        addedOff++;
                                    }
                                }
                                log("[LOCAL-X] ttsToneModels+" + addedAi + "(" + aiList.size() + ") offlineTtsToneModels+"
                                        + addedOff + "(" + offList.size() + ")");
                            } catch (Throwable ignored) {
                            }
                        }
                    });
        } catch (Throwable t) {
            log("LocalPageInfoRepo.X hook 失败: " + t);
        }
    }

    private boolean containsTtsModel(java.util.List<Object> list, long id) {
        if (list == null) return true;
        for (Object o : list) {
            try {
                if (XposedHelpers.getLongField(o, "toneId") == id) return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private boolean containsTone(java.util.List<Object> list, long id) {
        for (Object o : list) {
            try {
                if (XposedHelpers.getLongField(o, "c") == id) return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private Object newToneItem(XC_MethodHook.MethodHookParam param, String title, long id, String icon) {
        try {
            Class<?> cls = param.thisObject.getClass().getClassLoader()
                    .loadClass("ez1.e");
            return XposedHelpers.newInstance(cls, title, id, icon);
        } catch (Throwable t) {
            return null;
        }
    }

    private String summarizeTones(Object listObj) {
        try {
            java.util.List<?> list = (java.util.List<?>) listObj;
            if (list == null || list.isEmpty()) return "[]";
            StringBuilder sb = new StringBuilder("[");
            for (Object o : list) {
                if (sb.length() > 120) { sb.append("..."); break; }
                String title = (String) XposedHelpers.getObjectField(o, "a");
                long id = XposedHelpers.getLongField(o, "c");
                sb.append(id).append(":").append(title).append(", ");
            }
            return sb.append("]").toString();
        } catch (Throwable t) {
            return "<err>";
        }
    }
    // ==================== Cronet 响应字节抓取 (c$a.in 代理流) ====================

    /** 命中打印上限(每会话), 防止刷屏 */
    private static volatile int cronetHitCount = 0;
    /** 关键接口(playinfo/toneinfo/timepoint)独立命中计数 */
    private static volatile int cronetKeyHitCount = 0;
    private static final int MAX_KEY_HITS = 200;
    /** v2.4: execute() 最近一次请求 URL 缓存(供 after 用, 因为执行后 thisObject.e 可能被清空) */
    private static volatile String lastCronetUrl = "";

    /** 请求侧抓包: execute() 是所有 Cronet 请求最终入口, 按 URL 特征记录 TTS/播放请求 */
    private void hookCronetReq(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        XposedHelpers.findAndHookMethod(
                "com.bytedance.frameworks.baselib.network.http.cronet.impl.c",
                lpparam.classLoader, "execute", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (!cfgEnabled) return;
                        try {
                            String url = "";
                            try {
                                Object req = XposedHelpers.getObjectField(param.thisObject, "e");
                                if (req != null) {
                                    try { url = String.valueOf(XposedHelpers.callMethod(req, "getUrl")); } catch (Throwable ignored) {}
                                }
                            } catch (Throwable ignored) {
                            }
                            String lower = url.toLowerCase(Locale.US);
                            if (lower.contains("playinfo") || lower.contains("toneinfo")
                                    || lower.contains("timepoint") || lower.contains("/audio/")
                                    || lower.contains("tts") || lower.contains("speech")
                                    || lower.contains("synthes") || lower.contains("fq-tts")
                                    || lower.contains("tone")) {
                                lastCronetUrl = url;
                                String body = "";
                                Object bodyObj = null;
                                try {
                                    Object req = XposedHelpers.getObjectField(param.thisObject, "e");
                                    if (req != null) {
                                        try { body = String.valueOf(bodyObj = XposedHelpers.callMethod(req, "body")); } catch (Throwable ignored) {}
                                        if ((body == null || body.isEmpty()) && bodyObj == null) {
                                            try { body = String.valueOf(bodyObj = XposedHelpers.callMethod(req, "getBody")); } catch (Throwable ignored) {}
                                        }
                                        // v2.5.3: 用第一次拿到的对象 dump(二次调用 body() 流被消费会返回 null)
                                        if (bodyObj != null && body != null && body.startsWith("TypedByteArray")) {
                                            try {
                                                String content = dumpReqBytes(bodyObj);
                                                if (content != null) {
                                                    if (content.length() > 1500) content = content.substring(0, 1500) + "...";
                                                    log("[CRONET-REQ-BODY] " + url.substring(0, Math.min(url.length(), 120)) + " -> " + content);
                                                } else {
                                                    log("[CRONET-REQ-BODY] dump失败 b=" + bodyObj.getClass().getName());
                                                }
                                            } catch (Throwable ignored) {
                                            }
                                        }
                                    }
                                } catch (Throwable ignored) {
                                }
                                if (body != null && body.length() > 2000) body = body.substring(0, 2000) + "...";
                                log("[CRONET-REQ] " + url + " body=" + body);
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                });
    }


    /** 响应码抓取: c.execute() after 记录 TTS/playinfo/full 相关请求的服务端响应码 */
    private void hookCronetResp(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        XposedHelpers.findAndHookMethod(
                "com.bytedance.frameworks.baselib.network.http.cronet.impl.c",
                lpparam.classLoader, "execute", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (!cfgEnabled) return;
                        try {
                            // v2.4: 优先用 before 缓存的 URL(执行后 thisObject.e 可能被清空导致 URL 丢失)
                            String url = lastCronetUrl;
                            try {
                                Object req = XposedHelpers.getObjectField(param.thisObject, "e");
                                if (req != null) url = String.valueOf(XposedHelpers.callMethod(req, "getUrl"));
                            } catch (Throwable ignored) {
                            }
                            String lower = url.toLowerCase(Locale.US);
                            if (lower.contains("tts") || lower.contains("playinfo")
                                    || lower.contains("full") || lower.contains("toneinfo")) {
                                // v2.4: execute() 抛异常时记录(streamtts 无 [CRONET-RESP] 记录 => 疑似异常)
                                Throwable ex = param.getThrowable();
                                if (ex != null) {
                                    log("[CRONET-RESP-EX] url=" + url + " 异常: " + ex);
                                    return;
                                }
                                Object r = param.getResult();
                                String info = r == null ? "null" : r.getClass().getSimpleName();
                                int code = -1;
                                // 优先从 Cronet HttpURLConnection 拿真实 HTTP 响应码
                                try {
                                    Object conn = XposedHelpers.getObjectField(param.thisObject, "a");
                                    if (conn instanceof java.net.HttpURLConnection) {
                                        code = ((java.net.HttpURLConnection) conn).getResponseCode();
                                    }
                                } catch (Throwable ignored) {
                                }
                                if (code < 0 && r != null) {
                                    try { code = (Integer) XposedHelpers.callMethod(r, "code"); } catch (Throwable ignored) {}
                                    if (code < 0) { try { code = (Integer) XposedHelpers.callMethod(r, "getCode"); } catch (Throwable ignored) {} }
                                    if (code < 0) { try { code = (Integer) XposedHelpers.callMethod(r, "getResponseCode"); } catch (Throwable ignored) {} }
                                }
                                // 探测 SsResponse 可读方法, 尝试拿响应体(streamtts 响应未走 c$a.in(), 在此兜底)
                                String body = "";
                                if (r != null) {
                                    try { body = String.valueOf(XposedHelpers.callMethod(r, "body")); } catch (Throwable ignored) {}
                                    if (body == null || body.isEmpty() || body.contains("@")) {
                                        try { body = String.valueOf(XposedHelpers.callMethod(r, "getBody")); } catch (Throwable ignored) {}
                                    }
                                    if (body != null && body.length() > 400) body = body.substring(0, 400) + "...";
                                }
                                log("[CRONET-RESP] url=" + url + " code=" + code + " ret=" + info + " body=" + body);
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                });
    }

    // ==================== v2.2 字幕链路破解 ====================

    /**
     * 字幕加载失败链路(已100%破解):
     * AiTtsSubtitleDataCacher.h() -> Observable.zip(
     *   ASR: readerChapterService.k(d) -> ChapterOriginalContentHelper.h() -> ... -> i(bookId,chapterId,AudioBookASR)
     *        -> NumberUtils.parse(bookId)==0(本地书) -> 抛 ErrorCodeException(-4007) -> 不发 /reading/reader/full/v 请求
     *   TTS同步: readerTtsSyncService.t(b) -> AudioSyncReaderCacheMgr.v() -> 内存/磁盘缓存(无网络)
     * ) 任一 error -> 字幕加载失败
     * v2.2 实验: 记录各环节参数 + 本地书时 ASR 强制放行(改 bookId=1) + h() 替换返回跳过字幕加载
     */
    private void hookSubtitleChain(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        // 1. ASR 章节内容请求构造点(本地拦截根因): ChapterOriginalContentHelper.i(bookId, chapterId, FullReqType)
        try {
            Class<?> reqTypeCls = XposedHelpers.findClass(
                    "readersaas.com.dragon.read.saas.rpc.model.FullReqType", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(
                    "com.dragon.read.reader.utils.ChapterOriginalContentHelper",
                    lpparam.classLoader, "i",
                    String.class, String.class, reqTypeCls, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (!cfgEnabled) return;
                            try {
                                String bookId = String.valueOf(param.args[0]);
                                String chapterId = String.valueOf(param.args[1]);
                                log("[ASR-REQ] bookId=" + bookId + " chapterId=" + chapterId
                                        + " reqType=" + param.args[2]);
                                long b = parseLongSafe(bookId);
                                long c = parseLongSafe(chapterId);
                                if (b == 0) { param.args[0] = "1"; log("[ASR-REQ] bookId=0 -> 强制改1放行"); }
                                if (c == 0) { param.args[1] = "1"; log("[ASR-REQ] chapterId=0 -> 强制改1放行"); }
                            } catch (Throwable ignored) {
                            }
                        }
                    });
            log("ASR hook 完成");
        } catch (Throwable t) {
            log("ASR hook 失败: " + t);
        }

        // 2. RPC full/v 请求构造点: b04/a.m(FullRequest) 记录请求参数(确认通道)
        try {
            Class<?> fullReqCls = XposedHelpers.findClass(
                    "readersaas.com.dragon.read.saas.rpc.model.FullRequest", lpparam.classLoader);
            XposedHelpers.findAndHookMethod("b04.a", lpparam.classLoader, "m",
                    fullReqCls, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (!cfgEnabled) return;
                            try {
                                Object req = param.args[0];
                                log("[RPC-FULL] bookId=" + getFieldStr(req, "bookId")
                                        + " itemId=" + getFieldStr(req, "itemId")
                                        + " reqType=" + getFieldStr(req, "reqType"));
                            } catch (Throwable ignored) {
                            }
                        }
                    });
            log("RPC-FULL hook 完成");
        } catch (Throwable t) {
            log("RPC-FULL hook 失败: " + t);
        }

        // 3. 字幕加载入口: AiTtsSubtitleDataCacher.h(bookId, chapterId, toneId, isX)
        //    记录参数 + 替换返回 Observable.just(true) 跳过字幕加载(本地书 ASR 必失败)
        try {
            final ClassLoader cl = lpparam.classLoader;
            XposedHelpers.findAndHookMethod(
                    "com.dragon.read.component.audio.impl.ui.page.viewmodel.AiTtsSubtitleDataCacher",
                    lpparam.classLoader, "h",
                    String.class, String.class, long.class, boolean.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (!cfgEnabled) return;
                            try {
                                log("[SUB-REQ] bookId=" + param.args[0] + " chapterId=" + param.args[1]
                                        + " toneId=" + param.args[2] + " isX=" + param.args[3]);
                            } catch (Throwable ignored) {
                            }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            if (!cfgEnabled) return;
                            try {
                                log("[SUB-HOOK] 替换返回 -> Observable.just(true) (跳过字幕加载)");
                                Class<?> obsCls = XposedHelpers.findClass("io.reactivex.Observable", cl);
                                Object just = XposedHelpers.callStaticMethod(obsCls, "just", Boolean.TRUE);
                                param.setResult(just);
                            } catch (Throwable ignored) {
                            }
                        }
                    });
            log("SUB hook 完成");
        } catch (Throwable t) {
            log("SUB hook 失败: " + t);
        }

        // 4. zip 消费点: AiTtsSubtitleDataCacher$a.a(ChapterInfo, ChapterAudioSyncReaderModel)
        //    若被调用说明 h() 替换未生效(仍走原 zip); 记录验证
        try {
            Class<?> ciCls = XposedHelpers.findClass(
                    "com.dragon.read.reader.download.ChapterInfo", lpparam.classLoader);
            Class<?> armCls = XposedHelpers.findClass(
                    "com.dragon.read.component.audio.data.audiosync.ChapterAudioSyncReaderModel", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(
                    "com.dragon.read.component.audio.impl.ui.page.viewmodel.AiTtsSubtitleDataCacher$a",
                    lpparam.classLoader, "a", ciCls, armCls, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (!cfgEnabled) return;
                            try {
                                Object ci = param.args[0];
                                String content = ci == null ? "" : String.valueOf(getFieldStr(ci, "content"));
                                if (content.length() > 80) content = content.substring(0, 80) + "...";
                                log("[SUB-ZIP] content=" + content
                                        + " audioModel=" + (param.args[1] == null ? "" : param.args[1].getClass().getSimpleName()));
                            } catch (Throwable ignored) {
                            }
                        }
                    });
            log("SUB-ZIP hook 完成");
        } catch (Throwable t) {
            log("SUB-ZIP hook 失败: " + t);
        }

        // 5. TTS同步入口: AudioSyncReaderCacheMgr.v(hz1/b) 记录参数(确认本地书传入值)
        try {
            Class<?> hz1bCls = XposedHelpers.findClass("hz1.b", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(
                    "com.dragon.read.reader.audiosync.cache.AudioSyncReaderCacheMgr",
                    lpparam.classLoader, "v", hz1bCls, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (!cfgEnabled) return;
                            try {
                                Object b = param.args[0];
                                log("[TTS-SYNC] a=" + getFieldStr(b, "a")
                                        + " b=" + getFieldStr(b, "b")
                                        + " c=" + getFieldLong(b, "c")
                                        + " j=" + getFieldBool(b, "j")
                                        + " k=" + getFieldBool(b, "k"));
                            } catch (Throwable ignored) {
                            }
                        }
                    });
            log("TTS-SYNC hook 完成");
        } catch (Throwable t) {
            log("TTS-SYNC hook 失败: " + t);
        }
    }

    /**
     * v2.3: hook AiTtsSubtitleDataCacher.i() (public 入口, h() 的唯一调用者)
     * 实测确认: h()(private) 的 hook 注册成功但 before 从未触发(失效),
     * 而 zip 两个分支(ASR-REQ/TTS-SYNC)均触发 => h() 确实被调用但 hook 未挂上。
     * 必须在 public 入口 i() 处短路: 跳过整个方法体(不再走 h() -> ASR full/v 被服务端拒
     * -> "字幕加载失败"), 并主动触发 hideSubtitle Runnable(模拟字幕隐藏),
     * 让 streamtts 音频链路(独立)正常播放。
     */
    private void hookSubtitleEntry(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.dragon.read.component.audio.impl.ui.page.viewmodel.AiTtsSubtitleDataCacher",
                    lpparam.classLoader, "i",
                    "androidx.fragment.app.FragmentActivity",
                    "androidx.lifecycle.LiveData",
                    String.class, String.class, long.class, boolean.class, Runnable.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (!cfgEnabled) return;
                            try {
                                String bookId = String.valueOf(param.args[2]);
                                String chapterId = String.valueOf(param.args[3]);
                                log("[SUB-ENTRY] bookId=" + bookId + " chapterId=" + chapterId
                                        + " toneId=" + param.args[4] + " isX=" + param.args[5]
                                        + " hideSubtitle=" + (param.args[6] == null ? "" : param.args[6].getClass().getSimpleName()));
                                // 主动触发 hideSubtitle 回调(模拟字幕隐藏成功)
                                Object hide = param.args[6];
                                if (hide instanceof Runnable) {
                                    try {
                                        ((Runnable) hide).run();
                                        log("[SUB-ENTRY] hideSubtitle 已触发");
                                    } catch (Throwable t) {
                                        log("[SUB-ENTRY] hideSubtitle 异常: " + t);
                                    }
                                }
                                // 短路: 跳过整个方法体(不再走 h() -> ASR/TTS同步 -> 字幕加载失败)
                                param.setResult(null);
                                log("[SUB-ENTRY] 已短路跳过字幕加载");
                            } catch (Throwable ignored) {
                            }
                        }
                    });
            log("SUB-ENTRY hook 完成");
        } catch (Throwable t) {
            log("SUB-ENTRY hook 失败: " + t);
        }
    }

    /**
     * v2.4: hook 真实字幕链路入口(运行时实际走这两个, AiTtsSubtitleDataCacher 是死代码):
     *   SubtitleListProvider.c(String bookId, String chapterId, long toneId, boolean isAudio)V
     *   TTSSubtitleProvider.g(String bookId, String chapterId, long toneId)V
     * 两者内部 h() -> Observable.zip(ASR, TTS同步) -> 本地书 ASR bookId=hex 转 long=0 被拒 -> 字幕加载失败。
     * 短路: 记录参数 + setResult(null) 跳过, 避免干扰播放主链路。
     */
    private void hookSubtitleProvider(final XC_LoadPackage.LoadPackageParam lpparam) {
        // 1. SubtitleListProvider.c
        try {
            XposedHelpers.findAndHookMethod(
                    "com.dragon.read.component.audio.impl.ui.page.subtitle.SubtitleListProvider",
                    lpparam.classLoader, "c",
                    String.class, String.class, long.class, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (!cfgEnabled) return;
                        try {
                            String bookId = (String) param.args[0];
                            String chapterId = (String) param.args[1];
                            log("[SUB-PROV] SubtitleListProvider.c bookId=" + bookId
                                    + " chapterId=" + chapterId + " toneId=" + param.args[2]
                                    + " isAudio=" + param.args[3]);
                            param.setResult(null);
                            log("[SUB-PROV] 已短路 SubtitleListProvider.c");
                            // v2.5: 主动回调 listener 让 UI 收尾(空字幕列表), 结束"一直加载中"
                            // 接口 subtitle/b: a(String,Throwable)失败 / b(String)开始 / c(String,ArrayList,ChapterInfo)成功
                            try {
                                Object provider = param.thisObject;
                                Object listener = XposedHelpers.getObjectField(provider, "a");
                                if (listener != null) {
                                    try { XposedHelpers.callMethod(listener, "b", chapterId); } catch (Throwable ignored) {}
                                    try { XposedHelpers.callMethod(listener, "c", chapterId, new java.util.ArrayList(), null); } catch (Throwable t) {
                                        log("[SUB-PROV] SubtitleListProvider回调c失败: " + t);
                                    }
                                    log("[SUB-PROV] 已回调空字幕 chapterId=" + chapterId);
                                } else {
                                    log("[SUB-PROV] SubtitleListProvider listener(a)为空");
                                }
                            } catch (Throwable t) {
                                log("[SUB-PROV] SubtitleListProvider回调异常: " + t);
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                    });
            log("SubtitleListProvider.c hook 完成");
        } catch (Throwable t) {
            log("SubtitleListProvider.c hook 失败: " + t);
        }
        // 2. TTSSubtitleProvider.g
        try {
            XposedHelpers.findAndHookMethod(
                    "com.dragon.read.component.audio.impl.ui.page.subtitle.TTSSubtitleProvider",
                    lpparam.classLoader, "g",
                    String.class, String.class, long.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (!cfgEnabled) return;
                        try {
                            String bookId = (String) param.args[0];
                            String chapterId = (String) param.args[1];
                            log("[SUB-PROV] TTSSubtitleProvider.g bookId=" + bookId
                                    + " chapterId=" + chapterId + " toneId=" + param.args[2]);
                            param.setResult(null);
                            log("[SUB-PROV] 已短路 TTSSubtitleProvider.g");
                            // v2.5: 主动回调 listener 让 UI 收尾(空字幕列表), 结束"一直加载中"
                            // 接口 subtitle/d: a(String,Throwable)失败 / b(String)开始 / c(String,List)成功
                            try {
                                Object provider = param.thisObject;
                                Object listener = XposedHelpers.getObjectField(provider, "a");
                                if (listener != null) {
                                    try { XposedHelpers.callMethod(listener, "b", chapterId); } catch (Throwable ignored) {}
                                    try { XposedHelpers.callMethod(listener, "c", chapterId, new java.util.ArrayList()); } catch (Throwable t) {
                                        log("[SUB-PROV] TTSSubtitleProvider回调c失败: " + t);
                                    }
                                    log("[SUB-PROV] 已回调空字幕 chapterId=" + chapterId);
                                } else {
                                    log("[SUB-PROV] TTSSubtitleProvider listener(a)为空");
                                }
                            } catch (Throwable t) {
                                log("[SUB-PROV] TTSSubtitleProvider回调异常: " + t);
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                    });
            log("TTSSubtitleProvider.g hook 完成");
        } catch (Throwable t) {
            log("TTSSubtitleProvider.g hook 失败: " + t);
        }
    }

    private static long parseLongSafe(String s) {
        try {
            String t = s == null ? "" : s.trim();
            if (t.isEmpty()) return 0L;
            return Long.parseLong(t);
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static String getFieldStr(Object o, String name) {
        try {
            Object v = XposedHelpers.getObjectField(o, name);
            return String.valueOf(v);
        } catch (Throwable t) {
            return "?";
        }
    }

    private static long getFieldLong(Object o, String name) {
        try {
            Object v = XposedHelpers.getObjectField(o, name);
            if (v instanceof Number) return ((Number) v).longValue();
            return 0L;
        } catch (Throwable t) {
            return -1L;
        }
    }

    /** v2.5.3: dump StreamTtsItemRequest 全部字段(对比音色请求差异) */
    private static void dumpStreamFields(Object req) {
        try {
            java.lang.reflect.Field[] fs = req.getClass().getDeclaredFields();
            StringBuilder sb = new StringBuilder();
            for (java.lang.reflect.Field f : fs) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(req);
                    String vs = String.valueOf(v);
                    if (vs.length() > 200) vs = vs.substring(0, 200) + "...";
                    sb.append(f.getName()).append("=").append(vs).append(" | ");
                } catch (Throwable ignored) {
                }
            }
            log("[STREAM-FIELDS] " + sb.toString());
        } catch (Throwable ignored) {
        }
    }

    /** v2.5: 从 TypedByteArray 提取请求体内容(getBytes 优先, 字段 bytes 次之, in() 流兜底), gzip 自动解压 */
    private static String dumpReqBytes(Object b) {
        try {
            byte[] d = (byte[]) XposedHelpers.callMethod(b, "getBytes");
            d = tryGunzip(d);
            if (d != null && d.length > 0) return new String(d, "UTF-8");
            log("[CRONET-REQ-BODY] getBytes 返回空 b=" + b.getClass().getName());
        } catch (Throwable t) {
            log("[CRONET-REQ-BODY] getBytes 异常: " + t + " b=" + b.getClass().getName());
        }
        try {
            byte[] d = (byte[]) XposedHelpers.getObjectField(b, "bytes");
            d = tryGunzip(d);
            if (d != null && d.length > 0) return new String(d, "UTF-8");
            log("[CRONET-REQ-BODY] 字段bytes为空 b=" + b.getClass().getName());
        } catch (Throwable t) {
            log("[CRONET-REQ-BODY] 字段bytes异常: " + t);
        }
        try {
            java.io.InputStream is = (java.io.InputStream) XposedHelpers.callMethod(b, "in");
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] tmp = new byte[512];
            int n;
            while ((n = is.read(tmp)) > 0 && bos.size() < 4096) bos.write(tmp, 0, n);
            byte[] d = tryGunzip(bos.toByteArray());
            if (d != null && d.length > 0) return new String(d, "UTF-8");
            log("[CRONET-REQ-BODY] in()流为空 b=" + b.getClass().getName());
        } catch (Throwable t) {
            log("[CRONET-REQ-BODY] in()异常: " + t);
        }
        return null;
    }

    /** v2.5.4: gzip 魔数(1f 8b)则解压(streamtts 请求体被 TTRequestCompressManager 压缩) */
    private static byte[] tryGunzip(byte[] d) {
        if (d == null || d.length < 2) return d;
        if ((d[0] & 0xff) != 0x1f || (d[1] & 0xff) != 0x8b) return d;
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            java.util.zip.GZIPInputStream gz = new java.util.zip.GZIPInputStream(
                    new java.io.ByteArrayInputStream(d));
            byte[] tmp = new byte[512];
            int n;
            while ((n = gz.read(tmp)) > 0 && bos.size() < 65536) bos.write(tmp, 0, n);
            gz.close();
            return bos.toByteArray();
        } catch (Throwable t) {
            return d;
        }
    }

    private static boolean getFieldBool(Object o, String name) {
        try {
            Object v = XposedHelpers.getObjectField(o, name);
            return v instanceof Boolean && (Boolean) v;
        } catch (Throwable t) {
            return false;
        }
    }

    private void hookCronetTee(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        // c$a 实现 TypedInput, in() 是 Cronet 响应真实字节出口; 字段 a 是 HttpURLConnection 可拿 URL/响应码
        XposedHelpers.findAndHookMethod(
                "com.bytedance.frameworks.baselib.network.http.cronet.impl.c$a",
                lpparam.classLoader, "in", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (!cfgEnabled) return;
                        try {
                            Object orig = param.getResult();
                            if (orig instanceof InputStream) {
                                String url = "";
                                int code = 0;
                                try {
                                    Object conn = XposedHelpers.getObjectField(param.thisObject, "a");
                                    if (conn instanceof java.net.HttpURLConnection) {
                                        java.net.HttpURLConnection hc = (java.net.HttpURLConnection) conn;
                                        try { url = String.valueOf(hc.getURL()); } catch (Throwable ignored) {}
                                        try { code = hc.getResponseCode(); } catch (Throwable ignored) {}
                                    }
                                } catch (Throwable ignored) {
                                }
                                param.setResult(new TeeInputStream((InputStream) orig, url, code));
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                });
    }

    /** 代理输入流: 读取时静默累积字节, 流结束/关闭时检测音色关键字或播放特征, 命中才打日志 */
    private static class TeeInputStream extends InputStream {
        private static final String[] KEYS = {"ttsTones", "offlineTtsTones", "audioTones",
                "toneDecisionInfo", "recommendTone", "ToneInfo", "tts_tones", "offline_tts_tones",
                "speakerList", "voiceList", "toneList", "multiRole", "mature", "novel_tts",
                "err_msg", "error_code", "errorCode", "errorMsg", "\"message\"", "请稍候",
                "speech", "synthes", "synthesis"};
        private static final int MAX_CAP = 512 * 1024;   // 单条累积上限 512KB
        private static final int MAX_PRINT = 4000;       // 命中后打印上限
        private static final int MAX_HITS = 150;         // 每会话普通命中打印上限(v2.5 60->150)

        private final InputStream in;
        private final String url;
        private final int code;
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        private boolean closed = false;

        TeeInputStream(InputStream in, String url, int code) {
            this.in = in;
            this.url = url == null ? "" : url;
            this.code = code;
        }

        @Override
        public int read() throws java.io.IOException {
            int b = in.read();
            if (b >= 0 && buf.size() < MAX_CAP) buf.write(b);
            if (b < 0) finish();
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws java.io.IOException {
            int n = in.read(b, off, len);
            if (n > 0 && buf.size() < MAX_CAP) {
                int room = MAX_CAP - buf.size();
                buf.write(b, off, Math.min(n, room));
            }
            if (n < 0) finish();
            return n;
        }

        @Override
        public void close() throws java.io.IOException {
            try { finish(); } finally { in.close(); }
        }

        @Override
        public int available() throws java.io.IOException { return in.available(); }

        private synchronized void finish() {
            if (closed) return;
            closed = true;
            try {
                byte[] data = buf.toByteArray();
                if (data.length == 0) return;
                String s = new String(data, "UTF-8");
                String lower = url.toLowerCase(Locale.US);
                boolean urlHit = lower.contains("playinfo") || lower.contains("toneinfo")
                        || lower.contains("timepoint") || lower.contains("/audio/")
                        || lower.contains("tts") || lower.contains("speech")
                        || lower.contains("synthes") || lower.contains("fq-tts")
                        || lower.contains("tone") || lower.contains("/full/v")
                        || lower.contains("batch_full");
                boolean hit = urlHit;
                if (!hit) {
                    for (String k : KEYS) {
                        if (s.contains(k)) { hit = true; break; }
                    }
                }
                // 关键接口(playinfo/toneinfo/timepoint/streamtts/full/batch)独立计数, 不被普通请求刷掉
                // v2.5: streamtts 响应体必须无条件抓到(音频链路验证关键)
                boolean isKey = lower.contains("playinfo") || lower.contains("toneinfo")
                        || lower.contains("timepoint") || lower.contains("streamtts")
                        || lower.contains("/full/v") || lower.contains("batch_full");
                if (hit && (isKey ? cronetKeyHitCount < MAX_KEY_HITS : cronetHitCount < MAX_HITS)) {
                    if (isKey) cronetKeyHitCount++; else cronetHitCount++;
                    boolean textLike = s.indexOf(0) < 0 && (s.startsWith("{") || s.startsWith("[") || s.contains("\"code\""));
                    String content;
                    if (textLike) {
                        content = s.length() > MAX_PRINT
                                ? s.substring(0, MAX_PRINT) + "...(截断 " + s.length() + "字)" : s;
                    } else {
                        content = "[binary/audio " + data.length + "B] head=" + toHex(data, 64);
                    }
                    log("[CRONET-TONE] code=" + code + " url=" + url + " len=" + data.length + " -> " + content);
                }
            } catch (Throwable ignored) {
            }
        }

        private static String toHex(byte[] d, int n) {
            StringBuilder sb = new StringBuilder();
            int lim = Math.min(d.length, n);
            for (int i = 0; i < lim; i++) {
                sb.append(String.format("%02x", d[i] & 0xff));
            }
            return sb.toString();
        }
    }

    // ==================== AudioConfig 音色数据 hook ====================

    private void hookAudioConfig(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
    String cls = "com.dragon.read.component.audio.impl.api.AudioConfigApi";
    // O()/P(): 全局音色列表(离线/在线默认) —— 注入完整音色, 让本地书播放校验通过
    hookListInject(lpparam, cls, "P", "P", "LocalBookToneInfoConfig$ToneInfo", true);
    hookListInject(lpparam, cls, "O", "O", "LocalBookToneInfoConfig$ToneInfo", false);
    // w02/g.I(AudioPageInfo): 本地书 AI 音色列表 —— 真正播放校验数据源(s0 检查), 注入 ez1/e 列表
    hookW02GI(lpparam);
    // dialog/f.s0(AudioPageInfo): AI tab 播放前校验, 强制放行(双保险)
    hookS0(lpparam);
    // repo/d.a(StreamTtsItemRequest): 流式TTS请求数据有效性校验, 强制有效(本地书 bookId 转 long 无效)
    hookDValidity(lpparam);
    // InnerSegmentRepo$requestStreamTTS$2.invoke(J): 记录流式请求参数 + 确保不抛异常
    hookStreamInvoke(lpparam);
        // r(bookId): 每本书的 AudioConfig(含音色列表)
        try {
            XposedHelpers.findAndHookMethod(cls, lpparam.classLoader, "r", String.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (!cfgEnabled) return;
                    Object result = param.getResult();
                    // 精简: 只打印 bookId 和结果类名/大小, 避免刷屏
                    String dump = dumpObject(result, 0);
                    log("[AC-r] bookId=" + param.args[0] + " -> " + result.getClass().getSimpleName()
                            + "(" + dump.length() + "字)" + (dump.contains("tone") || dump.contains("Tone") ? " [含Tone字段!]" : ""));
                }
            });
        } catch (Throwable t) {
            log("hook r 失败: " + t);
        }
        // v(bookId): 返回播放速度
        try {
            XposedHelpers.findAndHookMethod(cls, lpparam.classLoader, "v", String.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (!cfgEnabled) return;
                    log("[AC-v] bookId=" + param.args[0] + " -> speed=" + param.getResult());
                }
            });
        } catch (Throwable t) {
            log("hook v 失败: " + t);
        }
    }

    /** O()/P() 音色列表注入: P=AI音色(在线), O=离线音色 */
    private void hookListInject(final XC_LoadPackage.LoadPackageParam lpparam, String cls, String name,
                                String tag, String elemCls, boolean isAi) {
        try {
            XposedHelpers.findAndHookMethod(cls, lpparam.classLoader, name, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (!cfgEnabled) return;
                    try {
                        Object result = param.getResult();
                        if (!(result instanceof java.util.List)) return;
                        java.util.List<Object> list = (java.util.List<Object>) result;
                        int added = 0;
                        Object[][] tones = isAi ? AI_TONES_GLOBAL : OFFLINE_TONES;
                        String clsName = "com.dragon.read.component.audio.data.setting." + elemCls;
                        Class<?> toneCls = XposedHelpers.findClass(clsName, lpparam.classLoader);
                        for (Object[] t : tones) {
                            long id = (Long) t[0];
                            boolean exists = false;
                            for (Object o : list) {
                                try {
                                    if (XposedHelpers.getLongField(o, "toneId") == id) { exists = true; break; }
                                } catch (Throwable ignored) {
                                }
                            }
                            if (!exists) {
                                Object ne = XposedHelpers.newInstance(toneCls,
                                        (Long) t[0], (String) t[1], (String) t[2]);
                                list.add(ne);
                                added++;
                            }
                        }
                        if (added > 0) log("[AC-INJ-" + tag + "] 补全 " + added + " 个音色, 当前共 " + list.size());
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable t) {
            log("hook " + tag + " 注入失败: " + t);
        }
    }

    /** hook w02/g.I(AudioPageInfo): AI音色列表(ez1/e) —— 本地书注入全量AI_TONES, TTS书(在线/成绩不达标)追加97多角色 */
    private void hookW02GI(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod("w02.g", lpparam.classLoader, "I",
                    "com.dragon.read.component.audio.biz.protocol.core.data.AudioPageInfo",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            if (!cfgEnabled) return;
                            try {
                                Object pageInfo = param.args[0];
                                if (pageInfo == null) return;
                                boolean isLocal = XposedHelpers.getBooleanField(pageInfo, "isLocalBook");
                                boolean isTts = false;
                                try {
                                    Object bookInfo = XposedHelpers.getObjectField(pageInfo, "bookInfo");
                                    if (bookInfo != null) isTts = XposedHelpers.getBooleanField(bookInfo, "isTtsBook");
                                } catch (Throwable ignored) {
                                }
                                Class<?> ez1e = XposedHelpers.findClass("ez1.e", lpparam.classLoader);
                                if (isLocal) {
                                    // 本地书: 注入完整 AI 音色列表
                                    java.util.List<Object> list = new java.util.ArrayList<Object>();
                                    for (Object[] t : AI_TONES) {
                                        Object item = XposedHelpers.newInstance(ez1e,
                                                (String) t[1], (Long) t[0], (String) t[2]);
                                        list.add(item);
                                    }
                                    param.setResult(list);
                                    log("[W02GI] 本地书注入 AI 音色 " + list.size() + " 个");
                                } else if (isTts) {
                                    // TTS书(在线/成绩不达标): 在原始列表上追加97多角色对话升级版
                                    java.util.List<Object> list = (java.util.List<Object>) param.getResult();
                                    if (list == null) {
                                        list = new java.util.ArrayList<Object>();
                                        param.setResult(list);
                                    }
                                    boolean has97 = false;
                                    for (Object e : list) {
                                        try {
                                            if (XposedHelpers.getLongField(e, "c") == 97L) { has97 = true; break; }
                                        } catch (Throwable ignored) {
                                        }
                                    }
                                    if (!has97) {
                                        list.add(XposedHelpers.newInstance(ez1e, "多角色对话升级版", 97L, ""));
                                        log("[W02GI] TTS书追加97多角色, 列表=" + list.size());
                                    } else {
                                        log("[W02GI] TTS书列表已含97, 列表=" + list.size());
                                    }
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });
            log("[W02GI] hook w02/g.I 完成(v2.5.5 TTS书注入)");
        } catch (Throwable t) {
            log("hook w02/g.I 失败: " + t);
        }
        // v2.5.7: hook w02/g.x(AudioCatalog) —— TTS书播放校验的speakerList转换点, 追加97多角色
        // (播放引擎直接查 catalog/TtsInfo.speakerList 校验音色, 仅注入UI列表不够)
        try {
            XposedHelpers.findAndHookMethod("w02.g", lpparam.classLoader, "x",
                    "com.dragon.read.component.download.model.AudioCatalog", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            if (!cfgEnabled) return;
                            try {
                                java.util.List<Object> list = (java.util.List<Object>) param.getResult();
                                if (list == null) return;
                                boolean has97 = false;
                                for (Object e : list) {
                                    try {
                                        if (XposedHelpers.getLongField(e, "c") == 97L) { has97 = true; break; }
                                    } catch (Throwable ignored) {
                                    }
                                }
                                if (!has97) {
                                    Class<?> ez1e = XposedHelpers.findClass("ez1.e", lpparam.classLoader);
                                    list.add(XposedHelpers.newInstance(ez1e, "多角色对话升级版", 97L, ""));
                                    log("[W02X] speakerList追加97, 列表=" + list.size());
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });
            log("[W02X] hook w02/g.x 完成");
        } catch (Throwable t) {
            log("hook w02/g.x 失败: " + t);
        }
    }

    /** hook dialog/f.s0(AudioPageInfo): AI tab 播放校验强制放行(双保险) */
    private void hookS0(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod("com.dragon.read.component.audio.impl.ui.dialog.f",
                    lpparam.classLoader, "s0",
                    "com.dragon.read.component.audio.biz.protocol.core.data.AudioPageInfo",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            if (!cfgEnabled) return;
                            if (Boolean.TRUE.equals(param.getResult())) {
                                param.setResult(false);
                                log("[S0] 强制放行 AI tab 播放校验");
                            }
                        }
                    });
            log("[S0] hook dialog/f.s0 完成");
        } catch (Throwable t) {
            log("hook dialog/f.s0 失败: " + t);
        }
    }

        /** hook repo/d.a(StreamTtsItemRequest): 流式TTS数据有效性校验, 强制返回 false(有效) */
    private void hookDValidity(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod("com.dragon.read.component.audio.impl.ui.audio.core.repo.d",
                    lpparam.classLoader, "a",
                    "com.dragon.read.rpc.model.StreamTtsItemRequest",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            if (!cfgEnabled) return;
                            if (Boolean.TRUE.equals(param.getResult())) {
                                param.setResult(false);
                                log("[DA] 强制流式TTS数据有效");
                            }
                        }
                    });
            log("[DA] hook repo/d.a 完成");
        } catch (Throwable t) {
            log("hook repo/d.a 失败: " + t);
        }
    }

    /** hook requestStreamTTS$2.invoke(J): 记录流式请求参数 + v2.4: dump全部字段 + 实验改bookId(0->itemId) */
    private void hookStreamInvoke(final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.dragon.read.component.audio.impl.ui.audio.core.repo.InnerSegmentRepo$requestStreamTTS$2",
                    lpparam.classLoader, "invoke", long.class,
                    new XC_MethodHook() {
                        @Override
protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                if (!cfgEnabled) return;
                                try {
                                    log("[STREAM-REQ] itemId=" + param.args[0]);
                                    // v2.5.3: dump StreamTtsItemRequest 全部字段原始值(对比 91/74 请求差异)
                                    try {
                                        Object req = XposedHelpers.getObjectField(param.thisObject, "this$0");
                                        if (req == null) req = XposedHelpers.getObjectField(param.thisObject, "a");
                                        if (req != null && req.getClass().getName().contains("InnerSegmentRepo")) {
                                            java.lang.reflect.Field[] fs = req.getClass().getDeclaredFields();
                                            for (java.lang.reflect.Field f : fs) {
                                                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                                                try {
                                                    f.setAccessible(true);
                                                    Object v = f.get(req);
                                                    if (v != null && v.getClass().getName().contains("StreamTtsItemRequest")) {
                                                        dumpStreamFields(v);
                                                    }
                                                } catch (Throwable ignored) {
                                                }
                                            }
                                        }
                                    } catch (Throwable ignored) {
                                    }
                                } catch (Throwable ignored) {
                                }
                            }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            if (!cfgEnabled) return;
                            try {
                                Object req = param.getResult();
                                if (req != null) {
                                    long bookId = XposedHelpers.getLongField(req, "bookId");
                                    long itemId = XposedHelpers.getLongField(req, "itemId");
                                    long toneId = XposedHelpers.getLongField(req, "toneId");
                                    boolean isLocal = XposedHelpers.getBooleanField(req, "isLocalBook");
                                    String taskId = getFieldStr(req, "taskId");
                                    boolean block = getFieldBool(req, "blockReaderSentencePart");
                                    log("[STREAM-REQ] bookId=" + bookId + " itemId=" + itemId +
                                            " toneId=" + toneId + " isLocalBook=" + isLocal +
                                            " taskId=" + taskId + " blockReaderSentencePart=" + block);
                                    // v2.4 实验: bookId=0(本地书hex溢出) 服务端拒绝 -> 改成 itemId 试试服务端是否接受
if (bookId == 0L && itemId != 0L) {
XposedHelpers.setLongField(req, "bookId", itemId);
log("[STREAM-REQ] 实验: bookId=0 -> 改为 itemId=" + itemId);
}
// v2.5.2 实验: 91(本地书多角色ID) -> 97(服务端在线书多角色ID, toneinfo实锤97=多角色对话升级版)
// 用户在线书 97 可播(main_url正常) => 服务端认可 97, 本地书 streamtts 91 被拒(102040)可能是 ID 不对
if (toneId == 91L) {
XposedHelpers.setLongField(req, "toneId", 97L);
log("[STREAM-REQ] 实验: toneId=91 -> 改为97(服务端在线书多角色ID)");
toneId = 97L;
}
                                } else if (param.getThrowable() != null) {
                                    log("[STREAM-REQ] 异常: " + param.getThrowable());
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });
            log("[STREAM-REQ] hook invoke 完成");
        } catch (Throwable t) {
            log("hook requestStreamTTS invoke 失败: " + t);
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