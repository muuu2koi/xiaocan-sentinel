package com.xiaocan.sentinel;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * WebView 壳: 加载 uni-app H5 界面, 通过 XcBridge 提供原生能力
 * (网络请求 / 通知 / 后台巡检 / 定位 / 配置)
 */
public class HomeActivity extends Activity {

    WebView web;
    SharedPreferences p;
    final Handler main = new Handler(Looper.getMainLooper());
    final ExecutorService pool = Executors.newCachedThreadPool();
    LocalHttpServer httpServer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        p = getSharedPreferences(getPackageName(), MODE_PRIVATE);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
        }
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, 1);
        }

        web = new WebView(this);
        setContentView(web);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback cb) {
                cb.invoke(origin, true, false); // 自动授权 H5 定位
            }
        });
        web.addJavascriptInterface(new Bridge(), "XcBridge");
        try {
            httpServer = new LocalHttpServer(this);
            httpServer.start();
            web.loadUrl(httpServer.baseUrl());
        } catch (Exception e) {
            web.loadUrl("file:///android_asset/h5/index.html"); // 兜底
        }
    }

    @Override
    protected void onDestroy() {
        if (httpServer != null) httpServer.stop();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack(); else super.onBackPressed();
    }

    void js(String script) { main.post(() -> web.evaluateJavascript(script, null)); }

    class Bridge {

        /** H5 读取全部配置 */
        @JavascriptInterface
        public String getConfig() {
            JSONObject o = new JSONObject();
            for (String k : new String[]{"token", "uid", "silk_id", "city_code", "lat", "lng", "session_id",
                    "keywords", "interval_min", "quiet_start", "quiet_end"}) {
                try { o.put(k, p.getString(k, "")); } catch (Exception ignore) { }
            }
            try {
                o.put("switch_all", p.getBoolean("switch_all", true));
                o.put("switch_keyword", p.getBoolean("switch_keyword", true));
                o.put("running", p.getBoolean(PollService.PREF_RUNNING, false));
            } catch (Exception ignore) { }
            return o.toString();
        }

        /** H5 写配置 (token/keywords/开关/间隔/静默) */
        @JavascriptInterface
        public void setConfig(String json) {
            try {
                JSONObject in = new JSONObject(json);
                SharedPreferences.Editor e = p.edit();
                Iterator<String> keys = in.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    Object v = in.get(k);
                    if (v instanceof Boolean) e.putBoolean(k, (Boolean) v);
                    else e.putString(k, String.valueOf(v));
                }
                e.apply();
            } catch (Exception ignore) { }
        }

        @JavascriptInterface
        public void startMonitor() {
            Intent i = new Intent(HomeActivity.this, PollService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        }

        @JavascriptInterface
        public void stopMonitor() {
            Intent i = new Intent(HomeActivity.this, PollService.class);
            i.setAction("STOP");
            startService(i);
        }

        /** 手机精确定位: 结果回投 window.__onLocation */
        @JavascriptInterface
        public void refreshLocation() {
            boolean granted = Build.VERSION.SDK_INT < 23 ||
                    checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
            if (!granted) { js("window.__onLocation && window.__onLocation({ok:false,reason:'无定位权限'})"); return; }
            LocHelper.fix(HomeActivity.this, (lat, lng, src) -> {
                try {
                    JSONObject o = new JSONObject();
                    if (lat != 0 && lng != 0) {
                        p.edit().putString("lat", String.valueOf(lat)).putString("lng", String.valueOf(lng)).apply();
                        o.put("ok", true); o.put("lat", lat); o.put("lng", lng); o.put("src", src);
                    } else {
                        o.put("ok", false); o.put("reason", src);
                        o.put("lat", parseD(p.getString("lat", "23.020500"), 23.020500));
                        o.put("lng", parseD(p.getString("lng", "113.751800"), 113.751800));
                    }
                    js("window.__onLocation && window.__onLocation(" + o.toString() + ")");
                } catch (Exception ignore) { }
            });
        }

        /** 拉取附近活动 Feed: 结果回投 window.__onFeed */
        @JavascriptInterface
        public void loadFeed(final double lat, final double lng) {
            pool.execute(() -> {
                try {
                    XcApi api = buildApi();
                    JSONArray items = api.fetchFeedAll(lat, lng, 4);
                    // 附上 NEW 标记集合
                    java.util.Set<Long> fresh = loadFreshIds();
                    JSONArray arr = new JSONArray();
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject it = items.getJSONObject(i);
                        it.put("__new", fresh.contains(it.optLong("promotion_id", 0)));
                        arr.put(it);
                    }
                    String payload = arr.toString();
                    js("window.__onFeed && window.__onFeed({ok:true,items:" + payload + "})");
                } catch (Exception e) {
                    try {
                        JSONObject o = new JSONObject();
                        o.put("ok", false);
                        o.put("error", String.valueOf(e.getMessage()));
                        js("window.__onFeed && window.__onFeed(" + o.toString() + ")");
                    } catch (Exception ignore) { }
                }
            });
        }

        /** 打开小蚕 App */
        @JavascriptInterface
        public void openXiaocan() {
            Intent li = getPackageManager().getLaunchIntentForPackage(PollService.TARGET_APP);
            if (li != null) startActivity(li);
        }

        @JavascriptInterface
        public void toast(final String msg) {
            main.post(() -> android.widget.Toast.makeText(HomeActivity.this, msg, android.widget.Toast.LENGTH_SHORT).show());
        }

        /** 权限状态: 通知/定位/电池白名单 */
        @JavascriptInterface
        public String getPermStatus() {
            JSONObject o = new JSONObject();
            try {
                boolean notif = true;
                if (Build.VERSION.SDK_INT >= 24) {
                    android.app.NotificationManager nm = getSystemService(android.app.NotificationManager.class);
                    notif = nm == null || nm.areNotificationsEnabled();
                }
                boolean loc = Build.VERSION.SDK_INT < 23 ||
                        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
                android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
                boolean battery = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
                o.put("notif", notif);
                o.put("location", loc);
                o.put("battery", battery);
            } catch (Exception ignore) { }
            return o.toString();
        }

        @JavascriptInterface
        public void openAppSettings() {
            try {
                android.content.Intent i = new android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:" + getPackageName()));
                startActivity(i);
            } catch (Exception ignore) { }
        }

        @JavascriptInterface
        public void requestNotifPerm() {
            main.post(() -> {
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
                }
            });
        }

        @JavascriptInterface
        public void requestLocationPerm() {
            main.post(() -> {
                if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, 1);
                }
            });
        }

        @JavascriptInterface
        public void requestBattery() {
            try {
                android.content.Intent i = new android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        android.net.Uri.parse("package:" + getPackageName()));
                startActivity(i);
            } catch (Exception e) {
                openAppSettings();
            }
        }
    }

    XcApi buildApi() {
        XcApi a = new XcApi();
        a.token = p.getString("token", "");
        a.uid = parseL(p.getString("uid", "0"));
        a.silkId = parseL(p.getString("silk_id", "0"));
        a.cityCode = (int) parseL(p.getString("city_code", "441900"));
        a.sessionId = p.getString("session_id", "");
        return a;
    }

    java.util.Set<Long> loadFreshIds() {
        java.util.Set<Long> fresh = new java.util.HashSet<>();
        try {
            java.io.File f = new java.io.File(getFilesDir(), "state.json");
            if (!f.exists()) return fresh;
            java.io.FileInputStream fin = new java.io.FileInputStream(f);
            byte[] buf = new byte[(int) f.length()];
            int n = fin.read(buf);
            fin.close();
            JSONObject o = new JSONObject(new String(buf, 0, Math.max(n, 0), "UTF-8"));
            long now = System.currentTimeMillis() / 1000;
            Iterator<String> keys = o.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                long fs = o.getJSONObject(k).optLong("fs", 0);
                if (fs > 0 && now - fs < 1800) fresh.add(Long.parseLong(k));
            }
        } catch (Exception ignore) { }
        return fresh;
    }

    static long parseL(String s) { try { return Long.parseLong(s.trim()); } catch (Exception e) { return 0; } }
    static double parseD(String s, double d) { try { return Double.parseDouble(s.trim()); } catch (Exception e) { return d; } }
}
