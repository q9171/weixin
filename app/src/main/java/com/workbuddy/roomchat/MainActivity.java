package com.workbuddy.roomchat;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
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
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {

    private WebView webView;
    private static final String PREFS = "roomchat_prefs";
    private static final String KEY_UI_URL = "ui_url";
    private static final String KEY_UPDATE_URL = "update_url";
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
    private static final String FALLBACK_TAG = "v1.0.6";

    // 最近一次「检查更新」得到的远程信息，供「下载并安装」使用
    private volatile String pendingApkUrl = "";
    private volatile String pendingVersionName = "";

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

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

        setContentView(webView);
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

    // 用户手动设置的更新地址（SharedPreferences）。为空表示「自动解析」。
    private String getStoredUpdateUrl() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        String u = p.getString(KEY_UPDATE_URL, "");
        if (u != null && !u.trim().isEmpty()) return u.trim();
        return "";
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

    // 解析最终要检查的更新地址：用户手动设置优先，否则自动发现最新标签
    private String resolveUpdateUrl() {
        String u = getStoredUpdateUrl();
        if (u != null && !u.isEmpty()) return u;
        return buildUpdateUrl(resolveLatestTag());
    }

    // 下载文件（用于 APK），带进度回调
    private void downloadFile(String urlStr, File out, ProgressListener listener)
            throws IOException {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        int total = conn.getContentLength();
        File parent = out.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (InputStream in = conn.getInputStream();
             FileOutputStream fos = new FileOutputStream(out)) {
            byte[] buf = new byte[8192];
            int read;
            long got = 0;
            while ((read = in.read(buf)) != -1) {
                fos.write(buf, 0, read);
                got += read;
                if (total > 0 && listener != null) {
                    listener.onProgress((int) (got * 100 / total));
                }
            }
        } finally {
            conn.disconnect();
        }
    }

    interface ProgressListener {
        void onProgress(int percent);
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
        pendingApkUrl = has ? apk : "";
        pendingVersionName = remoteName;
        JSONObject res = new JSONObject();
        res.put("hasUpdate", has);
        res.put("versionName", remoteName);
        res.put("currentVersion", cur);
        res.put("currentName", getCurrentVersionName());
        res.put("note", note);
        res.put("apkUrl", apk);
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

    // 下载 APK 并拉起系统安装器
    private void installUpdate() {
        // Android 8+ 必须先有「安装未知应用」权限，否则下载完也拉不起安装器。
        // 无权限时跳到系统设置页引导用户开启，并提示回来点「重试」。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !getPackageManager().canRequestPackageInstalls()) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    callJs("window.__onInstallDone && window.__onInstallDone('launch_err:请先允许威信安装应用')");
                    try {
                        Intent s = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
                        s.setData(Uri.parse("package:" + getPackageName()));
                        s.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(s);
                    } catch (Exception ignore) {}
                }
            });
            return;
        }
        if (pendingApkUrl == null || pendingApkUrl.isEmpty()) {
            callJs("window.__onInstallDone && window.__onInstallDone('no_apk')");
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                    if (dir == null) dir = getFilesDir();
                    if (!dir.exists()) dir.mkdirs();
                    File apk = new File(dir, "update.apk");
                    downloadFile(pendingApkUrl, apk, new ProgressListener() {
                        @Override
                        public void onProgress(int percent) {
                            callJs("window.__onInstallProgress && window.__onInstallProgress("
                                    + percent + ")");
                        }
                    });
                    callJs("window.__onInstallDone && window.__onInstallDone('ok')");
                    launchInstall(apk);
                } catch (Exception e) {
                    callJs("window.__onInstallDone && window.__onInstallDone('err:"
                            + String.valueOf(e.getMessage()).replace("'", "") + "')");
                }
            }
        }).start();
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
        public String getUpdateUrl() {
            String u = getStoredUpdateUrl();
            return (u == null || u.isEmpty()) ? "auto" : u;
        }

        @JavascriptInterface
        public void setUpdateUrl(String url) {
            SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
            if (url == null) url = "";
            p.edit().putString(KEY_UPDATE_URL, url.trim()).apply();
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
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }
}
