package com.workbuddy.roomchat;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.database.Cursor;
import android.view.KeyEvent;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.webkit.RenderProcessGoneDetail;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {

    private WebView webView;
    private DownloadManager dm;
    private long downloadId = -1;
    private BroadcastReceiver dlReceiver;
    private static final String PREFS = "roomchat_prefs";
    private static final String KEY_UI_URL = "ui_url";
    private static final String LOCAL = "file:///android_asset/index.html";

    // 更新信息地址解析策略（关键：绕开 jsDelivR 标签缓存限制）：
    // jsDelivR 对「标签名」做永久缓存——移动/重建同名标签、purge 都无法刷新旧内容。
    // 因此发版必须用「全新版本标签名」（如 v1.0.4），App 不能写死某个标签。
    // 本机先问 data.jsdelivr.com（实时、不缓存）拿到最新版本标签名，
    // 再取 https://cdn.jsdelivr.net/gh/Q9171/weixin@<最新标签>/version.json 。
    // 这样已装老用户也能自动收到更新提示，且每次都是新标签→CDN 即时返回新内容。
    // 用户可在 App 设置里手动改更新地址（覆盖自动解析）。
    private static final String REPO = "Q9171/weixin";
    private static final String META_URL = "https://data.jsdelivr.com/v1/packages/gh/" + REPO;
    // 兜底：元数据接口不可用时，退回最近已知版本标签（仍是全新标签名，CDN 即时）
    private static final String FALLBACK_TAG = "v1.2.0";

    // 最近一次「检查更新」得到的远程信息，供「下载并安装」使用
    private volatile String pendingApkUrl = "";
    private volatile String pendingVersionName = "";

    // 国内移动网络下 cdn.jsdelivr.net 常被限速/首字节极慢，统一切到 gcore 域名
    private String normalizeCdnUrl(String url) {
        if (url == null) return "";
        // 把 https://cdn.jsdelivr.net/... 替换成 https://gcore.jsdelivr.net/...
        // 保留 http 备用，只替换域名部分
        return url.replaceFirst("(?i)^https?://cdn\\.jsdelivr\\.net/", "https://gcore.jsdelivr.net/");
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 全局兜底：任何未捕获异常都不再「闪退」，而是把错误显示在屏幕上（用户可截图发回）
        installGlobalCrashHandler();
        try {
            initUi();
        } catch (Throwable e) {
            // 启动阶段崩溃（如设备 WebView 组件异常）直接显示错误，而不是强制退出
            showFatal(e);
        }
    }

    // 构建并加载界面；任何一步异常都会被 onCreate 的 try/catch 捕获
    private void initUi() {
        webView = new WebView(this);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        webView.addJavascriptInterface(new Bridge(), "RoomChat");
        webView.setWebViewClient(new ChatClient());
        loadUi();

        // 注册系统下载完成监听（DownloadManager 下载 APK 后自动拉起安装器）
        dlReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (id == downloadId) checkDownloadAndInstall();
            }
        };
        // Android 14+ 动态注册广播必须指定 RECEIVER_EXPORTED/NOT_EXPORTED，否则报 SecurityException
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(dlReceiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(dlReceiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
        }

        setContentView(webView);
    }

    // ============ 崩溃兜底（避免闪退 + 便于定位） ============

    // 全局未捕获异常处理器：把错误写入文件并在屏幕上显示，而不是直接闪退
    private void installGlobalCrashHandler() {
        final Thread.UncaughtExceptionHandler def = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                final String trace = stackTrace(e);
                writeCrashLog(trace);
                // 尽量在主线程把错误画到屏幕上；若主线程已死则退回系统默认处理
                try {
                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run() { showFatal(trace); }
                    });
                } catch (Throwable ignore) {
                    if (def != null) def.uncaughtException(t, e);
                }
            }
        });
    }

    // 把异常栈转成可显示的文本（只保留前若干行，避免手机屏过长）
    private String stackTrace(Throwable e) {
        try {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            String s = sw.toString();
            String[] lines = s.split("\n");
            int n = Math.min(lines.length, 40);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < n; i++) sb.append(lines[i]).append('\n');
            if (lines.length > n) sb.append("…(共 ").append(lines.length).append(" 行)");
            return sb.toString();
        } catch (Throwable ignore) {
            return String.valueOf(e);
        }
    }

    // 把崩溃信息存到应用私有目录，方便排查
    private void writeCrashLog(String trace) {
        try {
            File dir = getExternalFilesDir(null);
            if (dir == null) dir = getFilesDir();
            File f = new File(dir, "crash.txt");
            try (FileWriter fw = new FileWriter(f, false);
                 BufferedWriter bw = new BufferedWriter(fw)) {
                bw.write(trace);
            }
        } catch (Exception ignore) {
        }
    }

    // 在屏幕上显示 fatal 错误（不依赖 WebView，即使 WebView 本身挂了也能显示）
    private void showFatal(Throwable e) {
        showFatal(stackTrace(e));
    }

    private void showFatal(String trace) {
        writeCrashLog(trace);
        try {
            TextView tv = new TextView(this);
            tv.setText("威信遇到问题，已拦截以避免闪退。\n请把下方内容截图发给我：\n\n" + trace);
            tv.setTextColor(0xFFE9EDF0);
            tv.setBackgroundColor(0xFF0A1722);
            tv.setTextSize(11);
            tv.setTypeface(Typeface.MONOSPACE);
            tv.setPadding(28, 28, 28, 28);
            ScrollView sv = new ScrollView(this);
            sv.setBackgroundColor(0xFF0A1722);
            sv.addView(tv);
            setContentView(sv);
        } catch (Throwable ignore) {
            // 连错误界面都画不出来就放弃，交给系统默认处理
        }
    }

    // 根据设置决定加载远程界面还是本地内置界面
    private void loadUi() {
        String url = getUiUrl();
        if (url != null && !url.trim().isEmpty()) {
            webView.loadUrl(url.trim());
        } else {
            webView.loadUrl(LOCAL);
        }
    }

    private String getUiUrl() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        return p.getString(KEY_UI_URL, "");
    }

    @SuppressWarnings("deprecation")
    private int getCurrentVersionCode() {
        try {
            PackageManager pm = getPackageManager();
            PackageInfo pi = pm.getPackageInfo(getPackageName(), 0);
            return pi.versionCode;
        } catch (Exception e) {
            return 1;
        }
    }

    @SuppressWarnings("deprecation")
    private String getCurrentVersionName() {
        try {
            PackageManager pm = getPackageManager();
            PackageInfo pi = pm.getPackageInfo(getPackageName(), 0);
            return pi.versionName;
        } catch (Exception e) {
            return "1.0.0";
        }
    }

    // 远程主框架加载失败时，自动回退到本地内置界面
    private class ChatClient extends WebViewClient {
        private boolean autoChecked = false;

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            // 页面（含 JS）加载完成后，静默检查一次更新
            if (!autoChecked) {
                autoChecked = true;
                autoCheck();
            }
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request,
                                    android.webkit.WebResourceError error) {
            if (request != null && request.isForMainFrame() && view != null) {
                String cur = view.getUrl();
                if (cur != null && cur.startsWith("http")) {
                    view.loadUrl(LOCAL);
                }
            }
        }

        // 渲染进程崩溃（部分机型/WebView 组件异常会触发）：不强制退出，自动重载本地界面
        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            try {
                // 先尝试重载内置界面，避免直接回到空白/闪退
                if (view != null) view.loadUrl(LOCAL);
            } catch (Throwable ignore) {
            }
            return true; // 返回 true 表示我们已处理，系统不会因此杀掉整个 App
        }
    }

    private void callJs(final String js) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() { webView.loadUrl("javascript:" + js); }
        });
    }

    // ============ 更新功能 ============

    // 下载文本（用于 version.json）
    private String downloadString(String urlStr) throws IOException {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        conn.setRequestProperty("Accept", "application/json");
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
        } finally {
            conn.disconnect();
        }
        return sb.toString();
    }

    // 从 data.jsdelivr.com（实时、不缓存）取最新版本标签名（最高语义版本号）。
    // 注意：data API 返回的 version 不带 v 前缀（如 "1.0.3"），但 CDN 标签名带 v（v1.0.3），拼 URL 时要补回。
    private String resolveLatestTag() {
        try {
            String meta = downloadString(META_URL);
            JSONObject root = new JSONObject(meta);
            java.util.List<String> tags = new java.util.ArrayList<String>();
            org.json.JSONArray versions = root.optJSONArray("versions");
            if (versions != null) {
                for (int i = 0; i < versions.length(); i++) {
                    String ver = versions.getJSONObject(i).optString("version", "");
                    if (ver.matches("^v?\\d+\\.\\d+\\.\\d+$")) tags.add(ver);
                }
            }
            if (tags.isEmpty()) return FALLBACK_TAG;
            java.util.Collections.sort(tags, new java.util.Comparator<String>() {
                public int compare(String a, String b) {
                    return Integer.compare(parseVer(b), parseVer(a)); // 降序
                }
            });
            return tags.get(0);
        } catch (Exception e) {
            return FALLBACK_TAG;
        }
    }

    // "v1.0.3" / "1.0.3" -> 可比较整数（major*1e6+minor*1e3+patch）
    private int parseVer(String v) {
        String[] p = v.replaceFirst("^v", "").split("\\.");
        int maj = 0, min = 0, pat = 0;
        try { maj = Integer.parseInt(p[0]); } catch (Exception ignore) {}
        try { min = Integer.parseInt(p[1]); } catch (Exception ignore) {}
        try { pat = Integer.parseInt(p[2]); } catch (Exception ignore) {}
        return maj * 1000000 + min * 1000 + pat;
    }

    // 拼出某个标签对应的 version.json 地址（data API 给的版本号可能缺 v 前缀，这里补回）
    private String buildUpdateUrl(String ver) {
        String t = (ver != null && ver.startsWith("v")) ? ver : ("v" + ver);
        return "https://cdn.jsdelivr.net/gh/" + REPO + "@" + t + "/version.json";
    }

    // 解析最终要检查的更新地址：自动发现最新标签
    private String resolveUpdateUrl() {
        return buildUpdateUrl(resolveLatestTag());
    }

    // 国内 gcore.jsdelivr.net 速度最好，把任何 jsDelivr/cdn 域名统一切到 gcore；
    // raw.githubusercontent.com 在国内部分网络也会被墙/限速，因此也转成 gcore。
    private String normalizeApkUrl(String url) {
        if (url == null) return "";
        if (url.startsWith("https://raw.githubusercontent.com/Q9171/weixin/")) {
            String tail = url.substring("https://raw.githubusercontent.com/Q9171/weixin/".length());
            return "https://gcore.jsdelivr.net/gh/Q9171/weixin@" + tail.replaceFirst("/", "/");
        }
        return normalizeCdnUrl(url);
    }

    // 解析 version.json 并判断是否有更新；把结果通过回调交给 JS
    private JSONObject buildUpdateResult(String json) throws Exception {
        JSONObject o = new JSONObject(json);
        int remoteCode = o.optInt("versionCode", 0);
        String remoteName = o.optString("versionName", "");
        String note = o.optString("note", "");
        String apk = o.optString("apkUrl", "");
        int cur = getCurrentVersionCode();
        boolean has = remoteCode > cur && !apk.isEmpty();
        pendingApkUrl = has ? normalizeApkUrl(normalizeCdnUrl(apk)) : "";
        pendingVersionName = remoteName;
        JSONObject res = new JSONObject();
        res.put("hasUpdate", has);
        res.put("versionName", remoteName);
        res.put("currentVersion", cur);
        res.put("currentName", getCurrentVersionName());
        res.put("note", note);
        res.put("apkUrl", pendingApkUrl);
        return res;
    }

    // 静默自动检查（启动后）：仅在有更新时提示，不弹设置面板
    private void autoCheck() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject res = buildUpdateResult(downloadString(resolveUpdateUrl()));
                    if (res.optBoolean("hasUpdate", false)) {
                        callJs("window.__onAutoUpdate && window.__onAutoUpdate("
                                + res.toString() + ")");
                    }
                } catch (Exception ignore) {
                    // 静默失败，不打扰用户
                }
            }
        }).start();
    }

    // 手动检查（设置里的「检查更新」按钮）
    private void manualCheck() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject res = buildUpdateResult(downloadString(resolveUpdateUrl()));
                    callJs("window.__onUpdateChecked && window.__onUpdateChecked("
                            + res.toString() + ")");
                } catch (Exception e) {
                    try {
                        JSONObject res = new JSONObject();
                        res.put("hasUpdate", false);
                        res.put("error", String.valueOf(e.getMessage()));
                        callJs("window.__onUpdateChecked && window.__onUpdateChecked("
                                + res.toString() + ")");
                    } catch (Exception ignore) {
                    }
                }
            }
        }).start();
    }

    // 更新下载：直接跳转手机浏览器下载 APK。
    // 浏览器自带醒目的下载进度显示，下载完成后用户点击安装包即可安装，
    // 不再依赖 DownloadManager 通知栏（部分机型通知被拦截导致看不到进度）。
    private void installUpdate() {
        if (pendingApkUrl == null || pendingApkUrl.isEmpty()) {
            callJs("window.__onInstallDone && window.__onInstallDone('no_apk')");
            return;
        }
        final String url = normalizeApkUrl(pendingApkUrl);
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                    callJs("window.__onInstallDone && window.__onInstallDone('browser')");
                } catch (Exception e) {
                    callJs("window.__onInstallDone && window.__onInstallDone('launch_err:"
                            + String.valueOf(e.getMessage()).replace("'", "") + "')");
                }
            }
        });
    }

    // 系统下载完成后：检查状态，成功则拉起安装器
    private void checkDownloadAndInstall() {
        if (dm == null) return;
        DownloadManager.Query q = new DownloadManager.Query();
        q.setFilterById(downloadId);
        Cursor c = null;
        try {
            c = dm.query(q);
            if (c != null && c.moveToFirst()) {
                int status = c.getInt(c.getColumnIndex(DownloadManager.COLUMN_STATUS));
                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                    if (dir == null) dir = getFilesDir();
                    File apk = new File(dir, "update.apk");
                    callJs("window.__onInstallDone && window.__onInstallDone('ok')");
                    launchInstall(apk);
                } else if (status == DownloadManager.STATUS_FAILED) {
                    int reason = c.getInt(c.getColumnIndex(DownloadManager.COLUMN_REASON));
                    callJs("window.__onInstallDone && window.__onInstallDone('err:下载失败 code=" + reason + "')");
                }
            }
        } catch (Exception ignore) {
        } finally {
            if (c != null) c.close();
        }
    }

    private void launchInstall(final File apk) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    Uri uri = Uri.parse("content://" + ApkFileProvider.AUTHORITY + "/update.apk");
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.setDataAndType(uri, "application/vnd.android.package-archive");
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                } catch (Exception e) {
                    callJs("window.__onInstallDone && window.__onInstallDone('launch_err:"
                            + String.valueOf(e.getMessage()).replace("'", "") + "')");
                }
            }
        });
    }

    // 供网页调用的原生桥
    private class Bridge {
        @JavascriptInterface
        public void setUiUrl(String url) {
            SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
            if (url == null) url = "";
            p.edit().putString(KEY_UI_URL, url.trim()).apply();
        }

        @JavascriptInterface
        public String getUiUrl() {
            return getUiUrl();
        }

        @JavascriptInterface
        public void reloadUi() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() { loadUi(); }
            });
        }

        // —— 更新相关 ——
        @JavascriptInterface
        public String getVersion() {
            return getCurrentVersionName() + " (" + getCurrentVersionCode() + ")";
        }

        @JavascriptInterface
        public void checkUpdate() {
            manualCheck();
        }

        @JavascriptInterface
        public void installUpdate() {
            installUpdate();
        }
    }

    // 下载改用系统 DownloadManager，不需要 WRITE_EXTERNAL_STORAGE 权限，故不再需要权限回调

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
            webView.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        if (dlReceiver != null) {
            try { unregisterReceiver(dlReceiver); } catch (Exception ignore) {}
        }
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }
}
